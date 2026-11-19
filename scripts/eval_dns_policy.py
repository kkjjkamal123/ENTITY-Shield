"""Network Guard false-alarm check: how often does each DNS policy block a legitimate lookup?

  .venv/Scripts/python scripts/eval_dns_policy.py [--phone-log dns_log.tsv]

The URL model was trained on registrable domains (Tranco top 200k vs PhishTank hosts). Phones look up
full hostnames (api.x.io, connekt.flipkart.net, cdn...), and almost all of them are legitimate, so a
policy that blocks on the model score alone blocks real traffic. This compares:

  old  block if model >= scam threshold (allow-list: Tranco top 10k + official + DNS infra)
  new  never block a popular domain (Tranco top 1M), the requesting app's own domain, or a domain the
       user allowed; block only when the model says scam AND a strong red flag is present
       (brand look-alike, punycode, bare IP, free host + lure word, cheap TLD + lure word)

Simulation of the long tail: the popularity list here is Tranco top 500k and the legitimate test
hosts are ranks 500k-1M (never seen in training), with realistic subdomain prefixes. Phishing hosts
are the time-split PhishTank test set and the held-out OpenPhish feed (same split as training).
"""
import argparse
import csv
import gzip
import json
import random
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "training"))
import featurize as F  # noqa: E402
from executorch.runtime import Runtime  # noqa: E402
import torch  # noqa: E402

DS = ROOT / "datasets"
META = json.loads((ROOT / "training/url/meta.json").read_text(encoding="utf-8"))
T_SCAM = META["thresholds"]["scam"]
T_DNS = META["thresholds"].get("dns_block", 0.99)
V1 = ROOT / "models/detectors/v1"
T1 = json.loads((V1 / "url_meta.json").read_text(encoding="utf-8"))["thresholds"]["scam"]
PREFIXES = [""] * 8 + ["www.", "api.", "cdn.", "static.", "m.", "app.", "img.", "analytics.", "connect.", "auth.",
                       "login.", "mail.", "assets.", "events.", "push.", "graph.", "media.", "track.", "v2.api."]


class Model:
    def __init__(self, path=ROOT / "models/detectors/url.pte", n_sig=len(F.URL_SIGNALS)):
        self.m = Runtime.get().load_program(str(path)).load_method("forward")
        self.n_sig = n_sig  # v1 had 11 signals (no brand_lure)
        self.cache = {}

    def __call__(self, host):
        if host not in self.cache:
            x = np.zeros(F.DIM + self.n_sig, dtype=np.float32)
            for k, v in F.hashed(host).items():
                x[int(k)] = v
            x[F.DIM:] = F.url_signals(host)[:self.n_sig]
            self.cache[host] = float(self.m.execute([torch.from_numpy(x).unsqueeze(0)])[0].reshape(-1)[0])
        return self.cache[host]


SIG = {n: i for i, n in enumerate(F.URL_SIGNALS)}


def strong(host):
    s = F.url_signals(host)
    on = lambda n: s[SIG[n]] > 0
    return (on("brand_not_official") or on("punycode") or on("ip_host")
            or (on("free_hosting") and on("lure_word")) or (on("suspicious_tld") and on("lure_word")))


def own_domain(host, pkg):
    name = F.registrable(host).split(".")[0]
    return len(name) >= 3 and name in pkg.lower().replace("_", ".").split(".")


def old_policy(host, model, allow, infra, **_):
    """v1 as shipped until 2026-11-19: v1 model, v1 allow-list, block at the link threshold."""
    if any(host == d or host.endswith("." + d) for d in infra) or F.allowlisted(host, allow):
        return "ALLOW"
    return "BLOCK" if model(host) >= T1 else "ALLOW"


def new_policy(host, model, allow, infra, popular, pkg="", need_flag=True):
    if any(host == d or host.endswith("." + d) for d in infra) or F.allowlisted(host, allow):
        return "ALLOW"
    reg = F.registrable(host)
    free = any(host == d or host.endswith("." + d) for d in F.FREE_HOSTS)
    if (reg in popular and not free and reg not in F.SHORTENERS) or own_domain(host, pkg):
        return "ALLOW"
    p = model(host)
    if p >= T_DNS and (strong(host) or not need_flag):
        return "BLOCK"
    return "FLAG" if p >= T_SCAM else "ALLOW"


def phishing_test():
    tranco = [row[1] for row in csv.reader(open(DS / "tranco/top-1m.csv", encoding="utf-8"))]
    top100k = set(tranco[:100_000])
    first = {}
    pt = sorted(DS.glob("phishtank/online-valid_*.csv.gz"))[-1]
    with gzip.open(pt, "rt", encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            h = F.url_host(row["url"])
            if h and h not in top100k and (h not in first or row["submission_time"] < first[h]):
                first[h] = row["submission_time"]
    ph = sorted(first, key=first.get)
    n = len(ph)
    seen = set(ph[:int(n * .7)])
    op = sorted(DS.glob("openphish/feed_*.txt"))[-1]
    extra = {F.url_host(u) for u in open(op, encoding="utf-8") if u.strip()} - seen - top100k
    return tranco, ph[int(n * .8):], sorted(extra)


def umbrella(start, stop):
    out = []
    for i, row in enumerate(csv.reader(open(DS / "umbrella/top-1m.csv", encoding="utf-8"))):
        if start <= i < stop:
            out.append(row[1].strip().lower().rstrip("."))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20000)
    ap.add_argument("--phone-log", help="TSV host<TAB>pkg captured from the phone (DebugReceiver netdump)")
    ap.add_argument("--out", default=str(ROOT / "benchmarks/results/dns_policy_eval_2026-11-19.json"))
    a = ap.parse_args()
    r = random.Random(7)
    m1, m2 = Model(V1 / "url.pte", 11), Model()
    tranco, pt_test, op_test = phishing_test()
    allow1 = {l.strip() for l in open(V1 / "allowlist.txt", encoding="utf-8") if l.strip()}
    allow2 = {l.strip() for l in open(ROOT / "training/url/allowlist.txt", encoding="utf-8") if l.strip()}
    infra = [l.strip() for l in open(ROOT / "android-app/app/src/main/assets/dns_infra.txt", encoding="utf-8")
             if l.strip() and not l.startswith("#")]
    pop_sim = set(tranco[:500_000])
    sets = {
        "tranco_longtail_with_prefixes": [r.choice(PREFIXES) + d for d in r.sample(tranco[500_000:], a.n)],
        "umbrella_heldout_600k_1m": [h for h in r.sample(umbrella(600_000, 1_000_000), a.n) if "." in h and not h.endswith(".arpa")],
        "phishtank_test": pt_test,
        "openphish_heldout": op_test,
    }
    pols = {
        "v1_shipped": lambda h: old_policy(h, m1, allow1, infra),
        "v2_model_only": lambda h: new_policy(h, m2, allow2, infra, pop_sim, need_flag=False),
        "v2_model_and_red_flag": lambda h: new_policy(h, m2, allow2, infra, pop_sim, need_flag=True),
    }
    res = {"thresholds": {"v1_scam": T1, "v2_scam": T_SCAM, "v2_dns_block": T_DNS},
           "note": "popularity list simulated as Tranco top 500k; legit sets are outside it or held out",
           "sizes": {k: len(v) for k, v in sets.items()}, "rates": {}, "examples_blocked_legit": {}}
    for sname, hosts in sets.items():
        res["rates"][sname] = {}
        for pname, pol in pols.items():
            acts = [pol(h) for h in hosts]
            res["rates"][sname][pname] = {k: round(acts.count(k) / len(acts), 4) for k in ("BLOCK", "FLAG", "ALLOW")}
            if not sname.startswith(("phish", "openphish")):
                res["examples_blocked_legit"][f"{sname}/{pname}"] = [h for h, x in zip(hosts, acts) if x == "BLOCK"][:12]
    if a.phone_log:
        rows = [l.rstrip(chr(10)).split(chr(9)) for l in open(a.phone_log, encoding="utf-8") if chr(9) in l]
        popular = set(tranco)
        o = [old_policy(h, m1, allow1, infra) for h, _ in rows]
        nw = [new_policy(h, m2, allow2, infra, popular, pkg) for h, pkg in rows]
        res["phone_log"] = {"n": len(rows), "v1_blocked": o.count("BLOCK"), "v2_blocked": nw.count("BLOCK"), "v2_flagged": nw.count("FLAG"),
                            "v1_blocked_hosts": sorted({h for (h, _), x in zip(rows, o) if x == "BLOCK"})[:60],
                            "v2_blocked_hosts": sorted({h for (h, _), x in zip(rows, nw) if x == "BLOCK"})}
    print(json.dumps(res["rates"], indent=1))
    Path(a.out).write_text(json.dumps(res, indent=1), encoding="utf-8", newline=chr(10))


if __name__ == "__main__":
    main()
