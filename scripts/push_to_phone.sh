#!/usr/bin/env bash
# Pushes what scripts/phone_llm.sh needs to the connected phone: llama_main, tokenizer.model and one or more .pte models.
# Usage: scripts/push_to_phone.sh [model.pte ...]   (paths, or names under models/;
#        default: models/llama3_2_1b_instruct_qlora_ctx2048.pte)
# A model already on the phone with the same byte size is skipped (they are ~1 GB each).
# Env: ADB (default: adb), PHONE_DIR (default: /data/local/tmp/shield), RUNNER, TOKENIZER_SRC
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
DIR="${PHONE_DIR:-/data/local/tmp/shield}"
RUNNER="${RUNNER:-${REPO_ROOT}/third_party/executorch/cmake-out-android/examples/models/llama/llama_main}"
die() { echo "push_to_phone: $*" >&2; exit 1; }

if [ -z "${TOKENIZER_SRC:-}" ]; then
  for t in "${REPO_ROOT}/models/llama-3.2-1b-instruct/original/tokenizer.model" \
           "${REPO_ROOT}/models/llama-3.2-1b-instruct-qlora/tokenizer.model"; do
    [ -f "${t}" ] && { TOKENIZER_SRC="${t}"; break; }
  done
fi

[ $# -gt 0 ] || set -- llama3_2_1b_instruct_qlora_ctx2048.pte
models=()
for arg in "$@"; do
  m="${arg}"
  [ -f "${m}" ] || m="${REPO_ROOT}/models/${arg}"
  [ -f "${m}" ] || die "model not found: ${arg} (export one with training/llm/export_llama.sh)"
  [[ "$(basename "${m}")" =~ ^[A-Za-z0-9._+-]+\.pte$ ]] || die "model file name must be a plain *.pte name: ${m}"
  models+=("${m}")
done

[[ "${DIR}" =~ ^[A-Za-z0-9._/+-]+$ ]] || die "PHONE_DIR '${DIR}' must be a plain path"
[ -x "${RUNNER}" ] || die "llama_main not found at ${RUNNER} (run scripts/build_android_runner.sh)"
[ -f "${TOKENIZER_SRC:-}" ] || die "tokenizer.model not found under models/ (download the Llama 3.2 1B weights, see README)"
command -v "${ADB}" >/dev/null || die "adb not found (set ADB=/path/to/adb)"
[ "$("${ADB}" get-state 2>/dev/null || true)" = "device" ] || die "no authorized phone connected (check 'adb devices')"

"${ADB}" shell "mkdir -p ${DIR}"
"${ADB}" push "${RUNNER}" "${DIR}/llama_main"
"${ADB}" shell "chmod 755 ${DIR}/llama_main"
"${ADB}" push "${TOKENIZER_SRC}" "${DIR}/tokenizer.model"

for m in "${models[@]}"; do
  name="$(basename "${m}")"
  local_size="$(wc -c <"${m}" | tr -d ' ')"
  remote_size="$("${ADB}" shell "stat -c %s ${DIR}/${name} 2>/dev/null" | tr -d '\r' || true)"
  if [ "${remote_size}" = "${local_size}" ]; then
    echo "${name}: already on the phone (${local_size} bytes), skipped"
  else
    "${ADB}" push "${m}" "${DIR}/${name}"
  fi
done

echo "Ready in ${DIR}. Try: scripts/phone_llm.sh $(basename "${models[0]}") training/llm/prompts/2026-10-08_v4_en_factsheet_prefill.txt"
