# Results

Every number here is measured on real phones or held-out data. Raw files are in [`benchmarks/results/`](../benchmarks/results).
The [technical report](report/ENTITY-Shield-Report.pdf) has the same results with the method in full.

## Detectors: accuracy (held-out data, threshold set on validation for ≤ 1% false alarms)

SMS rows are the 0.5.1 model (`benchmarks/results/detector_sms_2026-11-21.json`) and the app's full
verdict on the same held-out split (`scripts/eval_sms_heldout.py`,
`benchmarks/results/sms_heldout_app_2026-11-23.json`; 0.5.2: `sms_heldout_app_2026-11-21.json`). The 0.5.1 model alone is weaker on unseen
templates than 0.5.0's (recall 0.90 -> 0.67): it was retrained to stop treating marketing as fraud, and
the scam gate now makes the final call. UCI is UK spam from 2011; the gate looks for fraud cues seen in
Indian scams, so it lets more of that through. The gate's rules were tuned on a challenge set and one
real inbox (below); only the 19 "fresh" challenge messages were written after tuning.

| Detector · test set | n | Precision | Recall | False-alarm rate | AUC |
|---|---:|---:|---:|---:|---:|
| SMS model alone · UCI SMS Spam, fraud-only positives (held out) | 788 | 1.000 | 0.783 | 0.00% | 0.990 |
| SMS model alone · **Indian scam/legit templates never seen in training** | 1,200 | 0.848 | 0.668 | 6.00% | 0.952 |
| SMS **app verdict 0.5.3** (model + URL model + scam gate v5) · same Indian templates, "scam" | 1,200 | **1.000** | **0.915** | **0.00%** | — |
| SMS **app verdict 0.5.3** · same Indian templates, "scam" or "be careful" | 1,200 | **0.966** | **0.933** | **1.63%** | — |
| SMS **app verdict 0.5.3** · UCI, "scam" or "be careful" | 788 | 0.939 | 0.767 | 0.41% | — |
| SMS app verdict 0.5.2 (gate v4) · Indian, "scam" / "scam" or "be careful" | 1,200 | 0.973 / 0.905 | 0.715 / 0.933 | 1.00% / 4.88% | — |
| SMS app verdict 0.5.2 (gate v4) · UCI, "scam" or "be careful" | 788 | 0.905 | 0.633 | 0.55% | — |
| URL v3 · PhishTank **newer than all training data** + real DNS hostnames and Tranco domains (with allow-list) | 19,873 | 0.938 | 0.526 | 0.72% | 0.898 |
| URL v3 · OpenPhish feed of 19 Nov 2026 (recall only) | 297 | — | 0.923 | — | — |
| URL v3 · 20,000 held-out real DNS hostnames (Umbrella ranks 600k–1M), false alarms only | 20,000 | — | — | 1.06% (0.1% at Network Guard's threshold) | — |
| URL · 41 official Indian bank/UPI/government domains | 41 | — | — | **0%** | — |

- **Scam gate v5 (0.5.3) was built on the validation split only** (`scripts/analyze_sms_misses.py`):
  Hindi "{number} पर कॉल करें" call-backs and service-cut threats, "send the code you received",
  premium-rate prizes, "Prize Code" no longer read as an OTP, police/court words only with something
  to do, payment receipts, government links, deep links of the brand that sent the message, and
  messages with nothing to click, call or reply to. On validation Indian scams went from 80 missed to
  0; the test split was run once after the rules were final. Held-out scams it still misses (34 of
  400): spoofed bank-header frauds (dltf1/2), tax refund, courier, loan and investment templates.
  Overall accuracy on the 1,200 held-out Indian messages: 96.7%.
- **Splits:** the SMS test uses *templates*, not just messages, that were never trained on. The URL
  test is a time split: train ≤ 2026-06-04, test ≥ 2026-08-07.
- **Reproducibility:** retraining the SMS model on Windows reproduced the 13 Nov Mac model
  exactly (43/43 golden scores, max difference 0.0).
- **Real-inbox audit (counts only, no message text):** [`inbox_audit_Infinix_X6878_2026-11-21.json`](../benchmarks/results/inbox_audit_Infinix_X6878_2026-11-21.json). The scam gate was tuned on that inbox, so it is not a held-out number. 0.5.3 on the phone (23 Nov): 7 of the newest 1,000 flagged, all scams by the owner's saved answers; 0 missed in a 10-message sample of the rest (personalised: the app applies those answers itself).
- **URL v3 vs v1:** v1's benign data held only bare domains (flipkart.com). On the new test, whose
  benign side includes real hostnames (api.x.io, connekt.flipkart.net), v1 had precision 0.38 and 23%
  false alarms. v3 adds real DNS names from the Cisco Umbrella list, split by domain so nothing leaks,
  and a sharper `brand_lure` signal. Recall on this harder test is lower than v1's 0.661 on the easier
  old one. Raw: [`detector_url_2026-11-19.json`](../benchmarks/results/detector_url_2026-11-19.json).

## Network Guard: false alarms (v1 → v2)

v1 blocked a lookup whenever the URL model said "scam". Apps look up thousands of hostnames a day, so
it blocked ordinary traffic. On the Infinix it stopped the phone maker's own Messages servers,
Airtel's backend and an Indian SMS-link service. v2 (`net/DnsPolicy.kt`) decides in this order:
1. Local names, OS/CDN infrastructure and sites the user allowed: let through.
2. Hosts on the bundled PhishTank/OpenPhish list: blocked.
3. Popular domains (Tranco top 1M) and domains that belong to the app asking: let through.
4. The URL model at a strict threshold: blocked.
5. Anything else the model doubts: flagged in the list, never blocked or notified.

| Blocked by mistake / phishing caught | v1 | **v2** |
|---|---:|---:|
| Long-tail legitimate domains with real subdomain prefixes (20,000) | 21.5% | **0.03%** |
| Held-out real DNS hostnames, Umbrella ranks 600k–1M (20,000) | 10.5% | **0.12%** |
| PhishTank time-split test (3,393), by the model alone | 65% | 33% (the feed list adds the known ones) |
| OpenPhish held-out (297), by the model alone | 93% | 87% |
| The 4 hosts v1 wrongly blocked on the Infinix | blocked | **allowed** |

The leftover v2 mistakes are mostly app backends on free hosts (Firebase, Pages). Those are blocked
only when a browser asks, and only flagged inside other apps. Raw:
[`dns_policy_eval_2026-11-19.json`](../benchmarks/results/dns_policy_eval_2026-11-19.json),
`scripts/eval_dns_policy.py`.

## Link sandbox: follow the link vs read the address

On the CMF Phone 1, the sandbox visited live links from the OpenPhish feed of 19 Nov 2026 and real
Indian sites. Two samples: the **tuning** sample set the signal weights, and the **held-out** sample
(different links, never looked at while tuning) checks them.

| Live links that answered | Address only: scam / unsure / safe | **Sandbox**: scam / unsure / safe |
|---|---|---|
| Phishing, tuning (34) | 24 / 3 / 7 | **26 / 3 / 5** |
| Phishing, held-out (32) | 28 / 0 / 4 | 27 / 1 / 4 |
| Big Indian brands: banks, government, payments, shopping (39) | 0 / 0 / 39 | 0 / 1 / 38 |
| Random long-tail `.in` sites, tuning (32) | 0 / 2 / 30 | 0 / 3 / 29 |
| Random long-tail `.in` sites, held-out (32) | 1 / 3 / 28 | 1 / 5 / 26 |

- **Honest reading:** on the held-out sample, following the link did not catch more phishing than the
  address model already did, and it made two more ordinary sites "unsure". Its value is in what the
  address cannot show: where a link really lands, what the page asks for, and where the form sends it.
- **Catches what the address hides:** `auth.re/…` scores 8% on its address, but its page copies a
  brand and asks for a password. A Netlify page titled "Free Fire" sends whatever is typed straight to
  a Telegram bot.
- **Found on the held-out sample, fixed in 0.4.0:** one phishing kit sent the sandbox on to the real
  `roblox.com`, and the trusted landing site cleared the link. A risky address that redirects to a
  trusted site now stays flagged ("cloaking"). Because the held-out sample found it, that case no
  longer counts as held out; the table shows the held-out run before the fix.
- **Nothing to read is not "safe":** a page that is gone (404), an address that no longer exists or a
  server that never answers is reported as "Couldn't check this link", and a link opened from a
  flagged message keeps that message's verdict.
- **What did not work:** the first weights flagged 11 ordinary sites. A lone password box, right-click
  blocking, a fresh Let's Encrypt certificate and broken certificate chains are all common on honest
  Indian sites. They were demoted to weak signals.
- **Isolation, checked on both phones in every end-to-end run:** the parser runs under an isolated
  uid (`isolated=true`), and reading Shield's files and opening a socket both fail.
- Raw: [`sandbox_eval_tuning_A015_2026-11-19.json`](../benchmarks/results/sandbox_eval_tuning_A015_2026-11-19.json),
  [`sandbox_eval_heldout_A015_2026-11-19.json`](../benchmarks/results/sandbox_eval_heldout_A015_2026-11-19.json),
  `scripts/eval_sandbox.py`.

## App smoothness (Android's own frame statistics)

`scripts/bench_ui.py` taps through the tabs 30 times, including the far jumps (Home ↔ More), and
reads `dumpsys gfxinfo`. "Before" is the 0.3 pager-based debug build; "after" is the 0.4 code
optimized the way it ships (R8, startup profile).

| Phone | Janky frames | p95 frame | p99 frame |
|---|---|---|---|
| CMF Phone 1 (Dimensity 7300, 120 Hz) | 4.0% → **1.15%** | 150 → **48 ms** | 1100 → **65 ms** |
| Infinix X6878 (Snapdragon 7s Gen 3, 120 Hz) | 7.1% → **2.4%** | 53 → **27 ms** | 117 → **30 ms** |

Where it came from:
- **Tab host:** each tab is built once and kept, so a switch never builds a screen mid-animation.
  This replaced a pager that built every page in between.
- **No offscreen buffers** for the page fades.
- **Nothing per frame:** no file checks or icon decoding on the main thread, and the nav animates
  without recomposing.
- **Glass costs nothing measurable:** a run with the glass forced off had the same frame times.
- **Every device:** the glass falls back to blur-only on Android 12, and to a plain frosted tint on
  Android 10–11, low-RAM phones and Battery Saver.

## Detectors: on the phone

| | CMF Phone 1 (Dimensity 7300) | Infinix X6878 (Snapdragon 7s Gen 3) |
|---|---|---|
| Same score as training, through the production path | SMS 43/43 · URL 40/40 (max diff 5×10⁻⁷) | SMS 43/43 · URL 40/40 (max diff 5×10⁻⁷) |
| Full SMS check (featurize + both models), n = 1000 | p50 **0.64 ms** · p95 0.80 ms | p50 **0.50 ms** · p95 0.65 ms |
| URL check, n = 1000 | p50 **0.15 ms** · p95 0.17 ms | p50 **0.11 ms** · p95 0.15 ms |
| Model size | 130 KB each (`.pte`) | |
| SMS in → notification posted (time to first warning), 6 messages | median 189 ms, max 380 ms | median **38 ms**, max 58 ms |

- Measured in the 0.4 end-to-end runs with the code built the way it ships (R8). The 0.3 debug build
  took 5.0 ms (SMS) and 1.1 ms (URL) on the CMF.
- On the CMF, the later warnings ran while Llama was loading for the explanation the user is likely to
  open next (the pre-warm, 1.1 GB from storage). We have not yet measured the two apart. In the 0.3
  run, without Llama, the CMF warned in 22 ms.

## Llama 3.2 1B on ExecuTorch: where the threads go

`scripts/bench_threads.py`, ExecuTorch `llama_main`. Infinix X6878: Snapdragon 7s Gen 3, 1× A720 @
2.7 GHz + 3× A720 @ 2.4 GHz + 4× A520, with i8mm. QAT+LoRA 4-bit model, 165-token prompt, 3
interleaved runs per arm, plugged in, 29–30 °C.

| Arm | Prefill tok/s | Decode tok/s | Time to first token |
|---|---:|---:|---:|
| **4 threads pinned to the A720s** | **185.6 ± 0.6** | **19.2** | **0.89 s** |
| ExecuTorch default (picked 4 threads here) | 171.0 ± 4.4 | 18.7 | 0.96 s |
| 8 threads (a common app default) | 162.6 ± 8.1 | 15.5 | 1.01 s |
| 3 threads (what ExecuTorch picks on the Dimensity 7300) | 153.8 ± 2.8 | 19.0 | 1.07 s |
| 4 threads pinned to the A520 little cores | 47.5 ± 0.1 | 9.6 | 3.48 s |

**Findings:**

1. **Android silently moves ExecuTorch to the little cores.** Our first build ran Llama on a
   `THREAD_PRIORITY_BACKGROUND` thread. `/proc/<pid>/task/*/cgroup` showed every XNNPACK worker in
   `cpuset:/background`. Inside the app on the CMF that meant prefill at 25 tok/s and a 9.2 s first
   token. Llama now runs at normal priority, and only while the alert is on screen.
2. **ExecuTorch's core-count heuristic picks 3 threads on the Dimensity 7300**, because `midr_el1`
   is unreadable and it falls back. On the bigger SoC above, 3 instead of 4 threads costs 17% of
   prefill. This is an upstream-fix candidate, like ENTITY's merged llama.cpp PR #25701.
3. **ENTITY's llama.cpp finding holds on ExecuTorch and on Armv9.** Thread count matters: 8 threads
   cost 19% decode. Pinning to the big cores gives the fastest and steadiest prefill (±0.6 vs ±6.6).

## Llama in the app: cold vs pre-warmed

When an alert fires, Shield pre-fills the fixed system prompt (99 tokens) into ExecuTorch's KV cache
(`LlmModule.prefillPrompt`), so opening the alert only pays for the message's own 134 tokens. Infinix,
app in the foreground, same alert, greedy:

| | Prompt tokens processed | First token |
|---|---:|---:|
| Cold | 233 | 1.78 s |
| **Pre-warmed** | 134 | **1.00 s (−44%)** |

The pre-warmed answer matches the cold one for its first 14 tokens, then takes a different but
equally correct wording ("…trick victims into revealing sensitive information" vs "…get victims to
take action"). This is consistent with a near-tie flipping under a different floating-point summation
order when the prompt is prefilled in two chunks; we have not confirmed it directly. Removing the
literal BOS token from the prefilled text made the answers diverge from the first sentence, so
`prefillPrompt` adds no BOS of its own.

## Llama explanations: quality

`training/llm/eval_explanations.py`. 40 held-out scam messages (one per never-seen Indian scam
template, i.e. all 10 in the test split, plus 30 UCI spam) → the app's exact prompt → ExecuTorch on
the phone → automatic checks plus a manual review.

| | Automatic checks pass* | Manual review: correct / vague / **wrong** | Indian messages only |
|---|---:|---|---|
| English | 40/40 | 28 / 8 / **4** | 8 / 2 / **0** |
| Hindi, warning signs given in English (first version) | 39/40 | 18 / 16 / **6** | 4 / 4 / **2** |
| **Hindi, warning signs given in Hindi (shipped)** | **40/40** | 17 / 20 / **3** | **7** / 3 / **0** |

\* No refusals, no invented links or phone numbers, never calls a flagged message safe, stops by itself
(median 26 tokens in English, 47 in Hindi).

**What the review found:** the 4 English errors invent a "PIN/password" detail on UK premium-rate spam.
In Hindi, the first version *negated* the evidence twice ("did **not** come from a personal number"):
the 1B had to translate English signs while answering. Giving it the signs already in Hindi (A/B on the
same 40 messages) halved the wrong answers (6 → 3), removed the negations, and on the Indian messages
took correct answers from 4 to 7 with none wrong. That version ships. The steps the user acts on are
never generated.


## Llama under sustained load

`scripts/bench_sustained.py --on-device`: 30 alert explanations back to back with no pause (4.8 min),
Infinix X6878, ExecuTorch `llama_main`, 162-token alert prompt
([`benchmarks/sustained_prompt.txt`](../benchmarks/sustained_prompt.txt)).

| | First 3 answers | Last 3 answers |
|---|---:|---:|
| Decode | 18.61 tok/s | 19.09 tok/s |
| Time to first token | 1.14 s | 1.04 s |

Hottest CPU zone 43.3 °C → peak 61.4 °C; battery 36.0 → 38.0 °C.
Slowest answer 16.3 tok/s: no thermal throttling. Runner memory 1552 MiB, flat.
The app at rest with the detectors loaded (no Llama): **163.8 MB** PSS.
Raw: [`sustained_Infinix_X6878_2026-11-21.json`](../benchmarks/results/sustained_Infinix_X6878_2026-11-21.json).
