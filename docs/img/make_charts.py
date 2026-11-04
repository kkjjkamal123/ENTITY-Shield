"""Regenerates the README result charts from the numbers in docs/PLAN.md section 0.
Usage: python docs/img/make_charts.py [output_dir]   (default: this script's folder)"""
import os, sys, matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
if len(sys.argv) > 2:
    sys.exit(__doc__)
out = sys.argv[1] if len(sys.argv) == 2 else os.path.dirname(os.path.abspath(__file__))
os.makedirs(out, exist_ok=True)
BG, FG, GRID = "#ffffff", "#1f2328", "#d0d7de"
C1, C2, C3 = "#2563eb", "#ea580c", "#6b7280"
# Helvetica Neue is macOS-only; fall back quietly elsewhere instead of warning on every text element.
plt.rcParams.update({"font.family": ["Helvetica Neue", "Helvetica", "Arial", "DejaVu Sans"], "font.size": 11, "text.color": FG, "axes.labelcolor": FG,
    "xtick.color": FG, "ytick.color": FG, "axes.edgecolor": GRID, "figure.facecolor": BG, "axes.facecolor": BG,
    "savefig.facecolor": BG})

def bars(ax, title, unit, labels, lo, hi, colors, better):
    mid = [(a + b) / 2 for a, b in zip(lo, hi)]
    err = [[m - a for m, a in zip(mid, lo)], [b - m for m, b in zip(mid, hi)]]
    ax.bar(labels, mid, color=colors, width=0.6, yerr=err, capsize=6, ecolor=FG, error_kw={"lw": 1.2})
    for i, (a, b) in enumerate(zip(lo, hi)):
        txt = (f"{a:g}" if a == b else f"{a:g}–{b:g}")
        if title == "Prefill speed" and labels[i].startswith("ENTITY"): txt = "~" + txt
        ax.text(i, b * 1.03, txt, ha="center", va="bottom", fontsize=11, fontweight="bold")
    ax.set_title(f"{title}\n({unit}, {better})", fontsize=11.5, fontweight="bold")
    ax.set_ylim(0, max(hi) * 1.25)
    ax.spines[["top", "right"]].set_visible(False)
    ax.yaxis.grid(True, color=GRID, lw=0.6); ax.set_axisbelow(True)

# 1. 8da4w vs QAT+LoRA (docs/PLAN.md §0, 2026-10-08 late)
L = ["8da4w\n(ours)", "QAT+LoRA\n(Meta)"]; col = [C1, C2]
fig, axs = plt.subplots(2, 3, figsize=(13, 7.6))
bars(axs[0,0], "Prefill speed", "tok/s", L, [105, 81], [130, 90], col, "higher is better")
bars(axs[0,1], "Decode speed", "tok/s", L, [18, 11], [20, 14], col, "higher is better")
bars(axs[0,2], "Time to first token, 165-token prompt", "s", L, [1.27, 2.04], [1.27, 2.04], col, "lower is better")
bars(axs[1,0], "Memory (RSS after load)", "MB", L, [934, 1297], [934, 1297], col, "lower is better")
bars(axs[1,1], "Model file size", "MB", L, [803, 1120], [803, 1120], col, "lower is better")
bars(axs[1,2], "English answer length (v4 prompt)", "tokens", L, [99, 19], [99, 19], col, "shorter is better")
fig.suptitle("Llama 3.2 1B Instruct on the CMF Phone 1 (Dimensity 7300): two 4-bit variants",
             fontsize=14, fontweight="bold", y=0.995)
fig.text(0.5, 0.005, "ExecuTorch 1.5.1 + XNNPACK, context 2048, 4 threads, greedy. Single runs, phone charging (29–38%): "
         "not a benchmark of record. Source: docs/PLAN.md §0 (2026-10-08).", ha="center", fontsize=9.5, color=C3)
fig.tight_layout(rect=(0, 0.03, 1, 0.97)); fig.savefig(f"{out}/llama_variants.png", dpi=150); plt.close(fig)

# 2. ExecuTorch vs ENTITY llama.cpp
L = ["ExecuTorch 1.5.1\n8da4w (2026-10-08)", "ENTITY llama.cpp\nQ4_0 (2026-07-18)"]; col = [C1, C3]
fig, axs = plt.subplots(1, 3, figsize=(13, 4.6))
bars(axs[0], "Prefill speed", "tok/s", L, [121, 128], [123, 128], col, "higher is better")
bars(axs[1], "Decode speed", "tok/s", L, [17.7, 18.1], [18.6, 18.1], col, "higher is better")
bars(axs[2], "Model file size", "MB", L, [803, 773], [803, 773], col, "lower is better")
fig.suptitle("Same phone, same model: ExecuTorch is level with ENTITY's tuned llama.cpp", fontsize=14, fontweight="bold")
fig.text(0.5, 0.01, "Llama 3.2 1B Instruct, 4 threads, CMF Phone 1. ExecuTorch: single runs, plugged in at ~29%. "
         "ENTITY prefill is approximate (~128). Source: docs/PLAN.md §0.", ha="center", fontsize=9.5, color=C3)
fig.tight_layout(rect=(0, 0.05, 1, 0.95)); fig.savefig(f"{out}/executorch_vs_llamacpp.png", dpi=150); plt.close(fig)

# 3. Explanation TTFT vs target (§8.0: < 1 s with cached system prompt)
fig, ax = plt.subplots(figsize=(10, 4.4))
rows = [("8da4w, 112–141-token prompt", 0.91, 1.14, C1), ("8da4w, 165-token prompt", 1.27, 1.27, C1),
        ("QAT+LoRA, 165-token prompt", 2.04, 2.04, C2)]
for i, (lab, a, b, c) in enumerate(rows):
    if a == b: ax.barh(i, a, color=c, height=0.55)
    else:
        ax.barh(i, a, color=c, height=0.55); ax.barh(i, b - a, left=a, color=c, alpha=0.45, height=0.55)
    ax.text(b + 0.04, i, f"{a:g} s" if a == b else f"{a:g}–{b:g} s", va="center", fontweight="bold")
ax.set_yticks(range(len(rows)), [r[0] for r in rows]); ax.invert_yaxis()
ax.axvline(1.0, color="#16a34a", lw=2, ls="--", label="target: < 1 s (§8.0)"); ax.legend(loc="upper right", frameon=False)
ax.set_xlim(0, 2.5); ax.set_xlabel("Time to first token (s), lower is better")
ax.spines[["top", "right"]].set_visible(False); ax.xaxis.grid(True, color=GRID, lw=0.6); ax.set_axisbelow(True)
ax.set_title("Explanation time to first token on the phone vs the target", fontsize=13, fontweight="bold", pad=10)
fig.text(0.5, 0.01, "Measured WITHOUT system-prompt KV-cache reuse (the target assumes a cached prompt, so these are worst case). "
         "Single runs. Source: docs/PLAN.md §0, §8.0.", ha="center", fontsize=9, color=C3)
fig.tight_layout(rect=(0, 0.05, 1, 1)); fig.savefig(f"{out}/ttft_vs_target.png", dpi=150); plt.close(fig)
print("ok")
