"""Where the app's SMS verdict goes wrong, on the VALIDATION split (never the test split).

  .venv/Scripts/python scripts/analyze_sms_misses.py [--split val]

Prints, per source, missed scams and false alarms with the model band, the gate result and the
signals that fired, so rule changes are made on validation data and the test split stays held out.
"""
import argparse
import collections
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "training"))
sys.path.insert(0, str(ROOT / "training" / "sms"))
import featurize as F  # noqa: E402
from train_detector import run_pte, dense_x, sms_data  # noqa: E402

RANK = {"SAFE": 0, "UNCERTAIN": 1, "SCAM": 2}


def band(p, t):
    return "SCAM" if p >= t["scam"] else "UNCERTAIN" if p >= t["uncertain"] else "SAFE"


def verdicts(rows):
    md = ROOT / "models" / "detectors"
    ts = json.loads((ROOT / "training" / "sms" / "meta.json").read_text(encoding="utf-8"))["thresholds"]
    tu = json.loads((ROOT / "training" / "url" / "meta.json").read_text(encoding="utf-8"))["thresholds"]
    allow = set((ROOT / "training" / "url" / "allowlist.txt").read_text(encoding="utf-8").split())
    p_sms = run_pte(md / "sms.pte", [dense_x(*F.vector("sms", r["text"], r["sender"])) for r in rows])
    out = []
    for r, ps in zip(rows, p_sms):
        hosts = F.risky_hosts(r["text"])
        sc = [h for h in hosts if not F.allowlisted(h, allow)]
        pu = run_pte(md / "url.pte", [dense_x(*F.vector("url", "http://" + h)) for h in sc]) if sc else []
        scam_hosts = [h for h, p in zip(sc, pu) if p >= tu["scam"]]
        b = max(band(ps, ts), band(max(pu, default=0.0), tu) if sc else "SAFE", key=RANK.get)
        sig = dict(zip(F.SMS_SIGNALS, F.sms_signals(r["text"], r["sender"])))
        g, _ = F.sms_gate(b, sig, scam_hosts, r["text"], r["sender"], hosts)
        out.append(dict(r, p=round(float(ps), 3), model=b, app=g, sig=[k for k, v in sig.items() if v]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="val", choices=["train", "val"])
    ap.add_argument("--show", type=int, default=40)
    a = ap.parse_args()
    rows = verdicts(sms_data()[0][a.split])
    for src in ("indian", "uci"):
        rs = [r for r in rows if r["source"] == src]
        pos = [r for r in rs if r["label"] == 1]
        neg = [r for r in rs if r["label"] == 0]
        print(f"\n=== {src} ({a.split}): {len(pos)} scams, {len(neg)} legit")
        for k in ("model", "app"):
            c = collections.Counter(r[k] for r in pos)
            f = collections.Counter(r[k] for r in neg)
            print(f"  {k:5s} scams -> {dict(c)}   legit -> {dict(f)}")
        miss = [r for r in pos if r["app"] == "SAFE"]
        weak = [r for r in pos if r["app"] == "UNCERTAIN"]
        fa = [r for r in neg if r["app"] != "SAFE"]
        for name, lst in (("MISSED (app SAFE)", miss), ("ONLY 'be careful'", weak), ("FALSE ALARM", fa)):
            print(f"\n  -- {name}: {len(lst)}")
            by_t = collections.Counter(r["template"] for r in lst)
            if src == "indian":
                print("    by template:", by_t.most_common(12))
            for r in lst[: a.show]:
                print(f"    [{r['model']}->{r['app']} p={r['p']}] {r['template']} | {r['text'][:110]!r} | {','.join(r['sig'])}")


if __name__ == "__main__":
    main()
