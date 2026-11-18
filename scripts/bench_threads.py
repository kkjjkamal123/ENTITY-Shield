"""ExecuTorch thread / core-placement ablation for Llama 3.2 1B on an Android phone (ENTITY protocol).

  python scripts/bench_threads.py --serial <adb serial> [--model llama3_2_1b_instruct_qlora_ctx2048.pte]
                                  [--runs 3] [--cooldown 20] [--prompt v4_en.txt] [--max-new 64]

Runs ExecuTorch's own llama_main runner (already in /data/local/tmp/shield) under taskset, so the
only thing that changes between arms is how many threads run and which cores they may use:

  naive8      8 threads, scheduler places them (the out-of-the-box default of many apps)
  t4          4 threads, no pinning
  t4_big      4 threads pinned to the 4 fastest cores
  t4_little   4 threads pinned to the 4 slowest cores
  t3_big      3 threads pinned to the 3 fastest cores (what ExecuTorch's heuristic picks on the CMF)
  default     --cpu_threads -1: ExecuTorch's own performant-core heuristic

Cores are ranked by cpuinfo_max_freq read from the phone, so the masks fit any cluster layout.
Writes benchmarks/results/threads_<model>_<device>_<date>.csv and prints median per arm.
"""
import argparse
import csv
import datetime
import json
import os
import re
import statistics
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = os.environ.get("ADB") or str(Path.home() / "Android/Sdk/platform-tools/adb.exe")
DIR = "/data/local/tmp/shield"


def adb(serial, *a, timeout=300):
    r = subprocess.run([ADB, "-s", serial, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return r.stdout, r.stderr


def sh(serial, cmd, timeout=300):
    return adb(serial, "shell", cmd, timeout=timeout)[0]


def masks(serial):
    freqs = {}
    for line in sh(serial, "for c in /sys/devices/system/cpu/cpu[0-9]*; do echo ${c##*cpu} $(cat $c/cpufreq/cpuinfo_max_freq); done").split("\n"):
        p = line.split()
        if len(p) == 2 and p[0].isdigit() and p[1].isdigit():
            freqs[int(p[0])] = int(p[1])
    ranked = sorted(freqs, key=lambda c: (-freqs[c], -c))
    m = lambda cores: format(sum(1 << c for c in cores), "x")
    return freqs, {"big4": m(ranked[:4]), "little4": m(ranked[-4:]), "big3": m(ranked[:3])}


def state(serial):
    b = sh(serial, "dumpsys battery")
    lvl = re.search(r"level: (\d+)", b)
    temp = re.search(r"temperature: (\d+)", b)
    plugged = any(f"{k} powered: true" in b for k in ("AC", "USB", "Wireless"))
    return dict(battery=int(lvl.group(1)) if lvl else -1, temp_c=int(temp.group(1)) / 10 if temp else -1, plugged=plugged)


def run(serial, model, prompt, threads, mask, max_new):
    pre = f"taskset {mask} " if mask else ""
    cmd = (f"cd {DIR} && {pre}./llama_main --model_path {model} --tokenizer_path tokenizer.model --prompt_file {prompt} "
           f"--temperature 0 --max_new_tokens {max_new} --cpu_threads {threads} 2>bench.err")
    out, _ = adb(serial, "shell", cmd, timeout=600)
    err = sh(serial, f"cat {DIR}/bench.err")
    m = None
    for m in re.finditer(r"^PyTorchObserver (\{.*\})\s*$", out, re.M):
        pass
    if not m:
        return None
    d = json.loads(m.group(1))
    unit = d.get("SCALING_FACTOR_UNITS_PER_SECOND", 1000)
    rss = re.search(r"RSS after loading model: ([0-9.]+) MiB", err)
    pool = re.search(r"Resetting threadpool to (\d+) threads", err)
    return dict(
        prompt_tokens=d["prompt_tokens"], generated=d["generated_tokens"],
        prefill_tps=d.get("prefill_token_per_sec"), decode_tps=d.get("decode_token_per_sec"),
        ttft_s=(d["first_token_ms"] - d["inference_start_ms"]) / unit,
        load_s=(d["model_load_end_ms"] - d["model_load_start_ms"]) / unit,
        rss_mib=float(rss.group(1)) if rss else None, pool_threads=int(pool.group(1)) if pool else None,
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--model", default="llama3_2_1b_instruct_qlora_ctx2048.pte")
    ap.add_argument("--prompt", default="v4_en.txt")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--cooldown", type=int, default=20)
    ap.add_argument("--max-new", type=int, default=64)
    ap.add_argument("--arms", default="naive8,t4,t4_big,t4_little,t3_big,default")
    a = ap.parse_args()
    s = a.serial
    device = sh(s, "getprop ro.product.model").strip()
    soc = sh(s, "getprop ro.soc.model").strip()
    freqs, mk = masks(s)
    arms = {"naive8": (8, ""), "t4": (4, ""), "t4_big": (4, mk["big4"]), "t4_little": (4, mk["little4"]),
            "t3_big": (3, mk["big3"]), "default": (-1, "")}
    sel = [x for x in a.arms.split(",") if x in arms]
    print(f"{device} ({soc}) cores {freqs} masks {mk} | {state(s)}")
    rows = []
    for r in range(1, a.runs + 1):          # interleave arms so drift (heat, battery) spreads evenly
        for arm in sel:
            th, mask = arms[arm]
            time.sleep(a.cooldown)
            st = state(s)
            res = run(s, a.model, a.prompt, th, mask, a.max_new)
            if not res:
                print(f"{arm} run {r}: no stats (runner failed)")
                continue
            row = dict(device=device, soc=soc, model=a.model, arm=arm, threads=th, mask=mask or "all", run=r, **st, **res)
            rows.append(row)
            print(f"{arm:10s} run {r}: prefill {res['prefill_tps']:.1f} tok/s · decode {res['decode_tps']:.1f} tok/s · "
                  f"TTFT {res['ttft_s']:.2f} s · pool {res['pool_threads']} · {st['temp_c']} °C · batt {st['battery']}%{' (plugged)' if st['plugged'] else ''}")
    out = ROOT / "benchmarks/results" / f"threads_{Path(a.model).stem}_{device.replace(' ', '_')}_{datetime.date.today()}.csv"
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(f"\nSaved {out}\n\n{'arm':10s} {'prefill tok/s':>15s} {'decode tok/s':>14s} {'TTFT s':>8s}")
    for arm in sel:
        rr = [x for x in rows if x["arm"] == arm]
        if not rr:
            continue
        f = lambda k: (statistics.median(x[k] for x in rr), statistics.pstdev(x[k] for x in rr))
        p, d, t = f("prefill_tps"), f("decode_tps"), f("ttft_s")
        print(f"{arm:10s} {p[0]:8.1f} ± {p[1]:4.1f} {d[0]:8.1f} ± {d[1]:4.1f} {t[0]:8.2f}")


if __name__ == "__main__":
    main()
