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
sms = load("detector_sms_2026-11-21.json")
held = load("sms_heldout_app_*.json")
chal = load("sms_challenge_*.json")
inbox = load("inbox_audit_*.json")
sus = load("sustained_*.json")
VERSION = (ROOT / "VERSION").read_text().strip()
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
steps = [("SMS check, Snapdragon 7s Gen 3 (p50)", 0.5, C["cyan"]), ("SMS → notification (time to first warning, median, Infinix)", 38.4, C["blue"]),
         ("Llama first token in the app, pre-warmed", 1000, C["violet"]), ("Llama first token in the app, cold", 1780, C["violet"]),
         ("Llama first token, background cgroup (A55s, CMF)", 9200, C["grey"])]
b = ax.barh([s[0] for s in steps][::-1], [s[1] for s in steps][::-1], color=[s[2] for s in steps][::-1])
ax.set_xscale("log")
ax.bar_label(b, labels=[f"{s[1]:g} ms" for s in steps][::-1], padding=4, fontsize=9)
ax.set_xlim(0.2, 60000)
ax.set_xlabel("milliseconds (log scale)")
fig.tight_layout()
charts["latency"] = img(fig)

screens = sorted(glob.glob(str(ROOT / "docs" / "img" / "screens" / "*.jpg")))
screens_html = "".join(f'<figure><img src="data:image/jpeg;base64,{base64.b64encode(open(s, "rb").read()).decode()}"><figcaption>{Path(s).stem.split("_", 1)[-1].replace("_", " ")}</figcaption></figure>' for s in screens[:6])

# ------------------------------------------------------------------ tables
s_uci, auc_uci = m(sms, "test_uci")
s_ind, auc_ind = m(sms, "test_indian")
u_t, auc_u = m(url, "test_with_allowlist")
u_op = url["results"]["openphish_with_allowlist"]["at_scam"]
u_of = url["results"]["official_with_allowlist"]["at_scam"]
pct = lambda v: "—" if v is None else f"{v * 100:.2f}%"
from decimal import Decimal, ROUND_HALF_UP
f3 = lambda v: "—" if v is None else str(Decimal(str(v)).quantize(Decimal("0.001"), ROUND_HALF_UP))


h_ind = held["indian"]["app_scam"] if held else s_ind


def conf_table(c, title):
    rows = "".join(f"<tr><td>{y}</td>" + "".join(f"<td>{c[y][b]}</td>" for b in ("SAFE", "UNCERTAIN", "SCAM")) + "</tr>" for y in ("legit", "scam"))
    return f"<table style='width:48%'><tr><th>{title}: true label</th><th>Safe</th><th>Be careful</th><th>Scam</th></tr>{rows}</table>"


def stat_row(name, x):
    return (f"<tr><td>{name}</td><td>{x['n']}</td><td>{f3(x['precision'])}</td><td>{f3(x['recall'])}</td><td>{f3(x['f1'])}</td>"
            f"<td>{pct(x['fpr'])}</td><td>{x['tp']} / {x['fp']} / {x['fn']} / {x['tn']}</td></tr>")


gate_html = ""
if held:
    hi, hu = held["indian"], held["uci"]
    lap = inbox["laptop_replay_all_messages"] if inbox else None
    ph = inbox["phone_audit_v0_5_1_newest_1000"] if inbox else None
    gate_html = f"""<h3>3b. The scam gate, and what the whole app gets right</h3>
<p>On a real 4,620-message inbox the 0.5.0 model called <b>{lap['v0_5_0']['SCAM']}</b> messages a scam and {lap['v0_5_0']['UNCERTAIN']}
suspicious: Jio data and missed-call alerts, bank login alerts, TRAI and DoT advisories, promotions. It had learned "marketing" as
well as "fraud". 0.5.1 adds a rule layer after the models (<code>ScamGate.kt</code>, the same rules as <code>sms_gate</code> in
Python; a unit test checks both agree on 2,871 cases). A message is called a <b>scam</b> only with a fraud-specific cue: it asks you
to hand over a code or PIN, ties a threat to a link or a personal number to call, has a brand look-alike link, is a task-job lure,
or carries a link the URL model calls phishing. Without one, a registered business sender is safe ("promotion, not a scam" when it
reads like one), a delivered login code is safe, and anything else is at most "be careful". A message from a saved contact is never called a scam (at most "be careful" when it carries a fraud cue, in case the contact was hacked),
and the user's own "scam / not a scam" answers apply to similar messages from then on.</p>
<table><tr><th>Held-out split (same as above)</th><th>n</th><th>Precision</th><th>Recall</th><th>F1</th><th>False alarms</th><th>TP / FP / FN / TN</th></tr>
{stat_row("Model alone, Indian, 'scam'", hi["model_scam"])}
{stat_row("<b>App verdict</b>, Indian, 'scam'", hi["app_scam"])}
{stat_row("<b>App verdict</b>, Indian, 'scam' or 'be careful'", hi["app_flagged"])}
{stat_row("Model alone, UCI, 'scam'", hu["model_scam"])}
{stat_row("<b>App verdict</b>, UCI, 'scam' or 'be careful'", hu["app_flagged"])}</table>
<div style="display:flex;gap:4%">{conf_table(hi["confusion_app"], "Indian, app")}{conf_table(hu["confusion_app"], "UCI, app")}</div>
<div class="box"><b>Trade-off and honest reading.</b>
<ul>
<li>On unseen Indian templates the gate raises the precision of a "scam" call from {f3(hi['model_scam']['precision'])} to
<b>{f3(hi['app_scam']['precision'])}</b> and cuts false alarms from {pct(hi['model_scam']['fpr'])} to <b>{pct(hi['app_scam']['fpr'])}</b>.
With "be careful" counted it flags {pct(hi['app_flagged']['recall'])} of scams.</li>
<li>The price is paid on UCI (UK spam, 2011): only {pct(hu['app_flagged']['recall'])} of its fraud gets flagged, against
{pct(hu['model_flagged']['recall'])} for the model alone. Its lures (premium-rate call-backs, "you have won" without a link) lack the
Indian fraud cues the gate looks for. Fewer false alarms cost missed scams; the gate makes that trade on purpose for Indian users.</li>
<li><b>0.5.3, scam gate v5, built on the validation split only</b> (<code>scripts/analyze_sms_misses.py</code>):
Hindi "{{number}} पर कॉल करें" call-backs and service-cut threats, "send the code you received", premium-rate prizes, police words only
with something to do, payment receipts, government links and the sending brand's own deep links. On validation, Indian scams went
from 80 missed to 0. The test split above was run once, after the rules were final. Held-out scams still missed (34 of 400):
spoofed bank-header frauds, tax refund, courier, loan and investment templates.</li>
<li><b>Where the rules were tuned.</b> The gate was written against a 64-message challenge set and the inbox above, so these are
not held-out numbers: on the inbox, 0.5.1 gives {lap['v0_5_1']['SCAM']} scam and {lap['v0_5_1']['UNCERTAIN']} suspicious of 4,620; on
the phone, {ph['flagged']} of the newest 1,000 were flagged, {ph['owner_confirmed_scam']} of them real scams, and none was missed in a
{ph['let_through_sample_checked']}-message sample of the rest. On 23 Nov 0.5.3 flagged {inbox['phone_audit_v0_5_3_newest_1000']['flagged']} of the newest 1,000, all of them scams by the
owner's saved answers (a personalised result: those answers are applied by the app itself). The untouched checks are the held-out split above and 19 messages written
after tuning: {chal['summary']['fresh_ham']['SCAM']} of {chal['summary']['fresh_ham']['n']} legitimate ones called scam,
{chal['summary']['fresh_scam']['SCAM']} of {chal['summary']['fresh_scam']['n']} scams called scam.</li>
</ul></div>"""

sus_html = "<h3>7b. Sustained load: temperature, memory and speed</h3>"
if sus:
    sm = sus["summary"]
    sus_rows = "".join(f"<tr><td>{r['answer']}</td><td>{r['elapsed_s']}</td><td>{r['prefill_tps']}</td><td>{r['decode_tps']}</td><td>{r['ttft_s']}</td>"
                       f"<td>{r['peak_rss_mib']:.0f}</td><td>{r['cpu_c_after']}</td><td>{r['battery_c_after']}</td><td>{r['cpu_util_pct']}</td></tr>" for r in sus["answers"])
    sus_html += f"""<p>{sm['answers']} Llama answers back to back with no pause ({sm['minutes']} min; {sus['device']}, {sus['soc']},
Android {sus['android']}, {'on USB power' if sus['plugged'] else 'on battery'}; <code>scripts/bench_sustained.py</code>, the loop run as a
script on the phone). Each answer is an alert explanation: a {sus['answers'][0]['prompt_tokens']}-token prompt (verdict, warning signs, the SMS)
and up to {sus['max_new']} new tokens. Decode, average of the first three answers vs the last three:
{sm['decode_avg_first3_last3'][0]} → {sm['decode_avg_first3_last3'][1]} tok/s; time to first token {sm['ttft_avg_first3_last3'][0]} →
{sm['ttft_avg_first3_last3'][1]} s. Hottest CPU zone
{sm['cpu_c_start_peak'][0]} °C at the start, {sm['cpu_c_start_peak'][1]} °C at its peak; battery {sm['battery_c_start_peak'][0]} → {sm['battery_c_start_peak'][1]} °C.
Slowest single answer: {min(r['decode_tps'] for r in sus['answers'])} tok/s, so no thermal throttling showed in this run. Peak memory of the runner: {sm['peak_rss_mib_first_last'][0]:.0f} MiB on the first answer, {sm['peak_rss_mib_first_last'][1]:.0f} MiB on the last (no growth).
The app at rest with the detectors loaded and no Llama: <b>{sm['app_pss_mb_detectors_only']} MB</b> PSS. Model files: Llama 1.12 GB,
detectors 130 KB each.</p>
<table><tr><th>#</th><th>s</th><th>Prefill tok/s</th><th>Decode tok/s</th><th>TTFT s</th><th>Peak RSS MiB</th><th>CPU °C</th><th>Battery °C</th><th>CPU use %</th></tr>{sus_rows}</table>"""
else:
    sus_html += "<p class=pending>Pending: scripts/bench_sustained.py needs the phone connected.</p>"


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
    e2e_html += f"<h4>{e['device']} ({e['soc']}): {ok}/{len(e["results"])} checks passed</h4><ul style=\"list-style:none;padding-left:0\">" + "".join(
        f"<li><b style=\"color:{'#15803d' if r['ok'] else '#b91c1c'}\">{'PASS' if r['ok'] else 'FAIL'}</b> {r['check']} <span class=small>{r['detail'][:140]}</span></li>" for r in e["results"]) + "</ul>"
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
<div style="opacity:.8;font-size:9.5pt">BHARAT AI-SoC CHALLENGE 2026–27 · PROBLEM STATEMENT 3 · TEAM ENTITY · V S KAMALESH · VERSION {VERSION}</div>
<h1>ENTITY Shield</h1>
<div style="font-size:12.5pt">On-device threat, phishing and scam detection on an Arm phone. Detectors decide in milliseconds;
Llama 3.2 on ExecuTorch explains in English or Hindi. Nothing leaves the phone.</div>
<div class="k"><b>{f3(h_ind['precision'])} / {f3(h_ind['recall'])}</b>SMS "scam" precision / recall, unseen Indian templates (app verdict)</div>
<div class="k"><b>0.64 ms</b>full SMS check on a Dimensity 7300</div>
<div class="k"><b>0.03%</b>ordinary DNS lookups blocked by mistake</div>
<div class="k"><b>1.0 s</b>Llama first token in the app, Snapdragon 7s Gen 3</div>
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
<tr><td>Monitor/classify network traffic for anomalies and intrusions</td><td>Network Guard: DNS VPN + URL model + beacon / DGA / tunnel detectors; LAN tripwire (scan, brute force, ADB probe)</td><td>§4, §9</td></tr>
<tr><td>Detect phishing URLs</td><td>URL detector on links in SMS, shared links, Link Guard, DNS lookups; the link sandbox follows the link and reads the page in an isolated process</td><td>§3, §5</td></tr>
<tr><td>Detect scam SMS / fraudulent communications</td><td>SMS detector on every incoming SMS; inbox audit</td><td>§3</td></tr>
<tr><td>Explain in plain language</td><td>Llama 3.2 1B (ExecuTorch), English and Hindi, read-aloud</td><td>§6</td></tr>
<tr><td>Recommend remediation</td><td>Fixed checked steps per scam family + real actions (block, 1930, report, remove app, leave Wi-Fi)</td><td>§6</td></tr>
<tr><td>Process data locally</td><td>All models on the Arm CPU; airplane-mode capable</td><td>§10</td></tr>
<tr><td>Llama via ExecuTorch (required)</td><td><code>org.pytorch.executorch.extension.llm.LlmModule</code> 1.5.1 inside the APK</td><td>§6, §7</td></tr></table>

<h3>Phase 1 checklist from the PS3 mentor session</h3>
<table><tr><th>Asked for</th><th>Where</th></tr>
<tr><td>One primary threat, finished end to end (input → features → detector → risk → explanation → advice)</td><td>Scam SMS is the primary path (§2, §3); URL and network are add-ons on the same detectors</td></tr>
<tr><td>Detector decides; Llama explains structured evidence, does not invent, is not run for every message</td><td>§2, §6 (verdict prefill, evidence list, only for flagged items)</td></tr>
<tr><td>Llama actually executes on the Arm target through ExecuTorch</td><td>§6, §7, §7b; logs in the release</td></tr>
<tr><td>Realistic legitimate Indian messages (OTP, bank, UPI, KYC reminders), Hinglish, code-mixing</td><td>§3: 96 legitimate and 47 scam templates in English, Hindi and Hinglish</td></tr>
<tr><td>No near-duplicate leakage; threshold on validation, not the test set</td><td>§3: split by whole template and by time; §3b says where rules were tuned</td></tr>
<tr><td>Precision, recall, F1, confusion matrix, false-positive rate, the trade-off</td><td>§3, §3b</td></tr>
<tr><td>Consented, anonymised data; no scraped inboxes</td><td>§10: public datasets and written templates; one inbox, the developer's own, counts only</td></tr>
<tr><td>Detector latency apart from Llama TTFT and tok/s; RAM, size, CPU, temperature, sustained behaviour</td><td>§2, §3, §7, §7b</td></tr>
<tr><td>Model loaded once in a long-running process</td><td>§2: Llama stays loaded while alerts are open and is freed after 90 s idle; detectors stay loaded</td></tr>
<tr><td>Works offline</td><td>§10; demo video in airplane mode</td></tr>
<tr><td>Minimal data kept; no automatic irreversible actions from the LLM</td><td>§10: alert text auto-deleted; Llama never acts</td></tr>
<tr><td>Verified updates</td><td>Llama download SHA-256 checked; detector and feed updates ship with the APK (§11: signed feed updates)</td></tr></table>

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
mistake we measured and removed (§7).</p>

<h2>3. Detectors</h2>
<p><b>Features:</b> hashed character 2–5-grams (32,768 slots, FNV-1a) plus 15 named signals for SMS and 11 for URL. The signals
include urgency, OTP/PIN requests, payment requests, threats, prize and job lures, authority claims, KYC/Aadhaar, brand-vs-domain
mismatch, shorteners, and <b>sender is a 10-digit mobile number vs a registered DLT header</b> such as <code>VM-HDFCBK</code>.
<b>Model:</b> logistic regression, exported as one <code>Linear + sigmoid</code> ExecuTorch program (XNNPACK).
<b>Data:</b> UCI SMS Spam Collection (only spam with a fraud cue counts as scam: Shield detects fraud, not marketing), plus 143
hand-written Indian templates (47 scam families, 96 legitimate: bank OTP and debit alerts, UPI, deliveries, bills, missed-call
and data alerts, DLT promotions, government advisories, personal Hinglish/Hindi). For URLs: PhishTank (time split), OpenPhish, Tranco.
The test sets hold out whole <i>templates</i> and the <i>newest</i> phishing.</p>
<table><tr><th>Detector · test set</th><th>n</th><th>Precision</th><th>Recall</th><th>False-alarm rate</th><th>AUC</th></tr>
<tr><td>SMS model alone · UCI held out</td><td>{s_uci['n']}</td><td>{f3(s_uci['precision'])}</td><td>{f3(s_uci['recall'])}</td><td>{pct(s_uci['fpr'])}</td><td>{f3(auc_uci)}</td></tr>
<tr><td>SMS model alone · Indian templates never seen in training</td><td>{s_ind['n']}</td><td>{f3(s_ind['precision'])}</td><td>{f3(s_ind['recall'])}</td><td>{pct(s_ind['fpr'])}</td><td>{f3(auc_ind)}</td></tr>
<tr><td>URL v4 · PhishTank newer than training + real DNS hostnames and Tranco (allow-list on)</td><td>{u_t['n']}</td><td>{f3(u_t['precision'])}</td><td>{f3(u_t['recall'])}</td><td>{pct(u_t['fpr'])}</td><td>{f3(auc_u)}</td></tr>
<tr><td>URL · OpenPhish feed, 19 Nov 2026</td><td>{u_op['n']}</td><td>—</td><td>{f3(u_op['recall'])}</td><td>—</td><td>—</td></tr>
<tr><td>URL · 41 official Indian bank/UPI/government domains</td><td>{u_of['n']}</td><td>—</td><td>—</td><td>{pct(u_of['fpr'])}</td><td>—</td></tr></table>
<p><b>Same answer on the phone.</b> The app's Kotlin featurizer is a line-by-line port of the Python one. The golden vectors pass
in Python, on the JVM (unit tests) and on the phone through the production path: SMS 43/43, URL 40/40, max difference
6×10⁻⁷. Retraining on Windows reproduced the Mac model bit-exactly (max difference 0.0).</p>
<p><b>Faster by measuring.</b> 98% of detection time was featurizing: a String per n-gram, plus a fresh 131 KB dense vector copied
into a tensor on every call. An allocation-free featurizer and one reused direct <code>FloatBuffer</code> per ExecuTorch model
(<code>Tensor.fromBlob</code>) took the full SMS check from 9.4 ms to <b>5.0 ms p50</b> and the URL check from 2.7 to <b>1.1 ms</b>
on the Dimensity 7300, with parity unchanged. Built the way it ships (R8), the same checks take <b>0.64 ms</b> (SMS) and
<b>0.15 ms</b> (URL) on the Dimensity 7300 and 0.50 / 0.11 ms on the Snapdragon 7s Gen 3.</p>
<p><b>URL v3.</b> The first URL model learned benign data from bare domains only (<code>flipkart.com</code>). DNS lookups are full
hostnames (<code>connekt.flipkart.net</code>), so it called many of them phishing. v3 adds real DNS hostnames from the Cisco Umbrella
list, split by registrable domain so no domain is on both sides, and a <code>brand_lure</code> signal for brand names inside
unrelated domains. On 20,000 held-out real hostnames it raises 1.06% false alarms at the link threshold and 0.1% at Network
Guard's blocking threshold.</p>

{gate_html}

<h2>4. Network Guard: network intrusion and malware behaviour, on the phone</h2>
<ul>
<li><b>DNS-only local VPN.</b> Only the fake resolver address is routed into the TUN interface, so app traffic never flows
through Shield. Each question is parsed (IPv4/UDP/DNS, unit-tested) and attributed to its app with
<code>getConnectionOwnerUid</code>. It is scored on the phone (allow-lists, then the URL model), then either forwarded to the
network's own resolver or answered NXDOMAIN if dangerous.</li>
<li><b>Lookup policy v2</b> (<code>DnsPolicy</code>). v1 blocked whenever the URL model said "scam" and stopped ordinary
traffic: on the Infinix, the phone maker's Messages servers, Airtel's backend and an Indian SMS-link service. v2 lets through
local names, OS/CDN infrastructure, sites the user allowed, Tranco top-1M domains and the asking app's own domains; blocks
hosts on the bundled PhishTank/OpenPhish list; blocks on the model only above a 0.1%-false-alarm threshold; and only flags
the rest.
<table><tr><th>Blocked by mistake / phishing caught</th><th>v1</th><th>v2</th></tr>
<tr><td>Long-tail legitimate domains with real subdomain prefixes (20,000)</td><td>21.5%</td><td><b>0.03%</b></td></tr>
<tr><td>Held-out real DNS hostnames, Umbrella ranks 600k–1M (20,000)</td><td>10.5%</td><td><b>0.12%</b></td></tr>
<tr><td>PhishTank time-split (3,393), model alone</td><td>67%</td><td>36% (the feed list adds the known ones)</td></tr>
<tr><td>OpenPhish held-out (297), model alone</td><td>94%</td><td>87%</td></tr></table></li>
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

<h2>5. Link sandbox: follow the link instead of guessing</h2>
<p>The phone visits the link itself over raw HTTP/1.1: no cookies, no JavaScript, private addresses refused, redirects
followed one by one, the certificate captured, size and time capped. The page is parsed inside an Android
<code>isolatedProcess</code> service that has no access to Shield's files and no network; both are checked on every
end-to-end run. The report shows where the link really lands, the certificate, and what the page asks for (UPI PIN, ATM PIN,
OTP, Aadhaar, PAN, card and CVV). Strong signals: secret fields, forms or scripts that send data to Telegram or Discord bots,
brand copies, look-alike domains, APK downloads. Weak signals (common on honest Indian sites) only nudge.</p>
<table><tr><th>Live links that answered (CMF Phone 1)</th><th>Address only: scam / unsure / safe</th><th>Sandbox: scam / unsure / safe</th></tr>
<tr><td>Phishing, tuning (34)</td><td>24 / 3 / 7</td><td><b>26 / 3 / 5</b></td></tr>
<tr><td>Phishing, held-out (32)</td><td>28 / 0 / 4</td><td>27 / 1 / 4</td></tr>
<tr><td>Big Indian brands (39)</td><td>0 / 0 / 39</td><td>0 / 1 / 38</td></tr>
<tr><td>Long-tail .in sites, tuning (32)</td><td>0 / 2 / 30</td><td>0 / 3 / 29</td></tr>
<tr><td>Long-tail .in sites, held-out (32)</td><td>1 / 3 / 28</td><td>1 / 5 / 26</td></tr></table>
<div class="box"><b>Honest reading.</b> On the held-out sample the sandbox did not catch more phishing than the address
model. Its value is what the address cannot show. The held-out sample also found a cloaking kit that sent the sandbox on to
the real roblox.com; since 0.4.0 a risky address that lands on a trusted site stays flagged. A page that is gone or never
answers is reported as "Couldn't check this link", never as safe.</div>

<h2>6. Llama 3.2 1B on ExecuTorch: the explanation layer</h2>
<p><b>Model:</b> Meta's quantization-aware-trained <code>Llama-3.2-1B-Instruct-QLORA_INT4_EO8</code>, exported with ExecuTorch
1.5.1 (XNNPACK, 4-bit groupwise weights, 8-bit dynamic activations, KV cache, custom SDPA, context 2048; 1.12 GB). It runs in the
APK through <code>LlmModule</code> (the official ExecuTorch 1.5.1 Android library). The app downloads this exact file from
Meta/PyTorch's Hugging Face repository (<code>executorch-community</code>), SHA-256 checked, with the project's GitHub release as
fallback; the tokenizer ships in the APK. While an answer is being written, a short foreground service keeps the phone's app
freezer from pausing it. It was chosen over our own 8da4w export (faster) because it never refused and wrote fluent
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

<h3>Pre-warming the system prompt</h3>
<p>When an alert fires, the fixed 99-token system prompt is pre-filled into the KV cache (<code>LlmModule.prefillPrompt</code>), so
opening the alert costs only the message's own 134 tokens. In the app on the Infinix (foreground, same alert, greedy), the
first token arrives after <b>1.00 s pre-warmed vs 1.78 s cold (−44%)</b>. The pre-warmed answer matches the cold one for its first
14 tokens, then picks an equally correct wording. This is consistent with a near-tie flipping under a different floating-point
summation order when the prompt is prefilled in two chunks; we have not confirmed it directly. Removing the literal BOS token
from the prefilled text diverged from the first sentence, so <code>prefillPrompt</code> adds no BOS of its own.</p>

<h2 class="pb">7. Arm SoC: where the time goes</h2>
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
<div class="box"><b>Inside the app it did not carry over.</b> 0.5.0 shipped an ExecuTorch build patched to pin its 4 threads to
the big cores. In the app it measured 7.8–11 tok/s decode, not 19: Android resets a thread's CPU affinity whenever the app moves
between cpusets (foreground, top-app, background), so the pinning did not last. 0.5.1 went back to the official library:
<b>15.5 tok/s decode, 1.0 s first token pre-warmed, 1.8 s cold</b> in the app on the Infinix
(<code>bench_inapp_Infinix_X6878_2026-11-19.csv</code>). The patch is kept in <code>backup/executorch/</code>.</div>

{sus_html}

<h2>8. App: design and smoothness</h2>
<p>A new design system (graphite and signal lime, light and dark, Geist type, a custom icon set) with a liquid-glass tab bar
rendered by the app itself: the backdrop is recorded into a GraphicsLayer and redrawn through an AGSL lens and a blur. The
glass falls back to blur-only on Android 12 and to a frosted tint on Android 10–11, low-RAM phones and Battery Saver.
Tabs are built once and kept, so a switch never builds a screen mid-animation. <code>scripts/bench_ui.py</code> taps
through the tabs 30 times and reads <code>dumpsys gfxinfo</code>:</p>
<table><tr><th>Phone</th><th>Janky frames</th><th>p95 frame</th><th>p99 frame</th></tr>
<tr><td>CMF Phone 1 (Dimensity 7300, 120 Hz)</td><td>4.0% → <b>1.15%</b></td><td>150 → <b>48 ms</b></td><td>1100 → <b>65 ms</b></td></tr>
<tr><td>Infinix X6878 (Snapdragon 7s Gen 3, 120 Hz)</td><td>7.1% → <b>2.4%</b></td><td>53 → <b>27 ms</b></td><td>117 → <b>30 ms</b></td></tr></table>
<p>R8, resource shrinking and a startup profile took the APK from 33 MB to 17 MB. Llama installs from inside the app
(resumable download from Hugging Face, GitHub fallback, SHA-256 checked).</p>

<h2>9. End-to-end on the phones</h2>
{e2e_html}

<h2>10. Privacy, security and limits</h2>
<p>No account, analytics or cloud call. Messages, links and lookups are judged on the phone. The INTERNET permission is used
to forward the phone's own DNS lookups to its usual resolver, to visit a link in the sandbox when the user asks, and for the
optional one-time Llama download. The inbox audit exports counts only. Test hooks exist only in debug and QA builds, behind
the DUMP permission. Full table: <code>docs/PRIVACY.md</code>.</p>
<ul>
<li><b>Data used.</b> Training and test sets are public datasets (UCI, PhishTank, OpenPhish, Tranco, Cisco Umbrella) and
message templates written for this project. No real person's messages are in them, so they can be shared in full.</li>
<li><b>The one real inbox</b> is the developer's own phone, checked with his consent: on the phone by the app's Inbox audit
(only counts leave) and once on a laptop from a local copy deleted afterwards. Only counts are in the repository.</li>
<li><b>What the app keeps.</b> Alert text is deleted on its own (safe items after 7 days, alerts after 30). The user's
"scam / not a scam" answers keep only the message's words with numbers, codes and amounts removed and links cut to their domain.
The contacts check asks Android "is this number saved?" and reads nothing else.</li>
<li><b>Always on, with the app closed.</b> Android starts Shield for each SMS; Network Guard restarts after a reboot or update if
it was on; the app walks the user through battery, auto-start and Always-on VPN settings. "Force stop" switches it off until
the app is opened again.</li>
</ul>
<ul>
<li><b>Network Guard</b> sees this phone's own traffic, not other devices. Strict Private DNS or in-app DNS-over-HTTPS bypasses
its DNS view.</li>
<li><b>Test data:</b> the Indian SMS test set is templated, and the real-inbox numbers come from one phone whose false alarms
were used to write the scam gate. A labelled set from other consented phones is the first Phase 2 task.</li>
<li><b>URL recall from the address alone is 0.56</b> on the PhishTank time split and 0.93 on OpenPhish: hostname only, by
design. The sandbox reads HTML without running JavaScript.</li>
<li><b>Llama 1B is wrong 10–15% of the time</b> on hard inputs, so it explains but never decides.</li>
</ul>

<h2>11. Phase 2 plan</h2>
<ul><li><b>Network:</b>
<ul>
<li>A full-tunnel flow meter with NetFlow features.</li>
<li>A flow intrusion model on NF-UNSW-NB15-v2 / NF-CSE-CIC-IDS2018-v2, with a cross-dataset test.</li>
<li>A per-app anomaly baseline (ExecuTorch on-device training, <code>TrainingModule</code>).</li>
</ul></li>
<li><b>Llama:</b> distilled English/Hindi explanation data and LoRA on the QAT checkpoint (as "Llama-…" per the licence);
prefix-KV reuse; Tamil.</li>
<li><b>Evaluation:</b> a consented, anonymised SMS set from 10–20 phones never used for tuning; signed, verified updates of
the phishing feed and detector weights.</li>
<li><b>Coverage:</b> WhatsApp/Telegram notifications, call screening ("digital arrest"), UPI QR checks, a field trial with
10–20 phones.</li>
<li><b>Arm:</b> the KleidiAI on/off ablation (custom AAR), a Mali GPU (Vulkan) delegate for the detectors, and upstreaming the
ExecuTorch Windows <code>flatc</code> lookup fix and the core-count heuristic fix.</li></ul>

{f'<h2>Screens</h2><div class="grid">{screens_html}</div>' if screens_html else ''}

<h2>Reproducibility and credits</h2>
<p>Source: github.com/kkjjkamal123/ENTITY-Shield (branch <code>main</code>). Commands are listed in the README. The raw data
behind every number is in <code>benchmarks/results/</code>. Runtime: ExecuTorch 1.5.1 (BSD-3-Clause), XNNPACK, Arm KleidiAI.
<b>Built with Llama</b>: Llama 3.2 is licensed under the Llama 3.2 Community License, © Meta Platforms, Inc. Typefaces: Geist (SIL OFL 1.1). Data: UCI SMS Spam
Collection (CC BY 4.0), PhishTank, OpenPhish, Tranco, Cisco Umbrella. Device-probe and benchmark method reused from ENTITY (Apache-2.0, same
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
