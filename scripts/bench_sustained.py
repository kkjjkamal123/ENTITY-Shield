"""Sustained Llama load on an Android phone: speed, temperature, memory and CPU, answer after answer.

  python scripts/bench_sustained.py --serial <adb serial> [--answers 12] [--max-new 96] [--on-device]

The report run: --model hf.pte --prompt sus_prompt.txt (benchmarks/sustained_prompt.txt pushed to the phone)
--answers 30 --max-new 160 --on-device.

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


def snap_cmd(zones):
    return ("dumpsys battery | grep -E 'temperature|level'; grep -E 'MemAvailable' /proc/meminfo; head -1 /proc/stat; "
            + "; ".join(f"echo Z {z} $(cat /sys/class/thermal/thermal_zone{z}/temp 2>/dev/null)" for z in zones))


def snapshot(serial, zones):
    return parse_snap(sh(serial, snap_cmd(zones)))


def parse_snap(out):
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
    return parse_answer(out, err)


def parse_answer(out, err):
    m = None
    for m in re.finditer(r"^PyTorchObserver (\{.*\})\s*$", out, re.M):
        pass
    if not m:
        print("   runner stopped early:", (err.strip().splitlines() or ["(no stderr)"])[-1][:160])
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


def on_device(s, a, zones):
    """The whole loop as a shell script on the phone, started with setsid/nohup: some phones (Infinix X6878)
    drop the USB data link under sustained CPU load, which kills an adb-driven loop. Results are pulled after."""
    import time
    snap = snap_cmd(zones)
    run = (f"./llama_main --model_path {a.model} --tokenizer_path tokenizer.model --prompt_file {a.prompt} "
           f"--temperature 0 --max_new_tokens {a.max_new} --cpu_threads -1")
    script = "\n".join([
        f"cd {DIR}",
        f"for i in $(seq 1 {a.answers}); do",
        f'echo "=BEFORE $i $(date +%s)"; {snap}',
        f"{run} 2>err.txt | grep PyTorchObserver; grep 'RSS after' err.txt",
        f'echo "=AFTER $i $(date +%s)"; {snap}',
        "done",
        "echo =DONE", ""])
    tmp = ROOT / "benchmarks/results/.sus_loop.sh"
    tmp.write_text(script, encoding="utf-8", newline="\n")
    if not a.collect:
        adb(s, "push", str(tmp), f"{DIR}/loop.sh")
        try:  # adb shell stays attached to the background job; the loop keeps running after the timeout
            sh(s, f"cd {DIR} && rm -f run.log && setsid nohup sh loop.sh > run.log 2>&1 < /dev/null &", timeout=10)
        except subprocess.TimeoutExpired:
            pass
        print("started on the phone; waiting (USB may drop and come back)")
    tmp.unlink()
    deadline = time.time() + a.answers * 60
    log = ""
    while time.time() < deadline:
        time.sleep(20)
        try:
            got = adb(s, "shell", f"cat {DIR}/run.log", timeout=30)
        except subprocess.TimeoutExpired:
            continue
        if "=BEFORE" not in got:  # phone off USB right now
            print("  (phone not reachable)", flush=True)
            continue
        log = got
        n = log.count("=AFTER")
        print(f"  {n}/{a.answers} answers", flush=True)
        if "=DONE" in log:
            break
    start = None
    rows = []
    t0 = None
    for blk in re.split(r"^=BEFORE ", log, flags=re.M)[1:]:
        i, ts = blk.split()[:2]
        pre, _, post = blk.partition("=AFTER ")
        before, after = parse_snap(pre), parse_snap(post)
        start = start or before
        t0 = t0 or int(ts)
        res = parse_answer(pre, pre)
        if not res or not post:
            print(f"answer {i}: runner failed")
            continue
        end = int(post.split()[1])
        rows.append(dict(answer=int(i), elapsed_s=end - t0, **res,
                         cpu_c_before=before["cpu_max_c"], cpu_c_after=after["cpu_max_c"],
                         battery_c_before=before["battery_c"], battery_c_after=after["battery_c"],
                         mem_available_mb=after["mem_available_mb"], cpu_util_pct=cpu_util(before["ticks"], after["ticks"]),
                         battery_pct=after["battery_pct"], retried=False))
    return start, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--model", default="llama3_2_1b_instruct_qlora_ctx2048.pte")
    ap.add_argument("--prompt", default="v4_en.txt")
    ap.add_argument("--answers", type=int, default=12)
    ap.add_argument("--max-new", type=int, default=96)
    ap.add_argument("--on-device", action="store_true", help="run the loop on the phone (survives USB drops)")
    ap.add_argument("--collect", action="store_true", help="with --on-device: only collect a loop that is already running")
    a = ap.parse_args()
    s = a.serial
    device = sh(s, "getprop ro.product.model").strip()
    soc = sh(s, "getprop ro.soc.model").strip()
    android = sh(s, "getprop ro.build.version.release").strip()
    zones = cpu_zones(s)
    print(f"{device} ({soc}, Android {android}); CPU thermal zones {zones}")
    if a.on_device:
        start, rows = on_device(s, a, zones)
    else:
        start, rows = snapshot(s, zones), []
    print("start", {k: v for k, v in start.items() if k != "ticks"})
    t0 = datetime.datetime.now()
    for i in range(1, 0 if a.on_device else a.answers + 1):
        before = snapshot(s, zones)
        res = answer(s, a.model, a.prompt, a.max_new)
        retried = False
        if not res:  # some vendor freezers kill a long shell process now and then; one retry, counted
            retried = True
            try:  # a USB link that drops for a moment: wait for the phone before the retry
                subprocess.run([ADB, "-s", s, "wait-for-device"], timeout=90)
            except subprocess.TimeoutExpired:
                print("   phone did not come back"); break
            res = answer(s, a.model, a.prompt, a.max_new)
        after = snapshot(s, zones)
        if not res:
            print(f"answer {i}: runner failed")
            continue
        row = dict(answer=i, elapsed_s=round((datetime.datetime.now() - t0).total_seconds()), **res,
                   cpu_c_before=before["cpu_max_c"], cpu_c_after=after["cpu_max_c"],
                   battery_c_before=before["battery_c"], battery_c_after=after["battery_c"],
                   mem_available_mb=after["mem_available_mb"], cpu_util_pct=cpu_util(before["ticks"], after["ticks"]),
                   battery_pct=after["battery_pct"], retried=retried)
        rows.append(row)
        print(f"#{i:2d} {row['elapsed_s']:4d}s  prefill {res['prefill_tps']:6.1f}  decode {res['decode_tps']:5.2f} tok/s  "
              f"TTFT {res['ttft_s']:.2f}s  RSS {res['peak_rss_mib']} MiB  CPU {row['cpu_c_after']} °C  batt {row['battery_c_after']} °C  "
              f"util {row['cpu_util_pct']}%  free {row['mem_available_mb']} MB")
    sh(s, "monkey -p app.entity.shield -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6")
    pss = None
    for _ in range(3):
        pss = pss or app_memory(s)
        if pss:
            break
        sh(s, "sleep 3")
    plugged = "powered: true" in sh(s, "dumpsys battery")
    first, last = rows[0], rows[-1]
    avg = lambda xs: round(sum(xs) / len(xs), 2)
    head, tail = rows[:3], rows[-3:]
    summary = dict(
        decode_avg_first3_last3=[avg([r["decode_tps"] for r in head]), avg([r["decode_tps"] for r in tail])],
        ttft_avg_first3_last3=[avg([r["ttft_s"] for r in head]), avg([r["ttft_s"] for r in tail])],
        answers=len(rows), minutes=round(last["elapsed_s"] / 60, 1),
        decode_first_last=[first["decode_tps"], last["decode_tps"]],
        decode_drop_pct=round(100 * (1 - last["decode_tps"] / first["decode_tps"]), 1),
        prefill_first_last=[first["prefill_tps"], last["prefill_tps"]],
        cpu_c_start_peak=[start["cpu_max_c"], max(r["cpu_c_after"] or 0 for r in rows)],
        battery_c_start_peak=[start["battery_c"], max(r["battery_c_after"] or 0 for r in rows)],
        peak_rss_mib_first_last=[first["peak_rss_mib"], last["peak_rss_mib"]],
        app_pss_mb_detectors_only=pss,
        failed_or_retried=a.answers - len(rows) + sum(r["retried"] for r in rows),
    )
    out = dict(device=device, soc=soc, android=android, date=str(datetime.date.today()), plugged=plugged, model=a.model,
               runtime="ExecuTorch llama_main, XNNPACK, --cpu_threads -1", mode="on-device loop" if a.on_device else "adb loop", prompt=a.prompt, max_new=a.max_new,
               thermal_zones=zones, start={k: v for k, v in start.items() if k != "ticks"}, summary=summary, answers=rows)
    path = ROOT / "benchmarks/results" / f"sustained_{device.replace(' ', '_')}_{datetime.date.today()}.json"
    path.write_text(json.dumps(out, indent=1), encoding="utf-8", newline="\n")
    print("\n", json.dumps(summary, indent=1), "\nSaved", path)


if __name__ == "__main__":
    main()
