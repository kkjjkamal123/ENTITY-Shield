# Changelog

Versions cover the repository as a whole (scripts, docs, prompts). The Android app and the Pi gateway
are not built yet, so there is no APK `versionCode` / `versionName` or gateway version to track.

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
