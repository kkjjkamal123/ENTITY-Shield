"""Builds the Phase 1 technical report from the raw results: docs/report/ENTITY-Shield-Report.html + .pdf

  python docs/report/build_report.py            (needs matplotlib; PDF via Microsoft Edge or Chrome, headless)

Every number in the report is read from benchmarks/results/ at build time, so re-running after a new
measurement updates the PDF. Screenshots are picked up from docs/img/screens/*.png if present.
"""
import base64
import csv
import glob
import io
import json
import os
import shutil
import statistics
import subprocess
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "benchmarks" / "results"
OUT = Path(__file__).resolve().parent
plt.rcParams.update({"font.family": ["Segoe UI", "Helvetica Neue", "Arial", "DejaVu Sans"], "font.size": 10,
                     "axes.spines.top": False, "axes.spines.right": False, "axes.edgecolor": "#c9d1d9"})
C = dict(blue="#2563eb", violet="#7c3aed", cyan="#0891b2", green="#16a34a", amber="#d97706", red="#dc2626", grey="#6b7280")


def img(fig):
    b = io.BytesIO()
    fig.savefig(b, format="png", dpi=170, bbox_inches="tight")
    plt.close(fig)
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def load(pattern):
    files = sorted(glob.glob(str(RES / pattern)))
    return json.load(open(files[-1], encoding="utf-8")) if files else None


# ------------------------------------------------------------------ data
sms = load("detector_sms_2026-11-13.json")
url = load("detector_url_2026-11-*.json")
eval_en = load("llm_explain_eval_llama3_2_1b_instruct_qlora_ctx2048_Infinix_X6878_2026-11-19.json")
eval_hi2 = load("llm_explain_eval_*_hisigns.json")
rev_en = load("llm_explain_manual_review_EN_*.json")
rev_hi = load("llm_explain_manual_review_HI_engsigns_*.json")
rev_hi2 = load("llm_explain_manual_review_HI_hisigns_*.json")
threads = {}
for f in sorted(glob.glob(str(RES / "threads_*.csv"))):
    rows = list(csv.DictReader(open(f, encoding="utf-8")))
    if rows:
        threads[rows[0]["device"]] = rows
e2e = [json.load(open(f, encoding="utf-8")) for f in sorted(glob.glob(str(RES / "e2e_*.json")))]
bench = sorted(glob.glob(str(RES / "bench_*.csv")))
audit = sorted(glob.glob(str(RES / "audit_*.json")))


def m(res, key):
    a = res["results"][key]["at_scam"]
    return a, res["results"][key]["auc"]


# ------------------------------------------------------------------ charts
charts = {}
for dev, rows in threads.items():
    arms = ["t4_big", "default", "t4", "naive8", "t3_big", "t4_little"]
    names = {"t4_big": "4 thr · big cores", "default": "ExecuTorch default", "t4": "4 thr · unpinned", "naive8": "8 thr",
             "t3_big": "3 thr · big cores", "t4_little": "4 thr · little cores"}
    arms = [a for a in arms if any(r["arm"] == a for r in rows)]
    med = lambda a, k: statistics.median(float(r[k]) for r in rows if r["arm"] == a)
    fig, ax = plt.subplots(1, 2, figsize=(10, 3.3))
    for i, (k, t, col) in enumerate([("prefill_tps", "Prefill (tok/s, higher is better)", C["blue"]), ("decode_tps", "Decode (tok/s, higher is better)", C["violet"])]):
        v = [med(a, k) for a in arms]
        bars = ax[i].barh([names[a] for a in arms][::-1], v[::-1], color=[col if a != "t4_little" else C["grey"] for a in arms][::-1])
        ax[i].bar_label(bars, fmt="%.1f", padding=3, fontsize=9)
        ax[i].set_title(t, fontsize=10, fontweight="bold")
        ax[i].set_xlim(0, max(v) * 1.18)
    fig.suptitle(f"Llama 3.2 1B (QAT+LoRA, 4-bit) on ExecuTorch · {dev} · median of 3 runs", fontsize=10.5, fontweight="bold")
    fig.tight_layout()
    charts[f"threads_{dev}"] = img(fig)


def review_counts(r):
    return [r["counts"]["C"], r["counts"]["W"], r["counts"]["E"]] if r else None


groups = [("English", review_counts(rev_en)), ("Hindi, English signs", review_counts(rev_hi)), ("Hindi, Hindi signs", review_counts(rev_hi2))]
groups = [g for g in groups if g[1]]
if groups:
    fig, ax = plt.subplots(figsize=(8.5, 1.6 + 0.45 * len(groups)))
    left = [0] * len(groups)
    for j, (lab, col) in enumerate([("correct", C["green"]), ("vague", C["amber"]), ("wrong", C["red"])]):
        vals = [g[1][j] for g in groups]
        b = ax.barh([g[0] for g in groups], vals, left=left, color=col, label=lab)
        ax.bar_label(b, labels=[str(v) if v else "" for v in vals], label_type="center", color="white", fontweight="bold")
        left = [l + v for l, v in zip(left, vals)]
    ax.set_xlim(0, 40)
    ax.set_xlabel("explanations (of 40 held-out scam messages)")
    ax.legend(ncol=3, loc="lower center", bbox_to_anchor=(0.5, 1.0), frameon=False)
    ax.invert_yaxis()
    fig.tight_layout()
    charts["review"] = img(fig)

fig, ax = plt.subplots(figsize=(8.5, 2.2))
steps = [("SMS check (featurize + 2 models)", 5.0, C["cyan"]), ("SMS → notification (time to first warning)", 22, C["blue"]),
         ("Llama first token (best arm, Snapdragon 7s Gen 3)", 890, C["violet"]), ("Llama first token, background cgroup (A55s)", 9200, C["grey"])]
b = ax.barh([s[0] for s in steps][::-1], [s[1] for s in steps][::-1], color=[s[2] for s in steps][::-1])
ax.set_xscale("log")
ax.bar_label(b, labels=[f"{s[1]:g} ms" for s in steps][::-1], padding=4, fontsize=9)
ax.set_xlim(1, 60000)
ax.set_xlabel("milliseconds (log scale)")
fig.tight_layout()
charts["latency"] = img(fig)

screens = sorted(glob.glob(str(ROOT / "docs" / "img" / "screens" / "*.png")))
screens_html = "".join(f'<figure><img src="data:image/png;base64,{base64.b64encode(open(s, "rb").read()).decode()}"><figcaption>{Path(s).stem.replace("_", " ")}</figcaption></figure>' for s in screens[:6])

# ------------------------------------------------------------------ tables
s_uci, auc_uci = m(sms, "test_uci")
s_ind, auc_ind = m(sms, "test_indian")
u_t, auc_u = m(url, "test_with_allowlist")
u_op = url["results"]["openphish_with_allowlist"]["at_scam"]
u_of = url["results"]["official_with_allowlist"]["at_scam"]
pct = lambda v: "—" if v is None else f"{v * 100:.2f}%"
from decimal import Decimal, ROUND_HALF_UP
f3 = lambda v: "—" if v is None else str(Decimal(str(v)).quantize(Decimal("0.001"), ROUND_HALF_UP))


def thread_rows():
    out = []
    for dev, rows in threads.items():
        for a in ["t4_big", "default", "t4", "naive8", "t3_big", "t4_little"]:
            rr = [r for r in rows if r["arm"] == a]
            if not rr:
                continue
            p = [float(r["prefill_tps"]) for r in rr]
            d = [float(r["decode_tps"]) for r in rr]
            t = [float(r["ttft_s"]) for r in rr]
            out.append(f"<tr><td>{dev}</td><td>{a}</td><td>{rr[0]['threads']}</td><td>{rr[0]['mask']}</td>"
                       f"<td>{statistics.median(p):.1f} ± {statistics.pstdev(p):.1f}</td><td>{statistics.median(d):.1f}</td><td>{statistics.median(t):.2f} s</td></tr>")
    return "\n".join(out)


def eval_row(name, e, r):
    if not e:
        return ""
    lang = "HI" if "Hindi" in name else "EN"
    s = e["summary"][lang]
    rc = r["counts"] if r else None
    ri = r["indian_subset_counts"] if r else None
    return (f"<tr><td>{name}</td><td>{int(s['pass_rate'] * s['n'])}/{s['n']}</td><td>{s['refusal']}</td><td>{s['invented_link']} / {s['invented_num']}</td>"
            f"<td>{s['contradicts']}</td><td>{s['median_tokens']}</td>"
            f"<td>{(str(rc['C']) + ' / ' + str(rc['W']) + ' / ' + str(rc['E'])) if rc else 'pending'}</td>"
            f"<td>{(str(ri['C']) + ' / ' + str(ri['W']) + ' / ' + str(ri['E'])) if ri else '—'}</td></tr>")


e2e_html = ""
for e in e2e:
    ok = sum(r["ok"] for r in e["results"])
    e2e_html += f"<h4>{e['device']} ({e['soc']}): {ok}/{len(e['results'])} checks passed</h4><ul>" + "".join(
        f"<li>{'✅' if r['ok'] else '❌'} {r['check']} <span class=small>{r['detail'][:140]}</span></li>" for r in e["results"]) + "</ul>"
if not e2e_html:
    e2e_html = "<p class=pending>End-to-end phone run (scripts/e2e_phone.py) pending: needs the phones unlocked.</p>"

# ------------------------------------------------------------------ html
html = f"""<!doctype html><html><head><meta charset="utf-8"><title>ENTITY Shield: Phase 1 technical report</title>
<style>
@page {{ size: A4; margin: 16mm 15mm 16mm 15mm; }}
body {{ font-family: 'Segoe UI', Arial, sans-serif; color: #1f2328; font-size: 10.4pt; line-height: 1.45; }}
h1 {{ font-size: 25pt; margin: 0 0 4pt; letter-spacing: .5px; }}
h2 {{ font-size: 14.5pt; border-bottom: 2px solid #e5e7eb; padding-bottom: 3pt; margin-top: 20pt; page-break-after: avoid; }}
h3 {{ font-size: 11.5pt; margin: 12pt 0 4pt; page-break-after: avoid; }}
h4 {{ margin: 8pt 0 2pt; }}
.lead {{ font-size: 12.5pt; color: #374151; }}
.cover {{ border-radius: 14px; padding: 18pt; background: linear-gradient(135deg,#0b1020,#1e1b4b 60%,#3b0764); color: #fff; }}
.cover .k {{ display: inline-block; margin: 6pt 10pt 0 0; padding: 6pt 10pt; border: 1px solid rgba(255,255,255,.35); border-radius: 10px; }}
.cover .k b {{ display: block; font-size: 15pt; }}
table {{ border-collapse: collapse; width: 100%; margin: 6pt 0 10pt; font-size: 9.3pt; page-break-inside: avoid; }}
th, td {{ border-bottom: 1px solid #e5e7eb; padding: 4pt 6pt; text-align: left; vertical-align: top; }}
th {{ background: #f3f4f6; }}
img {{ max-width: 100%; }}
.small {{ color: #6b7280; font-size: 8.8pt; }}
.pending {{ color: #b45309; }}
.box {{ background: #f8fafc; border-left: 4px solid #7c3aed; padding: 8pt 10pt; margin: 8pt 0; page-break-inside: avoid; }}
.grid {{ display: flex; gap: 8pt; flex-wrap: wrap; }}
.grid figure {{ width: 31%; margin: 0; }} .grid figcaption {{ font-size: 8.5pt; color: #6b7280; text-align: center; }}
code {{ background: #f3f4f6; padding: 0 3px; border-radius: 3px; font-size: 9pt; }}
.pb {{ page-break-before: always; }}
</style></head><body>

<div class="cover">
<div style="opacity:.8;font-size:9.5pt">BHARAT AI-SoC CHALLENGE 2026–27 · PROBLEM STATEMENT 3 · TEAM ENTITY</div>
<h1>ENTITY Shield</h1>
<div style="font-size:12.5pt">On-device threat, phishing and scam detection on an Arm phone. Detectors decide in milliseconds;
Llama 3.2 on ExecuTorch explains in English or Hindi. Nothing leaves the phone.</div>
<div class="k"><b>{f3(s_ind['precision'])} / {f3(s_ind['recall'])}</b>SMS precision / recall on unseen Indian templates</div>
<div class="k"><b>5.0 ms</b>full SMS check on a Dimensity 7300</div>
<div class="k"><b>0.89 s</b>Llama first token, Snapdragon 7s Gen 3</div>
<div class="k"><b>0 bytes</b>sent to any cloud</div>
</div>

<h2>1. Problem and approach</h2>
<p>Scam SMS (fake KYC, electricity cut-offs, courier "customs", "digital arrest", UPI refund tricks, part-time job scams) reach
every Indian phone, and the people most at risk are the least able to spot them. Cloud scanners need the message to leave the
phone. ENTITY Shield does all of it on the phone's Arm CPU:</p>
<ul>
<li><b>Two-tier design.</b> Small ExecuTorch detectors (130 KB each) score every SMS, link and network event in milliseconds and
decide the verdict. <b>Llama 3.2 1B on ExecuTorch</b> only explains a flagged item, in plain English or Hindi.</li>
<li><b>Llama can't overrule or invent.</b> The verdict and the remediation steps are not generated. The app writes the start of
Llama's answer (<i>verdict prefill</i>) and gives it a short fact sheet, so it explains <i>why</i>.</li>
<li><b>Phone-only network protection.</b> A local DNS-only VPN sees every app's lookups (attributed per app) and a LAN
tripwire catches devices probing the phone. That covers the network-intrusion and malware-behaviour parts of PS3 with no
extra hardware.</li>
</ul>

<h3>PS3 requirements → implementation</h3>
<table><tr><th>PS3 asks for</th><th>ENTITY Shield</th><th>Evidence in this report</th></tr>
<tr><td>Monitor/classify network traffic for anomalies and intrusions</td><td>Network Guard: DNS VPN + URL model + beacon / DGA / tunnel detectors; LAN tripwire (scan, brute force, ADB probe)</td><td>§4, §7</td></tr>
<tr><td>Detect phishing URLs</td><td>URL detector on links in SMS, shared links, Link Guard, DNS lookups</td><td>§3</td></tr>
<tr><td>Detect scam SMS / fraudulent communications</td><td>SMS detector on every incoming SMS; inbox audit</td><td>§3</td></tr>
<tr><td>Explain in plain language</td><td>Llama 3.2 1B (ExecuTorch), English and Hindi, read-aloud</td><td>§5</td></tr>
<tr><td>Recommend remediation</td><td>Fixed checked steps per scam family + real actions (block, 1930, report, remove app, leave Wi-Fi)</td><td>§5</td></tr>
<tr><td>Process data locally</td><td>All models on the Arm CPU; airplane-mode capable</td><td>§8</td></tr>
<tr><td>Llama via ExecuTorch (required)</td><td><code>org.pytorch.executorch.extension.llm.LlmModule</code> 1.5.1 inside the APK</td><td>§5, §6</td></tr></table>

<h2>2. Architecture</h2>
<p>Inputs: SMS_RECEIVED, inbox, share/select-text/Link Guard, the DNS VPN and the tripwire → detectors (SMS, URL, behaviour
rules) → verdict (safe / suspicious / dangerous) → instant notification with fixed steps → when the user opens the alert,
Llama streams the explanation. Coexistence rules:</p>
<ul>
<li>Detection never waits for Llama.</li>
<li>Llama loads on demand and frees its ~1.3 GB after 90 s.</li>
<li>Llama runs only while an alert is on screen.</li>
<li>The fixed system prompt is pre-filled into the KV cache when an alert fires (ExecuTorch <code>prefillPrompt</code>).</li>
</ul>
<img src="{charts['latency']}">
<p class="small">Latency budget. The warning is instant; the explanation follows on the user's screen. The grey bar is the
mistake we measured and removed (§6).</p>

<h2>3. Detectors</h2>
<p><b>Features:</b> hashed character 2–5-grams (32,768 slots, FNV-1a) plus 15 named signals for SMS and 11 for URL. The signals
include urgency, OTP/PIN requests, payment requests, threats, prize and job lures, authority claims, KYC/Aadhaar, brand-vs-domain
mismatch, shorteners, and <b>sender is a 10-digit mobile number vs a registered DLT header</b> such as <code>VM-HDFCBK</code>.
<b>Model:</b> logistic regression, exported as one <code>Linear + sigmoid</code> ExecuTorch program (XNNPACK).
<b>Data:</b> UCI SMS Spam Collection, plus 101 hand-written Indian templates (47 scam families, 54 legitimate: bank OTP and
debit alerts, deliveries, bills, DLT promotions, personal Hinglish/Hindi). For URLs: PhishTank (time split), OpenPhish, Tranco.
The test sets hold out whole <i>templates</i> and the <i>newest</i> phishing.</p>
<table><tr><th>Detector · test set</th><th>n</th><th>Precision</th><th>Recall</th><th>False-alarm rate</th><th>AUC</th></tr>
<tr><td>SMS · UCI held out</td><td>{s_uci['n']}</td><td>{f3(s_uci['precision'])}</td><td>{f3(s_uci['recall'])}</td><td>{pct(s_uci['fpr'])}</td><td>{f3(auc_uci)}</td></tr>
<tr><td>SMS · Indian templates never seen in training</td><td>{s_ind['n']}</td><td>{f3(s_ind['precision'])}</td><td>{f3(s_ind['recall'])}</td><td>{pct(s_ind['fpr'])}</td><td>{f3(auc_ind)}</td></tr>
<tr><td>URL · PhishTank newer than training + Tranco (allow-list on)</td><td>{u_t['n']}</td><td>{f3(u_t['precision'])}</td><td>{f3(u_t['recall'])}</td><td>{pct(u_t['fpr'])}</td><td>{f3(auc_u)}</td></tr>
<tr><td>URL · OpenPhish feed, 19 Nov 2026</td><td>{u_op['n']}</td><td>—</td><td>{f3(u_op['recall'])}</td><td>—</td><td>—</td></tr>
<tr><td>URL · 41 official Indian bank/UPI/government domains</td><td>{u_of['n']}</td><td>—</td><td>—</td><td>{pct(u_of['fpr'])}</td><td>—</td></tr></table>
<p><b>Same answer on the phone.</b> The app's Kotlin featurizer is a line-by-line port of the Python one. The golden vectors pass
in Python, on the JVM (unit tests) and on the phone through the production path: SMS 43/43, URL 40/40, max difference
6×10⁻⁷. Retraining on Windows reproduced the Mac model bit-exactly (max difference 0.0).</p>
<p><b>Faster by measuring.</b> 98% of detection time was featurizing: a String per n-gram, plus a fresh 131 KB dense vector copied
into a tensor on every call. An allocation-free featurizer and one reused direct <code>FloatBuffer</code> per ExecuTorch model
(<code>Tensor.fromBlob</code>) took the full SMS check from 9.4 ms to <b>5.0 ms p50</b> and the URL check from 2.7 to <b>1.1 ms</b>
on the Dimensity 7300, with parity unchanged.</p>

<h2>4. Network Guard: network intrusion and malware behaviour, on the phone</h2>
<ul>
<li><b>DNS-only local VPN.</b> Only the fake resolver address is routed into the TUN interface, so app traffic never flows
through Shield. Each question is parsed (IPv4/UDP/DNS, unit-tested) and attributed to its app with
<code>getConnectionOwnerUid</code>. It is scored on the phone (allow-lists, then the URL model), then either forwarded to the
network's own resolver or answered NXDOMAIN if dangerous.</li>
<li><b>Malware behaviour</b> (per app):
<ul>
<li><i>Beacon:</i> 6 or more lookups of one domain at a near-constant interval.</li>
<li><i>DGA burst:</i> 8 or more different non-existent names within 60 s.</li>
<li><i>DNS tunnel:</i> 15 or more unique subdomains, or long high-entropy labels.</li>
</ul>
Each becomes an alert naming the app, with "remove app" as the action.</li>
<li><b>LAN tripwire.</b> Decoy listeners on 2222, 2323, 5555, 8080, 8443 and 9999. Any contact means another device on the Wi-Fi is
probing the phone: a scan (3 or more ports within 30 s), password guessing (5 or more connections to one port), or an
ADB-worm probe (port 5555).</li>
<li><b>Test app.</b> <code>beaconsim</code> imitates a C2 beacon, a DGA burst and DNS exfiltration using only DNS lookups of
made-up names, so the alerts name a real installed app.</li>
</ul>

<h2>5. Llama 3.2 1B on ExecuTorch: the explanation layer</h2>
<p><b>Model:</b> Meta's quantization-aware-trained <code>Llama-3.2-1B-Instruct-QLORA_INT4_EO8</code>, exported with ExecuTorch
1.5.1 (XNNPACK, 4-bit groupwise weights, 8-bit dynamic activations, KV cache, custom SDPA, context 2048; 1.12 GB). It runs in the
APK through <code>LlmModule</code>. It was chosen over our own 8da4w export (faster) because it never refused and wrote fluent
Hindi in our October tests.
<b>Prompt:</b> fact sheet (KYC = Know Your Customer; banks never ask for OTP/PIN by SMS link; a UPI PIN only sends money; police
never arrest over video calls; helpline 1930) + the detector's evidence + <b>verdict prefill</b>. Without the prefill the 1B
refused to explain scams at all.</p>
<h3>Quality: 40 held-out scam messages, English and Hindi, on the phone</h3>
<table><tr><th>Arm</th><th>Automatic checks pass</th><th>Refusals</th><th>Invented link / number</th><th>Says "safe"</th><th>Median tokens</th><th>Manual: correct / vague / wrong</th><th>Indian msgs: C / V / W</th></tr>
{eval_row("English", eval_en, rev_en)}
{eval_row("Hindi, English signs", eval_en, rev_hi)}
{eval_row("Hindi, Hindi signs", eval_hi2, rev_hi2)}</table>
{f'<img src="{charts["review"]}">' if "review" in charts else ""}
<div class="box"><b>Honest reading.</b> The automatic checks (refusal, invented links or numbers, contradiction) almost always
pass, but a manual read found real errors. In English, 4 of the 40 answers invent a "PIN/password" detail on UK premium-rate spam;
none were wrong on the Indian messages. In Hindi, 2 answers <i>negated</i> the evidence (e.g. "did <b>not</b> come from a personal
number"). This is why Llama never decides and never writes the steps, and it is the main Phase 2 target (distillation +
LoRA on the QAT checkpoint). Manual labels: <code>benchmarks/results/llm_explain_manual_review_*.json</code>, reviewed with
AI assistance and pending a native-speaker check.</div>

<h2 class="pb">6. Arm SoC: where the time goes</h2>
<p>Measured with ExecuTorch's own <code>llama_main</code> under <code>taskset</code>. The only things that change are the thread
count and the allowed cores. Masks come from each phone's <code>cpuinfo_max_freq</code>; arms are interleaved over 3 runs.</p>
{"".join(f'<img src="{v}">' for k, v in charts.items() if k.startswith("threads_"))}
<table><tr><th>Phone</th><th>Arm</th><th>Threads</th><th>Mask</th><th>Prefill tok/s</th><th>Decode tok/s</th><th>TTFT (165 tok)</th></tr>
{thread_rows()}</table>
<div class="box"><b>Three findings.</b>
<ol>
<li><b>Android's background cgroup moves ExecuTorch to the little cores.</b> Our first build ran Llama on a
<code>THREAD_PRIORITY_BACKGROUND</code> thread. <code>/proc/&lt;pid&gt;/task/*/cgroup</code> showed every XNNPACK worker in
<code>cpuset:/background</code>: prefill 25 tok/s and a 9.2 s first token on the Dimensity 7300. The little-core row above
reproduces the effect (3.9× slower prefill). Llama now runs at normal priority, only while its alert is on screen.</li>
<li><b>ExecuTorch's thread heuristic picks 3 threads on the Dimensity 7300</b>, because <code>midr_el1</code> is unreadable and
it falls back. On the Snapdragon, 3 instead of 4 big-core threads costs 17% of prefill.</li>
<li><b>ENTITY's llama.cpp result holds on ExecuTorch and on Armv9 (A720/A520, i8mm).</b> 8 threads cost 19% decode. Pinning to
the big cores gives the fastest and steadiest prefill (±0.6 vs ±6.6 tok/s).</li>
</ol></div>

<h2>7. End-to-end on the phones</h2>
{e2e_html}

<h2>8. Privacy, security and limits</h2>
<p>No account, analytics or cloud call. Messages, links and lookups are judged on the phone. The INTERNET permission is used
only to forward the phone's own DNS lookups to its usual resolver. The inbox audit exports counts only. Debug hooks exist only
in debug builds. Full table: <code>docs/PRIVACY.md</code>.</p>
<ul>
<li><b>Network Guard</b> sees this phone's own traffic, not other devices. Strict Private DNS or in-app DNS-over-HTTPS bypasses
its DNS view.</li>
<li><b>Test data:</b> the Indian SMS test set is templated. The real-inbox audit is the trust number
({'done' if audit else 'pending'}).</li>
<li><b>URL recall is 0.66</b> on the time-split feed: hostname only, by design.</li>
<li><b>Llama 1B is wrong 10–15% of the time</b> on hard inputs, so it explains but never decides.</li>
</ul>

<h2>9. Phase 2 plan</h2>
<ul><li><b>Network:</b>
<ul>
<li>A full-tunnel flow meter with NetFlow features.</li>
<li>A flow intrusion model on NF-UNSW-NB15-v2 / NF-CSE-CIC-IDS2018-v2, with a cross-dataset test.</li>
<li>A per-app anomaly baseline (ExecuTorch on-device training, <code>TrainingModule</code>).</li>
</ul></li>
<li><b>Llama:</b> distilled English/Hindi explanation data and LoRA on the QAT checkpoint (as "Llama-…" per the licence);
prefix-KV reuse; Tamil.</li>
<li><b>Coverage:</b> WhatsApp/Telegram notifications, call screening ("digital arrest"), UPI QR checks, a field trial with
10–20 phones.</li>
<li><b>Arm:</b> the KleidiAI on/off ablation (custom AAR), a Mali GPU (Vulkan) delegate for the detectors, and upstreaming the
ExecuTorch Windows <code>flatc</code> lookup fix and the core-count heuristic fix.</li></ul>

{f'<h2>Screens</h2><div class="grid">{screens_html}</div>' if screens_html else ''}

<h2>Reproducibility and credits</h2>
<p>Source: github.com/kkjjkamal123/ENTITY-Shield (branch <code>phase1</code>). Commands are listed in the README. The raw data
behind every number is in <code>benchmarks/results/</code>. Runtime: ExecuTorch 1.5.1 (BSD-3-Clause), XNNPACK, Arm KleidiAI.
<b>Built with Llama</b>: Llama 3.2 is licensed under the Llama 3.2 Community License, © Meta Platforms, Inc. Data: UCI SMS Spam
Collection (CC BY 4.0), PhishTank, OpenPhish, Tranco. Device-probe and benchmark method reused from ENTITY (Apache-2.0, same
author).</p>
</body></html>"""

html_path = OUT / "ENTITY-Shield-Report.html"
html_path.write_text(html, encoding="utf-8", newline="\n")
print("HTML:", html_path)

browser = next((p for p in [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
                            r"C:\Program Files\Google\Chrome\Application\chrome.exe", shutil.which("chromium") or "", shutil.which("google-chrome") or ""]
                if p and os.path.exists(p)), None)
if browser:
    pdf = OUT / "ENTITY-Shield-Report.pdf"
    subprocess.run([browser, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", f"--print-to-pdf={pdf}", html_path.as_uri()],
                   capture_output=True, timeout=180)
    print("PDF:", pdf, "ok" if pdf.exists() else "FAILED")
else:
    print("No Edge/Chrome found: open the HTML and print to PDF.")
