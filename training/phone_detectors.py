"""Run both detector .pte files on the phone through ExecuTorch's executor_runner.

  .venv/bin/python training/phone_detectors.py

1. Every golden input is run on the phone; its score must match golden.json (|diff| < 1e-4).
2. 1000 timed runs per model -> p50 / p95 latency (model only; featurizing is the app's job).
Writes benchmarks/results/detector_phone_<date>.csv. Needs third_party/executorch/cmake-out-android/executor_runner.
"""
import csv
import datetime
import json
import re
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "training"))
import featurize as F  # noqa: E402

RUNNER = ROOT / "third_party/executorch/cmake-out-android/executor_runner"
DEV = "/data/local/tmp/shield/det"


def adb(*a, check=True):
    return subprocess.run(["adb", *a], check=check, capture_output=True, text=True).stdout


def dense(g):
    x = np.zeros(F.DIM + len(g["signals"]), dtype=np.float32)
    for k, v in g["hashed"].items():
        x[int(k)] = v
    x[F.DIM:] = g["signals"]
    return x


def main():
    assert RUNNER.exists(), f"build it first: cmake --build cmake-out-android --target executor_runner ({RUNNER})"
    model = adb("shell", "getprop", "ro.product.model").strip()
    soc = adb("shell", "getprop", "ro.soc.model").strip()
    adb("shell", f"mkdir -p {DEV}/in {DEV}/out")
    adb("push", str(RUNNER), f"{DEV}/executor_runner")
    adb("shell", f"chmod 755 {DEV}/executor_runner")
    rows = []
    for kind in ("sms", "url"):
        pte = ROOT / "models/detectors" / f"{kind}.pte"
        gold = json.loads((ROOT / "training" / kind / "golden.json").read_text())
        adb("push", str(pte), f"{DEV}/{kind}.pte")
        adb("shell", f"rm -f {DEV}/in/* {DEV}/out/*")
        with tempfile.TemporaryDirectory() as tmp:
            for i, g in enumerate(gold):
                p = Path(tmp) / f"{kind}_{i}.bin"
                dense(g).tofile(p)
            adb("push", *[str(p) for p in sorted(Path(tmp).iterdir())], f"{DEV}/in/")
        loop = (f"cd {DEV} && for i in $(seq 0 {len(gold) - 1}); do "
                f"./executor_runner --model_path={kind}.pte --inputs=in/{kind}_$i.bin --output_file=out/{kind}_$i "
                f">/dev/null 2>&1 || echo FAIL $i; done")
        fails = adb("shell", loop).split()
        assert not fails, f"{kind}: runner failed: {fails}"
        with tempfile.TemporaryDirectory() as tmp:
            adb("pull", f"{DEV}/out/.", tmp)
            got = [float(np.fromfile(Path(tmp) / f"{kind}_{i}-0.bin", dtype=np.float32)[0]) for i in range(len(gold))]
        diffs = [abs(a - g["score"]) for a, g in zip(got, gold)]
        assert max(diffs) < 1e-4, f"{kind}: phone score differs from golden by {max(diffs)}"

        log = adb("shell", f"cd {DEV} && ./executor_runner --model_path={kind}.pte --inputs=in/{kind}_0.bin "
                           f"--num_executions=1000 2>&1")
        ms = [float(x) for x in re.findall(r"Iteration \d+ of \d+: ([\d.]+) ms", log)]
        if not ms:  # per-iteration lines are debug-level in some builds: fall back to the total
            total = float(re.search(r"executed successfully (\d+) time\(s\) in ([\d.]+) ms", log).group(2))
            ms = [total / 1000]
        q = statistics.quantiles(ms, n=20) if len(ms) > 1 else [ms[0]] * 19
        rows.append(dict(date=str(datetime.date.today()), device=model, soc=soc, kind=kind,
                         pte_bytes=pte.stat().st_size, golden_n=len(gold), golden_max_abs_diff=f"{max(diffs):.2e}",
                         runs=len(ms), p50_ms=round(statistics.median(ms), 4), p95_ms=round(q[18], 4),
                         mean_ms=round(statistics.fmean(ms), 4)))
        print(rows[-1])
    out = ROOT / "benchmarks/results" / f"detector_phone_{datetime.date.today()}.csv"
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print("wrote", out)


if __name__ == "__main__":
    main()
