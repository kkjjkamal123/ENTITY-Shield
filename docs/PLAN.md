# ENTITY Shield — Project Plan

> Bharat AI-SoC Challenge 2026–27 · Problem Statement 3 · On-device threat, phishing and scam detection
>
> Working name: **ENTITY Shield**. Status: **Phase 1 sprint, 4 days left.** Last updated 2026-11-19.
>
> **Now:** Phase 1 is due **23 Nov 2026**. **Phone only** (the Pi sentinel is dropped; network detection moved onto the phone as Network Guard). Llama 3.2 1B runs **inside the app** through ExecuTorch `LlmModule`; detectors, Network Guard and the new dashboard are built and verified on two phones. Development moved to the Windows laptop. See [Progress log](#0-progress-log).

---

## Contents

0. [Progress log](#0-progress-log)
1. [The challenge](#1-the-challenge)
2. [Timeline](#2-timeline)
3. [Phase 1 plan: 10 days](#3-phase-1-plan-10-days)
4. [Design](#4-design)
5. [Evidence for the report](#5-evidence-for-the-report)
6. [Cut from Phase 1](#6-cut-from-phase-1)
7. [Risks](#7-risks)
8. [Compliance checklist](#8-compliance-checklist)
9. [Repository layout](#9-repository-layout)
10. [References](#10-references)

The October plan (4 devices, Pi hotspot, LoRA, ENTITY policy port, lessons from Arm Create and Voxel) is in git history: `git show 9555179:docs/PLAN.md`.

---

## 0. Progress log

Newest first.

### 2026-11-19 (early morning): end to end on both phones, video, report

**End-to-end (`scripts/e2e_phone.py`)** with the app shown above the lock screen (debug builds only), so
it runs in `cpuset:/top-app` on a locked phone:
- Infinix X6878: **15/15** in one run. Detector parity 43/43 + 40/40; 6 SMS verdicts; time to first
  warning median 21.6 ms; Llama EN (TTFT 1.87 s) and HI (2.46 s) in the app; Network Guard up;
  phishing domains blocked; DGA burst detected.
- CMF Phone 1: **13/13** (Llama skipped at 8% battery), including the **LAN tripwire catching a
  port scan from this laptop** over the shared Wi-Fi.

**Bugs found by the e2e loop:**
1. The VPN needs `VpnService.prepare()` before `establish()`, even with the ACTIVATE_VPN appop.
2. Alert de-duplication hid repeat detections across runs (fixed with `netreset` in tests).
3. A burst of real app lookups after Wi-Fi reconnected pushed the test domain out of the last-8 window
   (fixed with a host filter).

**In-app benchmark of record (Infinix, plugged):** time to first warning n=20 p50 12.1 ms, p95 29.3 ms.
Llama first token: cold 1.91 / 1.78 s, **pre-warmed 1.02 / 1.00 s**
(`benchmarks/results/bench_inapp_Infinix_X6878_2026-11-19.csv`).

**Pre-warm correctness:** the pre-warmed answer equals the cold one for 14 tokens, then takes a different
correct wording. The likely cause is chunked prefill (99 + 134 tokens) changing attention's
floating-point summation order, which flips a near-tie. Stripping the literal BOS from the prefilled
text made the answers diverge from the first sentence, so `prefillPrompt` adds no BOS.

**Explanation quality:**
- Manual review of the 40-message evaluation: EN 28 correct / 8 vague / 4 wrong (Indian messages
  8/2/0).
- HI with English signs: 18/16/6, including 2 evidence negations.
- **HI with Hindi signs (shipped): 17/20/3, Indian 7/3/0, no negations.**
- Labels are in `benchmarks/results/llm_explain_manual_review_*.json`, pending a native-speaker check.

**Deliverables now in the repo:**
- Signed release APK (key outside the repo).
- `docs/report/ENTITY-Shield-Report.pdf`, generated from the raw results.
- `demo/ENTITY-Shield-demo.mp4` (2:16, real phone recordings: airplane-mode SMS alert EN→HI, Network
  Guard vs the beaconsim test app, tripwire vs the laptop, in-app benchmark).
- README rewritten with screenshots.

**Still needs the user:**
- Inbox audit (labelling their own SMS).
- Optional voice-over.
- Make the repo public (or invite the judges).
- Publish the GitHub Release.
- Submit the form.
- Revoke the pasted GitHub token.

### 2026-11-19 (night): app runs Llama in-process, Network Guard built, Windows toolchain, two phones

**Decisions (from the user):** phone only (no Pi sentinel; C1 moves onto the phone), Phase 1 scope =
all three pillars (SMS, URL, network), premium dashboard inside the phone app, mixed licence
(Apache-2.0 engine/training/docs, proprietary UI). Dev host is now the Windows laptop.

**Phones:** CMF Phone 1 (Dimensity 7300, A78+A55, dotprod, **no SIM**) = benchmark of record and network
demos. Infinix X6878 (Snapdragon 7s Gen 3: 1×A720 2.7 GHz + 3×A720 2.4 + 4×A520, **i8mm**, two SIMs) =
real-SMS demo, inbox audit, second-device numbers.

**Windows toolchain gotchas (fixed, documented):**
- ExecuTorch 1.5.1's Windows wheel ships `executorch/data/bin/flatc.exe` but `_flatbuffer.py` only looks
  for a resource named `flatc` → XNNPACK export dies with WinError 2. Fix: `FLATC_EXECUTABLE` (now set in
  `train_detector.py`). Upstream-fix candidate.
- `write_text()` defaults to cp1252 on Windows → the Hindi golden vectors crashed. All training IO is UTF-8/LF.
- `sdkmanager.bat` splits `platforms;android-36` at `;` (cmd) → use `--package_file`.
- `local.properties` needs forward slashes; git `core.autocrlf=false` for this repo.

**Reproducibility:** the SMS detector retrained on Windows reproduces the 13 Nov Mac model exactly
(43/43 golden scores, max diff 0.0; `benchmarks/results/detector_sms_2026-11-19_windows-repro.json`).
URL detector retrained on the 19 Nov PhishTank feed (train ≤ 2026-06-04, test ≥ 2026-08-07):
test P 0.973 / R 0.679 / FPR 0.96% (with allow-list P 0.972 / R 0.661), OpenPhish 19 Nov R 0.933,
41 official Indian domains 0 false alarms.

**App (13 Nov skeleton had never compiled against the real AAR):** `LlmGenerationConfig.Builder()` is
internal in 1.5.1 → `LlmGenerationConfig.create()`. Now: Llama 3.2 1B QAT+LoRA explains inside the APK
("It creates urgency and threatens to block the account, which is a common tactic used by scammers…").
`<|eot_id|>` stripped. On-device parity through the production path: SMS 43/43, URL 40/40 (max diff 6e-7).

**Finding: Android's background cgroup silently moves ExecuTorch to the little cores.** The explainer
thread used `THREAD_PRIORITY_BACKGROUND`; `/proc/<pid>/task/*/cgroup` showed all its XNNPACK workers in
`cpuset:/background` (= Cortex-A55): prefill 25 tok/s, decode 7.6 tok/s, TTFT 9.2 s for 233 tokens.
Same happens to the whole process while the phone is locked. Fix: normal priority, and Llama runs only
while the alert is on screen (detection itself never waits for it).

**Finding: ExecuTorch's thread heuristic picks 3 threads on the CMF** ("Resetting threadpool to 3
threads": `midr_el1` is unreadable, so its core-type detection falls back). Measured on the Infinix
(`scripts/bench_threads.py`, 3 interleaved runs, plugged, 29–30 °C, QAT+LoRA, 165-token prompt):

| Arm | Prefill tok/s | Decode tok/s | TTFT |
|---|---:|---:|---:|
| 4 threads pinned to the A720s | **185.6 ± 0.6** | 19.2 ± 0.1 | **0.89 s** |
| ExecuTorch default (picked 4 here) | 171.0 ± 4.4 | 18.7 ± 0.4 | 0.96 s |
| 4 threads, unpinned | 170.1 ± 6.6 | 19.3 ± 0.4 | 0.97 s |
| 8 threads (naive) | 162.6 ± 8.1 | 15.5 ± 0.2 | 1.01 s |
| 3 threads pinned to big (the CMF's heuristic) | 153.8 ± 2.8 | 19.0 ± 0.1 | 1.07 s |
| 4 threads pinned to the A520s | 47.5 ± 0.1 | 9.6 ± 0.0 | 3.48 s |

ENTITY's llama.cpp result holds on a different runtime and an Armv9 i8mm SoC: the thread count
matters (8 threads cost 19% decode), pinning to the big cores gives the best and steadiest prefill
(±0.6 vs ±6.6 tok/s), and the little cores are 2–4× slower.

**Featurizer optimisation:** 98% of detection time was featurizing (a String + ByteArray per n-gram and a
fresh 131 KB dense vector copied into a tensor per call). Allocation-free hashing + one reused direct
`FloatBuffer` per model (ExecuTorch `Tensor.fromBlob(FloatBuffer)`): SMS check p50 9.4 → 5.0 ms, URL
2.7 → 1.1 ms (same locked-phone conditions; golden parity unchanged). JVM test `fastPathEqualsReference`.

**Built:** Network Guard (DNS-only VPN with per-app attribution, URL-model scoring, NXDOMAIN blocking,
beacon / DGA-burst / DNS-tunnel detectors, LAN tripwire for scans, password guessing and ADB-worm
probes), Link Guard, "Check with Shield" text action, real-inbox audit (counts-only export), in-app
benchmark (parity, latency, time to first warning, Llama cold vs pre-warmed, energy), read-aloud,
pre-warm of the system prompt via `prefillPrompt`, new dashboard UI, `beaconsim` test app (harmless
DNS-only imitation of C2 beacons, DGA bursts and DNS exfiltration), `scripts/e2e_phone.py`,
`scripts/lan_scan_demo.py`. Unit tests 10/10 (DNS codec, featurizer parity, fast path).

**Blocked on the user:** both phones locked → the app cannot come to the foreground (Android blocks
starting the VPN from the background: `BackgroundServiceStartNotAllowedException`). CMF battery at 1%.

### 2026-11-13 (afternoon): both detectors trained, exported and running on the phone ✅ (day 2 work, done a day early)

**Device change:** the phone connected today is an **Infinix X6878** (Snapdragon 7s Gen 3 / SM7635, 1× A720 @ 2.7 GHz + 3× A720 @ 2.4 GHz + 4× A520 @ 1.8 GHz, **i8mm + bf16**, 7.5 GB RAM, Android 16), not the CMF Phone 1. Building and measuring on it. The October Llama numbers are from the CMF and get re-measured anyway.

**Toolchain:** `.venv`, NDK, the Llama weights and the October `.pte` files had been deleted. `setup_mac.sh` restored the venv and NDK. **Llama weights still need a re-download** (gated: `hf auth login` with a new token).

**Detectors:** `training/featurize.py` (hashed char 2–5-grams, 32,768 slots + named 0/1 signals; ASCII-only lowercasing and FNV-1a so Kotlin can match it exactly) → logistic regression → one `Linear + sigmoid` `.pte` (XNNPACK, 133 KB each). `training/train_detector.py sms|url`. Settings picked on the validation split only (SMS: L2 1e-4, 6 epochs; URL: L2 1e-6, 15 epochs).

**SMS data:** UCI (random 70/15/15) + `training/sms/indian_sms.py`, 101 hand-written templates (47 scam families incl. KYC, electricity, courier, digital arrest, job/task, UPI collect, loans, investment, account takeover; 54 legitimate incl. bank OTP/debit alerts, deliveries, bills, PF, ITR, promotions from DLT senders, personal Hinglish/Hindi). **Split by template** (60/20/20): test wordings were never seen in training.

**URL data:** hostname only (Tranco has no paths, so path features would leak the label; the DNS guard only sees hostnames). PhishTank **time split**: train ≤ 2026-06-14, test ≥ 2026-08-06. OpenPhish snapshot as an extra recall-only set.

**Test results (threshold set at ≤ 1% false alarms on validation):**

| Set | n | Precision | Recall | F1 | False-alarm rate | AUC |
|---|---:|---:|---:|---:|---:|---:|
| SMS, UCI test | 837 | 0.974 | 0.942 | 0.958 | 0.42% | 0.997 |
| SMS, Indian test (unseen templates) | 840 | 0.978 | 0.903 | 0.939 | 1.82% | 0.982 |
| URL, PhishTank newer than training + Tranco | 11,249 | 0.964 | 0.695 | 0.808 | 1.29% | 0.920 |
| URL, OpenPhish snapshot (recall only) | 277 | 1.0 | 0.863 | — | — | — |
| URL, 41 official Indian brand domains | 41 | — | — | — | **0%** | — |

URL rows include the allow-list (below). Model alone: recall 0.704, but 4 of 41 official domains flagged.

**What went wrong and the fixes:**
- First SMS run: 6.3% false alarms on unseen Indian templates. Causes: links to official domains (`bescom.co.in`) counted as `has_link`, and bare IP links were missed. Fixed in the featurizer, plus 27 more templates.
- First URL run underfit (train recall 0.80) because it used the SMS regularisation. Each detector now has its own setting.
- The URL model flagged new official domains (`axis.bank.in`, `sbi.bank.in`, `pay.google.com`) → **allow-list first, model second**: official brand domains + Tranco top 10k, **minus 271 shared platforms** found in the *training* phish (myportfolio.com, hsforms.com, gitbook.io, lovable.app, duckdns.org, translate.goog, amazonaws.com, …) and the free-hosting list. `training/url/allowlist.txt`, 9,935 domains.

**Learned SMS signal weights** (make sense): registered sender ID −2.6, "do not share" −1.5, asks for OTP/PIN −1.1 (real OTP messages are common); has link +2.0, prize/lottery +1.9, phone number to call +1.6, brand/domain mismatch +1.5, sender is a mobile number +1.3.

**On the phone** (`training/phone_detectors.py`, ExecuTorch `executor_runner` built for arm64 Android): all 83 golden inputs give the same score as on the Mac (max diff 6.5e-7). Model latency over 1,000 runs: **SMS p50 0.022 ms / p95 0.031 ms; URL p50 0.021 ms / p95 0.024 ms.** Raw: `benchmarks/results/detector_*_2026-11-13.*`.

**Limits to state in the report:** the Indian SMS set is templated, not real messages (the real-inbox false-alarm test is still to do); the URL model sees only the hostname, so phishing on shared platforms is caught only by the free-hosting signal; latency is model only (featurizing gets timed in the app).

### 2026-11-13: plan replaced, 10 days left

**Found:** the challenge page now lists Phase 1 as due **23 Nov 2026**, Phase 2 **5 Feb 2027**, finals **15–16 Feb 2027**. Registration closed 19 Oct; the team is registered. No judging rubric and no submission form link on the page.

**Decision:** the October plan needs ~7 weeks of work and there are 10 days, solo. Replaced by the plan in §3: phone first (SMS + URL detectors, Llama explanations, real actions), then a Pi 5 sentinel for network intrusion, then a phone DNS guard (timeboxed, cut first). Everything else moves to Phase 2 (§6).

**Kept from October:** QAT+LoRA Llama 3.2 1B, verdict prefill, fact sheet, fixed remediation text, detection never waits for Llama, load on demand.

**Changed:** the "app has no internet permission" claim is dropped (LAN alerts and `VpnService` need it). Offline is proven with airplane mode instead.

**Datasets downloaded** (`scripts/download_datasets.sh`, into the gitignored `datasets/`):

| Dataset | Rows | Note |
|---|---:|---|
| UCI SMS Spam | 5,574 (4,827 ham / 747 spam) | English only; Indian scam set still to write |
| Tranco top 1M | 1,000,000 | Benign domains |
| OpenPhish feed | 300 | Snapshot 2026-11-13 |
| PhishTank online-valid | 45,928 | Has `submission_time`, so a time-split test set is possible |
| CICIDS2017 | — | Needs the UNB request form; fetch by hand before Wed 18 |

**Still open today:** submission form link and Phase 1 format (from the registration email); email support about the rubric and video/report length.

_October entries below are kept as history. Section numbers in them (§6.7, §8.0, …) refer to the October plan in git history._

### 2026-10-08 (night): coexistence requirement added

**Requirement (from the user):** everything must keep working, and the phone must stay fully usable, while the model runs. Documented as **§6.7** (what runs when + 5 rules), with targets in **§8.0** and a **"normal use" stress test** in **§8.2**.

**Also decided (discussion, 2026-10-08):** running locally is mandatory under PS 3 (no cloud LLM), so the work is to make local good. Levers, in order of impact: (1) Llama only on flagged items, detection never waits for it; (2) **distillation**: a bigger Llama writes high-quality EN/HI explanations at *training* time, and the 1B is LoRA-fine-tuned on them (check the Llama license terms for training on Llama outputs first); (3) pre-written, checked explanations per scam family, with Llama adding only the message-specific part; (4) system-prompt KV-cache reuse; (5) GPU (Vulkan) / MediaTek NPU backends as optional experiments.

### 2026-10-08 (late): two Llama 1B variants compared, Hindi tested

**New tooling:** `scripts/phone_llm.sh <model.pte> <prompt file> [max_new] [threads]` runs one prompt on the phone and prints the answer plus prefill / TTFT / decode. `training/llm/export_llama.sh` now takes `CONFIG`, `SRC`, `OUT_NAME` (and uses Hydra's `++` override syntax; `+` fails when the config already sets a key, e.g. `export.max_seq_length` in the QAT config).

**Models compared** (both Llama 3.2 1B Instruct, XNNPACK, context 2048, prefill chunk 512):

- **8da4w (ours):** Meta's bf16 checkpoint, post-training quantized: 8-bit dynamic activations + 4-bit weights (group 128), 4-bit embeddings. 803 MB.
- **QAT+LoRA (Meta's):** `meta-llama/Llama-3.2-1B-Instruct-QLORA_INT4_EO8`, quantization-aware trained + LoRA rank 16: 4-bit weights (group 32), **8-bit embeddings and 8-bit output layer**. Export config `llama_xnnpack_qat.yaml`. 1.12 GB.

**Prompts:** v1 (no prefill), v4 English and v4 Hindi (fact sheet in the system prompt + verdict prefill; `training/llm/prompts/2026-10-08_v4_*`). Greedy, 4 threads, max 100 new tokens.

| | 8da4w (ours) | QAT+LoRA (Meta) |
|---|---|---|
| v1, no verdict prefill | ❌ refuses | ✅ explains + "Steps to take now" (no refusal) |
| v4 English | ✅ correct (KYC = Know Your Customer, thanks to the fact sheet) but verbose: 3 points + extra, 99 tokens | ✅ **one correct sentence, stops by itself** (19 tokens): "It asks for personal info (KYC update) and links to a non-official site." |
| v4 **Hindi** | ❌ awkward, vague, **repeats itself** | ✅ **fluent and correct, stops by itself** (48 tokens): "यह आपके खाते को ब्लॉक करने की धमकी देता है, जो आपके खाते की सुरक्षा को खतरे में डाल सकता है।" |
| Prefill | 105–130 tok/s | 81–90 tok/s |
| Decode | 18–20 tok/s | 11–14 tok/s |
| TTFT, 165-token prompt | 1.27 s | 2.04 s |
| RSS after load | 934 MB | 1,297 MB |

**Why QAT+LoRA is slower:** 8-bit embeddings and an 8-bit 128k-vocabulary output layer mean more bytes per token, and decode on this phone is bandwidth-bound (ENTITY's finding). Group size 32 instead of 128 also adds scale overhead.

**Caveats:** single runs; phone charging (29–38%); QAT runs came after the 8da4w runs (warmer phone); one earlier run of the 8da4w model dropped to 8.3 tok/s decode, so variance is high under these conditions. **Not a benchmark of record.**

**Decision (provisional, pending the controlled benchmark):** **QAT+LoRA for explanations.** A refusal or broken Hindi in a demo costs more than 0.7 s. Win the latency back with (1) system-prompt KV-cache reuse (about half of the prompt is fixed), (2) short answers (it stops at 19–48 tokens on its own), (3) the thread/pinning ablation.

**Answer design (decided):** ⚠️ **verdict** (written by the app from the detector) → **why** (Llama, about this specific message: lookalike domain, the trick used) → **what to do** (**fixed, pre-checked text** per scam type in English and Hindi: never generated, so it can't be wrong). Llama also handles follow-up questions ("I already clicked, what now?"), languages and network-alert explanations, so it does real work rather than decoration.

**Not allowed by PS 3 (asked 2026-10-08):** a non-Llama model (Gemma, Qwen, Phi) for the advisory part; Apple Intelligence (macOS/iOS are not listed hardware, and the model isn't Llama). Apple's on-device model can still be a development tool on the Mac (e.g. drafting English test messages); check Apple's terms before using its output as training data.

### 2026-10-08 (evening): first Llama 3.2 1B run on the phone ✅

**Model:** Llama 3.2 1B Instruct (Meta `original/` checkpoint) → `training/llm/export_llama.sh` → `models/llama3_2_1b_instruct_8da4w_ctx2048.pte`: XNNPACK, 8da4w (group 128), 4-bit embeddings (group 32), KV cache + custom SDPA, context 2048, prefill chunk 512. **803 MB** (ENTITY's Q4_0 GGUF: 773 MB). Export: 130 s on the M5, 6.8 GB peak RAM.

**Preliminary numbers** (4 threads, phone **plugged in at ~29%**, screen on, single runs: not yet a benchmark of record):

| | ExecuTorch 1.5.1 (today) | ENTITY llama.cpp Q4_0 (2026-07-18) |
|---|---:|---:|
| Prefill | 121–123 tok/s | ~128 tok/s |
| Decode | 17.7–18.6 tok/s | 18.1 tok/s |
| Time to first token, 112–141-token prompt | **0.91–1.14 s** | — |
| RSS after load | 934 MB | — |
| Model load | 1.8–2.1 s | — |

So ExecuTorch on this phone is level with ENTITY's tuned llama.cpp, and the sub-1-second explanation target is already met for a ~110-token prompt, before any KV-cache reuse.

**Explanation behaviour** (prompts in `training/llm/prompts/`, greedy decoding):

| Prompt | Result |
|---|---|
| v1: system prompt asks to explain why a flagged message is dangerous | ❌ **Refused:** "I cannot provide instructions or advice regarding the scamming of customers." |
| v2: framed from the victim's side ("I got this SMS. Is it safe?") | ❌ **Refused:** "I cannot advise you on how to respond to a fake SMS message." |
| v3: **verdict prefill**: the app writes the start of the answer ("⚠️ No, this SMS is a scam.") and Llama continues | ✅ Explains the warning signs correctly in structure, **but** expands KYC as "Key Identification Card" (wrong: Know Your Customer) and runs past 160 tokens without reaching the "what to do" steps |

**Decision: verdict prefill is the explanation design.** The detector's verdict is written by the app into the first sentence of the answer, and Llama only continues it. This avoids the safety refusal and also guarantees Llama can't contradict the verdict (faithfulness, §6.4).

**Next for quality:** fixed short output shape (≤ 2 lines why + 2 steps, ~60–80 tokens); remediation steps first; a small fact sheet in the prompt (KYC, UPI, 1930) to stop wrong expansions; then LoRA fine-tuning (§6.4) if prompt work isn't enough, especially for Hindi.

**Benchmark caveat:** the runner logged `Failed to open midr file` and fell back to its own core count (4 "efficient" cores detected). Check which cores the 4 threads actually land on during the thread ablation.

### 2026-10-08: phone first, toolchain and ExecuTorch runner

**Decision:** start with the **phone as the demo**: the CMF Phone 1 is the core product ("movable" mode, §6.6), and the Pi gateway comes later.

**Done**

- [x] Mac toolchain installed and scripted in `scripts/setup_mac.sh`: `adb`, Android command-line tools, **NDK r28c** (`28.2.13676358`), JDK 17, CMake, Ninja, uv, Python 3.11 venv (`.venv`) with **ExecuTorch 1.5.1** + **PyTorch 2.14.0** (MPS available)
- [x] ExecuTorch **v1.5.1** source in `third_party/executorch` (gitignored)
- [x] `llama_main` cross-compiled for arm64 Android with XNNPACK + optimized/quantized/LLM kernels: `scripts/build_android_runner.sh` (first try, 8.4 MB binary)
- [x] Runner pushed to `/data/local/tmp/shield/` on the phone and confirmed working. It has a `-cpu_threads` flag, so ENTITY's thread ablation can be repeated on ExecuTorch
- [x] Phone connected over USB and authorized for `adb`

**Phone probe (CMF Phone 1)**

| | Value | Implication |
|---|---|---|
| OS / SoC | Android 16 (SDK 36), MT6878 = Dimensity 7300, arm64-v8a | As planned |
| Cores | cpu0–3: 2.0 GHz (Cortex-A55) · cpu4–7: 2.5 GHz (Cortex-A78) | Same 4+4 layout as ENTITY's record |
| CPU features | `asimddp` (dotprod), **no `i8mm`** | KleidiAI's dotprod path, not i8mm |
| RAM | 5.5 GB total, **~1.3 GB available** at probe time | Tight for Llama 1B (~1–1.5 GB); close apps before benchmarks; keep 1B, not 3B |
| Storage | **8.1 GB free (93% full)** | Enough for one model; free space before adding more |
| Battery | 22% at probe | Charge fully before unplugged benchmarks |

**Gotchas hit (recorded so they don't cost time again)**

- `yes | sdkmanager --licenses` under `set -o pipefail` exits 141 (SIGPIPE) and aborts the script → `|| true`
- The `executorch` 1.5.1 wheel **does not depend on `torch`**: install the version from its `torch_pin.py` (**2.14.0**) explicitly
- `huggingface_hub` 2.x no longer has a `[cli]` extra; the `hf` CLI comes with the base package
- `sdkmanager` now prints a deprecation warning (replacement: `android sdk`). It still works

**Blocked**

- [x] ~~Llama 3.2 1B Instruct access~~: approved the same day; weights downloaded

**Next**

1. Download `original/` (consolidated.00.pth, params.json, tokenizer.model) → `models/llama-3.2-1b-instruct/`
2. Export with `executorch.extension.llm.export.export_llm`: XNNPACK, `8da4w`, KV cache + SDPA, shared 4-bit embeddings
3. Push the `.pte` + tokenizer, run on the phone; record TTFT and decode tok/s (unplugged, screen state stated, several runs; ENTITY + Voxel protocol)
4. Thread ablation on ExecuTorch: 8 threads vs 4 vs 4 pinned to the A78s; compare with ENTITY's llama.cpp record (decode 18.1 tok/s, prompt ~128 tok/s)

**Housekeeping**

- [ ] Revoke the Hugging Face token pasted into chat on 2026-10-08 (it's now in the session log and the Obsidian sync); use `! .venv/bin/hf auth login` for a fresh one
- [ ] Revoke the GitHub token found in the Obsidian vault (session note from 2026-09-29)
- [ ] Register the team by **19 Oct**

---

## 1. The challenge

**Source:** <https://arm-education.github.io/Arm-Developer-Labs/Bharat_AI_SoC_2026_27.html> (checked 2026-11-13)

Objective (PS3): an on-device AI system that detects network intrusions, malware behaviour and phishing or scam communications, entirely on Arm edge hardware, no cloud.

| # | PS3 requirement | Phase 1 answer |
|---|---|---|
| C1 | Monitor and classify network traffic for anomalies and intrusions | **Pi 5 sentinel** (flow classifier + scan/beacon rules), then **phone DNS guard** |
| C2 | Detect phishing URLs | URL model on the phone (links in SMS, share-to-app, DNS guard domains) |
| C3 | Detect scam SMS | SMS model on the phone, runs as each SMS arrives |
| C4 | Explain in plain language | Llama 3.2 1B on ExecuTorch, English and Hindi |
| C5 | Recommend remediation | Fixed, pre-checked steps per scam type + one-tap actions that really act |
| C6 | No cloud | Whole demo runs in airplane mode / WAN unplugged |
| Req | "A model from the Llama family must be used for the advisory or reasoning component and deployed on-device using ExecuTorch" | Llama 3.2 1B QAT+LoRA `.pte`, XNNPACK, in the APK |
| Out | Source code, application, demonstration video, technical report | GitHub repo, signed APK release, video, PDF report |

Phase 1 asks for "report, source code and demo", hosted on GitHub, through the challenge submission form. Optional tech named: CICIDS / UNSW-NB15 / NSL-KDD, PhishTank / OpenPhish, UCI SMS Spam, libpcap / Scapy, Hailo SDK. Hardware: Arm Android phones, Raspberry Pi 5 (CPU or AI HAT+), Arm Linux.

**Not stated on the page:** judging rubric and weights, prize amount, submission form link, video or report length. Ask `support@armbharatchallenge.com`; technical questions to `tech-queries@armbharatchallenge.com` with "PS3" in the subject.

## 2. Timeline

| Milestone | Date |
|---|---|
| Launch | 2026-09-19 |
| Registration closed | 2026-10-19 (registered) |
| **Phase 1 submission** | **2026-11-23** (we submit 2026-11-22) |
| Phase 1 results | mid-December 2026 |
| Phase 2 submission | 2027-02-05 |
| Finals (live virtual demo + jury Q&A) | 2027-02-15 – 16 |

## 3. Phase 1 plan: 10 days

| Day | Work | Done when | Status |
|---|---|---|---|
| Fri 13 | Rewrite this plan, fix dates. Find the submission form and Phase 1 rules in the registration email. Download datasets. | Plan committed; form link known or support emailed | 🟡 |
| Sat 14 | SMS + URL detectors: data, train, evaluate, export `.pte`, golden vectors | F1 and false-positive tables; both `.pte` run on the phone | ✅ done Fri 13 (real-inbox test left) |
| Sun 15 | App skeleton: SMS receiver, featurizer, detectors, alert card | Real SMS → alert on screen, airplane mode | ⏳ |
| Mon 16 | Llama in the app: prefill, streaming, EN/HI, fixed steps, actions | Alert card shows the explanation; sender block works | ⏳ |
| Tue 17 | Share-to-app links, follow-up questions, history, demo screen | **Phone-only build covers C2–C6 end to end** | ⏳ |
| Wed 18 | Pi sentinel + alert to the phone | `nmap` from the Mac → explained alert on the phone | ⏳ |
| Thu 19 | DNS guard (cut at end of day) | Bad domain blocked on the phone, or cut and noted | ⏳ |
| Fri 20 | **Feature freeze.** Benchmarks of record, bug fixes | Numbers in `benchmarks/results/` | ⏳ |
| Sat 21 | Demo video, technical report, README, signed APK release | All four deliverables exist | ⏳ |
| Sun 22 | Final check against §8, submit | Submitted | ⏳ |

**If a day slips:** DNS guard goes first, then the Llama second opinion, then follow-up questions. The phone-only build from Tuesday is never put at risk for the network parts.

## 4. Design

**One sentence for the judges:** *"Airplane mode on: a scam SMS is flagged in milliseconds, explained in Hindi in about a second, and the sender is blocked. Nothing leaves the phone."*

**Headline metric:** time to first warning (SMS arrives → alert on screen).

### 4.1 Detectors (phone)

- One architecture for SMS and URL: hashed character n-grams plus ~15 named signals, into a small linear/MLP model, exported to `.pte`, run with ExecuTorch.
- Named signals are plain rules: urgency words, asks for OTP/KYC/PIN, payment request, link present, bank/brand name with a non-official domain, sender is a 10-digit mobile number rather than a DLT header (`VM-HDFCBK`), IP-in-host, punycode, shortener, suspicious TLD.
- The signals that fired are the **evidence** given to Llama, so an explanation can only cite reasons the detector used.
- Three bands: safe (silent), uncertain (soft warning), scam (alert).
- Data: UCI SMS Spam; a hand-written and templated Indian scam set (KYC, UPI collect, electricity, courier, digital arrest, job/task, loan app, lottery; EN, Hinglish, HI); PhishTank/OpenPhish + Tranco for URLs.
- **Real-inbox false-positive test:** own SMS inbox pulled over `adb`; only the aggregate rate is published, messages stay local and gitignored.

### 4.2 Llama (phone): advisory and reasoning

| Job | How |
|---|---|
| Why this message is a scam | Verdict prefill + evidence → 1–2 sentences, EN or HI |
| What to do | Fixed text per scam type, never generated |
| Follow-up ("I already clicked", "I shared the OTP") | Llama answers with the matching playbook entry in the prompt (1930, cybercrime.gov.in, call the bank) |
| Network alerts from the Pi | Same prompt shape, structured verdict in |
| Second opinion on the uncertain band (stretch) | One constrained token (SCAM/SAFE); kept only if it measurably helps |

Rules: detection never waits for Llama; the model loads on demand and is freed after; Llama cannot change a verdict.

### 4.3 Android app (`android-app/`, Kotlin, one module)

- `RECEIVE_SMS` receiver → detectors → notification + alert card (verdict now, explanation streams in under it).
- Share-to-app for links; history list; EN/HI switch.
- Actions: block sender, dial 1930, open the cybercrime report page, copy a report text.
- ExecuTorch Android AAR for detectors and Llama (`LlmModule`).
- Demo screen that injects the test SMS, so the video and jury don't need a second phone.

### 4.4 Pi 5 sentinel (`pi-gateway/`, Python)

- Scapy sniffs the Pi's own interface and aggregates flows.
- Small classifier trained on CICIDS2017, **restricted to features the Pi computes itself** (duration, packet/byte counts per direction, mean packet size, rate, SYN/RST/FIN counts, destination port).
- Two statistical rules for what the dataset misses: port-scan fan-out and fixed-interval beaconing (the "malware behaviour" in the objective).
- One JSON POST per alert to the phone over the LAN; the phone's Llama explains it.
- Demo attacks from the Mac against the Pi only: `nmap`, SSH brute force, a beacon script.

### 4.5 Phone DNS guard (timeboxed to Thu 19)

- Local `VpnService` routing only DNS; each domain scored by the URL model; bad domains get a blocked answer.
- Cut at the end of its day if not stable. The Pi sentinel already covers C1.

## 5. Evidence for the report

| Number | Method | Status |
|---|---|---|
| Time to first warning | SMS receive → alert drawn, n ≥ 10 | ⏳ |
| Detector latency p50/p95 | On the phone, n ≥ 1000 | ✅ model only: 0.022 / 0.031 ms; with featurizing ⏳ |
| SMS precision / recall / F1 / FPR | UCI held-out, Indian scam held-out, own inbox (FPR only) | ✅ held-out sets; own inbox ⏳ |
| URL precision / recall / AUC | Time-split phishing feed + Tranco | ✅ |
| Network per-class F1 | CICIDS2017 held-out, plus own lab attacks reported separately | ⏳ |
| Llama TTFT, decode tok/s, RSS | Unplugged, screen on, n ≥ 5 (replaces the October single runs) | ⏳ |
| Energy per explanation | Battery current × voltage over the run | ⏳ |
| Offline proof | Airplane mode / WAN unplugged, in the video | ⏳ |

## 6. Cut from Phase 1

Moved to Phase 2: Pi Wi-Fi hotspot and the 4-device lineup, Jetson, LoRA fine-tuning and distillation, ENTITY policy-layer port (affinity, ADPF, thermal), thread ablation, KV-cache reuse, Llama 3B on the Pi, safe-preview WebView, link detonation container, WhatsApp/Telegram scanning, more languages, `LEDGER.md` / `JOURNEY.md`, Tailscale.

## 7. Risks

| Risk | Handling |
|---|---|
| ExecuTorch 1.5.1 Android AAR may not ship the LLM module | Check Sun 15 morning; fallback is building the AAR from `third_party/executorch` |
| CICIDS features differ from what Scapy computes on the Pi | Train only on self-computed features; report lab attacks separately |
| Little real Indian scam SMS data | Hand-written + templated set, method documented; real-inbox FPR is the trust number |
| `INTERNET` permission needed for LAN alerts and `VpnService` | Offline proven by airplane mode, not by the permission list |
| Android Private DNS bypasses the DNS guard | Stated as a known limit |
| ~1.3 GB free RAM, 8 GB free storage on the phone | One Llama model on the phone; fall back to the 803 MB 8da4w export if QAT+LoRA is killed |
| No rubric, no stated video/report length | Ask support on day 1; default 3-minute video, 6–8 page report |

## 8. Compliance checklist

- [x] Team registered (before 2026-10-19)
- [ ] Submission form link and Phase 1 format confirmed
- [ ] Llama-family model used, on **ExecuTorch**, on-device
- [ ] Runs on Arm hardware: CMF Phone 1 and Raspberry Pi 5
- [ ] C1 network intrusion / anomaly detection demonstrated
- [ ] C2 phishing URL detection demonstrated
- [ ] C3 scam SMS detection demonstrated
- [ ] C4 plain-language explanations demonstrated
- [ ] C5 remediation demonstrated
- [ ] C6 works offline (airplane mode, WAN unplugged)
- [ ] Code on **GitHub**, public
- [ ] Application published (signed APK + Pi setup)
- [ ] Demo video
- [ ] Technical report
- [ ] "Built with Llama" attribution
- [ ] ENTITY reuse disclosed
- [ ] Dataset sources and licenses documented
- [ ] Hugging Face and GitHub tokens exposed in October revoked

## 9. Repository layout

| Path | Purpose |
|---|---|
| `docs/PLAN.md` | This plan and the progress log |
| `docs/REPORT.md` | Technical report (→ PDF) |
| `training/sms`, `training/url` | Data prep, shared featurizer, train, evaluate, export, golden vectors |
| `training/network` | CICIDS feature subset, train, evaluate |
| `training/llm` | Llama export script and dated prompts (reused) |
| `android-app/` | Kotlin app |
| `pi-gateway/` | `sentinel.py` + setup notes |
| `scripts/` | Mac setup, Android runner build, push/run on phone, offline tests (reused) |
| `benchmarks/results/` | Raw CSVs |
| `models/`, `datasets/`, `third_party/` | Not committed |

## 10. References

- Challenge page: <https://arm-education.github.io/Arm-Developer-Labs/Bharat_AI_SoC_2026_27.html>
- ExecuTorch on Arm course: <https://learn.arm.com/courses/executorch-on-arm/>
- ExecuTorch: <https://github.com/pytorch/executorch>
- ENTITY: <https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge>
- UCI SMS Spam Collection: <https://archive.ics.uci.edu/dataset/228/sms+spam+collection>
- CICIDS2017: <https://www.unb.ca/cic/datasets/ids-2017.html>
- PhishTank: <https://phishtank.org> · OpenPhish: <https://openphish.com> · Tranco: <https://tranco-list.eu>
- Scapy: <https://scapy.net>
