"""Shield's SMS verdict on the detector's held-out test split: the model alone vs. the app's full path.

  .venv/Scripts/python scripts/eval_sms_heldout.py [--out FILE]

The test split is the one training/train_detector.py held out (same seed): UCI messages plus Indian
templates the model never saw. "App" mirrors Detectors.checkSms(): SMS model, URL model on each
non-official link host, the worse band, then the scam gate (training/featurize.py sms_gate). Contacts
and the user's own labels are not part of this test.
"""
import argparse
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


def stats(rows, key, flagged):
    hit = (lambda b: b != "SAFE") if flagged else (lambda b: b == "SCAM")
    pos = [r for r in rows if r["label"] == 1]
    neg = [r for r in rows if r["label"] == 0]
    tp = sum(hit(r[key]) for r in pos)
    fp = sum(hit(r[key]) for r in neg)
    return dict(n=len(rows), pos=len(pos), neg=len(neg), recall=round(tp / max(1, len(pos)), 4),
                fpr=round(fp / max(1, len(neg)), 4), precision=round(tp / max(1, tp + fp), 4))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    a = ap.parse_args()
    md = ROOT / "models" / "detectors"
    ts = json.loads((ROOT / "training" / "sms" / "meta.json").read_text(encoding="utf-8"))["thresholds"]
    tu = json.loads((ROOT / "training" / "url" / "meta.json").read_text(encoding="utf-8"))["thresholds"]
    allow = set((ROOT / "training" / "url" / "allowlist.txt").read_text(encoding="utf-8").split())
    test = sms_data()[0]["test"]
    p_sms = run_pte(md / "sms.pte", [dense_x(*F.vector("sms", r["text"], r["sender"])) for r in test])
    rows = []
    for r, ps in zip(test, p_sms):
        hosts = F.risky_hosts(r["text"])
        sc = [h for h in hosts if not F.allowlisted(h, allow)]
        pu = run_pte(md / "url.pte", [dense_x(*F.vector("url", "http://" + h)) for h in sc]) if sc else []
        scam_hosts = [h for h, p in zip(sc, pu) if p >= tu["scam"]]
        b = max(band(ps, ts), band(max(pu, default=0.0), tu) if sc else "SAFE", key=RANK.get)
        sig = dict(zip(F.SMS_SIGNALS, F.sms_signals(r["text"], r["sender"])))
        g, _ = F.sms_gate(b, sig, scam_hosts, r["text"], r["sender"], hosts)
        rows.append(dict(source=r["source"], label=r["label"], model=band(ps, ts), app=g))
    out = {}
    for name, sel in (("all", rows), ("uci", [r for r in rows if r["source"] == "uci"]), ("indian", [r for r in rows if r["source"] != "uci"])):
        out[name] = {f"{k}_{lvl}": stats(sel, k, lvl == "flagged") for k in ("model", "app") for lvl in ("scam", "flagged")}
    print(json.dumps(out, indent=1))
    if a.out:
        Path(a.out).write_text(json.dumps(out, indent=1), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
