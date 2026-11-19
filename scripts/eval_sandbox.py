"""Does following the link beat guessing from the address? Runs the link sandbox on a real phone.

  python scripts/eval_sandbox.py --serial <adb serial> [--n 40]

Sets (all fetched by the phone itself through LinkInspector, debug build, `inspect` command):
  phishing   n live URLs from the OpenPhish community feed of the day (datasets/openphish/feed_live_*.txt)
  brands     40 big Indian banks, government, payment, shopping and news sites (should be safe)
  longtail   n random .in sites from Tranco ranks 20k-300k: real, ordinary, not on any allow-list
For each URL: the address-only verdict (ExecuTorch URL model, what Shield did before) and the sandbox
verdict (visit + isolated page analysis + text model). Dead phishing links (taken down, DNS gone) are
reported separately: nothing to read, the address check is all there is.
Output: benchmarks/results/sandbox_eval_<date>.json
"""
import argparse
import csv
import datetime
import json
import random
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path.home() / "Android/Sdk/platform-tools/adb.exe")
PKG = "app.entity.shield"
META = json.loads((ROOT / "training/url/meta.json").read_text(encoding="utf-8"))["thresholds"]
BRANDS = ["https://www.onlinesbi.sbi/", "https://www.hdfcbank.com/", "https://www.icicibank.com/", "https://www.axisbank.com/",
          "https://www.kotak.com/", "https://www.pnbindia.in/", "https://paytm.com/", "https://www.phonepe.com/",
          "https://pay.google.com/", "https://www.irctc.co.in/", "https://www.indiapost.gov.in/", "https://www.amazon.in/",
          "https://www.flipkart.com/", "https://uidai.gov.in/", "https://www.incometax.gov.in/", "https://www.epfindia.gov.in/",
          "https://www.npci.org.in/", "https://www.jio.com/", "https://www.airtel.in/", "https://cybercrime.gov.in/",
          "https://www.myntra.com/", "https://www.swiggy.com/", "https://www.zomato.com/", "https://www.bigbasket.com/",
          "https://www.nykaa.com/", "https://www.makemytrip.com/", "https://zerodha.com/", "https://groww.in/",
          "https://www.policybazaar.com/", "https://in.bookmyshow.com/", "https://www.thehindu.com/", "https://indianexpress.com/",
          "https://www.ndtv.com/", "https://www.cricbuzz.com/", "https://www.naukri.com/", "https://www.olx.in/",
          "https://www.meesho.com/", "https://www.ajio.com/", "https://licindia.in/", "https://www.sbicard.com/"]
LINE = re.compile(r"inspect lex0=([\d.]+) url=(\S+) final=(\S*) hops=(\d+) error=(\S+) band=(\w+) score=([\d.]+) page=([\d.]+) lex=([\d.]+) signals=(\S*)")


def adb(serial, *a, timeout=60):
    return subprocess.run([ADB, "-s", serial, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout


def inspect(serial, url):
    adb(serial, "logcat", "-c")
    adb(serial, "shell", "am", "broadcast", "-n", f"{PKG}/.DebugReceiver", "-a", f"{PKG}.DEBUG", "--es", "cmd", "inspect", "--es", "link", url)
    t0 = time.time()
    while time.time() - t0 < 30:
        out = adb(serial, "logcat", "-d", "-s", "ShieldSandbox:I")
        m = LINE.search(out)
        if m:
            g = m.groups()
            return dict(url=url, lex0=float(g[0]), final=g[2], hops=int(g[3]), error=None if g[4] == "null" else g[4], band=g[5],
                        score=float(g[6]), page=float(g[7]), lex=float(g[8]), signals=[s for s in g[9].split(",") if s])
        time.sleep(0.8)
    return dict(url=url, error="no_result")


def band(p):
    return "SCAM" if p >= META["scam"] else "UNCERTAIN" if p >= META["uncertain"] else "SAFE"


def summary(rows):
    ok = [r for r in rows if r.get("band")]
    alive = [r for r in ok if not r.get("error")]
    def dist(rs, key):
        return {b: sum(1 for r in rs if key(r) == b) for b in ("SCAM", "UNCERTAIN", "SAFE")}
    return dict(n=len(rows), answered=len(ok), alive=len(alive),
                address_only=dist(alive, lambda r: band(r["lex0"])), sandbox=dist(alive, lambda r: r["band"]),
                dead_or_unreachable=dist([r for r in ok if r.get("error")], lambda r: r["band"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--n", type=int, default=40)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--tag", default="tuning", help="tuning (the sample the weights were set on) or heldout")
    ap.add_argument("--exclude", nargs="*", default=[], help="earlier result files whose URLs must not be reused")
    a = ap.parse_args()
    r = random.Random(a.seed)
    used = set()
    for f in a.exclude:
        for v in json.loads(Path(f).read_text(encoding="utf-8"))["sets"].values():
            used |= {row["url"] for row in v["rows"]}
    feed = sorted(ROOT.glob("datasets/openphish/feed_live_*.txt"))[-1]
    phish = r.sample([u.strip() for u in feed.read_text(encoding="utf-8").splitlines() if u.startswith("http") and u.strip() not in used], a.n)
    tranco = [row[1] for row in csv.reader(open(ROOT / "datasets/tranco/top-1m.csv", encoding="utf-8"))]
    longtail = ["https://" + d + "/" for d in r.sample([d for d in tranco[20_000:300_000] if d.endswith(".in") and "https://" + d + "/" not in used], a.n)]
    adb(a.serial, "shell", "svc power stayon true; input keyevent KEYCODE_WAKEUP")
    adb(a.serial, "shell", f"am start -n {PKG}/.MainActivity --ez e2e true")
    time.sleep(3)
    res = {"date": str(datetime.date.today()), "device": adb(a.serial, "shell", "getprop ro.product.model").strip(),
           "feed": feed.name, "thresholds": META, "tag": a.tag, "seed": a.seed, "sets": {}}
    sets = (("phishing", phish), ("brands", BRANDS), ("longtail", longtail)) if a.tag == "tuning" else (("phishing", phish), ("longtail", longtail))
    for name, urls in sets:
        rows = []
        for i, u in enumerate(urls):
            row = inspect(a.serial, u)
            rows.append(row)
            print(f"{name} {i + 1}/{len(urls)} {row.get('band')} lex0={row.get('lex0')} err={row.get('error')} {u[:70]}", flush=True)
        res["sets"][name] = {"summary": summary(rows), "rows": rows}
    adb(a.serial, "shell", "svc power stayon false")
    out = ROOT / "benchmarks/results" / f"sandbox_eval_{a.tag}_{res['device'].replace(' ', '_')}_{res['date']}.json"
    out.write_text(json.dumps(res, indent=1), encoding="utf-8", newline="\n")
    for k, v in res["sets"].items():
        print(k, json.dumps(v["summary"]))
    print("saved", out)


if __name__ == "__main__":
    main()
