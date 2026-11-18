"""Records the demo-video scenes from real phones (debug APK), driving the real pipeline over adb.

  python scripts/record_demo.py --serial <infinix> [--cmf <cmf serial>] [--scenes 1,2,3,4]

Scenes (see docs/DEMO.md):
  1  airplane mode on -> scam SMS through the real pipeline -> alert -> Llama explains (EN) -> Hindi -> steps
  2  Network Guard on -> "Beacon Simulator (test)" DGA burst + DNS exfiltration -> alerts naming the app
  3  (CMF on the laptop's Wi-Fi) LAN tripwire: this laptop scans the phone -> alert
  4  Lab -> Bench results
Clips land in demo/raw/. Coordinates are for a 1208x2644 screen (Infinix X6878) and scaled for others.
"""
import argparse
import re
import socket
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path.home() / "Android/Sdk/platform-tools/adb.exe")
PKG = "app.entity.shield"
RAW = ROOT / "demo" / "raw"
SCAM = "Dear customer your SBI account will be blocked today. Update KYC immediately at http://sbi-kyc-update.in/verify"


class P:
    def __init__(self, s):
        self.s = s
        size = self.sh("wm size").split(":")[-1].strip()
        self.w, self.h = (int(x) for x in size.split("x"))

    def adb(self, *a, timeout=120):
        return subprocess.run([ADB, "-s", self.s, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout

    def sh(self, c, timeout=120):
        return self.adb("shell", c, timeout=timeout)

    def cmd(self, name, **ex):
        a = ["shell", "am", "broadcast", "-n", f"{PKG}/.DebugReceiver", "-a", f"{PKG}.DEBUG", "--es", "cmd", name]
        for k, v in ex.items():
            a += (["--ei", k, str(v)] if isinstance(v, int) else ["--es", k, str(v)])
        self.adb(*a)

    def tap(self, x, y):  # coordinates measured on 1208x2644
        self.sh(f"input tap {int(x * self.w / 1208)} {int(y * self.h / 2644)}")

    def swipe_up(self):
        self.sh(f"input swipe {self.w // 2} {int(self.h * .75)} {self.w // 2} {int(self.h * .30)} 600")

    def start(self, *extra):
        self.sh(f"am start -n {PKG}/.MainActivity --ez e2e true {' '.join(extra)}")

    def record(self, name, secs):
        self.sh(f"rm -f /sdcard/{name}.mp4")
        return subprocess.Popen([ADB, "-s", self.s, "shell", f"screenrecord --bit-rate 6000000 --size 720x1576 --time-limit {secs} /sdcard/{name}.mp4"])

    def pull(self, name):
        RAW.mkdir(parents=True, exist_ok=True)
        self.adb("pull", f"/sdcard/{name}.mp4", str(RAW / f"{name}.mp4"), timeout=300)
        self.sh(f"rm -f /sdcard/{name}.mp4")
        print("saved", RAW / f"{name}.mp4")

    def last_id(self):
        out = self.adb("logcat", "-d", "-s", "ShieldE2E:I")
        ids = re.findall(r"sms id=(\d+)", out)
        return ids[-1] if ids else None


def clean(p, lang="EN"):
    p.sh("svc power stayon true")
    p.sh("input keyevent KEYCODE_WAKEUP")
    p.sh(f"am force-stop {PKG}")
    p.sh(f"am force-stop app.entity.beaconsim")
    p.start()
    time.sleep(2.5)
    p.cmd("clear")
    p.cmd("lang", v=lang)
    p.cmd("net", state="off")
    time.sleep(1)
    p.sh(f"am force-stop {PKG}")   # restart so the UI picks up the cleared state and language
    p.adb("logcat", "-c")
    p.start()
    time.sleep(3)


def scene1(p):
    clean(p)
    p.sh("cmd connectivity airplane-mode enable")
    time.sleep(2)
    rec = p.record("s1_sms_alert", 50)
    time.sleep(4)                                   # home, airplane icon visible
    p.cmd("sms", **{"from": "+919876543210", "body": f"'{SCAM}'"})
    time.sleep(3.5)                                 # notification pops
    i = p.last_id()
    p.start("--el", "alert", i)
    time.sleep(12)                                  # banner + Llama EN streams
    p.tap(1062, 212)                                # EN -> हिं
    time.sleep(14)                                  # Hindi explanation streams
    p.swipe_up()
    time.sleep(6)
    p.swipe_up()
    rec.wait()
    p.pull("s1_sms_alert")
    p.sh("cmd connectivity airplane-mode disable")


def scene2(p):
    clean(p)
    time.sleep(3)
    rec = p.record("s2_network", 55)
    p.tap(605, 2462)                                # Network tab
    time.sleep(3)
    p.cmd("net", state="on")                       # same as the "Turn on" button (consent already given)
    time.sleep(6)
    p.sh("am start -n app.entity.beaconsim/.MainActivity --es mode dga")
    time.sleep(12)
    p.sh("am force-stop app.entity.beaconsim")
    p.sh("am start -n app.entity.beaconsim/.MainActivity --es mode exfil")
    time.sleep(12)
    p.start("--ei", "tab", "1")                     # Alerts
    time.sleep(6)
    p.tap(600, 680)                                 # newest alert
    time.sleep(10)
    rec.wait()
    p.pull("s2_network")
    p.cmd("net", state="off")


def scene3(cmf):
    clean(cmf)
    ip = re.search(r"inet ([\d.]+)", cmf.sh("ip -4 addr show wlan0"))
    if not ip:
        print("CMF not on Wi-Fi: skip scene 3")
        return
    rec = cmf.record("s3_tripwire", 35)
    cmf.tap(605, 2462)
    time.sleep(2)
    cmf.cmd("net", state="on")
    time.sleep(8)
    for port in (2222, 2323, 8080, 8443, 9999):     # this laptop, same Wi-Fi: a quick port scan
        try:
            with socket.create_connection((ip.group(1), port), timeout=1.5) as s:
                s.sendall(b"GET / HTTP/1.0\r\n\r\n")
        except OSError:
            pass
        time.sleep(0.4)
    time.sleep(5)
    cmf.start("--ei", "tab", "1")
    time.sleep(4)
    cmf.tap(600, 680)
    time.sleep(10)
    rec.wait()
    cmf.pull("s3_tripwire")
    cmf.cmd("net", state="off")


def scene4(p):
    clean(p)
    p.cmd("bench", runs=2)
    t0 = time.time()
    while time.time() - t0 < 300 and "bench done" not in p.adb("logcat", "-d", "-s", "ShieldE2E:I"):
        time.sleep(3)
    p.start("--ei", "tab", "4")
    time.sleep(2)
    rec = p.record("s4_bench", 20)
    p.tap(560, 370)                                 # "Bench" chip
    time.sleep(5)
    p.swipe_up()
    time.sleep(6)
    p.swipe_up()
    rec.wait()
    p.pull("s4_bench")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--cmf")
    ap.add_argument("--scenes", default="1,2,3,4")
    a = ap.parse_args()
    p = P(a.serial)
    sc = a.scenes.split(",")
    if "1" in sc: scene1(p)
    if "2" in sc: scene2(p)
    if "3" in sc and a.cmf: scene3(P(a.cmf))
    if "4" in sc: scene4(p)


if __name__ == "__main__":
    main()
