"""How Shield's SMS verdict does on the held-out challenge set (training/sms/challenge.py).

  .venv/Scripts/python scripts/eval_sms_challenge.py [--models DIR] [--no-gate] [--out FILE]

Mirrors Detectors.checkSms() in the app: SMS model on the text + sender, URL model on every link
host that is not an official brand domain (allow-listed hosts score 0), verdict = the worse band,
then the scam gate (training/featurize.py sms_gate) unless --no-gate.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "training"))
sys.path.insert(0, str(ROOT / "training" / "sms"))
import featurize as F  # noqa: E402
import challenge  # noqa: E402
from train_detector import run_pte, dense_x  # noqa: E402


def band(p, t):
    return "SCAM" if p >= t["scam"] else "UNCERTAIN" if p >= t["uncertain"] else "SAFE"


RANK = {"SAFE": 0, "UNCERTAIN": 1, "SCAM": 2}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=str(ROOT / "models" / "detectors"))
    ap.add_argument("--sms-meta", default=str(ROOT / "training" / "sms" / "meta.json"))
    ap.add_argument("--no-gate", action="store_true")
    ap.add_argument("--out")
    a = ap.parse_args()
    md = Path(a.models)
    ts = json.loads(Path(a.sms_meta).read_text(encoding="utf-8"))["thresholds"]
    tu = json.loads((ROOT / "training" / "url" / "meta.json").read_text(encoding="utf-8"))["thresholds"]
    allow = set((ROOT / "training" / "url" / "allowlist.txt").read_text(encoding="utf-8").split())

    rows = challenge.rows()
    p_sms = run_pte(md / "sms.pte", [dense_x(*F.vector("sms", r["text"], r["sender"])) for r in rows])
    out = []
    for r, ps in zip(rows, p_sms):
        hosts = F.risky_hosts(r["text"])
        sc = [h for h in hosts if not F.allowlisted(h, allow)]
        pu = run_pte(md / "url.pte", [dense_x(*F.vector("url", "http://" + h)) for h in sc]) if sc else []
        scam_hosts = [h for h, p in zip(sc, pu) if p >= tu["scam"]]
        p_url = max(pu, default=0.0)
        b_url = band(p_url, tu) if sc else "SAFE"
        b = max(band(ps, ts), b_url, key=RANK.get)
        sig = dict(zip(F.SMS_SIGNALS, F.sms_signals(r["text"], r["sender"])))
        fam = None
        if not a.no_gate:
            b, fam = F.sms_gate(b, sig, scam_hosts, r["text"], r["sender"], hosts)
        out.append(dict(group=r["group"], label=r["label"], band=b, family=fam, p_sms=round(ps, 3), p_url=round(p_url, 3),
                        sender=r["sender"], text=r["text"][:90]))

    summary = {}
    for g in ("promo", "service", "scam", "fresh_ham", "fresh_scam"):
        sel = [o for o in out if o["group"] == g]
        summary[g] = {k: sum(o["band"] == k for o in sel) for k in ("SCAM", "UNCERTAIN", "SAFE")} | {"n": len(sel)}
    print(json.dumps(summary))
    for o in out:
        wrong = (o["label"] == 0 and o["band"] == "SCAM") or (o["label"] == 1 and o["band"] == "SAFE")
        if wrong:
            print("WRONG", o["group"], o["band"], o["p_sms"], o["p_url"], o["sender"], "|", o["text"])
    if a.out:
        Path(a.out).write_text(json.dumps(dict(summary=summary, rows=out), ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
