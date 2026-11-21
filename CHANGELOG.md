# Changelog

Versions cover the whole repository. From 0.3.0 the Android app's `versionName` matches.

## 0.5.2 (2026-11-21)

### Always-on protection
- SMS checks never needed the app open; what stopped them was the phone freezing or killing Shield.
  New "Always-on protection" card in More: battery optimisation off (one system dialog), the
  maker's auto-start screen (Infinix/Tecno/itel, Xiaomi, Oppo/Realme, Vivo, Huawei, Samsung), and
  Android's "Always-on VPN" for Network Guard, each showing whether it is done.
- Network Guard comes back by itself after a reboot or an app update if it was on (`BootReceiver`).
  Turning it off, or another VPN taking over, is respected.

### Fixes from an audit of 0.5.1
- Allowed sites: "Not a scam" on an SMS with a link, or "Allow this site", could add a whole hosting
  platform (`web.app`, `blogspot.com`) or a link shortener (`bit.ly`) and so trust every site on it.
  Now a shortener is never allowed, on shared platforms only that exact site is, and entries 0.5.1
  added that way are removed on update. The bundled phishing list is checked before Allowed sites.
- The risk number stays inside the verdict's band: a safe message no longer shows "64% risk".
- Saved contacts: "be careful" (never "scam") now also when the message asks for a code or makes a
  threat with a number to call, not only for phishing links (a contact's account can be taken over).
  Safe messages from contacts no longer list a phishing warning sign. Saving or editing a contact
  takes effect at once (the lookup cache is cleared on change).
- Your labels: a message of one or two words from a phone number ("hi", "ok") is remembered for that
  exact text and number only, not for every phone sender. Saving a label no longer blocks the screen,
  and demo messages can't be labelled.
- Business sender IDs without the "XX-" network prefix still count for the model but no longer get
  the business shortcut in the gate (they can be spoofed through international gateways).
- Llama download: if Hugging Face drops partway, it resumes there instead of starting the 1.1 GB again
  from GitHub. The answer's keep-alive notification can no longer crash the app when an answer ends
  instantly.
- Inbox audit export says when verdicts used your saved answers (`personalised`, `saved_patterns`,
  labels answered now vs. filled in from patterns), so it is not read as the model alone.
- Alert history deletes message text on its own: safe messages after 7 days, alerts after 30.
- Privacy screen says plainly what a saved label keeps: the message's words, with numbers, codes and
  amounts removed and links cut to the domain.
- Accuracy write-up corrected (README, 0.5.1 notes below): the app's verdict on held-out data
  (`scripts/eval_sms_heldout.py`) is now reported next to the model alone.

## 0.5.1 (2026-11-21)

### Scam detection: fraud, not marketing
- Measured on a real 4,620-message inbox (Infinix X6878, evaluated locally, not kept): 0.5.0 called
  537 messages a scam and 1,573 suspicious (Jio data and missed-call alerts, bank login alerts, TRAI
  and DoT advisories, promotions). 0.5.1: 6 scam, 46 suspicious.
- New scam gate (`core/ScamGate.kt`, same rules as `sms_gate` in `training/featurize.py`, checked case
  by case by `ScamGateTest` on 1,590 cases): SCAM needs a fraud-specific cue: asking you to hand over a
  code or PIN, a threat tied to a link or a number to call, a brand look-alike link, a task job, or a
  link the URL model calls phishing. Registered business senders without one are safe ("Promotion
  (spam), not a scam" when it reads like marketing); a login code being delivered is safe.
- Business senders are recognised with or without the "XX-" prefix (`JIOPAY`, `JT-JioPay-S`); before,
  phones that drop the prefix saw the same Jio alert scored as a scam. "won't" no longer reads as "won".
- SMS model retrained: UCI "spam" that is only marketing (322 of 747) is no longer a scam example;
  +40 Indian templates (promotions with links, missed-call and data alerts, bank security alerts,
  advisories, chats).
- Challenge set written for this (`training/sms/challenge.py`, `scripts/eval_sms_challenge.py`,
  `benchmarks/results/sms_challenge_2026-11-21.json`): promotions and service messages called scam
  12 of 48 -> 0 of 48; scams caught or flagged 20 of 20 -> 20 of 20 (14 scam, 6 suspicious).
  Correction (0.5.2): the gate was tuned on these 48 promotions and 16 scams, so they are not held
  out. Only the 19 "fresh" messages were written after tuning (15 of 15 normal messages not called
  scam, 4 of 4 scams called scam).
- Saved contacts are never called a scam (only a phishing link still shows "be careful"). Needs the
  contacts permission; only a lookup, nothing is copied.
- "Not a scam" / "It's a scam" on an SMS alert saves the message's pattern (numbers, codes and links
  reduced) and applies to every similar message from the same kind of sender (`core/SmsTrust.kt`).

### Llama
- Back to the official ExecuTorch library: in the app the patched build measured 7.8-11 tok/s decode
  (Android resets thread affinity when the app changes cpuset). Kept in `backup/executorch/`.
- An answer keeps Shield in the foreground (short-service notification). The Infinix's app freezer had
  frozen Shield 4 s into an answer for 12.6 minutes when the user switched apps.
- The model downloads from Hugging Face (Meta/PyTorch's own ExecuTorch export, checked on the phone),
  with the project's GitHub release as fallback. The tokenizer ships in the APK, so importing only the
  .pte works.

### Other
- Credits: created by V S Kamalesh. About shows the real version.

## 0.5.0 (2026-11-20)

### Llama speed
- Shield now ships its own build of ExecuTorch 1.5.1 (`android-app/app/libs`, built by
  `.github/workflows/executorch-android.yml`; same Java API and the same 274 operators as the official
  library). The app sets the LLM thread pool to 4 threads pinned to the phone's 4 fastest cores, the
  fastest arm of the thread ablation (Infinix X6878: 185 tok/s prefill vs 168 for ExecuTorch's default).
  Upstream picks "performance cores - 1", which is 3 on the CMF Phone 1. Settings: `llm_threads`,
  `llm_pin_big`. Not yet re-measured inside the app.
- ADPF performance hints over the Llama thread and its XNNPACK workers (`llm_adpf`). Not yet measured.

### Network Guard stability
- No more crashes under heavy DNS traffic: bounded query queue, lookups shown in 300 ms batches
  instead of one main-thread update each, pruned behaviour history, 96 px icon cache, a read loop
  that backs off on errors, and a bounded tripwire pool instead of one thread per connection.
- Network alerts: at most one notification per 20 s, no background Llama load; alert history capped
  at 500.

## 0.4.2 (2026-11-20)

### Llama install
- Download and Import now save the model in Shield's private storage instead of the shared
  `Android/data` folder. There, a folder made by `adb` before the app's first start could be unreadable
  by the app, and phone cleaners may delete large files, so Llama asked to be installed again. Models
  pushed with `adb` to the old folder are still found; once a private copy exists, the duplicate is
  removed.
- A background file check that started before an import finished could overwrite the result and
  report Llama as missing; fixed.
- The Llama card lists the model files it finds (size, readable, complete) and the load error, if any.

## 0.4.1 (2026-11-20)

### Llama install
- Import from files works with Downloads: the model and tokenizer can be picked together or one at a
  time, and are recognised by their content, so browser renames ("(1)", ".bin") no longer block them.
  Imported files are checked against the published SHA-256 and free space is checked first.
- A half-copied or half-downloaded model is no longer reported as installed. If the model fails to
  load, the Llama card says so and offers Download and Import again instead of hiding them.
- New "Use mobile data" switch, plus a one-off "Download on mobile data" button. Off by default.
- The download retries dropped connections with backoff and resumes where it stopped, and runs under
  a foreground service with a progress notification so leaving the app does not kill it.
- A 401/403/404 from the release now says the release is not reachable and points to Import.

## 0.4.0 (2026-11-19)

### Network Guard
- New decision policy (`net/DnsPolicy.kt`). Lookups are let through when they are local names, OS or CDN
  infrastructure, sites the user allowed, popular domains (Tranco top 1M) or domains that belong to the
  app asking. Hosts on the bundled PhishTank/OpenPhish list are blocked. The URL model blocks on its
  own only at a strict threshold (0.1% false alarms on held-out real DNS names); anything else it
  doubts is flagged in the list, never blocked or notified.
- False blocks on real long-tail lookups: 21.5% (v1) to 0.03% (v2). Held-out phishing still stopped by
  the model alone: 87% (OpenPhish).
- "Allow this site" from any network alert; one alert per app and site per 6 hours.

### URL detector v3
- Benign training data now includes real DNS hostnames (Cisco Umbrella), split by registrable domain so
  no domain appears on both sides. New `brand_lure` signal for brand names inside unrelated domains.

### Link sandbox (new)
- The phone follows the link itself: HTTP/1.1 with no cookies and no JavaScript, private addresses
  refused, redirects followed one by one, certificate captured, size and time capped.
- The page is parsed inside an Android isolated process (`android:isolatedProcess`), which has no access
  to Shield's files and no network. Verified on the device.
- Reports the final address, the certificate, and what the page asks for (UPI PIN, ATM PIN, OTP,
  Aadhaar, PAN, card and CVV). Flags brand copies, look-alike domains, forms posting to messengers and
  APK downloads.
- Link Guard shows the steps live and opens the browser only when the page is clean.
- A page that is gone, an address that no longer exists or a server that never answers is reported
  as "Couldn't check this link", not as safe. A link opened from a flagged message keeps that verdict.
- A risky address that redirects to a trusted site stays flagged (cloaking: phishing kits send
  scanners on to the real brand).

### App
- New design system: graphite and signal-lime palette, light and dark themes, Geist typefaces, a
  custom icon set, liquid-glass tab bar and surfaces with GPU, Android-version and Battery Saver
  fallbacks.
- Keep-alive tab host instead of a pager. Janky frames 4.0% to 1.15% (CMF Phone 1) and 7.1% to 2.4%
  (Infinix X6878).
- Adaptive layout for small phones, tablets, foldables, landscape, display cutouts and large font
  sizes.
- R8, resource shrinking and a startup profile: APK 33 MB to 17 MB.
- More, On-device AI, Download Llama: resumable download from the release with SHA-256 checks, or
  import the two files from storage.
- Source reorganized into `core`, `llm`, `net`, `sms`, `sandbox`, `bench`, `util` and `ui` packages.

### Tooling
- `scripts/eval_dns_policy.py`, `scripts/eval_sandbox.py`, `scripts/bench_ui.py`.
- `qa` build type: release code and signing with the test hooks, for measurements. Its test receiver
  was missing at first because AGP 9 compiles Kotlin only from `kotlin` source directories; fixed.
- `scripts/screenshots.py`: README screenshots with a clean, cropped status bar; it captures only
  while ENTITY Shield is the app in front.
- `scripts/e2e_phone.py`: sandbox isolation and link checks; `--apk` to pick the build.

## 0.3.0 (2026-11-19)

First version of the Android app.
- SMS and URL detectors exported to ExecuTorch (`.pte`, 130 KB each) and verified against the training
  scores on the phone.
- Llama 3.2 1B Instruct on ExecuTorch explains alerts in English and Hindi, with the verdict written
  into the start of its answer and fixed, checked remediation steps.
- Network Guard: local DNS-only VPN with per-app attribution, beacon, DGA-burst and DNS-tunnel
  detection, plus a LAN tripwire on decoy ports.
- Link Guard, share-to-check, "Check with Shield" on selected text, on-device inbox audit.
- Thread ablation, explanation-quality evaluation, end-to-end phone tests, technical report and demo
  video.

## 0.2.0 (2026-11-06)

Tooling fixes and docs. No change to the models, the prompts or the measured numbers.

Not yet run on the phone: the new `push_to_phone.sh` and the changed `phone_llm.sh` paths were checked
only against a fake `adb` that replays real `llama_main` v1.5.1 output (no phone was connected).

### Fixed
- `scripts/phone_llm.sh`: failures were silent: a failed `adb push` or `llama_main` run just exited 1
  with the runner's stderr left on the phone. It now names the failing step and prints the tail of
  `llama_main.err`.
- `scripts/phone_llm.sh`: the prompt file name and the model name went unquoted into `adb shell`, so a
  space in the file name broke the command and shell metacharacters would run on the phone. Names are
  now validated (model) or sanitized (prompt), and numeric arguments are checked.
- `scripts/phone_llm.sh`: crashed with a `KeyError` traceback when the runner leaves out
  `prefill_token_per_sec` / `decode_token_per_sec` (it does when the interval is 0). It now shows `n/a`.
- `scripts/phone_llm.sh`: prints a usage line instead of "unbound variable" when run without arguments.
- `scripts/setup_mac.sh`: a second run aborted because `uv venv` refuses an existing `.venv`.
- `scripts/setup_mac.sh`: README said the scripts recreate the ExecuTorch checkout, but none did.
  Setup now clones ExecuTorch v1.5.1 with submodules (skip with `SKIP_EXECUTORCH_CLONE=1`) and warns
  if an existing checkout is a different version.
- `scripts/build_android_runner.sh`, `training/llm/export_llama.sh`: a missing checkout, NDK, venv,
  config or checkpoint now gives a one-line message with the fix, instead of a CMake or Hydra error
  deep in the run. The export script also rejects `MAX_SEQ` > `MAX_CTX`.
- `docs/img/make_charts.py`: crashed with `IndexError` without an argument; it now defaults to its
  own folder. Off macOS it now uses a fallback font quietly (chart output on macOS is unchanged).

### Added
- `scripts/push_to_phone.sh`: copies `llama_main`, `tokenizer.model` and `.pte` models to
  `/data/local/tmp/shield`, skipping models already there. This is the step README skipped between
  building the runner and running a prompt.
- `scripts/phone_llm.sh` also reports model load time and RSS after load.
- `scripts/tests/run_tests.sh`: 29 offline checks for every script, using a fake `adb` and a fake venv.
- `ANDROID_HOME` and `JOBS` are honoured by `build_android_runner.sh`; absolute `CONFIG` / `SRC` are
  accepted by `export_llama.sh`.

### Docs
- README "Getting started": added the Hugging Face download commands (both repos are gated) and the
  push-to-phone step, corrected the Linux note and the "scripts recreate them" claim, and documented
  the tests. Added a version badge, `VERSION` and this changelog.

## 0.1.0 (2026-10-08)

The first state, tagged after the fact: project plan, Mac toolchain setup, ExecuTorch 1.5.1 `llama_main`
cross-compiled for Android, Llama 3.2 1B (8da4w and QAT+LoRA) exported and run on the CMF Phone 1,
prompt experiments v1–v4 in English and Hindi, result charts.
