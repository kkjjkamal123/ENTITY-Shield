#!/usr/bin/env bash
# Offline tests for the repo's shell scripts. No phone, weights, NDK or venv needed:
# adb and the venv's python are replaced by small fakes inside a temp copy of the scripts.
# Usage: scripts/tests/run_tests.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/shield-tests.XXXXXX")" && pwd)"
trap 'rm -rf "${T}"' EXIT
pass=0; fail=0
ok()  { pass=$((pass + 1)); echo "ok   - $1"; }
bad() { fail=$((fail + 1)); echo "FAIL - $1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/       /'; }
check() { # check <name> <expected rc> <grep pattern or ""> <output> <rc>
  local name="$1" want_rc="$2" pat="$3" out="$4" rc="$5"
  if [ "${rc}" != "${want_rc}" ]; then bad "${name} (rc ${rc}, want ${want_rc})" "${out}"
  elif [ -n "${pat}" ] && ! grep -qE -- "${pat}" <<<"${out}"; then bad "${name} (no /${pat}/)" "${out}"
  else ok "${name}"; fi
}

# ---- fake repo layout (scripts derive REPO_ROOT from their own path) ----
R="${T}/repo"
mkdir -p "${R}/scripts" "${R}/training/llm" "${R}/models/llama-3.2-1b-instruct/original" \
  "${R}/third_party/executorch/examples/models/llama/config" \
  "${R}/third_party/executorch/cmake-out-android/examples/models/llama" "${R}/.venv/bin" "${T}/bin"
cp "${REPO_ROOT}"/scripts/*.sh "${R}/scripts/"
cp "${REPO_ROOT}/training/llm/export_llama.sh" "${R}/training/llm/"
touch "${R}/third_party/executorch/examples/models/llama/config/llama_xnnpack.yaml"
printf 'runner' >"${R}/third_party/executorch/cmake-out-android/examples/models/llama/llama_main"
chmod +x "${R}/third_party/executorch/cmake-out-android/examples/models/llama/llama_main"
printf 'tok' >"${R}/models/llama-3.2-1b-instruct/original/tokenizer.model"
printf '0123456789' >"${R}/models/test.pte"

# Fake venv python: records its args and writes the requested .pte into the working directory.
cat >"${R}/.venv/bin/python" <<'PY'
#!/usr/bin/env bash
printf '%s\n' "$@" >"${FAKE_PY_LOG}"
for a in "$@"; do case "$a" in ++export.output_name=*) : >"${a#++export.output_name=}";; esac; done
PY
chmod +x "${R}/.venv/bin/python"

# Fake adb: logs every call; replays canned llama_main stdout/stderr.
cat >"${T}/bin/adb" <<'ADB'
#!/usr/bin/env bash
printf 'adb %s\n' "$*" >>"${FAKE_ADB_LOG}"
case "$1" in
  get-state) [ -n "${FAKE_NO_DEVICE:-}" ] && { echo "error: no devices/emulators found" >&2; exit 1; }; echo device ;;
  push) [ -n "${FAKE_PUSH_FAIL:-}" ] && { echo "adb: error: failed to copy" >&2; exit 1; }; echo "1 file pushed" ;;
  shell) shift
    case "$*" in
      *"cat "*llama_main.err*) [ -n "${FAKE_ERR:-}" ] && cat "${FAKE_ERR}" ;;
      *"stat -c"*) echo "${FAKE_REMOTE_SIZE:-}" ;;
      *llama_main\ --model_path*) [ -n "${FAKE_OUT:-}" ] && cat "${FAKE_OUT}"; exit "${FAKE_RC:-0}" ;;
    esac ;;
esac
ADB
chmod +x "${T}/bin/adb"
export PATH="${T}/bin:${PATH}" FAKE_ADB_LOG="${T}/adb.log" FAKE_PY_LOG="${T}/py.log"

# ---- syntax ----
for f in "${REPO_ROOT}"/scripts/*.sh "${REPO_ROOT}"/scripts/tests/*.sh "${REPO_ROOT}"/training/llm/*.sh; do
  out="$(bash -n "${f}" 2>&1)"; check "bash -n ${f#"${REPO_ROOT}"/}" 0 "" "${out}" $?
done

# ---- phone_llm.sh ----
P="${REPO_ROOT}/training/llm/prompts/2026-10-08_v4_en_factsheet_prefill.txt"
stats='{"prefill_token_per_sec":85.2,"decode_token_per_sec":12.9,"prompt_tokens":165,"generated_tokens":19,"model_load_start_ms":1000,"model_load_end_ms":3000,"inference_start_ms":3100,"inference_end_ms":6500,"prompt_eval_end_ms":5040,"first_token_ms":5140,"aggregate_sampling_time_ms":3,"SCALING_FACTOR_UNITS_PER_SECOND":1000}'
{ cat "${P}"; printf ' It asks for personal info (KYC update) and links to a non-official site.\n'
  printf 'PyTorchObserver %s\n' "${stats}"; } >"${T}/ok.out"
sed 's/"decode_token_per_sec":12.9,//' "${T}/ok.out" >"${T}/nodecode.out"
printf 'I 00:00:02.0 RSS after loading model: 1297.4 MiB (0 if unsupported)\n' >"${T}/ok.err"
printf 'E 00:00:00.1 main.cpp:88] Failed to create llama runner\n' >"${T}/fail.err"
PL="${R}/scripts/phone_llm.sh"

out="$(bash "${PL}" 2>&1)"; check "phone_llm: no args prints usage" 2 "^usage:" "${out}" $?
out="$(FAKE_OUT="${T}/ok.out" FAKE_ERR="${T}/ok.err" bash "${PL}" m.pte "${P}" 2>&1)"; rc=$?
check "phone_llm: answer keeps the prefill and drops the prompt" 0 "^⚠️ No, this SMS is a scam. Why: It asks for personal info" "${out}" ${rc}
check "phone_llm: stats line" 0 "^prompt 165 tok \| prefill 85.2 tok/s \| TTFT 2.04 s \| decode 12.9 tok/s \| generated 19 \| load 2.00 s \| RSS after load 1297 MiB$" "${out}" ${rc}
if grep -qE 'PyTorchObserver|begin_of_text' <<<"${out}"; then bad "phone_llm: prompt/stats leak into the answer" "${out}"; else ok "phone_llm: prompt/stats do not leak into the answer"; fi
out="$(FAKE_OUT="${T}/nodecode.out" bash "${PL}" m.pte "${P}" 2>&1)"; check "phone_llm: missing rate field shows n/a" 0 "decode n/a" "${out}" $?
out="$(FAKE_OUT=/dev/null FAKE_RC=1 FAKE_ERR="${T}/fail.err" bash "${PL}" m.pte "${P}" 2>&1)"
check "phone_llm: runner failure shows the phone's stderr" 1 "Failed to create llama runner" "${out}" $?
out="$(FAKE_PUSH_FAIL=1 bash "${PL}" m.pte "${P}" 2>&1)"; check "phone_llm: push failure is reported" 1 "adb push of the prompt" "${out}" $?
out="$(FAKE_NO_DEVICE=1 bash "${PL}" m.pte "${P}" 2>&1)"; check "phone_llm: no phone is reported" 1 "no authorized phone" "${out}" $?
out="$(bash "${PL}" 'm.pte;reboot' "${P}" 2>&1)"; check "phone_llm: shell metacharacters in model name rejected" 1 "must match" "${out}" $?
out="$(bash "${PL}" m.pte "${P}" abc 2>&1)"; check "phone_llm: non-numeric max_new_tokens rejected" 1 "positive integer" "${out}" $?
cp "${P}" "${T}/my prompt;x.txt"; : >"${FAKE_ADB_LOG}"
out="$(FAKE_OUT="${T}/ok.out" bash "${PL}" m.pte "${T}/my prompt;x.txt" 2>&1)"; rc=$?
check "phone_llm: odd prompt filename is sanitized" 0 "--prompt_file my_prompt_x.txt " "$(cat "${FAKE_ADB_LOG}")" ${rc}

# ---- push_to_phone.sh ----
PP="${R}/scripts/push_to_phone.sh"
: >"${FAKE_ADB_LOG}"; out="$(bash "${PP}" test.pte 2>&1)"; rc=$?
check "push_to_phone: pushes runner, tokenizer and model" 0 "push .*/models/test.pte /data/local/tmp/shield/test.pte" "$(cat "${FAKE_ADB_LOG}")" ${rc}
check "push_to_phone: runner made executable" 0 "chmod 755 /data/local/tmp/shield/llama_main" "$(cat "${FAKE_ADB_LOG}")" ${rc}
: >"${FAKE_ADB_LOG}"; out="$(FAKE_REMOTE_SIZE=10 bash "${PP}" test.pte 2>&1)"; check "push_to_phone: same-size model skipped" 0 "already on the phone" "${out}" $?
out="$(bash "${PP}" missing.pte 2>&1)"; check "push_to_phone: missing model reported" 1 "model not found: missing.pte" "${out}" $?
out="$(RUNNER=/nonexistent bash "${PP}" test.pte 2>&1)"; check "push_to_phone: missing runner reported" 1 "build_android_runner.sh" "${out}" $?

# ---- export_llama.sh ----
EX="${R}/training/llm/export_llama.sh"
out="$(bash "${EX}" 2>&1)"; check "export_llama: missing checkpoint reported with download hint" 1 "hf download" "${out}" $?
: >"${R}/models/llama-3.2-1b-instruct/original/consolidated.00.pth"; : >"${R}/models/llama-3.2-1b-instruct/original/params.json"
out="$(bash "${EX}" 2>&1)"; rc=$?
check "export_llama: passes absolute checkpoint path" 0 "^\+\+base.checkpoint=${R}/models/llama-3.2-1b-instruct/original/consolidated.00.pth$" "$(cat "${FAKE_PY_LOG}")" ${rc}
check "export_llama: writes the default output name" 0 "llama3_2_1b_instruct_8da4w_ctx2048.pte" "${out}" ${rc}
out="$(MAX_SEQ=4096 bash "${EX}" 2>&1)"; check "export_llama: MAX_SEQ > MAX_CTX rejected" 1 "MAX_SEQ <= MAX_CTX" "${out}" $?
out="$(CONFIG=nope.yaml bash "${EX}" 2>&1)"; check "export_llama: missing config reported" 1 "config not found" "${out}" $?

# ---- build_android_runner.sh (preflight only) ----
out="$(ANDROID_NDK=/nonexistent bash "${R}/scripts/build_android_runner.sh" 2>&1)"
check "build_android_runner: missing checkout reported" 1 "ExecuTorch checkout not found" "${out}" $?

# ---- detector featurizer and SMS template set (plain python3, no deps) ----
out="$(python3 "${REPO_ROOT}/training/featurize.py" 2>&1)"; check "featurize.py self-check" 0 "self-check ok" "${out}" $?
out="$(python3 "${REPO_ROOT}/training/sms/indian_sms.py" 2>&1)"; check "indian_sms.py fills every template" 0 "rows;" "${out}" $?

# ---- charts (only when matplotlib is installed) ----
if python3 -c 'import matplotlib' 2>/dev/null; then
  out="$(python3 "${REPO_ROOT}/docs/img/make_charts.py" "${T}" 2>&1)"; rc=$?
  n="$(ls "${T}"/*.png 2>/dev/null | wc -l | tr -d ' ')"
  check "make_charts.py writes 3 charts" 0 "^3$" "${n}" ${rc}
else
  echo "skip - make_charts.py (matplotlib not installed)"
fi

echo "# ${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
