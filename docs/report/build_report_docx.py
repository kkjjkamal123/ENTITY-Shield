"""Builds the Phase 1 project report as a Word document, then a PDF through Microsoft Word.

  python docs/report/build_report_docx.py

Every number is read from benchmarks/results/ (the same loaders as build_report.py), so a new measurement
updates the report. Output: docs/report/ENTITY-Shield-Report.docx and .pdf
"""
import os
import statistics
import subprocess
from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TMP = HERE / "_build"
TMP.mkdir(exist_ok=True)

# --- data: reuse the loaders of build_report.py (everything above its HTML part) ---------------------------
_src = (HERE / "build_report.py").read_text(encoding="utf-8").split("# ------------------------------------------------------------------ html")[0]
D = {"__file__": str(HERE / "build_report.py")}
exec(_src, D)
sms, url, held, sus, chal, inbox = D["sms"], D["url"], D["held"], D["sus"], D["chal"], D["inbox"]
eval_en, eval_hi2, rev_en, rev_hi, rev_hi2 = D["eval_en"], D["eval_hi2"], D["rev_en"], D["rev_hi"], D["rev_hi2"]
threads, e2e, VERSION = D["threads"], D["e2e"], D["VERSION"]
f3, pct = D["f3"], D["pct"]
s_uci, auc_uci = D["s_uci"], D["auc_uci"]
s_ind, auc_ind = D["s_ind"], D["auc_ind"]
u_t, auc_u, u_op, u_of = D["u_t"], D["auc_u"], D["u_op"], D["u_of"]
hi, hu = held["indian"], held["uci"]
lap = inbox["laptop_replay_all_messages"]
ph1 = inbox["phone_audit_v0_5_1_newest_1000"]
ph3 = inbox["phone_audit_v0_5_3_newest_1000"]
sm = sus["summary"]
p1 = lambda v: f"{v * 100:.1f}%"

# --- charts: plain, report style ---------------------------------------------------------------------------
import matplotlib  # noqa: E402

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import FancyBboxPatch  # noqa: E402

plt.rcdefaults()
plt.rcParams.update({"font.family": "Times New Roman", "font.size": 10, "axes.spines.top": False, "axes.spines.right": False,
                     "axes.grid": True, "grid.color": "#dddddd", "grid.linewidth": 0.6, "axes.axisbelow": True})
DARK, MID, LIGHT = "#1f3b63", "#6f8fb8", "#b9c7da"


def save(fig, name):
    p = TMP / f"{name}.png"
    fig.savefig(p, dpi=220, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    return p


def chart_architecture():
    fig, ax = plt.subplots(figsize=(9.2, 4.4))
    ax.set_xlim(0, 100)
    ax.set_ylim(0, 50)
    ax.axis("off")

    def box(x, y, w, h, text, fill="#ffffff", bold=False):
        ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.25,rounding_size=0.8", fc=fill, ec="black", lw=0.9))
        ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=8.6, fontweight="bold" if bold else "normal", linespacing=1.25)

    def arrow(x1, y1, x2, y2):
        ax.annotate("", xy=(x2, y2), xytext=(x1, y1), arrowprops=dict(arrowstyle="-|>", lw=0.9, color="black"))

    ins = ["Incoming SMS\n(SMS_RECEIVED)", "Inbox audit", "Shared / selected link,\nLink Guard", "DNS lookups\n(local DNS-only VPN)", "LAN tripwire\n(decoy ports)"]
    for i, t in enumerate(ins):
        box(0.5, 41 - i * 9.6, 17, 6.6, t)
    box(23, 27, 17, 13, "Feature extraction\n(Kotlin port of the\nPython featurizer)")
    box(23, 3, 17, 13, "Behaviour rules\nbeacon · DGA burst ·\nDNS tunnel · port scan")
    box(45.5, 27, 17, 13, "ExecuTorch detectors\nsms.pte · url.pte\n(XNNPACK, CPU)", fill="#eef2f8", bold=True)
    box(45.5, 3, 17, 13, "Scam gate and lists\nfraud cues, phishing feed,\ncontacts, user labels")
    box(68, 17, 13.5, 13, "Verdict\nsafe / be careful /\nscam")
    box(85.5, 30, 14, 14, "Notification with\nfixed remediation\nsteps (instant)")
    box(85.5, 4, 14, 16, "Llama 3.2 1B\n(ExecuTorch\nLlmModule)\nexplains, EN / HI", fill="#eef2f8", bold=True)
    for i in range(3):
        arrow(17.8, 44.3 - i * 9.6, 23, 33.5)
    arrow(17.8, 15.5, 23, 11)
    arrow(17.8, 5.9, 23, 7.5)
    arrow(17.8, 15.5, 23, 31)
    arrow(40.3, 33.5, 45.5, 33.5)
    arrow(54, 27, 54, 16.3)
    arrow(40.3, 9.5, 45.5, 9.5)
    arrow(62.8, 9.5, 68, 21)
    arrow(81.8, 26, 85.5, 36)
    arrow(92.5, 30, 92.5, 20.3)
    ax.text(93.6, 25, "when the user\nopens the alert", fontsize=7.4, style="italic", va="center")
    return save(fig, "architecture")


def chart_threads():
    rows = threads.get("Infinix X6878")
    if not rows:
        return None
    arms = [("t4_big", "4 threads,\nbig cores"), ("default", "ExecuTorch\ndefault"), ("t4", "4 threads,\nunpinned"),
            ("t3_big", "3 threads,\nbig cores"), ("naive8", "8 threads"), ("t4_little", "4 threads,\nlittle cores")]
    arms = [a for a in arms if any(r["arm"] == a[0] for r in rows)]
    med = lambda a, k: statistics.median(float(r[k]) for r in rows if r["arm"] == a)
    fig, ax = plt.subplots(1, 2, figsize=(9, 3.2))
    for i, (k, title) in enumerate([("prefill_tps", "(a) Prefill speed (tokens/s)"), ("decode_tps", "(b) Decode speed (tokens/s)")]):
        v = [med(a, k) for a, _ in arms]
        b = ax[i].bar(range(len(arms)), v, color=[DARK if a != "t4_little" else LIGHT for a, _ in arms], width=0.62, edgecolor="black", linewidth=0.5)
        ax[i].bar_label(b, fmt="%.1f", fontsize=8, padding=2)
        ax[i].set_xticks(range(len(arms)), [n for _, n in arms], fontsize=7.6)
        ax[i].set_title(title, fontsize=10)
        ax[i].set_ylim(0, max(v) * 1.15)
        ax[i].grid(axis="x", visible=False)
    fig.tight_layout()
    return save(fig, "threads")


def chart_latency():
    steps = [("SMS check (p50)", 0.5), ("SMS received to\nwarning shown", 38.4), ("Llama first token,\nprompt pre-warmed", 1000),
             ("Llama first token,\ncold", 1780), ("Llama first token,\nbackground cgroup*", 9200)]
    fig, ax = plt.subplots(figsize=(7.6, 2.9))
    b = ax.barh([s[0] for s in steps][::-1], [s[1] for s in steps][::-1], color=[LIGHT, DARK, DARK, MID, MID][::-1], edgecolor="black", linewidth=0.5, height=0.6)
    ax.set_xscale("log")
    ax.bar_label(b, labels=[f"{s[1]:g} ms" for s in steps][::-1], padding=4, fontsize=8.5)
    ax.set_xlim(0.2, 80000)
    ax.set_xlabel("time in milliseconds (log scale)")
    ax.grid(axis="y", visible=False)
    ax.tick_params(axis="y", labelsize=8.6)
    fig.tight_layout()
    return save(fig, "latency")


def chart_review():
    groups = [("English", rev_en), ("Hindi (English\nwarning signs)", rev_hi), ("Hindi (Hindi\nwarning signs)", rev_hi2)]
    groups = [(n, r["counts"]) for n, r in groups if r]
    fig, ax = plt.subplots(figsize=(7.4, 2.3))
    left = [0] * len(groups)
    for key, lab, col in [("C", "correct", DARK), ("W", "vague", MID), ("E", "wrong", "#d9d9d9")]:
        vals = [g[1][key] for g in groups]
        b = ax.barh([g[0] for g in groups], vals, left=left, color=col, edgecolor="black", linewidth=0.5, label=lab, height=0.55)
        ax.bar_label(b, labels=[str(v) if v else "" for v in vals], label_type="center", fontsize=8.5,
                     color="white" if key == "C" else "black")
        left = [l + v for l, v in zip(left, vals)]
    ax.set_xlim(0, 40)
    ax.set_xlabel("number of explanations (40 held-out scam messages per language setting)")
    ax.legend(ncol=3, loc="lower center", bbox_to_anchor=(0.5, 1.0), frameon=False, fontsize=8.5)
    ax.invert_yaxis()
    ax.grid(axis="y", visible=False)
    fig.tight_layout()
    return save(fig, "review")


def chart_sustained():
    a = sus["answers"]
    x = [r["answer"] for r in a]
    fig, ax = plt.subplots(1, 2, figsize=(9, 2.9))
    ax[0].plot(x, [r["decode_tps"] for r in a], marker="o", ms=3, color=DARK, lw=1.1, label="decode")
    ax[0].set_ylim(0, 25)
    ax[0].set_title("(a) Decode speed per answer (tokens/s)", fontsize=10)
    ax[0].set_xlabel("answer number")
    ax[1].plot(x, [r["cpu_c_after"] for r in a], marker="o", ms=3, color=DARK, lw=1.1, label="hottest CPU zone")
    ax[1].plot(x, [r["battery_c_after"] for r in a], marker="s", ms=3, color=MID, lw=1.1, label="battery")
    ax[1].set_ylim(30, 70)
    ax[1].set_title("(b) Temperature after each answer (°C)", fontsize=10)
    ax[1].set_xlabel("answer number")
    ax[1].legend(fontsize=8, frameon=False, loc="lower right")
    fig.tight_layout()
    return save(fig, "sustained")


FIG = {"arch": chart_architecture(), "threads": chart_threads(), "latency": chart_latency(), "review": chart_review(), "sustained": chart_sustained()}

# --- document helpers ----------------------------------------------------------------------------------------
doc = Document()
FONT = "Times New Roman"


def set_font(style, size, bold=None, italic=None, color=None):
    style.font.name = FONT
    style.font.size = Pt(size)
    if bold is not None:
        style.font.bold = bold
    if italic is not None:
        style.font.italic = italic
    style.font.color.rgb = RGBColor(0, 0, 0) if color is None else color
    rpr = style.element.get_or_add_rPr()
    rf = rpr.find(qn("w:rFonts"))
    if rf is None:
        rf = OxmlElement("w:rFonts")
        rpr.append(rf)
    for k in ("w:ascii", "w:hAnsi", "w:eastAsia", "w:cs"):
        rf.set(qn(k), FONT)
    for k in ("w:asciiTheme", "w:hAnsiTheme", "w:eastAsiaTheme", "w:cstheme"):
        if rf.get(qn(k)) is not None:
            del rf.attrib[qn(k)]


st = doc.styles
set_font(st["Normal"], 12)
pf = st["Normal"].paragraph_format
pf.line_spacing = 1.5
pf.space_after = Pt(6)
pf.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
for name, size in [("Heading 1", 16), ("Heading 2", 14), ("Heading 3", 12)]:
    set_font(st[name], size, bold=True)
    hf = st[name].paragraph_format
    hf.space_before = Pt(18 if name == "Heading 1" else 12)
    hf.space_after = Pt(12 if name == "Heading 1" else 6)
    hf.line_spacing = 1.15
    hf.keep_with_next = True
    hf.alignment = WD_ALIGN_PARAGRAPH.LEFT
st["Heading 1"].paragraph_format.page_break_before = True
set_font(st["Caption"], 11, bold=False, italic=False)
st["Caption"].paragraph_format.alignment = WD_ALIGN_PARAGRAPH.CENTER
st["Caption"].paragraph_format.space_after = Pt(10)
st["Caption"].paragraph_format.line_spacing = 1.15
for name in ("List Bullet", "List Number"):
    set_font(st[name], 12)
    st[name].paragraph_format.line_spacing = 1.5
    st[name].paragraph_format.space_after = Pt(3)
for name in ("TOC 1", "TOC 2", "TOC 3", "Table of Figures"):
    try:
        set_font(st[name], 12, bold=(name == "TOC 1"))
    except KeyError:
        pass

sec = doc.sections[0]
sec.page_height, sec.page_width = Inches(11.69), Inches(8.27)
sec.left_margin, sec.right_margin, sec.top_margin, sec.bottom_margin = Inches(1.25), Inches(1.0), Inches(1.0), Inches(1.0)


def field(par, instr, placeholder=""):
    r = par.add_run()
    b = OxmlElement("w:fldChar")
    b.set(qn("w:fldCharType"), "begin")
    b.set(qn("w:dirty"), "true")
    r._r.append(b)
    r2 = par.add_run()
    t = OxmlElement("w:instrText")
    t.set(qn("xml:space"), "preserve")
    t.text = f" {instr} "
    r2._r.append(t)
    r3 = par.add_run()
    s = OxmlElement("w:fldChar")
    s.set(qn("w:fldCharType"), "separate")
    r3._r.append(s)
    par.add_run(placeholder)
    r5 = par.add_run()
    e = OxmlElement("w:fldChar")
    e.set(qn("w:fldCharType"), "end")
    r5._r.append(e)


def runs(par, text, size=None):
    """`code` in Consolas, *italic*, **bold**."""
    import re
    for tok in re.split(r"(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*)", text):
        if not tok:
            continue
        if tok.startswith("`"):
            r = par.add_run(tok[1:-1])
            r.font.name = "Consolas"
            r.font.size = Pt((size or 12) - 1.5)
        elif tok.startswith("**"):
            r = par.add_run(tok[2:-2])
            r.bold = True
        elif tok.startswith("*"):
            r = par.add_run(tok[1:-1])
            r.italic = True
        else:
            r = par.add_run(tok)
        if size and not tok.startswith("`"):
            r.font.size = Pt(size)
    return par


def P(text, align=None, indent=True):
    p = doc.add_paragraph()
    runs(p, " ".join(text.split()))
    if align:
        p.alignment = align
    return p


def H1(t):
    return doc.add_heading(t, level=1)


def H2(t):
    return doc.add_heading(t, level=2)


def H3(t):
    return doc.add_heading(t, level=3)


def bullets(items, style="List Bullet"):
    for it in items:
        p = doc.add_paragraph(style=style)
        runs(p, " ".join(it.split()))
        p.alignment = WD_ALIGN_PARAGRAPH.LEFT


def caption(kind, text):
    p = doc.add_paragraph(style="Caption")
    p.add_run(f"{kind} ")
    field(p, f"SEQ {kind} \\* ARABIC", "1")
    p.add_run(f": {text}")
    return p


def figure(path, width, text):
    if not path:
        return
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.keep_with_next = True
    p.paragraph_format.space_before = Pt(6)
    p.add_run().add_picture(str(path), width=Inches(width))
    caption("Figure", text)


def shade(cell, hexcol):
    tcpr = cell._tc.get_or_add_tcPr()
    sh = OxmlElement("w:shd")
    sh.set(qn("w:val"), "clear")
    sh.set(qn("w:color"), "auto")
    sh.set(qn("w:fill"), hexcol)
    tcpr.append(sh)


def table(text, header, rows, widths=None, size=10.5):
    c = caption("Table", text)
    c.paragraph_format.keep_with_next = True
    t = doc.add_table(rows=1, cols=len(header))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, h in enumerate(header):
        cell = t.rows[0].cells[i]
        cell.text = ""
        runs(cell.paragraphs[0], h, size).runs[0].bold = True
        for r in cell.paragraphs[0].runs:
            r.bold = True
        shade(cell, "E7E6E6")
    for row in rows:
        cells = t.add_row().cells
        for i, v in enumerate(row):
            cells[i].text = ""
            runs(cells[i].paragraphs[0], str(v), size)
    for row in t.rows:
        trpr = row._tr.get_or_add_trPr()
        cs = OxmlElement("w:cantSplit")
        trpr.append(cs)
        for i, cell in enumerate(row.cells):
            for par in cell.paragraphs:
                par.paragraph_format.line_spacing = 1.0
                par.paragraph_format.space_after = Pt(2)
                par.paragraph_format.space_before = Pt(2)
                par.alignment = WD_ALIGN_PARAGRAPH.LEFT
            if widths:
                cell.width = Inches(widths[i])
    hdr = t.rows[0]._tr.get_or_add_trPr()
    th = OxmlElement("w:tblHeader")
    hdr.append(th)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return t


def centered(text, size=12, bold=False, space_after=6, italic=False):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_after = Pt(space_after)
    p.paragraph_format.line_spacing = 1.15
    r = p.add_run(text)
    r.font.size = Pt(size)
    r.bold = bold
    r.italic = italic
    return p


def front_title(text):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_after = Pt(18)
    r = p.add_run(text)
    r.bold = True
    r.font.size = Pt(16)
    return p


def page_break():
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


def page_numbers(section, fmt):
    pg = section._sectPr.find(qn("w:pgNumType"))
    if pg is None:
        pg = OxmlElement("w:pgNumType")
        section._sectPr.append(pg)
    pg.set(qn("w:fmt"), fmt)
    pg.set(qn("w:start"), "1")


# --- title page ----------------------------------------------------------------------------------------------
for _ in range(2):
    doc.add_paragraph()
centered("BHARAT AI-SoC STUDENT CHALLENGE 2026–27", 13, True, 4)
centered("Problem Statement 3: On-Device Threat, Phishing and Scam Detection on Arm", 12, False, 30)
centered("ENTITY SHIELD", 24, True, 6)
centered("On-Device Scam, Phishing and Network Threat Detection for Android Phones,", 14, False, 0)
centered("with Llama 3.2 1B Explanations on ExecuTorch", 14, False, 30)
centered("PHASE 1 PROJECT REPORT", 13, True, 36)
logo = ROOT / "docs" / "img" / "logo.png"
if logo.exists():
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.add_run().add_picture(str(logo), width=Inches(1.0))
centered("", 12, False, 18)
centered("Submitted by", 12, False, 4, italic=True)
centered("V S KAMALESH (24EC0243)", 13, True, 4)
centered("Team ENTITY", 12, False, 4)
centered("B.E. Electronics and Communication Engineering (2024–2028)", 12, False, 30)
centered("CHENNAI INSTITUTE OF TECHNOLOGY", 14, True, 4)
centered("Chennai, Tamil Nadu", 12, False, 30)
centered("November 2026", 12, False, 4)

# --- front matter (roman page numbers) ------------------------------------------------------------------------
front = doc.add_section(WD_SECTION.NEW_PAGE)
front.footer.is_linked_to_previous = False
front.header.is_linked_to_previous = False
fp = front.footer.paragraphs[0]
fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
field(fp, "PAGE", "i")
hp = front.header.paragraphs[0]
hp.alignment = WD_ALIGN_PARAGRAPH.RIGHT
hr = hp.add_run("ENTITY Shield: Phase 1 Report")
hr.italic = True
hr.font.size = Pt(9)
page_numbers(front, "lowerRoman")

front_title("ABSTRACT")
P(f"""Fraudulent SMS messages, phishing links and malicious apps are now a daily problem for mobile users in India.
Most existing protection depends on cloud services, which means the user's messages have to leave the phone. In this project
we built **ENTITY Shield**, an Android application that detects scam SMS, phishing URLs and suspicious network behaviour entirely
on the phone's Arm CPU, and explains each threat to the user in English or Hindi.""")
P(f"""The system uses two tiers. Small logistic-regression detectors over hashed character n-grams and hand-designed
fraud signals (about 130 KB each) are exported with ExecuTorch and run on every SMS, link and DNS lookup in under a
millisecond. A rule layer (the "scam gate") turns the model score into a verdict using fraud-specific cues. Only when a
message is flagged and the user opens the alert does Llama 3.2 1B Instruct, quantised to 4 bits and run through the
ExecuTorch `LlmModule`, write a short explanation. The verdict and the remediation steps are never generated by the
language model.""")
P(f"""On Indian scam and legitimate message templates that were held out from training, the complete app reached a
precision of {f3(hi['app_scam']['precision'])} and recall of {f3(hi['app_scam']['recall'])} for the "scam" verdict, with
{hi['app_scam']['fp']} of {hi['app_scam']['fp'] + hi['app_scam']['tn']} legitimate messages wrongly called scam. The URL model caught
{p1(u_op['recall'])} of a live OpenPhish snapshot from the hostname alone, and the on-device DNS filter blocked 0.03% of
legitimate long-tail lookups by mistake. On a Snapdragon 7s Gen 3 phone the SMS check takes about 0.5 ms, Llama's first token
appears after 1.0 s, and decoding stays at about 19 tokens/s over 30 consecutive answers with no thermal throttling. We also
describe the hardware measurements that shaped the design, including a 3.9 times prefill slowdown we traced to Android
placing the inference threads on the little cores.""")
p = P("**Keywords:** on-device AI, ExecuTorch, Llama 3.2, Arm, SMS fraud detection, phishing detection, DNS, Android.", indent=False)
p.alignment = WD_ALIGN_PARAGRAPH.LEFT

page_break()
front_title("ACKNOWLEDGEMENT")
P("""We thank Arm and the organisers of the Bharat AI-SoC Student Challenge for the problem statement, the mentor sessions and
the technical material provided during Phase 1. The Phase 1 mentor session on Problem Statement 3 directly changed our design:
the advice that the detector should decide and the language model should only explain is now the core of the system.""")
P("""We thank the faculty of the Department of Electronics and Communication Engineering, Chennai Institute of Technology,
for their guidance and support, and the Principal and management for encouraging student participation in national
challenges.""")
P("""We are also grateful to the maintainers of the open datasets and tools this work depends on: the UCI SMS Spam Collection,
PhishTank, OpenPhish, Tranco and the Cisco Umbrella popularity list, and the PyTorch ExecuTorch and Meta Llama teams.""")
p = P("V S Kamalesh, Team ENTITY", align=WD_ALIGN_PARAGRAPH.RIGHT)

page_break()
front_title("TABLE OF CONTENTS")
field(doc.add_paragraph(), 'TOC \\o "1-2" \\h \\z \\u', "Right-click and choose Update Field.")
page_break()
front_title("LIST OF FIGURES")
field(doc.add_paragraph(), 'TOC \\h \\z \\c "Figure"', "Right-click and choose Update Field.")
front_title("LIST OF TABLES")
field(doc.add_paragraph(), 'TOC \\h \\z \\c "Table"', "Right-click and choose Update Field.")
page_break()
front_title("LIST OF ABBREVIATIONS")
abbr = [("AUC", "Area under the ROC curve"), ("C2", "Command and control (server of a malware operator)"),
        ("DGA", "Domain generation algorithm"), ("DLT", "Distributed Ledger Technology (TRAI sender registration, e.g. VM-HDFCBK)"),
        ("DNS", "Domain Name System"), ("FPR", "False positive rate"), ("KV cache", "Key/value cache of a transformer"),
        ("LLM", "Large language model"), ("NXDOMAIN", "DNS answer meaning that a name does not exist"),
        ("OTP", "One-time password"), ("PSS", "Proportional set size (Android memory measure)"), ("QAT", "Quantisation-aware training"),
        ("RSS", "Resident set size"), ("TTFT", "Time to first token"), ("TUN", "Virtual network interface used by Android VpnService"),
        ("UPI", "Unified Payments Interface"), ("XNNPACK", "Optimised neural-network operator library used by ExecuTorch on Arm CPUs")]
t = doc.add_table(rows=0, cols=2)
for a, b in abbr:
    c = t.add_row().cells
    c[0].text, c[1].text = a, b
    c[0].width, c[1].width = Inches(1.3), Inches(4.6)
    for cell in c:
        cell.paragraphs[0].paragraph_format.line_spacing = 1.15
        cell.paragraphs[0].paragraph_format.space_after = Pt(2)
        cell.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.LEFT
    c[0].paragraphs[0].runs[0].bold = True

# --- main matter (arabic page numbers) -----------------------------------------------------------------------
main = doc.add_section(WD_SECTION.NEW_PAGE)
page_numbers(main, "decimal")

# 1 ----------------------------------------------------------------------------------------------------------
H1("1. Introduction")
H2("1.1 Background")
P("""Almost every phone in India now receives fraudulent messages: fake KYC expiry notices, electricity disconnection
warnings, courier "customs" charges, part-time job offers that ask for an advance, and "digital arrest" threats that ask the
victim to call a number. The Government has set up the national cyber crime helpline 1930, the reporting portal
cybercrime.gov.in [15] and the Chakshu facility on Sanchar Saathi [16] to report suspected fraud communication. These channels
work after the user already suspects a message. The people who are most at risk, such as elderly users and first-time
smartphone users, often do not reach that point.""")
P("""Protection that runs in the cloud has a privacy cost, because the content of personal messages has to be uploaded.
A modern Arm phone, on the other hand, has enough compute to run small classifiers in microseconds and even a
one-billion-parameter language model at interactive speed. This project tests whether useful scam, phishing and network
threat protection can be built entirely on the phone, without any server.""")
H2("1.2 Problem Statement")
P("""Problem Statement 3 of the Bharat AI-SoC Student Challenge asks for an on-device system that monitors network
traffic for anomalies and intrusions, detects phishing URLs and scam or fraudulent communication, explains the threat in
plain language, recommends remediation, and processes all data locally on an Arm-based platform. Llama run through
ExecuTorch is required for the language part.""")
H2("1.3 Objectives")
bullets([
    "Detect scam SMS on arrival, including when the app is closed, with very few false alarms on genuine bank, OTP and service messages.",
    "Judge links found in messages, shared from other apps or typed by the user, and the DNS lookups made by every app on the phone.",
    "Detect malware-like network behaviour (beaconing, DGA bursts, DNS tunnelling) and probes from other devices on the same Wi-Fi.",
    "Explain each threat in simple English or Hindi with Llama 3.2 1B on ExecuTorch, without letting the model decide the verdict or invent advice.",
    "Measure accuracy, latency, memory, temperature and sustained speed on real Arm phones, and optimise the slow parts.",
])
H2("1.4 Organisation of the Report")
P("""Chapter 2 reviews related work. Chapter 3 describes the methodology: the data, the features, the detectors, the
scam gate, the network and link analysis and the explanation layer. Chapter 4 covers the Android implementation. Chapter 5
presents the results. Chapter 6 discusses hardware utilisation on the two test phones and Chapter 7 the optimisation
techniques we applied. Chapter 8 lists the main problems we ran into and Chapter 9 concludes with the plan for Phase 2.""")

# 2 ----------------------------------------------------------------------------------------------------------
H1("2. Literature Survey")
H2("2.1 SMS Spam and Fraud Detection")
P("""Almeida et al. [1] released the SMS Spam Collection, 5,574 English messages from the UK and Singapore, and showed that
linear models such as support vector machines and naive Bayes over word features reach high accuracy on it. The dataset is
still the most common public benchmark. It is, however, more than ten years old and contains mostly premium-rate marketing
spam rather than the bank, KYC and UPI frauds that Indian users see today. For this reason we use it together with our own
Indian templates (Section 3.2) and we count only fraud-like spam as "scam".""")
H2("2.2 Phishing URL Detection")
P("""Ma et al. [2] showed that lexical and host-based features of the URL alone can separate malicious from benign sites
with a simple online linear classifier, without fetching the page. This is important for our case, since a DNS lookup only
contains the hostname. Feature hashing [3] lets such a model use a very large number of character n-grams in a fixed-size
vector, which keeps the model small enough for a phone. We use PhishTank [9] and OpenPhish [10] for phishing hosts and
Tranco [11] and the Cisco Umbrella list [12] for benign hosts.""")
H2("2.3 Malware Behaviour in DNS Traffic")
P("""Antonakakis et al. [4] showed that malware using domain generation algorithms produces bursts of lookups for
non-existent names, and Plohmann et al. [13] measured these algorithms across many malware families. Regular "beacon" lookups
to a command and control server and long random subdomains used for DNS tunnelling are other well-known signs. These
patterns can be counted per app on the phone without any deep packet inspection.""")
H2("2.4 Small Language Models on Mobile Devices")
P("""Meta's Llama 3.2 1B and 3B models [5] were released for on-device use, followed by quantised versions trained with
quantisation-aware training and LoRA adapters [6], which keep most of the quality at 4-bit weights. ExecuTorch [7] is the
PyTorch runtime for edge devices; on Arm CPUs it uses the XNNPACK backend [8], which in turn uses Arm KleidiAI micro-kernels
for low-bit matrix multiplication. Integer quantisation for efficient inference follows the approach of Jacob et al. [14].""")
H2("2.5 Gap Addressed by This Work")
P("""Published work usually treats SMS spam, phishing URLs and network intrusion as separate problems, evaluates them on a
desktop, and does not explain the result to the user. We combine all three on one phone, keep every model on the Arm CPU,
measure on real devices, and use a small language model only as an explainer whose output is constrained by the detector's
evidence.""")

# 3 ----------------------------------------------------------------------------------------------------------
H1("3. Methodology")
H2("3.1 System Overview")
P("""Figure 1 shows the overall design. Every input (an incoming SMS, a message in the inbox, a link, a DNS lookup or a
connection to a decoy port) is first converted to features and scored by a small ExecuTorch detector or by behaviour rules.
A rule layer combines the score with fraud cues and lists and produces one of three verdicts: safe, be careful, or scam.
For "be careful" and "scam" the app shows a notification at once with fixed remediation steps. Llama is loaded only when
the user opens the alert, and its job is to explain why this particular message is dangerous.""")
figure(FIG["arch"], 6.2, "Block diagram of ENTITY Shield. Shaded blocks are ExecuTorch programs running on the Arm CPU.")
P("""We chose this split after the Phase 1 mentor session. A one-billion-parameter model takes around a second to start
answering and is wrong on a noticeable fraction of hard inputs (Section 5.6), so it cannot be the component that decides.
The detectors, on the other hand, are fast, deterministic and can be tested exactly against the Python reference.""")

H2("3.2 Datasets and Data Splits")
P(f"""**SMS.** We used the UCI SMS Spam Collection [1] and 143 Indian message templates that we wrote for this project:
47 scam families (KYC, electricity, courier, digital arrest, UPI refund, job and task scams, loan and investment, and others)
and 96 legitimate types (bank OTP and debit alerts, UPI receipts, deliveries, bills, carrier data and missed-call alerts,
DLT-registered promotions, government advisories and personal messages in English, Hindi and Hinglish). Each template has
slots for names, amounts, links and numbers and is expanded into many messages. Only UCI spam that contains a fraud cue is
labelled "scam", because the goal is to detect fraud and not marketing.""")
P(f"""To avoid near-duplicate leakage, the Indian data is split by **whole template**: all messages from a test template
are unseen in training. The final split has {sms['sizes']['train']:,} training, {sms['sizes']['val']:,} validation and
{sms['sizes']['test']:,} test messages. All thresholds and all scam-gate rules were tuned on the validation split; the test
split was run once at the end.""")
P(f"""**URLs and hostnames.** Phishing hosts come from PhishTank [9] and OpenPhish [10], benign hosts from Tranco [11]
and Cisco Umbrella [12]. PhishTank is split by time, so the test set contains only phishing reported after the training data.
Umbrella hostnames are split by registrable domain so that no domain appears on both sides. The sizes are
{url['sizes']['train']:,} training, {url['sizes']['val']:,} validation and {url['sizes']['test']:,} test hostnames, plus three extra
evaluation sets: {url['sizes']['dns_longtail']:,} long-tail real hostnames, a live OpenPhish snapshot ({url['sizes']['openphish']} hosts)
and {url['sizes']['official']} official Indian bank, UPI and government domains.""")

H2("3.3 Feature Extraction")
P(f"""Each SMS is lower-cased and normalised, and character n-grams of length 2 to 5 are hashed into a {sms['dim']:,}-slot vector
with the FNV-1a hash [3]. To this we add 15 named signals that encode domain knowledge, for example urgency words, a request
for an OTP or PIN, a payment request, a threat to block an account, prize and job lures, KYC or Aadhaar words, a link whose
domain does not match the brand named in the text, a URL shortener, and whether the sender is a 10-digit mobile number or a
registered DLT header such as `VM-HDFCBK`. The URL model uses the same hashed n-grams over the hostname and 12 signals such
as an IP address as host, a free hosting or dynamic DNS platform, a brand name inside an unrelated domain, punycode and
suspicious top-level domains.""")
P("""The Android app has its own Kotlin featurizer, written as a line-by-line port of the Python one. Golden test vectors
generated in Python are checked on the JVM in unit tests and on the phone through the production code path, so the phone
gives exactly the same feature vector as training.""")

H2("3.4 Detector Model and Training")
P("""Both detectors are logistic regression models:""")
P("""p(scam | x) = σ(w · x + b),   where σ(z) = 1 / (1 + e^(−z))""", align=WD_ALIGN_PARAGRAPH.CENTER)
P(f"""They are trained in PyTorch with L2 regularisation ({sms['hyperparams']['l2']}) for {sms['hyperparams']['epochs']} epochs and exported
as a single `Linear + sigmoid` ExecuTorch program delegated to XNNPACK. Each `.pte` file is {sms['pte_bytes']:,} bytes, and the
exported program matches the PyTorch model to within {sms['pte_vs_torch_max_abs_diff']:.1e}. Two thresholds are chosen on the
validation split: for SMS, a score above {sms['thresholds']['scam']} means "scam" and above {sms['thresholds']['uncertain']}
means "be careful"; for URLs the thresholds are {url['thresholds']['scam']} and {url['thresholds']['uncertain']}. Network Guard
blocks a lookup on the model score alone only above {url['thresholds']['dns_block']}. We also tried a small multi-layer
perceptron for URLs; it did not improve validation results enough to justify the larger model, so we kept the linear one.""")
P("""The largest learned signal weights are easy to interpret, which helped us check that the model learned fraud and
not artefacts of the data:""")
sw = sms["signal_weights"]
uw = url["signal_weights"]
table("Learned weights of the named signals (largest positive and negative)",
      ["SMS signal", "Weight", "URL signal", "Weight"],
      [[a, f"{sw[a]:+.2f}", b, f"{uw[b]:+.2f}"] for a, b in zip(list(sw)[:6] + list(sw)[-2:], list(uw)[:6] + list(uw)[-2:])],
      [1.9, 0.8, 1.9, 0.8])

H2("3.5 The Scam Gate")
P(f"""When we ran the first model over a real inbox of {lap['messages']:,} messages (the developer's own phone, with consent),
it called {lap['v0_5_0']['SCAM']} of them a scam and {lap['v0_5_0']['UNCERTAIN']:,} suspicious. Most were carrier data alerts, bank
login alerts, regulator advisories and promotions: the model had learned "marketing" as well as "fraud". We therefore added a
rule layer after the models, implemented identically in Python (`sms_gate`) and Kotlin (`ScamGate.kt`) and checked for
agreement on 2,871 cases by a unit test.""")
P("""A message is called a scam only if it has a fraud-specific cue: it asks the user to share a code or PIN, ties a
threat to a link or to a personal number to call, has a link that imitates a brand, is a task-job lure, or carries a link that
the URL model calls phishing. Without such a cue, a message from a registered business sender is safe, a delivered login
code is safe, and anything else is at most "be careful". A message from a saved contact is never called a scam (at most "be
careful", in case the contact's phone was compromised), and the user's own "scam" and "not a scam" answers are applied to
similar messages afterwards.""")

H2("3.6 Network Guard")
P("""Network Guard is a local VPN built with Android `VpnService` [17] that routes only a fake resolver address into the
TUN interface. Normal app traffic therefore never passes through Shield; only DNS questions do. Each question is parsed
(IPv4, UDP and DNS), attributed to the app that asked it with `getConnectionOwnerUid`, and checked by a lookup policy:""")
bullets([
    "local names, operating system and CDN infrastructure, domains the user allowed, Tranco top-1M domains and the asking app's own domains are let through;",
    "hosts on the bundled PhishTank and OpenPhish list are blocked with an NXDOMAIN answer;",
    f"other hosts are blocked on the URL model only above the {url['thresholds']['dns_block']} threshold, and otherwise only flagged.",
], "List Number")
P("""Per app, the guard also counts three malware patterns: a beacon (six or more lookups of one domain at a nearly constant
interval), a DGA burst (eight or more different non-existent names within 60 seconds) and DNS tunnelling (fifteen or more
unique subdomains, or long high-entropy labels). A separate LAN tripwire listens on decoy ports 2222, 2323, 5555, 8080, 8443
and 9999 and reports port scans, password guessing and ADB-worm probes from other devices on the Wi-Fi. To test this without
real malware we wrote a small companion app, `beaconsim`, which produces the same DNS patterns with made-up names.""")

H2("3.7 Link Sandbox")
P("""When the user asks Shield to check a link, the phone visits it itself over plain HTTP/1.1, with no cookies and no
JavaScript, refusing private addresses and following redirects one at a time with size and time limits. The page is parsed
inside an Android `isolatedProcess` service that has no access to the app's files and no network of its own. The report shows
where the link really lands, the certificate, and whether the page asks for a UPI PIN, ATM PIN, OTP, Aadhaar, PAN or card
details. Forms that post to Telegram or Discord bots, brand copies, look-alike domains and APK downloads are treated as strong
signals.""")

H2("3.8 Explanation Layer with Llama 3.2 1B")
P("""We use Meta's quantisation-aware-trained `Llama-3.2-1B-Instruct-QLORA_INT4_EO8` [6], exported with ExecuTorch 1.5.1
using XNNPACK, 4-bit group-wise weights, 8-bit dynamic activations, a KV cache, the custom SDPA operator and a context of
2,048 tokens (1.12 GB file). We also exported our own 8da4w model, which was faster, but in our tests it refused to explain
scams more often and its Hindi was weaker, so we chose the QAT model.""")
P("""The prompt contains a short fact sheet (for example, that banks never ask for an OTP or PIN through an SMS link, that a
UPI PIN is only needed to send money, and that the cyber crime helpline is 1930), the detector's evidence for this message,
and the message itself. The app then writes the beginning of Llama's answer, which states the verdict (we call this
*verdict prefill*). Without the prefill the 1B model refused to explain most scams; with it, the model continues from the
correct verdict and explains the evidence. The remediation steps shown to the user come from a fixed table per scam family
and are never generated.""")

H2("3.9 Evaluation Metrics")
P("""For the detectors we report precision = TP / (TP + FP), recall = TP / (TP + FN), F1 score, false positive rate
FPR = FP / (FP + TN) and the area under the ROC curve. A scam correctly called scam is a true positive. Because the app has
three verdicts, we report both "called scam" and "flagged at all" (scam or be careful). For Llama we used automatic checks
(refusal, invented link or phone number, contradiction of the verdict) and a manual reading of every answer, marked as
correct, vague or wrong.""")

# 4 ----------------------------------------------------------------------------------------------------------
H1("4. Implementation")
H2("4.1 Hardware and Software Platform")
table("Test devices", ["Device", "SoC", "CPU cores", "Used for"],
      [["Infinix X6878", "Qualcomm Snapdragon 7s Gen 3 (SM7635)", "Armv9: Cortex-A720 (big) + Cortex-A520 (little)", "Main development, Llama benchmarks, sustained run, end-to-end tests, demo video"],
       ["CMF Phone 1 (A015)", "MediaTek Dimensity 7300 (MT6878)", "Armv8.2: Cortex-A78 (big) + Cortex-A55 (little)", "Second phone: detector timing, UI smoothness, link sandbox study, end-to-end tests"]],
      [1.3, 1.5, 1.7, 1.8])
P(f"""Software: Android app in Kotlin with Jetpack Compose, ExecuTorch 1.5.1 Android library (`executorch-android`), PyTorch
2.14 for training and export, Python 3.12 for the evaluation scripts. The detectors run through the ExecuTorch `Module` API
and Llama through `org.pytorch.executorch.extension.llm.LlmModule`.""")
H2("4.2 Application Structure")
bullets([
    "`core`: featurizer, detectors, scam gate, advice tables and the verdict pipeline;",
    "`llm`: Llama loading, prompt building, verdict prefill, streaming and model download with SHA-256 check;",
    "`net`: the DNS-only VPN, DNS parser, lookup policy, behaviour detectors and LAN tripwire;",
    "`sandbox`: link fetcher and the isolated-process page analyser;",
    "`sms`: SMS receiver and inbox audit; `ui`: screens, alerts and settings.",
])
P("""SMS arrive through a manifest-registered receiver, so Android starts Shield for each message even when the app is not
running. Network Guard restarts after a reboot or app update if it was on, and the app guides the user through the battery,
auto-start and Always-on VPN settings that some phone makers require.""")
H2("4.3 Testing")
P(f"""Besides unit tests for the featurizer, DNS parser and scam gate, we wrote an end-to-end script (`scripts/e2e_phone.py`)
that installs a test build, sends real SMS intents, opens links in the sandbox, starts Network Guard and `beaconsim`, and
reads the results back from the app. It checks {len(e2e[-1]['results']) if e2e else 20} things in one run, including detector
parity on the phone, the sandbox isolation, the instant warning time and Llama in English and Hindi. Long runs are started
as shell scripts on the phone itself, because the USB connection of our main phone dropped under heavy load.""")

# 5 ----------------------------------------------------------------------------------------------------------
H1("5. Results and Discussion")
H2("5.1 SMS Detector")
table("SMS detector alone on the held-out test sets",
      ["Test set", "n", "Precision", "Recall", "F1", "FPR", "AUC"],
      [["UCI, held out", s_uci["n"], f3(s_uci["precision"]), f3(s_uci["recall"]), f3(s_uci["f1"]), pct(s_uci["fpr"]), f3(auc_uci)],
       ["Indian templates never seen in training", s_ind["n"], f3(s_ind["precision"]), f3(s_ind["recall"]), f3(s_ind["f1"]), pct(s_ind["fpr"]), f3(auc_ind)]],
      [2.4, 0.6, 0.8, 0.7, 0.6, 0.7, 0.6])
P(f"""The model alone separates the classes well (AUC {f3(auc_ind)} on unseen Indian templates), but at the scam threshold it
still calls {hi['model_scam']['fp']} of {hi['model_scam']['fp'] + hi['model_scam']['tn']} legitimate Indian messages a scam ({pct(s_ind['fpr'])}). For a product that
interrupts the user, this was too high, which led to the scam gate.""")
H2("5.2 Complete App Verdict with the Scam Gate")
st_rows = []
for name, x in [("Model alone, Indian, called scam", hi["model_scam"]), ("App verdict, Indian, called scam", hi["app_scam"]),
                ("App verdict, Indian, flagged at all", hi["app_flagged"]), ("Model alone, UCI, called scam", hu["model_scam"]),
                ("App verdict, UCI, flagged at all", hu["app_flagged"])]:
    st_rows.append([name, x["n"], f3(x["precision"]), f3(x["recall"]), f3(x["f1"]), pct(x["fpr"]), f"{x['tp']} / {x['fp']} / {x['fn']} / {x['tn']}"])
table("Held-out SMS results for the model alone and for the full app", ["Setting", "n", "Precision", "Recall", "F1", "FPR", "TP / FP / FN / TN"],
      st_rows, [2.2, 0.5, 0.75, 0.65, 0.55, 0.65, 1.2], size=10)
cf = hi["confusion_app"]
table("Confusion matrix of the app verdict on the held-out Indian templates",
      ["True label", "Safe", "Be careful", "Scam"],
      [["Legitimate", cf["legit"]["SAFE"], cf["legit"]["UNCERTAIN"], cf["legit"]["SCAM"]],
       ["Scam", cf["scam"]["SAFE"], cf["scam"]["UNCERTAIN"], cf["scam"]["SCAM"]]], [1.8, 1.2, 1.2, 1.2])
correct = cf["legit"]["SAFE"] + cf["scam"]["SCAM"] + cf["scam"]["UNCERTAIN"]
P(f"""On unseen Indian templates the gate raises the precision of a "scam" call from {f3(hi['model_scam']['precision'])} to
{f3(hi['app_scam']['precision'])} and lowers the false positive rate from {pct(hi['model_scam']['fpr'])} to {pct(hi['app_scam']['fpr'])},
while recall goes up from {f3(hi['model_scam']['recall'])} to {f3(hi['app_scam']['recall'])} because the fraud cues also catch scams the
model scored low. Counting "be careful" as a correct outcome for scams, {correct:,} of {hi['app_scam']['n']:,} messages
({correct / hi['app_scam']['n'] * 100:.1f}%) get an acceptable verdict.""")
P(f"""The trade-off shows on the UCI data. Only {pct(hu['app_flagged']['recall'])} of its fraud is flagged by the app, against
{pct(hu['model_flagged']['recall'])} for the model alone. UCI fraud is mostly UK premium-rate call-backs and "you have won" messages
without a link, which do not have the Indian fraud cues the gate looks for. We accepted this, since the target users are in
India, but it is a real limitation.""")
P(f"""The {hi['app_scam']['fn']} Indian scams that the app still lets through are mainly spoofed bank-header frauds and tax refund,
courier, loan and investment templates. The scam gate was first written while looking at a 64-message challenge set and at
the inbox mentioned above, so numbers on those are not held-out. On the phone, version 0.5.3 flagged {ph3['flagged']} of the newest
1,000 inbox messages, and all {ph3['owner_labelled_scam']} were scams; a sample of {ph3['let_through_sample_labelled']} messages that were let through
contained no missed scam. This result is personalised, because the owner's {ph3['saved_patterns']} earlier answers are applied by the
app. On 19 messages written after tuning, {chal['summary']['fresh_ham']['SCAM']} of {chal['summary']['fresh_ham']['n']} legitimate
messages were called scam and {chal['summary']['fresh_scam']['SCAM']} of {chal['summary']['fresh_scam']['n']} scams were caught.""")

H2("5.3 URL Detector")
table("URL detector results (allow-list on)", ["Test set", "n", "Precision", "Recall", "FPR", "AUC"],
      [["PhishTank newer than training + real DNS and Tranco hosts", u_t["n"], f3(u_t["precision"]), f3(u_t["recall"]), pct(u_t["fpr"]), f3(auc_u)],
       ["OpenPhish live snapshot, 19 Nov 2026", u_op["n"], "–", f3(u_op["recall"]), "–", "–"],
       ["Official Indian bank, UPI and government domains", u_of["n"], "–", "–", pct(u_of["fpr"]), "–"]],
      [2.9, 0.7, 0.8, 0.7, 0.7, 0.6])
P(f"""The model works from the hostname only, because that is all a DNS lookup contains. It catches {p1(u_op['recall'])} of the
live OpenPhish hosts, where attackers mostly use free hosting and fresh look-alike domains. Recall on the older PhishTank
time-split is lower ({f3(u_t['recall'])}), because many of those entries are hacked legitimate websites whose hostname looks normal.
For those cases the sandbox and the bundled phishing list are the main protection. The first version of the URL model was
trained only on bare benign domains such as `flipkart.com`, and it called many real DNS names such as `connekt.flipkart.net`
phishing. Adding real hostnames from the Umbrella list fixed this.""")

H2("5.4 Network Guard")
table("False blocks and phishing caught by the DNS lookup policy", ["Measure", "Policy v1", "Policy v2"],
      [["Long-tail legitimate domains with real subdomains (20,000), blocked by mistake", "21.5%", "0.03%"],
       ["Held-out real DNS hostnames, Umbrella ranks 600k–1M (20,000), blocked by mistake", "10.5%", "0.12%"],
       ["PhishTank time-split (3,393), caught by the model alone", "67%", "36%"],
       ["OpenPhish held-out (297), caught by the model alone", "94%", "87%"]], [3.9, 1.1, 1.1])
P("""Version 1 of the policy blocked whenever the URL model said scam. On our phone it blocked the phone maker's messaging
servers, the carrier's backend and an Indian SMS link service, which is unacceptable for a filter that sits in front of every
app. Version 2 lowers the model-only catch rate, but known phishing hosts are still blocked through the bundled feed list, and
the false block rate fell to 0.03%. In the end-to-end test, the `beaconsim` app's DGA burst, beacon and tunnelling patterns
were each detected and the alert named the app.""")

H2("5.5 Link Sandbox")
table("Live links checked on the CMF Phone 1 (scam / unsure / safe)", ["Set (links that answered)", "Address only", "Sandbox"],
      [["Phishing, tuning (34)", "24 / 3 / 7", "26 / 3 / 5"], ["Phishing, held out (32)", "28 / 0 / 4", "27 / 1 / 4"],
       ["Large Indian brands (39)", "0 / 0 / 39", "0 / 1 / 38"], ["Long-tail .in sites, tuning (32)", "0 / 2 / 30", "0 / 3 / 29"],
       ["Long-tail .in sites, held out (32)", "1 / 3 / 28", "1 / 5 / 26"]], [2.6, 1.5, 1.5])
P("""On the held-out sample the sandbox did not catch more phishing than the address model. Its value is in what the address
cannot show: where the link really lands and what the page asks for. The held-out sample also found a cloaking kit that
redirected our sandbox to the real roblox.com; since version 0.4.0 a risky address that ends on a trusted site stays flagged.""")

H2("5.6 Quality of Llama's Explanations")


def ev(name, e, r, lang):
    s = e["summary"][lang]
    rc = r["counts"]
    return [name, f"{int(s['pass_rate'] * s['n'])}/{s['n']}", s["refusal"], f"{s['invented_link']} / {s['invented_num']}", s["median_tokens"],
            f"{rc['C']} / {rc['W']} / {rc['E']}"]


table("Explanation quality on 40 held-out scam messages, run on the phone",
      ["Setting", "Automatic checks passed", "Refusals", "Invented link / number", "Median tokens", "Manual: correct / vague / wrong"],
      [ev("English", eval_en, rev_en, "EN"), ev("Hindi, English warning signs", eval_en, rev_hi, "HI"), ev("Hindi, Hindi warning signs", eval_hi2, rev_hi2, "HI")],
      [1.7, 0.9, 0.7, 0.9, 0.7, 1.3], size=10)
figure(FIG["review"], 5.6, "Manual review of Llama 3.2 1B explanations")
P(f"""The automatic checks almost always pass, but reading the answers found real errors. In English, {rev_en['counts']['E']} of the 40
answers add a wrong "PIN or password" detail to UK premium-rate spam; none of the answers to Indian messages was wrong. In Hindi,
a few answers negated the evidence (for example, saying the message did *not* come from a personal number). The manual labels
were made with AI assistance and still need to be checked by a native Hindi speaker. These errors are the reason the language
model never decides the verdict and never writes the remediation steps.""")

H2("5.7 End-to-End Tests on the Phones")
e2e_rows = [[e["device"], e["soc"], e.get("date", ""), f"{sum(r['ok'] for r in e['results'])} / {len(e['results'])}"] for e in e2e]
table("End-to-end test runs", ["Device", "SoC", "Date", "Checks passed"], e2e_rows, [1.6, 1.4, 1.4, 1.2])
P("""The last run on the Infinix (version 0.5.3) passed all checks, including detector parity on the phone (SMS 43/43 and
URL 40/40 golden vectors, maximum difference below 10⁻⁶), the sandbox isolation (no file or network access), a real bank site
judged safe, the warning shown about 31 ms after the scam SMS, and Llama explanations in English and Hindi.""")

# 6 ----------------------------------------------------------------------------------------------------------
H1("6. Hardware Utilisation")
H2("6.1 Use of the Arm CPU")
P("""All models run on the Arm CPU cores through ExecuTorch's XNNPACK backend. We did not use the GPU or NPU in Phase 1.
The detectors are so small that moving data to an accelerator would cost more than the computation, and for Llama the
XNNPACK path with KleidiAI kernels is the supported and tested route in ExecuTorch 1.5.1. Figure 2 shows where the time goes
from the moment an SMS arrives.""")
figure(FIG["latency"], 5.6, "Latency budget on the Infinix X6878. *The last bar is the bug described in Section 6.2, measured on the CMF Phone 1.")
H2("6.2 Thread Placement and Core Selection")
P("""To understand how Llama uses the CPU we ran ExecuTorch's own `llama_main` runner under `taskset`, changing only the
number of threads and the set of allowed cores. CPU masks were taken from each core's `cpuinfo_max_freq`, and the
configurations were interleaved over three runs to avoid thermal bias. Figure 3 shows the median results on the Infinix.""")
figure(FIG["threads"], 6.2, "Llama 3.2 1B prefill and decode speed for different thread and core settings (Infinix X6878, median of 3 runs)")
tr = threads.get("Infinix X6878", [])
md = lambda a, k: statistics.median(float(r[k]) for r in tr if r["arm"] == a) if any(r["arm"] == a for r in tr) else float("nan")
P(f"""Four threads on the four big cores gave the fastest prefill ({md('t4_big', 'prefill_tps'):.1f} tokens/s) and the most stable
one. Eight threads, which is what a naive setting would use, lowered decode speed to {md('naive8', 'decode_tps'):.1f} tokens/s
because the little cores slow down the synchronised workers. Running on the little cores only cut prefill to
{md('t4_little', 'prefill_tps'):.1f} tokens/s, 3.9 times slower than the big cores.""")
P("""This last result explained a bug in our first app build. Llama ran on a thread with `THREAD_PRIORITY_BACKGROUND`, and
`/proc/<pid>/task/*/cgroup` showed every XNNPACK worker in `cpuset:/background`, which Android restricts to the little cores.
The first token took 9.2 seconds on the CMF Phone 1. Llama now runs at normal priority and only while its alert is on
screen. We also found that ExecuTorch's default thread heuristic picks three threads on the Dimensity 7300, because it cannot
read `midr_el1` there and falls back; on the Snapdragon, three instead of four big-core threads costs 17% of prefill speed.""")
H2("6.3 Memory and Storage")
table("Memory and storage", ["Item", "Size"],
      [["SMS and URL detector (.pte, each)", f"{sms['pte_bytes'] / 1024:.0f} KB"], ["Llama 3.2 1B QAT model file", "1.12 GB (downloaded once, SHA-256 checked)"],
       ["App at rest, detectors loaded, no Llama (PSS)", f"{sm['app_pss_mb_detectors_only']} MB"],
       ["Llama runner peak RSS, first and 30th answer", f"{sm['peak_rss_mib_first_last'][0]:.0f} MiB and {sm['peak_rss_mib_first_last'][1]:.0f} MiB"],
       ["Release APK", "about 18 MB"]], [3.6, 2.6])
P("""Llama is loaded on demand when an alert is opened and released after 90 seconds without use, so the 1.3 to 1.5 GB it
needs is held only while the user is reading an explanation. Peak memory did not grow over 30 answers, so there is no leak in
the long-running process.""")
H2("6.4 Sustained Load and Temperature")
P(f"""To check thermal behaviour we generated {sm['answers']} alert explanations back to back with no pause ({sm['minutes']} minutes,
{sus['device']}, {sus['soc']}, Android {sus['android']}, {'on USB power' if sus['plugged'] else 'on battery'}). Each answer used a
{sus['answers'][0]['prompt_tokens']}-token prompt and up to {sus['max_new']} new tokens. Average decode speed over the first three answers
was {sm['decode_avg_first3_last3'][0]} tokens/s and over the last three {sm['decode_avg_first3_last3'][1]} tokens/s. The hottest CPU zone
rose from {sm['cpu_c_start_peak'][0]} °C to a peak of {sm['cpu_c_start_peak'][1]} °C and the battery from {sm['battery_c_start_peak'][0]} °C to
{sm['battery_c_start_peak'][1]} °C. The slowest single answer still decoded at {min(r['decode_tps'] for r in sus['answers'])} tokens/s, so
no throttling was seen in this run (Figure 4).""")
figure(FIG["sustained"], 6.2, f"Thirty Llama answers back to back on the {sus['device']}")

# 7 ----------------------------------------------------------------------------------------------------------
H1("7. Optimisation Techniques")
P("""Each optimisation below was made after measuring the slow part on the phone, and each was checked against the golden
vectors or the evaluation sets so that it did not change the results.""")
H2("7.1 Allocation-Free Featurizer and Reused Input Buffer")
P("""Profiling showed that 98% of the detection time was spent in feature extraction, not in the model. The first version
created a String for every n-gram and a new 131 KB dense vector for every call, which was then copied into a tensor. We
rewrote the featurizer to hash characters in place without allocation and gave each ExecuTorch model one direct
`FloatBuffer` that is reused through `Tensor.fromBlob`. The full SMS check went from 9.4 ms to 5.0 ms (median) and the URL check
from 2.7 ms to 1.1 ms on the Dimensity 7300 in a debug build, with the golden vectors unchanged.""")
H2("7.2 Release Build with R8")
P("""Built as it ships, with R8 code optimisation, resource shrinking and a baseline startup profile, the same checks take
0.64 ms (SMS) and 0.15 ms (URL) on the Dimensity 7300 and 0.50 ms and 0.11 ms on the Snapdragon 7s Gen 3. The APK went from
33 MB to about 18 MB.""")
H2("7.3 Quantisation of Llama")
P("""The QAT + LoRA model uses 4-bit group-wise weights and 8-bit dynamic activations, which brings the 1B model down to
1.12 GB and allows about 19 tokens/s decode on the CPU. Because the quantisation was learned during training rather than
applied afterwards, the model kept fluent Hindi, which was the deciding factor over our own post-training 8da4w export.""")
H2("7.4 Pre-Filling the System Prompt")
P("""The fixed 99-token system prompt is the same for every alert. When an alert fires, the app already feeds it into the KV
cache with `LlmModule.prefillPrompt`, so opening the alert costs only the message's own tokens. The first token in the app
went from 1.78 s to 1.00 s (44% lower). The pre-warmed answer matched the cold one for the first 14 tokens and then chose an
equally correct wording, which we believe is due to a different floating-point summation order when the prompt is prefilled
in two parts.""")
H2("7.5 Thread Priority, Thread Count and Core Pinning")
P("""Moving Llama off the background cgroup (Section 6.2) removed the 9.2 s first-token delay. We also built a patched
ExecuTorch library that pinned its four threads to the big cores. It was fastest under `llama_main`, but inside the app it
measured only 7.8 to 11 tokens/s, because Android resets a thread's CPU affinity whenever the app moves between cpusets
(foreground, top-app, background). We went back to the official library, which gives 15.5 tokens/s decode in the app, and
kept the patch for reference. This attempt did not work, but it showed that affinity cannot be relied on inside an Android app.""")
H2("7.6 Memory Management")
P("""The detectors stay loaded all the time because they are tiny. Llama is loaded only for an open alert and freed after 90
seconds idle, so the app at rest uses about 164 MB. A short foreground service keeps the phone maker's app freezer from
pausing Llama while an answer is being written.""")
H2("7.7 Network Path and User Interface")
P("""Routing only the resolver address through the VPN means that Shield never handles the user's actual traffic, which
saves battery and avoids slowing down other apps. In the user interface, tabs are built once and kept, so a tab switch never
builds a screen during an animation; janky frames on the CMF Phone 1 dropped from 4.0% to 1.15% and on the Infinix from 7.1% to
2.4%, measured with `dumpsys gfxinfo` over 30 rounds of tab switching.""")
table("Summary of optimisations and their measured effect", ["Technique", "Before", "After"],
      [["Allocation-free featurizer, reused input buffer", "SMS 9.4 ms, URL 2.7 ms", "SMS 5.0 ms, URL 1.1 ms"],
       ["R8 release build", "debug build", "SMS 0.64 ms, URL 0.15 ms"],
       ["System prompt pre-filled into the KV cache", "first token 1.78 s", "first token 1.00 s"],
       ["Llama off the background cgroup (CMF Phone 1)", "little cores, first token 9.2 s", "big cores"],
       ["4 big-core threads instead of 8", "15.5 tok/s decode", "19.2 tok/s decode"],
       ["DNS lookup policy v2", "21.5% false blocks", "0.03% false blocks"],
       ["R8, shrinking, startup profile", "APK 33 MB", "about 18 MB"],
       ["Screens built once", "4.0% janky frames", "1.15% janky frames"]], [2.9, 1.6, 1.7])

# 8 ----------------------------------------------------------------------------------------------------------
H1("8. Challenges Faced")
bullets([
    """**Llama refused to explain scams.** Asked directly, the 1B model often answered that it could not help with fraud. Writing the
    first words of the answer ourselves (verdict prefill) solved this without any fine-tuning.""",
    f"""**Too many false alarms on a real inbox.** The first model flagged {lap['v0_5_0']['SCAM']} genuine messages as scams. Adding
    the scam gate brought this down to {lap['v0_5_1']['SCAM']}, but it also cost recall on UK spam (Section 5.2).""",
    """**The DNS filter broke normal apps.** The first lookup policy blocked the phone maker's and the carrier's own servers. We
    had to design the allow-list layers and a much stricter blocking threshold.""",
    """**Slow Llama in the app but not in the benchmark.** This took several days to trace to Android's background cgroup and,
    later, to affinity being reset between cpusets.""",
    """**An evaluation mistake.** At one point the OpenPhish score dropped from 92% to 76%. The cause was not the model: a file
    pattern in the evaluation script had picked up a newer feed file. We fixed the pattern so the same snapshot is always used.""",
    """**Unstable USB connection.** The main phone disconnected from the laptop under heavy load, so long benchmarks and tests
    were run as scripts on the phone and the results were copied back afterwards.""",
])

# 9 ----------------------------------------------------------------------------------------------------------
H1("9. Conclusion and Future Work")
H2("9.1 Conclusion")
P(f"""ENTITY Shield shows that scam SMS, phishing link and DNS-level threat detection, together with natural-language
explanations from Llama 3.2 1B, can run entirely on a mid-range Arm phone. The detectors give a verdict in well under a
millisecond and the explanation starts in about one second. Keeping the language model out of the decision made the system
predictable: on held-out Indian templates the app called {hi['app_scam']['fp']} legitimate messages a scam while catching
{p1(hi['app_scam']['recall'])} of scams outright. Measuring on the phones, rather than assuming, found the biggest performance
problems in this project, such as the background cgroup and the featurizer allocations.""")
H2("9.2 Limitations")
bullets([
    "The Indian SMS test set is made from templates we wrote, and the real-inbox numbers come from one phone whose false alarms were used to write the scam gate.",
    "Network Guard only sees this phone's DNS traffic; strict Private DNS or an app's own DNS-over-HTTPS bypasses it.",
    "URL recall from the hostname alone is limited for hacked legitimate sites, and the sandbox does not run JavaScript.",
    "Llama 3.2 1B is wrong in roughly 10 to 15% of hard cases in our manual review.",
])
H2("9.3 Plan for Phase 2")
bullets([
    "A consented and anonymised SMS test set from 10 to 20 phones that are never used for tuning, and a small field trial.",
    "A full-tunnel flow meter with NetFlow features and a flow-based intrusion model trained on NF-UNSW-NB15-v2 and NF-CSE-CIC-IDS2018-v2, tested across datasets.",
    "Distillation and LoRA fine-tuning of the explainer on English and Hindi data, and Tamil support.",
    "Arm-specific work: a KleidiAI on/off comparison, a GPU (Vulkan) delegate for the detectors, and upstreaming our ExecuTorch fixes.",
    "Signed updates of the phishing feed and detector weights; checks for WhatsApp notifications, calls and UPI QR codes.",
])

# references ---------------------------------------------------------------------------------------------------
H1("References")
refs = [
    "T. A. Almeida, J. M. G. Hidalgo and A. Yamakami, \"Contributions to the study of SMS spam filtering: new collection and results,\" in *Proc. 11th ACM Symp. Document Engineering (DocEng)*, 2011, pp. 259–262.",
    "J. Ma, L. K. Saul, S. Savage and G. M. Voelker, \"Beyond blacklists: learning to detect malicious web sites from suspicious URLs,\" in *Proc. 15th ACM SIGKDD Int. Conf. Knowledge Discovery and Data Mining*, 2009, pp. 1245–1254.",
    "K. Weinberger, A. Dasgupta, J. Langford, A. Smola and J. Attenberg, \"Feature hashing for large scale multitask learning,\" in *Proc. 26th Int. Conf. Machine Learning (ICML)*, 2009, pp. 1113–1120.",
    "M. Antonakakis et al., \"From throw-away traffic to bots: detecting the rise of DGA-based malware,\" in *Proc. 21st USENIX Security Symposium*, 2012.",
    "Meta AI, \"Llama 3.2: Revolutionizing edge AI and vision with open, customizable models,\" Sep. 2024. [Online]. Available: https://ai.meta.com/blog/llama-3-2-connect-2024-vision-edge-mobile-devices/",
    "Meta AI, \"Introducing quantized Llama models with increased speed and a reduced memory footprint,\" Oct. 2024. [Online]. Available: https://ai.meta.com/blog/meta-llama-quantized-lightweight-models/",
    "PyTorch, \"ExecuTorch documentation,\" version 1.5. [Online]. Available: https://pytorch.org/executorch/",
    "Google, \"XNNPACK: high-efficiency floating-point and quantized neural network inference operators,\" [Online]. Available: https://github.com/google/XNNPACK",
    "PhishTank, \"Developer information and data,\" [Online]. Available: https://phishtank.org/",
    "OpenPhish, \"Phishing intelligence feed,\" [Online]. Available: https://openphish.com/",
    "V. Le Pochat, T. Van Goethem, S. Tajalizadehkhoob, M. Korczyński and W. Joosen, \"Tranco: a research-oriented top sites ranking hardened against manipulation,\" in *Proc. Network and Distributed System Security Symp. (NDSS)*, 2019.",
    "Cisco Umbrella, \"Umbrella popularity list,\" [Online]. Available: https://s3-us-west-1.amazonaws.com/umbrella-static/index.html",
    "D. Plohmann, K. Yakdan, M. Klatt, J. Bader and E. Gerhards-Padilla, \"A comprehensive measurement study of domain generating malware,\" in *Proc. 25th USENIX Security Symposium*, 2016, pp. 263–278.",
    "B. Jacob et al., \"Quantization and training of neural networks for efficient integer-arithmetic-only inference,\" in *Proc. IEEE Conf. Computer Vision and Pattern Recognition (CVPR)*, 2018, pp. 2704–2713.",
    "Indian Cyber Crime Coordination Centre, Ministry of Home Affairs, \"National Cyber Crime Reporting Portal,\" [Online]. Available: https://cybercrime.gov.in/",
    "Department of Telecommunications, Government of India, \"Sanchar Saathi: Chakshu, report suspected fraud communication,\" [Online]. Available: https://sancharsaathi.gov.in/",
    "Android Developers, \"VpnService,\" [Online]. Available: https://developer.android.com/reference/android/net/VpnService",
]
for i, r in enumerate(refs, 1):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    p.paragraph_format.left_indent = Inches(0.45)
    p.paragraph_format.first_line_indent = Inches(-0.45)
    p.paragraph_format.line_spacing = 1.15
    runs(p, f"[{i}]\t{r}")

# appendix -----------------------------------------------------------------------------------------------------
H1("Appendix A: Application Screens")
shots = sorted((ROOT / "docs" / "img" / "screens").glob("*.jpg"))[:6]
names = {"1_home": "(a) Home", "2_alert_en": "(b) Scam SMS explained", "3_alert_hi": "(c) Same alert in Hindi",
         "4_link_check": "(d) Link sandbox report", "5_network": "(e) Network Guard", "6_alerts": "(f) Alerts list"}
if shots:
    t = doc.add_table(rows=2, cols=3)
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, s in enumerate(shots):
        cell = t.rows[i // 3].cells[i % 3]
        par = cell.paragraphs[0]
        par.alignment = WD_ALIGN_PARAGRAPH.CENTER
        par.add_run().add_picture(str(s), width=Inches(1.75))
        c = cell.add_paragraph(names.get(s.stem, s.stem))
        c.alignment = WD_ALIGN_PARAGRAPH.CENTER
        c.runs[0].font.size = Pt(10)
    caption("Figure", "Screens of ENTITY Shield on the Infinix X6878")
H1("Appendix B: Source Code and Reproducibility")
P("""The complete source code, trained models, evaluation scripts and the raw result files behind every number in this
report are available at **https://github.com/kkjjkamal123/ENTITY-Shield** (branch `main`). The README lists the commands to
train and export the detectors, build the app, and run the end-to-end test on a phone. Each result file in
`benchmarks/results/` names the script that produced it.""")
P(f"""This report describes app version {VERSION}. Licences: ExecuTorch (BSD-3-Clause), XNNPACK, Arm KleidiAI. Built with Llama:
Llama 3.2 is licensed under the Llama 3.2 Community License, Copyright © Meta Platforms, Inc. All Rights Reserved. Data: UCI SMS
Spam Collection (CC BY 4.0), PhishTank, OpenPhish, Tranco and Cisco Umbrella.""")

out = HERE / "ENTITY-Shield-Report.docx"
doc.save(out)
print("DOCX:", out)

# --- Word: update fields (contents, lists, captions, page numbers) and export PDF ------------------------------
ps = f"""
$ErrorActionPreference = 'Stop'
$w = New-Object -ComObject Word.Application
$w.Visible = $false
$w.DisplayAlerts = 0
try {{
  $d = $w.Documents.Open('{out}')
  $d.Fields.Update() | Out-Null
  foreach ($t in $d.TablesOfContents) {{ $t.Update() | Out-Null }}
  foreach ($t in $d.TablesOfFigures) {{ $t.Update() | Out-Null }}
  $d.Repaginate()
  foreach ($t in $d.TablesOfContents) {{ $t.UpdatePageNumbers() | Out-Null }}
  foreach ($t in $d.TablesOfFigures) {{ $t.UpdatePageNumbers() | Out-Null }}
  $d.Save()
  $d.ExportAsFixedFormat('{HERE / "ENTITY-Shield-Report.pdf"}', 17, $false, 0, 0, 0, 0, 0, $true, $true, 1)
  $d.Close()
}} finally {{ $w.Quit() }}
"""
r = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True)
print("PDF:", HERE / "ENTITY-Shield-Report.pdf", "ok" if r.returncode == 0 else r.stderr[-800:])
