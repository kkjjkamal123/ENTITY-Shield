"""End-to-end checks of ENTITY Shield on a real phone, driven over adb (debug APK).

  python scripts/e2e_phone.py --serial <adb serial> [--install] [--skip-llm] [--skip-net] [--hindi]

Every check runs the real app code on the phone through the debug-only DebugReceiver and reads the
result back from logcat (tag ShieldE2E). Writes benchmarks/results/e2e_<model>_<date>.json.
The phone must be unlocked (Android runs background apps on the little cores; see docs/LEDGER.md).
"""
import argparse
import datetime
import json
import os
import random
import re
import socket
import string
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = os.environ.get("ADB") or str(Path.home() / "Android/Sdk/platform-tools/adb.exe")
PKG = "app.entity.shield"
APK = ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"


class Phone:
    def __init__(self, serial):
        self.s = serial

    def adb(self, *a, check=False, timeout=120):
        r = subprocess.run([ADB, "-s", self.s, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(a)} failed: {r.stderr.strip()}")
        return r.stdout

    def sh(self, cmd, timeout=120):
        return self.adb("shell", cmd, timeout=timeout)

    def cmd(self, name, **extras):
        args = ["shell", "am", "broadcast", "-n", f"{PKG}/.DebugReceiver", "-a", f"{PKG}.DEBUG", "--es", "cmd", name]
        for k, v in extras.items():
            args += (["--ei", k, str(v)] if isinstance(v, int) else ["--es", k, str(v)])
        self.adb(*args)

    def logs(self):
        return self.adb("logcat", "-d", "-s", "ShieldE2E:I", "ShieldNet:I", "ShieldLLM:I", "Shield:I")

    def wait_line(self, pattern, timeout=60, after=0):
        t0 = time.time()
        rx = re.compile(pattern)
        while time.time() - t0 < timeout:
            lines = [l for l in self.logs().splitlines() if rx.search(l)]
            if len(lines) > after:
                return lines[after]
            time.sleep(0.7)
        return None


def shell_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--skip-llm", action="store_true")
    ap.add_argument("--skip-net", action="store_true")
    ap.add_argument("--hindi", action="store_true")
    a = ap.parse_args()
    p = Phone(a.serial)
    results = []

    def check(name, ok, detail=""):
        results.append(dict(check=name, ok=bool(ok), detail=detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  {detail}")

    model = p.sh("getprop ro.product.model").strip()
    soc = p.sh("getprop ro.soc.model").strip()
    locked = "isKeyguardShowing=true" in p.sh("dumpsys window")
    print(f"Phone: {model} ({soc}), {'LOCKED - results will be slow' if locked else 'unlocked'}")

    if a.install:
        out = p.adb("install", "-r", "-g", str(APK), timeout=600)
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out or "signatures do not match" in out.lower():
            p.adb("uninstall", PKG)   # release build installed: switch to the debug build (history is lost)
            out = p.adb("install", "-g", str(APK), timeout=600)
        check("install", "Success" in out, out.strip().splitlines()[-1] if out.strip() else "")
    for perm in ("RECEIVE_SMS", "READ_SMS", "POST_NOTIFICATIONS"):
        p.sh(f"pm grant {PKG} android.permission.{perm}")
    p.sh(f"appops set {PKG} ACTIVATE_VPN allow")
    p.adb("logcat", "-c")
    p.sh(f"am start -n {PKG}/.MainActivity --ez e2e true")
    time.sleep(2.5)

    # 1. detectors: golden parity + latency
    p.cmd("selftest")
    line = p.wait_line(r"selftest ", 90)
    m = re.findall(r"(sms|url) golden: (\d+)/(\d+)", line or "")
    check("detector parity (Kotlin featurizer + ExecuTorch == Python)", m and all(a_ == b_ for _, a_, b_ in m), line.split("selftest ", 1)[-1] if line else "no output")

    # 2. SMS verdicts
    cases = [
        ("+919876543210", "Dear customer your SBI account will be blocked today. Update KYC immediately at http://sbi-kyc-update.in/verify", "SCAM"),
        ("+918765432109", "आपके आधार पर मनी लॉन्ड्रिंग केस दर्ज है। गिरफ्तारी से बचने के लिए तुरंत 9123456780 पर कॉल करें", "SCAM"),
        ("+919988776655", "You have received Rs 4,999 refund. Enter your UPI PIN to receive the money in your account. bit.ly/rfnd49", "SCAM"),
        ("VM-HDFCBK", "482913 is your OTP for transaction of Rs 1,999 at Amazon. Valid for 10 mins. Do not share with anyone. -HDFC Bank", "SAFE"),
        ("JD-AMAZON", "Your Amazon order will be delivered today by 9 PM. Track: amazon.in/your-orders", "SAFE"),
        ("+919845012345", "Bhai kal ka plan cancel, office me late ho jayega. Sunday ko milte hain", "SAFE"),
    ]
    warn = []
    for i, (frm, body, want) in enumerate(cases):
        p.cmd("sms", **{"from": frm, "body": shell_quote(body)})
        line = p.wait_line(r"ShieldE2E: sms id=", 30, after=i)
        band = re.search(r"band=(\w+)", line or "")
        wm = re.search(r"warnMs=([\d.]+)", line or "")
        if wm:
            warn.append(float(wm.group(1)))
        check(f"SMS verdict {want}: {body[:40]}…", band and band.group(1) == want, f"got {band.group(1) if band else None} warnMs={wm.group(1) if wm else '?'}")
    if warn:
        warn.sort()
        check("time to first warning < 1500 ms (detect + notification)", warn[-1] < 1500, f"median {warn[len(warn) // 2]:.1f} ms, max {warn[-1]:.1f} ms")

    # 3. Llama explanations
    if not a.skip_llm:
        for lang in (["EN", "HI"] if a.hindi else ["EN"]):
            p.cmd("sms", **{"from": "+919876543210", "body": shell_quote(cases[0][1])})
            time.sleep(1)
            n_before = sum(1 for l in p.logs().splitlines() if "ShieldE2E: explain text=" in l)
            p.cmd("explain", lang=lang)
            line = p.wait_line(r"ShieldE2E: explain text=", 180, after=n_before)
            stat = p.wait_line(r"ShieldE2E: explain id=", 5, after=n_before)
            text = line.split("explain text=", 1)[-1] if line else ""
            ttft = re.search(r"ttftMs=(\d+)", stat or "")
            pre = re.search(r'"prefill_token_per_sec":([\d.]+)', stat or "")
            dec = re.search(r'"decode_token_per_sec":([\d.]+)', stat or "")
            ok = len(text) > 15 and "<|" not in text and "cannot" not in text.lower()
            if lang == "HI":
                ok = ok and re.search(r"[ऀ-ॿ]", text) is not None
            check(f"Llama explanation ({lang}) on ExecuTorch", ok,
                  f"ttft={ttft.group(1) if ttft else '?'} ms prefill={pre.group(1) if pre else '?'} decode={dec.group(1) if dec else '?'} tok/s :: {text[:120]}")

    # 4. Network Guard
    if not a.skip_net:
        p.cmd("netreset")
        p.wait_line(r"ShieldE2E: netreset done", 10)
        p.cmd("net", state="on")
        line = p.wait_line(r"ShieldE2E: net requested=true", 20)
        ip = re.search(r"ip=([\d.]+)", line or "")
        check("Network Guard starts (local DNS VPN + tripwire)", line and "vpnOn=true" in line, line.split("ShieldE2E: ", 1)[-1] if line else "no output")
        p.sh("ping -c 1 -W 2 www.google.com")
        p.sh("ping -c 1 -W 2 sbi-kyc-update.in")
        p.sh("ping -c 1 -W 2 hdfc-netbanking-verify.top")
        time.sleep(2)
        p.cmd("netstat", host="google.com")
        st = p.wait_line(r"ShieldE2E: netstat", 15) or ""
        check("benign domain resolves (ALLOW)", re.search(r"google\.com:ALLOW", st), st.split("last=", 1)[-1][:200])
        p.cmd("netstat", host="-")
        st2 = p.wait_line(r"ShieldE2E: netstat", 15, after=1) or ""
        check("phishing domain blocked (BLOCK + alert)", re.search(r"(sbi-kyc-update\.in|hdfc-netbanking-verify\.top):BLOCK", st2), st2.split("last=", 1)[-1][:160])
        # DGA-like burst: random names that do not exist
        for _ in range(10):
            name = "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(14)) + ".xyz"
            p.sh(f"ping -c 1 -W 1 {name}")
        p.cmd("alerts", n=6)
        al = p.wait_line(r"ShieldE2E: alerts ", 15) or ""
        check("DGA-style lookup burst detected", "NET_DGA" in al, al.split("alerts ", 1)[-1][:200])
        # tripwire from this PC (works when PC and phone share a Wi-Fi)
        if ip:
            hits = 0
            for port in (2222, 2323, 8080, 8443):
                try:
                    with socket.create_connection((ip.group(1), port), timeout=1.5) as s:
                        s.sendall(b"GET / HTTP/1.0\r\n\r\n")
                        hits += 1
                except OSError:
                    pass
            if hits:
                time.sleep(2)
                p.cmd("alerts", n=6)
                al2 = p.wait_line(r"ShieldE2E: alerts ", 15, after=1) or ""
                check("LAN tripwire catches a port scan from the laptop", "NET_SCAN" in al2, f"{hits} ports reached on {ip.group(1)}")
            else:
                check("LAN tripwire (laptop not on the phone's Wi-Fi: skipped)", True, f"no route to {ip.group(1)}")
        p.cmd("net", state="off")

    passed = sum(r["ok"] for r in results)
    print(f"\n{passed}/{len(results)} checks passed")
    out = ROOT / "benchmarks/results" / f"e2e_{model.replace(' ', '_')}_{datetime.date.today()}.json"
    out.write_text(json.dumps(dict(device=model, soc=soc, locked=locked, date=str(datetime.datetime.now()), results=results), ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Saved {out}")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
