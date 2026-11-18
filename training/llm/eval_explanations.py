"""Explanation quality of Llama 3.2 1B (ExecuTorch, on the phone) on held-out scam messages.

  python training/llm/eval_explanations.py --serial <adb serial> [--n 40] [--langs EN,HI]
                                           [--model llama3_2_1b_instruct_qlora_ctx2048.pte] [--mask f0]

For each message from the *test* split (Indian templates the detector never saw, plus UCI spam):
detector score + evidence exactly as the app computes them -> the app's exact v4 prompt (fact sheet +
verdict prefill, ported from Advice.kt) -> ExecuTorch's llama_main on the phone -> automatic checks:

  refusal       the model declines ("I cannot ...")
  invented_link the answer names a URL/domain that is not in the message
  invented_num  the answer names a phone number that is not in the message (1930 is allowed)
  contradicts   the answer says the message is safe/legitimate (it must not overturn the verdict)
  wrong_fact    known wrong expansions (e.g. KYC = "Key Identification ...")
  script        Hindi answers must be mostly Devanagari
  stops         the model ended by itself before max_new_tokens

Writes benchmarks/results/llm_explain_eval_<model>_<device>_<date>.json (every prompt and answer).
"""
import argparse
import datetime
import json
import os
import random
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "training"))
sys.path.insert(0, str(ROOT / "training" / "sms"))
import featurize as F  # noqa: E402

ADB = os.environ.get("ADB") or str(Path.home() / "Android/Sdk/platform-tools/adb.exe")
DIR = "/data/local/tmp/shield"

# ---- ported from android-app/.../Advice.kt (keep in sync) ----
FACTS = ("Facts: KYC means Know Your Customer. Banks never ask for KYC, OTP, PIN or passwords through SMS links. "
         "A UPI PIN is only needed to send money, never to receive it. Police and courts never arrest anyone over a phone or video call. "
         "The cyber crime helpline in India is 1930.")


def phrase(sig, host):
    return {
        "has_link": f"contains a link to {host}" if host else "contains a link",
        "urgency": "creates urgency", "asks_secret": "asks for OTP, PIN or password", "money_request": "asks for a payment",
        "account_threat": "threatens to block or cut a service", "prize_lottery": "promises a prize or reward",
        "job_task": "offers easy money for tasks", "authority_threat": "claims to be police, court or a regulator",
        "kyc_identity": "asks about KYC, PAN or Aadhaar",
        "brand_link_mismatch": f"link domain {host} is not the official site of the brand it names" if host else "uses a fake brand link",
        "shortener_link": "hides the real link behind a short link",
        "sender_mobile": "comes from a personal mobile number, not a registered business sender",
        "call_number": "asks you to call a phone number",
    }.get(sig)


def explain_prompt(text, score, band, signs, lang):
    hindi = " Answer in simple Hindi (Devanagari)." if lang == "HI" else ""
    label = "FRAUD" if band == "SCAM" else "SUSPICIOUS"
    if lang == "HI":
        pre = "⚠️ नहीं, यह SMS एक धोखा है। कारण:" if band == "SCAM" else "⚠️ यह SMS संदिग्ध लगता है। कारण:"
    else:
        pre = "⚠️ No, this SMS is a scam. Why:" if band == "SCAM" else "⚠️ This SMS looks suspicious. Why:"
    p = ("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n"
         f"You protect people in India from fraud. Explain warnings in very simple words. {FACTS} "
         f"Write at most 2 short sentences about this specific case.{hindi}<|eot_id|>"
         f"<|start_header_id|>user<|end_header_id|>\n\nI got this SMS. Is it safe?\n\"{text[:600]}\"\n"
         f"Safety check: {label} ({int(score * 100)}%). Signs: {', '.join(signs) or 'pattern matches known scams'}."
         f"<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n{pre}")
    return p, pre

# ---- detectors, as the app runs them ----


class Det:
    def __init__(self):
        if os.name == "nt" and "FLATC_EXECUTABLE" not in os.environ:
            pass
        from executorch.runtime import Runtime
        import numpy as np
        self.np = np
        rt = Runtime.get()
        self.sms = rt.load_program(str(ROOT / "models/detectors/sms.pte")).load_method("forward")
        self.url = rt.load_program(str(ROOT / "models/detectors/url.pte")).load_method("forward")
        self.allow = set((ROOT / "training/url/allowlist.txt").read_text(encoding="utf-8").split())
        self.t_sms = json.loads((ROOT / "training/sms/meta.json").read_text(encoding="utf-8"))["thresholds"]
        self.t_url = json.loads((ROOT / "training/url/meta.json").read_text(encoding="utf-8"))["thresholds"]

    def _dense(self, h, s):
        x = self.np.zeros(F.DIM + len(s), dtype=self.np.float32)
        for k, v in h.items():
            x[k] = v
        x[F.DIM:] = s
        return x

    def _run(self, m, x):
        import torch
        return float(m.execute([torch.from_numpy(x)[None]])[0].reshape(-1)[0])

    def check(self, text, sender):
        h, s = F.vector("sms", text, sender)
        p_sms = self._run(self.sms, self._dense(h, s))
        official = {d for ds in F.BRANDS.values() for d in ds}
        hosts = [F.url_host(l if "://" in l else "http://" + l) for l in F.URL_RE.findall(text)]
        hosts = [x for x in hosts if not any(x == d or x.endswith("." + d) for d in official)]
        worst, p_url = None, 0.0
        for x in hosts:
            p = 0.0 if F.allowlisted(x, self.allow) else self._run(self.url, self._dense(*F.vector("url", x)))
            if p >= p_url:
                worst, p_url = x, p
        band = lambda p, t: "SCAM" if p >= t["scam"] else "UNCERTAIN" if p >= t["uncertain"] else "SAFE"
        order = {"SAFE": 0, "UNCERTAIN": 1, "SCAM": 2}
        b = max(band(p_sms, self.t_sms), band(p_url, self.t_url), key=order.get)
        ev = [n for n, v in zip(F.SMS_SIGNALS, s) if v]
        signs = list(dict.fromkeys(filter(None, (phrase(e, worst) for e in ev))))
        return max(p_sms, p_url), b, signs

# ---- checks ----


REFUSAL = re.compile(r"\b(i can(no|')t|i cannot|i am unable|i'm unable|i won't|i'm not able|as an ai)\b|मैं .{0,30}नहीं (कर|दे) सकत", re.I)
SAFE_CLAIM = re.compile(r"\b(is (safe|legit|legitimate|genuine|real)|not a scam|no need to worry)\b|सुरक्षित है|धोखा नहीं|असली है", re.I)
WRONG = re.compile(r"key identification|know your card|kyc means key", re.I)
DOMAIN = re.compile(r"\b(?:https?://)?(?:[a-z0-9-]+\.)+(?:com|in|net|org|xyz|top|info|co|me|ly|io|app|dev|site|online|live|shop|click|cc|tk|sbi|gov|bank)\b", re.I)
NUM = re.compile(r"\+?\d[\d -]{6,}\d")


def checks(answer, msg, lang, stopped):
    a = answer
    doms = {d.lower().split("//")[-1] for d in DOMAIN.findall(a)}
    msg_l = msg.lower()
    invented_dom = sorted(d for d in doms if d not in msg_l and d not in ("cybercrime.gov.in", "onlinesbi.sbi", "sbi.co.in"))
    nums = {re.sub(r"[ -]", "", n) for n in NUM.findall(a)}
    invented_num = sorted(n for n in nums if n not in re.sub(r"[ -]", "", msg) and n != "1930")
    letters = [c for c in a if c.isalpha()]
    deva = sum(1 for c in letters if "ऀ" <= c <= "ॿ")
    r = dict(
        refusal=bool(REFUSAL.search(a)), invented_link=invented_dom, invented_num=invented_num,
        contradicts=bool(SAFE_CLAIM.search(a)), wrong_fact=bool(WRONG.search(a)),
        script_ok=(deva / max(1, len(letters)) >= 0.5) if lang == "HI" else True, stops=stopped,
        empty=len(a.strip()) < 8,
    )
    r["pass"] = not (r["refusal"] or r["invented_link"] or r["invented_num"] or r["contradicts"] or r["wrong_fact"] or r["empty"]) and r["script_ok"]
    return r


def run_phone(serial, model, prompt_path, max_new, mask):
    name = "eval_prompt.txt"
    subprocess.run([ADB, "-s", serial, "push", str(prompt_path), f"{DIR}/{name}"], capture_output=True, check=True)
    pre = f"taskset {mask} " if mask else ""
    cmd = (f"cd {DIR} && {pre}./llama_main --model_path {model} --tokenizer_path tokenizer.model --prompt_file {name} "
           f"--temperature 0 --max_new_tokens {max_new} --cpu_threads 4 2>eval.err")
    out = subprocess.run([ADB, "-s", serial, "shell", cmd], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    m = None
    for m in re.finditer(r"^PyTorchObserver (\{.*\})\s*$", out, re.M):
        pass
    body = out[: m.start()] if m else out
    head = "assistant<|end_header_id|>"
    body = body[body.rfind(head) + len(head):] if head in body else body
    stats = json.loads(m.group(1)) if m else {}
    return body.strip(), stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--model", default="llama3_2_1b_instruct_qlora_ctx2048.pte")
    ap.add_argument("--n", type=int, default=40)
    ap.add_argument("--langs", default="EN,HI")
    ap.add_argument("--max-new", type=int, default=80)
    ap.add_argument("--mask", default="", help="taskset mask, e.g. f0 for cpu4-7")
    a = ap.parse_args()

    import train_detector as T  # same split as training (seed 7)
    data, _ = T.sms_data()
    test = [x for x in data["test"] if x["label"] == 1]
    r = random.Random(11)
    indian = [x for x in test if x["source"] == "indian"]
    uci = [x for x in test if x["source"] == "uci"]
    # one message per Indian template first (variety), then UCI spam
    by_t = {}
    for x in indian:
        by_t.setdefault(x["template"], x)
    pick = list(by_t.values())
    r.shuffle(pick)
    pick = pick[: int(a.n * 0.75)] + r.sample(uci, min(len(uci), a.n - min(len(pick), int(a.n * 0.75))))

    det = Det()
    device = subprocess.run([ADB, "-s", a.serial, "shell", "getprop ro.product.model"], capture_output=True, text=True).stdout.strip()
    rows = []
    with tempfile.TemporaryDirectory() as tmp:
        for i, x in enumerate(pick):
            score, band, signs = det.check(x["text"], x.get("sender", ""))
            if band == "SAFE":  # the app would not ask Llama; record and skip
                rows.append(dict(i=i, text=x["text"], source=x["source"], template=x.get("template"), band=band, score=score, skipped=True))
                continue
            for lang in a.langs.split(","):
                prompt, pre = explain_prompt(x["text"], score, band, signs, lang)
                p = Path(tmp) / "p.txt"
                p.write_text(prompt, encoding="utf-8", newline="\n")
                body, st = run_phone(a.serial, a.model, p, a.max_new, a.mask)
                answer = body.split(pre, 1)[-1].strip() if pre in body else body
                stopped = st.get("generated_tokens", a.max_new) < a.max_new
                c = checks(answer, x["text"], lang, stopped)
                rows.append(dict(i=i, lang=lang, text=x["text"], source=x["source"], template=x.get("template"), band=band,
                                 score=round(score, 4), signs=signs, answer=answer, tokens=st.get("generated_tokens"),
                                 prefill_tps=st.get("prefill_token_per_sec"), decode_tps=st.get("decode_token_per_sec"), **c))
                print(f"[{i:02d} {lang}] {'PASS' if c['pass'] else 'FAIL'} {band} {score:.2f} :: {answer[:110]!r}")
    done = [x for x in rows if not x.get("skipped")]
    summary = {}
    for lang in a.langs.split(","):
        L = [x for x in done if x["lang"] == lang]
        if not L:
            continue
        k = lambda f: sum(1 for x in L if f(x))
        summary[lang] = dict(n=len(L), pass_rate=round(k(lambda x: x["pass"]) / len(L), 3),
                             refusal=k(lambda x: x["refusal"]), invented_link=k(lambda x: x["invented_link"]),
                             invented_num=k(lambda x: x["invented_num"]), contradicts=k(lambda x: x["contradicts"]),
                             wrong_fact=k(lambda x: x["wrong_fact"]), script_ok=k(lambda x: x["script_ok"]),
                             stops_by_itself=k(lambda x: x["stops"]),
                             median_tokens=sorted(x["tokens"] or 0 for x in L)[len(L) // 2])
    summary["skipped_as_safe_by_detector"] = sum(1 for x in rows if x.get("skipped"))
    out = ROOT / "benchmarks/results" / f"llm_explain_eval_{Path(a.model).stem}_{device.replace(' ', '_')}_{datetime.date.today()}.json"
    out.write_text(json.dumps(dict(model=a.model, device=device, date=str(datetime.datetime.now()), summary=summary, rows=rows),
                              ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    print("Saved", out)


if __name__ == "__main__":
    main()
