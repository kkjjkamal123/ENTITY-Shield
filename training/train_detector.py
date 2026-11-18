"""Train, evaluate and export the SMS or URL detector.

  .venv/bin/python training/train_detector.py sms|url

Model: logistic regression over the hashed n-grams + named signals (featurize.py). Trained as an
EmbeddingBag (sparse input), exported as one dense Linear + sigmoid to ExecuTorch (XNNPACK).
Writes: models/detectors/<kind>.pte, training/<kind>/meta.json, training/<kind>/golden.json,
benchmarks/results/detector_<kind>_<date>.json
"""
import csv
import datetime
import gzip
import json
import random
import sys
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "training"))
sys.path.insert(0, str(ROOT / "training" / "sms"))
import featurize as F  # noqa: E402

DS = ROOT / "datasets"
SEED = 7

# ---------------------------------------------------------------- data


def split3(items, r, a=0.6, b=0.2):
    items = list(items)
    r.shuffle(items)
    i, j = int(len(items) * a), int(len(items) * (a + b))
    return items[:i], items[i:j], items[j:]


def sms_data():
    import indian_sms
    r = random.Random(SEED)
    uci = []
    for line in open(DS / "uci_sms" / "SMSSpamCollection", encoding="utf-8"):
        lab, text = line.rstrip("\n").split("\t", 1)
        uci.append(dict(text=text, sender="", label=int(lab == "spam"), template="uci", source="uci"))
    u_tr, u_va, u_te = split3(uci, r, 0.7, 0.15)
    ind = indian_sms.generate()
    out = {"train": u_tr, "val": u_va, "test": u_te}
    for lab in (0, 1):  # split templates per label so every split has both classes
        tids = sorted({x["template"] for x in ind if x["label"] == lab})
        t_tr, t_va, t_te = split3(tids, r)
        for name, ts in (("train", t_tr), ("val", t_va), ("test", t_te)):
            out[name] += [x for x in ind if x["label"] == lab and x["template"] in set(ts)]
    held = {"test_templates": sorted({x["template"] for x in out["test"] if x["source"] == "indian"})}
    return out, held


def url_data():
    r = random.Random(SEED)
    tranco = [row[1] for row in csv.reader(open(DS / "tranco" / "top-1m.csv", encoding="utf-8"))]
    top100k = set(tranco[:100_000])
    first_seen = {}
    pt = sorted(DS.glob("phishtank/online-valid_*.csv.gz"))[-1]
    with gzip.open(pt, "rt", encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            h = F.url_host(row["url"])
            if not h or h in top100k:  # abused big platforms (docs.google.com, forms.gle): label noise
                continue
            t = row["submission_time"]
            if h not in first_seen or t < first_seen[h]:
                first_seen[h] = t
    phish = sorted(first_seen, key=first_seen.get)  # time order: test = newest
    n = len(phish)
    p_tr, p_va, p_te = phish[:int(n * .7)], phish[int(n * .7):int(n * .8)], phish[int(n * .8):]
    phish_set = set(phish)
    benign = [d for d in tranco[:200_000] if d not in phish_set]
    benign = r.sample(benign, min(len(benign), 2 * n))
    benign = [("www." + d if r.random() < 0.3 else d) for d in benign]
    b_tr, b_va, b_te = split3(benign, r, 0.7, 0.1)
    mk = lambda hs, lab: [dict(text=h, sender="", label=lab, source="phishtank" if lab else "tranco") for h in hs]
    out = {"train": mk(p_tr, 1) + mk(b_tr, 0), "val": mk(p_va, 1) + mk(b_va, 0), "test": mk(p_te, 1) + mk(b_te, 0)}
    seen = set(p_tr)
    op = sorted(DS.glob("openphish/feed_*.txt"))[-1]
    extra = {F.url_host(u) for u in open(op, encoding="utf-8") if u.strip()} - seen - top100k
    out["openphish"] = mk(sorted(extra), 1)
    out["official"] = mk(sorted({d for ds in F.BRANDS.values() for d in ds}), 0)
    held = {"phishtank_test_from": first_seen[p_te[0]], "phishtank_train_until": first_seen[p_tr[-1]]}
    return out, held

# ---------------------------------------------------------------- model


class Detector(torch.nn.Module):
    def __init__(self, n_sig):
        super().__init__()
        self.emb = torch.nn.EmbeddingBag(F.DIM, 1, mode="sum")
        self.sig = torch.nn.Linear(n_sig, 1)
        torch.nn.init.zeros_(self.emb.weight)

    def forward(self, idx, off, w, sig):
        return (self.emb(idx, off, per_sample_weights=w) + self.sig(sig)).squeeze(1)


class Dense(torch.nn.Module):  # what ships: x = [hashed (DIM) | signals (S)] -> P(scam)
    def __init__(self, det):
        super().__init__()
        self.lin = torch.nn.Linear(F.DIM + det.sig.in_features, 1)
        with torch.no_grad():
            self.lin.weight.copy_(torch.cat([det.emb.weight[:, 0], det.sig.weight[0]])[None])
            self.lin.bias.copy_(det.sig.bias)

    def forward(self, x):
        return torch.sigmoid(self.lin(x))


def featurize_rows(kind, rows):
    out = []
    for x in rows:
        h, s = F.vector(kind, x["text"], x["sender"])
        out.append((h, s, x["label"]))
    return out


def batch(feats):
    idx, off, w, sig, y = [], [], [], [], []
    for h, s, lab in feats:
        off.append(len(idx))
        idx += list(h.keys())
        w += list(h.values())
        sig.append(s)
        y.append(lab)
    return (torch.tensor(idx, dtype=torch.long), torch.tensor(off, dtype=torch.long),
            torch.tensor(w, dtype=torch.float32), torch.tensor(sig, dtype=torch.float32),
            torch.tensor(y, dtype=torch.float32))


def train(feats, n_sig, epochs, l2):
    torch.manual_seed(SEED)
    m = Detector(n_sig)
    opt = torch.optim.Adam(m.parameters(), lr=0.05)
    pos = sum(f[2] for f in feats)
    pw = torch.tensor((len(feats) - pos) / max(pos, 1))
    r = random.Random(SEED)
    for _ in range(epochs):
        r.shuffle(feats)
        for i in range(0, len(feats), 256):
            idx, off, w, sig, y = batch(feats[i:i + 256])
            logit = m(idx, off, w, sig)
            loss = torch.nn.functional.binary_cross_entropy_with_logits(logit, y, pos_weight=pw)
            loss = loss + l2 * (m.emb.weight.pow(2).sum() + m.sig.weight.pow(2).sum())
            opt.zero_grad()
            loss.backward()
            opt.step()
    return m


def scores(m, feats):
    with torch.no_grad():
        out = []
        for i in range(0, len(feats), 2048):
            idx, off, w, sig, _ = batch(feats[i:i + 2048])
            out += torch.sigmoid(m(idx, off, w, sig)).tolist()
    return out

# ---------------------------------------------------------------- metrics


def threshold_at_fpr(p, y, fpr):
    neg = sorted((s for s, l in zip(p, y) if l == 0), reverse=True)
    k = int(len(neg) * fpr)
    return min(0.99, max(0.5 if fpr <= 0.01 else 0.2, (neg[k] + 1e-6) if k < len(neg) else 0.5))


def metrics(p, y, t):
    tp = sum(1 for s, l in zip(p, y) if s >= t and l == 1)
    fp = sum(1 for s, l in zip(p, y) if s >= t and l == 0)
    fn = sum(1 for s, l in zip(p, y) if s < t and l == 1)
    tn = sum(1 for s, l in zip(p, y) if s < t and l == 0)
    prec = tp / (tp + fp) if tp + fp else None
    rec = tp / (tp + fn) if tp + fn else None
    f1 = 2 * prec * rec / (prec + rec) if prec and rec else None
    rnd = lambda v: None if v is None else round(v, 4)
    return dict(n=len(y), pos=tp + fn, neg=fp + tn, precision=rnd(prec), recall=rnd(rec), f1=rnd(f1),
                fpr=rnd(fp / (fp + tn)) if fp + tn else None)


def auc(p, y):
    pairs = sorted(zip(p, y))
    pos = sum(y)
    neg = len(y) - pos
    if not pos or not neg:
        return None
    rank_sum, i = 0.0, 0
    while i < len(pairs):  # average ranks over ties
        j = i
        while j < len(pairs) and pairs[j][0] == pairs[i][0]:
            j += 1
        rank_sum += sum(1 for k in range(i, j) if pairs[k][1]) * (i + j + 1) / 2
        i = j
    return round((rank_sum - pos * (pos + 1) / 2) / (pos * neg), 4)

# ---------------------------------------------------------------- export


def windows_flatc_workaround():
    """ExecuTorch 1.5.1 on Windows: the wheel ships executorch/data/bin/flatc.exe, but the lookup in
    exir/_serialize/_flatbuffer.py only checks for a resource named 'flatc', then falls back to
    $FLATC_EXECUTABLE or 'flatc' on PATH, so XNNPACK export fails with WinError 2. Point it at the
    bundled binary."""
    import os
    if os.name != "nt" or "FLATC_EXECUTABLE" in os.environ:
        return
    import importlib.resources
    exe = importlib.resources.files("executorch.data.bin").joinpath("flatc.exe")
    if exe.is_file():
        os.environ["FLATC_EXECUTABLE"] = str(exe)


def export_pte(dense, n_in, path):
    windows_flatc_workaround()
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.exir import to_edge_transform_and_lower
    ep = torch.export.export(dense.eval(), (torch.zeros(1, n_in),))
    prog = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()]).to_executorch()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(prog.buffer)


def run_pte(path, xs):
    from executorch.runtime import Runtime
    method = Runtime.get().load_program(str(path)).load_method("forward")
    return [float(method.execute([x[None]])[0].reshape(-1)[0]) for x in xs]


def dense_x(h, s):
    x = torch.zeros(F.DIM + len(s))
    for k, v in h.items():
        x[k] = v
    x[F.DIM:] = torch.tensor(s, dtype=torch.float32)
    return x


def main(kind):
    data, held = sms_data() if kind == "sms" else url_data()
    names = F.signal_names(kind)
    feats = {k: featurize_rows(kind, v) for k, v in data.items()}
    hp = dict(epochs=6, l2=1e-4) if kind == "sms" else dict(epochs=15, l2=1e-6)  # each picked on val
    m = train(list(feats["train"]), len(names), **hp)
    p = {k: scores(m, v) for k, v in feats.items()}
    y = {k: [f[2] for f in v] for k, v in feats.items()}
    t_scam = threshold_at_fpr(p["val"], y["val"], 0.01)
    t_unsure = min(t_scam, threshold_at_fpr(p["val"], y["val"], 0.05))

    report = dict(kind=kind, hyperparams=hp, date=str(datetime.date.today()), seed=SEED, dim=F.DIM, ngrams=list(F.NGRAMS),
                  thresholds=dict(scam=round(t_scam, 4), uncertain=round(t_unsure, 4)), split=held,
                  sizes={k: len(v) for k, v in data.items()}, results={})
    for k in data:
        report["results"][k] = dict(at_scam=metrics(p[k], y[k], t_scam), at_uncertain=metrics(p[k], y[k], t_unsure),
                                    auc=auc(p[k], y[k]))
        if kind == "sms":  # per source, since UCI and the Indian set behave very differently
            for src in ("uci", "indian"):
                sel = [i for i, x in enumerate(data[k]) if x["source"] == src]
                if sel:
                    ps, ys = [p[k][i] for i in sel], [y[k][i] for i in sel]
                    report["results"][f"{k}_{src}"] = dict(at_scam=metrics(ps, ys, t_scam), auc=auc(ps, ys))
    if kind == "url":  # what the app does: allow-list first, model second
        allow = {d for ds in F.BRANDS.values() for d in ds}
        allow |= {row[1] for i, row in zip(range(10_000), csv.reader(open(DS / "tranco" / "top-1m.csv", encoding="utf-8")))}
        allow -= set(F.FREE_HOSTS)
        # shared platforms: >= 2 phishing subdomains in the TRAIN split -> anyone can host there, don't trust it
        regs = {}
        for x in data["train"]:
            if x["label"] == 1:
                regs.setdefault(F.registrable(x["text"]), set()).add(x["text"])
        shared = {r for r, hs in regs.items() if len(hs) >= 2} - {d for ds in F.BRANDS.values() for d in ds}
        allow -= shared
        report["allowlist"] = dict(size=len(allow), shared_platforms_removed=len(shared))
        (ROOT / "training" / "url" / "allowlist.txt").write_text("\n".join(sorted(allow)) + "\n", encoding="utf-8", newline="\n")
        for k in data:
            pa = [0.0 if F.allowlisted(F.url_host(x["text"]), allow) else s for x, s in zip(data[k], p[k])]
            report["results"][f"{k}_with_allowlist"] = dict(at_scam=metrics(pa, y[k], t_scam), auc=auc(pa, y[k]))
    sw = m.sig.weight[0].tolist()
    report["signal_weights"] = dict(sorted(zip(names, [round(v, 3) for v in sw]), key=lambda kv: -kv[1]))

    dense = Dense(m)
    pte = ROOT / "models" / "detectors" / f"{kind}.pte"
    export_pte(dense, F.DIM + len(names), pte)

    # golden vectors: the app must reproduce these features and scores exactly
    r = random.Random(SEED)
    pool = data["test"] + data.get("official", [])
    gold_rows = r.sample(pool, min(40, len(pool)))
    if kind == "sms":
        gold_rows += [dict(text="Dear customer your SBI account will be blocked today. Update KYC immediately at http://sbi-kyc-update.in/verify", sender="+919876543210", label=1),
                      dict(text="482913 is your OTP for transaction of Rs 1,999 at Amazon. Do not share with anyone. -HDFC Bank", sender="VM-HDFCBK", label=0),
                      dict(text="आपका खाता आज ब्लॉक हो जाएगा 😱 तुरंत KYC करें", sender="", label=1)]
    xs = [dense_x(*F.vector(kind, g["text"], g["sender"])) for g in gold_rows]
    with torch.no_grad():
        p_torch = [float(dense(x[None])) for x in xs]
    p_pte = run_pte(pte, xs)
    worst = max(abs(a - b) for a, b in zip(p_torch, p_pte))
    assert worst < 1e-4, f"pte disagrees with torch: {worst}"
    gold = []
    for g, pt in zip(gold_rows, p_pte):
        h, s = F.vector(kind, g["text"], g["sender"])
        gold.append(dict(text=g["text"], sender=g["sender"], label=g["label"],
                         hashed={str(k): round(v, 7) for k, v in sorted(h.items())}, signals=s, score=round(pt, 6)))
    out_dir = ROOT / "training" / kind
    (out_dir / "golden.json").write_text(json.dumps(gold, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")
    meta = dict(kind=kind, version=report["date"], dim=F.DIM, ngrams=list(F.NGRAMS), signals=names,
                thresholds=report["thresholds"], input=[1, F.DIM + len(names)], output="P(scam) in [0,1]")
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=1), encoding="utf-8", newline="\n")
    report["pte_bytes"] = pte.stat().st_size
    report["pte_vs_torch_max_abs_diff"] = worst
    res = ROOT / "benchmarks" / "results" / f"detector_{kind}_{report['date']}.json"
    res.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")

    print(json.dumps({k: report[k] for k in ("sizes", "thresholds", "split", "pte_bytes")}, indent=1))
    for k, v in report["results"].items():
        a = v["at_scam"]
        print(f"{k:16s} n={a['n']:6d} P={a['precision']} R={a['recall']} F1={a['f1']} FPR={a['fpr']} AUC={v['auc']}")
    print("signal weights:", report["signal_weights"])


if __name__ == "__main__":
    assert sys.argv[1:] in (["sms"], ["url"]), __doc__
    main(sys.argv[1])
