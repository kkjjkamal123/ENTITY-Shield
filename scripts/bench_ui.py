"""UI smoothness benchmark: taps through the tabs (including far jumps such as Home -> More) and reads
Android's own frame statistics (dumpsys gfxinfo).

  python scripts/bench_ui.py --serial <adb serial> --label qa [--apk path] [--rounds 3]

The nav cells are found by their labels with uiautomator, so it works on any screen size. Output:
benchmarks/results/ui_frames_<model>_<label>_<date>.json with janky-frame share and frame-time
percentiles. Taps stay inside ENTITY Shield.
"""
import argparse
import datetime
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path.home() / "Android/Sdk/platform-tools/adb.exe")
PKG = "app.entity.shield"
SEQ = ["More", "Home", "Alerts", "More", "Network", "Home", "Scan", "Alerts", "More", "Home"]


def adb(s, *a, timeout=90):
    return subprocess.run([ADB, "-s", s, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout


def nav_cells(s):
    adb(s, "shell", "uiautomator dump /sdcard/ui.xml")
    xml = adb(s, "shell", "cat /sdcard/ui.xml")
    adb(s, "shell", "rm -f /sdcard/ui.xml")
    cells = {}
    for m in re.finditer(r'text="(Home|Alerts|Network|Scan|More)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        if m.group(1) not in cells or y1 > cells[m.group(1)][1]:   # the nav label is the lowest one
            cells[m.group(1)] = ((x1 + x2) // 2, (y1 + y2) // 2 - 20)
    return cells


def gfx(s):
    out = adb(s, "shell", f"dumpsys gfxinfo {PKG}")
    num = lambda pat: (lambda m: float(m.group(1)) if m else None)(re.search(pat, out))
    return dict(frames=num(r"Total frames rendered: (\d+)"), janky=num(r"Janky frames: (\d+)"),
                janky_pct=num(r"Janky frames: \d+ \(([\d.]+)%\)"),
                p50=num(r"50th percentile: (\d+)ms"), p90=num(r"90th percentile: (\d+)ms"),
                p95=num(r"95th percentile: (\d+)ms"), p99=num(r"99th percentile: (\d+)ms"),
                slow_ui=num(r"Number Slow UI thread: (\d+)"), slow_draw=num(r"Number Slow issue draw commands: (\d+)"),
                gpu_p90=num(r"50th gpu percentile: (\d+)ms"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--apk")
    ap.add_argument("--rounds", type=int, default=3)
    a = ap.parse_args()
    s = a.serial
    if a.apk:
        print(adb(s, "install", "-r", a.apk, timeout=300).strip().splitlines()[-1:])
        adb(s, "shell", f"cmd package compile -m speed-profile -f {PKG}", timeout=300)   # what the Play Store / profileinstaller does
    adb(s, "shell", "svc power stayon true; input keyevent KEYCODE_WAKEUP")
    adb(s, "shell", f"am start -n {PKG}/.MainActivity --ez e2e true")
    time.sleep(2)
    adb(s, "shell", "am", "broadcast", "-n", f"{PKG}/.DebugReceiver", "-a", f"{PKG}.DEBUG", "--es", "cmd", "lang", "--es", "v", "EN")  # nav labels are matched in English
    time.sleep(1)
    adb(s, "shell", f"am force-stop {PKG}")
    adb(s, "shell", f"am start -n {PKG}/.MainActivity --ez e2e true")
    time.sleep(4)
    cells = nav_cells(s)
    assert len(cells) == 5, f"nav not found: {cells}"
    adb(s, "shell", f"input tap {cells['Home'][0]} {cells['Home'][1]}")
    time.sleep(2.5)   # every tab warmed up
    adb(s, "shell", f"dumpsys gfxinfo {PKG} reset")
    for _ in range(a.rounds):
        for t in SEQ:
            adb(s, "shell", f"input tap {cells[t][0]} {cells[t][1]}")
            time.sleep(0.9)
    g = gfx(s)
    hz = adb(s, "shell", "dumpsys display | grep -m1 -o 'fps=[0-9.]*'").strip()
    res = dict(date=str(datetime.date.today()), device=adb(s, "shell", "getprop ro.product.model").strip(), label=a.label,
               display=hz, taps=len(SEQ) * a.rounds, sequence=SEQ, **g)
    adb(s, "shell", "svc power stayon false")
    out = ROOT / "benchmarks/results" / f"ui_frames_{res['device'].replace(' ', '_')}_{a.label}_{res['date']}.json"
    out.write_text(json.dumps(res, indent=1), encoding="utf-8", newline="\n")
    print(json.dumps(res))


if __name__ == "__main__":
    main()
