"""Helpers for the README screenshots: clean status bar (System UI demo mode), tap by visible text,
capture as JPEG. Drives the qa or debug build (needs the test hooks for language and sample data).

  python scripts/screenshots.py --serial <id> demo-on|demo-off
  python scripts/screenshots.py --serial <id> texts                 list visible texts and their centres
  python scripts/screenshots.py --serial <id> tap "<text>" [index]  tap the n-th element with that text
  python scripts/screenshots.py --serial <id> shot <name>           save docs/img/screens/<name>.jpg
  python scripts/screenshots.py --serial <id> hook <cmd> [key=value ...]   send a test-hook command

Taps stay inside ENTITY Shield. Notifications are hidden in demo mode, so nothing personal is captured.
"""
import argparse
import html
import io
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path.home() / "Android/Sdk/platform-tools/adb.exe")
OUT = ROOT / "docs" / "img" / "screens"


def adb(s, *a, binary=False, timeout=90):
    r = subprocess.run([ADB, "-s", s, *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def demo(s, on):
    if not on:
        adb(s, "shell", "am broadcast -a com.android.systemui.demo -e command exit")
        adb(s, "shell", "settings put global sysui_demo_allowed 0")
        return
    adb(s, "shell", "settings put global sysui_demo_allowed 1")
    for c in ["-e command enter", "-e command clock -e hhmm 0930", "-e command battery -e level 86 -e plugged false",
              "-e command network -e wifi show -e level 4 -e fully true", "-e command network -e mobile show -e level 4 -e datatype none",
              "-e command notifications -e visible false", "-e command status -e volume hidden -e bluetooth hidden -e location hidden -e alarm hidden"]:
        adb(s, "shell", f"am broadcast -a com.android.systemui.demo {c}")


def front(s):
    """Package of the window in front: capture nothing unless it is ENTITY Shield (privacy)."""
    m = re.search(r"mCurrentFocus=Window\{\S+ u\d+ ([\w.]+)", adb(s, "shell", "dumpsys window"))
    return m.group(1) if m else ""


def nodes(s):
    if front(s) != "app.entity.shield":
        sys.exit(f"ENTITY Shield is not in front ({front(s) or 'unknown'}): nothing captured")
    adb(s, "shell", "uiautomator dump /sdcard/ui.xml")
    xml = adb(s, "shell", "cat /sdcard/ui.xml")
    adb(s, "shell", "rm -f /sdcard/ui.xml")
    out = []
    for m in re.finditer(r'(?:text|content-desc)="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        out.append((html.unescape(m.group(1)), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("action")
    ap.add_argument("args", nargs="*")
    a = ap.parse_args()
    s = a.serial
    if a.action in ("demo-on", "demo-off"):
        demo(s, a.action == "demo-on")
    elif a.action == "texts":
        for t, x, y in nodes(s):
            print(f"{x:5d} {y:5d}  {t[:90]}")
    elif a.action == "tap":
        want, idx = a.args[0], int(a.args[1]) if len(a.args) > 1 else 0
        hits = [(x, y) for t, x, y in nodes(s) if t == want] or [(x, y) for t, x, y in nodes(s) if want in t]
        if len(hits) <= idx:
            sys.exit(f"not found: {want}")
        adb(s, "shell", f"input tap {hits[idx][0]} {hits[idx][1]}")
        print("tapped", want, hits[idx])
    elif a.action == "hook":
        q = lambda v: "'" + v.replace("'", "'\\''") + "'"
        extra = " ".join(f"--es {k} {q(v)}" for k, v in (kv.split("=", 1) for kv in a.args[1:]))
        adb(s, "shell", f"am broadcast -n app.entity.shield/.DebugReceiver -a app.entity.shield.DEBUG --es cmd {a.args[0]} {extra}")
    elif a.action == "shot":
        from PIL import Image
        if front(s) != "app.entity.shield":
            sys.exit(f"ENTITY Shield is not in front ({front(s) or 'unknown'}): nothing captured")
        png = adb(s, "exec-out", "screencap -p", binary=True)
        im = Image.open(io.BytesIO(png)).convert("RGB")
        # drop the status bar: media players and other apps can put personal content there
        m = re.search(r"statusBars frame=\[0,0\]\[\d+,(\d+)\]", adb(s, "shell", "dumpsys window"))
        top = (int(m.group(1)) if m else round(im.height * 0.05)) + 12   # plus the edge of a camera-cutout pill
        im = im.crop((0, top, im.width, im.height))
        w = 720
        im = im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)
        OUT.mkdir(parents=True, exist_ok=True)
        p = OUT / f"{a.args[0]}.jpg"
        im.save(p, quality=86, optimize=True, progressive=True)
        print("saved", p, im.size, p.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
