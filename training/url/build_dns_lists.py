"""Builds the two lists Network Guard ships with (models/detectors/*.bin, copied into the APK assets).

  .venv/Scripts/python training/url/build_dns_lists.py

popular.bin  Registrable domains of the Tranco top 1M. A popular, long-lived domain is not a fresh
             phishing domain, so Network Guard never blocks it on the model's say-so (links to it
             are still checked when the user asks). Platforms anyone can host on (free hosts,
             shorteners, domains with several phishing subdomains) are left out.
phish.bin    Exact hostnames from the PhishTank and OpenPhish feeds of the build date. Always
             blocked. Hosts on big shared platforms (registrable domain in the top 10k, except free
             hosts where the subdomain belongs to the phisher) are left out so a single bad page
             cannot block e.g. storage.googleapis.com for everyone.

Both are sorted little-endian int32 arrays of FNV-1a 32 hashes (same hash as the featurizer), so the
app binary-searches them without parsing text. 1M hashes = 4 MB; a random domain collides with
probability ~0.02%.
"""
import csv
import gzip
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "training"))
import featurize as F  # noqa: E402

DS = ROOT / "datasets"
OUT = ROOT / "models" / "detectors"


def h32(s):
    v = F.fnv1a32(s.encode("utf-8"))
    return v - (1 << 32) if v >= (1 << 31) else v


def write(name, hashes):
    a = np.array(sorted(set(hashes)), dtype="<i4")
    (OUT / name).write_bytes(a.tobytes())
    return len(a)


def main():
    tranco = [row[1].strip().lower() for row in csv.reader(open(DS / "tranco/top-1m.csv", encoding="utf-8"))]
    top10k = set(tranco[:10_000])
    phish_hosts = set()
    pt = sorted(DS.glob("phishtank/online-valid_*.csv.gz"))[-1]
    with gzip.open(pt, "rt", encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            h = F.url_host(row["url"])
            if h:
                phish_hosts.add(h)
    op = sorted(DS.glob("openphish/feed_*.txt"))[-1]
    phish_hosts |= {F.url_host(u) for u in open(op, encoding="utf-8") if u.strip()}
    phish_hosts.discard("")

    on = lambda h, d: h == d or h.endswith("." + d)
    free = lambda h: any(on(h, d) for d in F.FREE_HOSTS)
    by_reg = {}
    for h in phish_hosts:
        by_reg.setdefault(F.registrable(h), set()).add(h)
    shared = {r for r, hs in by_reg.items() if len(hs) >= 2}
    official = {d for ds in F.BRANDS.values() for d in ds}

    popular = [d for d in tranco if d not in F.SHORTENERS and not free(d) and (d in top10k or d not in shared) and d not in phish_hosts]
    blocked = [h for h in phish_hosts
               if (free(h) or F.registrable(h) not in top10k) and F.registrable(h) not in official and h not in F.SHORTENERS
               and not (h == F.registrable(h) and h in set(tranco[:100_000]))]
    OUT.mkdir(parents=True, exist_ok=True)
    info = dict(date=pt.name.split("_")[-1].split(".")[0], popular=write("popular.bin", map(h32, popular)),
                phish=write("phish.bin", map(h32, blocked)), phish_sources=[pt.name, op.name],
                shared_platforms_excluded=len(shared))
    (OUT / "dns_lists.json").write_text(json.dumps(info, indent=1), encoding="utf-8", newline="\n")
    print(json.dumps(info, indent=1))


if __name__ == "__main__":
    main()
