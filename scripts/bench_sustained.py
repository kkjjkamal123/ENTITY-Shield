"""Sustained Llama load on an Android phone: speed, temperature, memory and CPU, answer after answer.

  python scripts/bench_sustained.py --serial <adb serial> [--answers 12] [--max-new 96]

Runs ExecuTorch's llama_main (already in /data/local/tmp/shield) back to back with no cool-down, the
worst case for a phone: a user opening alert after alert. ExecuTorch picks its own thread count, as in
the app. For every answer it records prefill / decode tok/s, time to first token, model load time,
the runner's peak RSS, the hottest CPU thermal zone and the battery temperature before and after,
free memory, and whole-phone CPU utilisation while the answer ran. Then it measures the app itself
at rest (PSS with the detectors loaded, no Llama).

Writes benchmarks/results/sustained_<device>_<date>.json.
"""
import argparse
import datetime
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = os.environ.get("ADB") or str(Path.home() / "Android/Sdk/platform-tools/adb.exe")
DIR = "/data/local/tmp/shield"


def adb(serial, *a, timeout=600):
    r = subprocess.run([ADB, "-s", serial, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return r.stdout


def sh(serial, cmd, timeout=600):
    return adb(serial, "shell", cmd, timeout=timeout)


def cpu_zones(serial):
    """Thermal zones whose type names a CPU cluster (readable by the shell user on most phones)."""
    out = sh(serial, "for z in /sys/class/thermal/thermal_zone*; do echo ${z##*zone} $(cat $z/type 2>/dev/null); done")
    zs = []
    for line in out.splitlines():
        p = line.split(maxsplit=1)
        if len(p) == 2 and re.search(r"cpu|cpuss|cluster|apc|little|big|mid|soc", p[1], re.I) and not re.search(r"gpu|usb|batt|skin|pa_|modem", p[1], re.I):
            zs.append(p[0])
    return zs


def snapshot(serial, zones):
    cmd = ("dumpsys battery | grep -E 'temperature|level'; grep -E 'MemAvailable' /proc/meminfo; head -1 /proc/stat; "
           + "; ".join(f"echo Z {z} $(cat /sys/class/thermal/thermal_zone{z}/temp 2>/dev/null)" for z in zones))
    out = sh(serial, cmd)
    t = re.search(r"temperature: (\d+)", out)
    lvl = re.search(r"level: (\d+)", out)
    mem = re.search(r"MemAvailable:\s+(\d+)", out)
    cpu = re.search(r"^cpu\s+(.+)$", out, re.M)
    zt = []
    for m in re.finditer(r"^Z \d+ (-?\d+)\s*$", out, re.M):
        v = int(m.group(1))
        zt.append(v / 1000 if v > 1000 else v)
    ticks = [int(x) for x in cpu.group(1).split()] if cpu else None
    return dict(battery_c=int(t.group(1)) / 10 if t else None, battery_pct=int(lvl.group(1)) if lvl else None,
                mem_available_mb=round(int(mem.group(1)) / 1024) if mem else None,
                cpu_max_c=max(zt) if zt else None, ticks=ticks)


def cpu_util(a, b):
    if not a or not b:
        return None
    d = [y - x for x, y in zip(a, b)]
    idle = d[3] + (d[4] if len(d) > 4 else 0)
    return round(100 * (1 - idle / max(1, sum(d))), 1)


def answer(serial, model, prompt, max_new):
    cmd = (f"cd {DIR} && ./llama_main --model_path {model} --tokenizer_path tokenizer.model --prompt_file {prompt} "
           f"--temperature 0 --max_new_tokens {max_new} --cpu_threads -1 2>sus.err")
    out = adb(serial, "shell", cmd)
    err = sh(serial, f"cat {DIR}/sus.err")
    m = None
    for m in re.finditer(r"^PyTorchObserver (\{.*\})\s*$", out, re.M):
        pass
    if not m:
        return None
    d = json.loads(m.group(1))
    unit = d.get("SCALING_FACTOR_UNITS_PER_SECOND", 1000)
    rss = [float(x) for x in re.findall(r"RSS after [a-z ]+: ([0-9.]+) MiB", err)]
    return dict(prompt_tokens=d["prompt_tokens"], generated=d["generated_tokens"],
                prefill_tps=round(d["prefill_token_per_sec"], 1), decode_tps=round(d["decode_token_per_sec"], 2),
                ttft_s=round((d["first_token_ms"] - d["inference_start_ms"]) / unit, 3),
                load_s=round((d["model_load_end_ms"] - d["model_load_start_ms"]) / unit, 3),
                peak_rss_mib=max(rss) if rss else None)


def app_memory(serial, pkg="app.entity.shield"):
    out = sh(serial, f"dumpsys meminfo {pkg}")
    tot = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"^\s*TOTAL\s+(\d+)", out, re.M)
    return round(int(tot.group(1)) / 1024, 1) if tot else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--model", default="llama3_2_1b_instruct_qlora_ctx2048.pte")
    ap.add_argument("--prompt", default="v4_en.txt")
    ap.add_argument("--answers", type=int, default=12)
    ap.add_argument("--max-new", type=int, default=96)
    a = ap.parse_args()
    s = a.serial
    device = sh(s, "getprop ro.product.model").strip()
    soc = sh(s, "getprop ro.soc.model").strip()
    android = sh(s, "getprop ro.build.version.release").strip()
    zones = cpu_zones(s)
    print(f"{device} ({soc}, Android {android}); CPU thermal zones {zones}")
    start = snapshot(s, zones)
    print("start", {k: v for k, v in start.items() if k != "ticks"})
    rows = []
    t0 = datetime.datetime.now()
    for i in range(1, a.answers + 1):
        before = snapshot(s, zones)
        res = answer(s, a.model, a.prompt, a.max_new)
        after = snapshot(s, zones)
        if not res:
            print(f"answer {i}: runner failed")
            continue
        row = dict(answer=i, elapsed_s=round((datetime.datetime.now() - t0).total_seconds()), **res,
                   cpu_c_before=before["cpu_max_c"], cpu_c_after=after["cpu_max_c"],
                   battery_c_before=before["battery_c"], battery_c_after=after["battery_c"],
                   mem_available_mb=after["mem_available_mb"], cpu_util_pct=cpu_util(before["ticks"], after["ticks"]),
                   battery_pct=after["battery_pct"])
        rows.append(row)
        print(f"#{i:2d} {row['elapsed_s']:4d}s  prefill {res['prefill_tps']:6.1f}  decode {res['decode_tps']:5.2f} tok/s  "
              f"TTFT {res['ttft_s']:.2f}s  RSS {res['peak_rss_mib']} MiB  CPU {row['cpu_c_after']} °C  batt {row['battery_c_after']} °C  "
              f"util {row['cpu_util_pct']}%  free {row['mem_available_mb']} MB")
    sh(s, "monkey -p app.entity.shield -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6")
    pss = app_memory(s)
    plugged = "powered: true" in sh(s, "dumpsys battery")
    first, last = rows[0], rows[-1]
    summary = dict(
        answers=len(rows), minutes=round(last["elapsed_s"] / 60, 1),
        decode_first_last=[first["decode_tps"], last["decode_tps"]],
        decode_drop_pct=round(100 * (1 - last["decode_tps"] / first["decode_tps"]), 1),
        prefill_first_last=[first["prefill_tps"], last["prefill_tps"]],
        cpu_c_start_peak=[start["cpu_max_c"], max(r["cpu_c_after"] or 0 for r in rows)],
        battery_c_start_peak=[start["battery_c"], max(r["battery_c_after"] or 0 for r in rows)],
        peak_rss_mib_first_last=[first["peak_rss_mib"], last["peak_rss_mib"]],
        app_pss_mb_detectors_only=pss,
    )
    out = dict(device=device, soc=soc, android=android, date=str(datetime.date.today()), plugged=plugged, model=a.model,
               runtime="ExecuTorch llama_main, XNNPACK, --cpu_threads -1", prompt=a.prompt, max_new=a.max_new,
               thermal_zones=zones, start={k: v for k, v in start.items() if k != "ticks"}, summary=summary, answers=rows)
    path = ROOT / "benchmarks/results" / f"sustained_{device.replace(' ', '_')}_{datetime.date.today()}.json"
    path.write_text(json.dumps(out, indent=1), encoding="utf-8", newline="\n")
    print("\n", json.dumps(summary, indent=1), "\nSaved", path)


if __name__ == "__main__":
    main()
