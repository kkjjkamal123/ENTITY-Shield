#!/usr/bin/env bash
# Runs one prompt file through llama_main on the connected phone and prints the answer + key stats.
# Usage: scripts/phone_llm.sh <model.pte name on phone> <local prompt file> [max_new_tokens=80] [cpu_threads=4]
# Expects llama_main and tokenizer.model already in /data/local/tmp/shield (see docs/PLAN.md §0).
set -euo pipefail

MODEL="$1"; PROMPT="$2"; MAX_NEW="${3:-80}"; THREADS="${4:-4}"
DIR=/data/local/tmp/shield
NAME="$(basename "${PROMPT}")"

adb push "${PROMPT}" "${DIR}/${NAME}" >/dev/null 2>&1
# Logs go to stderr on the phone; keep them out of the answer text.
out="$(adb shell "cd ${DIR} && ./llama_main --model_path ${MODEL} --tokenizer_path tokenizer.model \
  --prompt_file ${NAME} --temperature 0 --max_new_tokens ${MAX_NEW} --cpu_threads ${THREADS} 2>llama_main.err")"

# stdout = echoed prompt, then the answer, then the PyTorchObserver stats line.
echo "${out}" | sed -n '/assistant<|end_header_id|>/,$p' | tail -n +2 | grep -v '^PyTorchObserver'
echo "---"
echo "${out}" | grep -o 'PyTorchObserver.*' | sed 's/PyTorchObserver //' | python3 -c '
import json, sys
d = json.load(sys.stdin)
ttft = (d["first_token_ms"] - d["inference_start_ms"]) / 1000
print("prompt %d tok | prefill %.1f tok/s | TTFT %.2f s | decode %.1f tok/s | generated %d" % (
    d["prompt_tokens"], d["prefill_token_per_sec"], ttft, d["decode_token_per_sec"], d["generated_tokens"]))'
