#!/usr/bin/env bash
# Runs one prompt file through llama_main on the connected phone and prints the answer + key stats.
# Usage: scripts/phone_llm.sh <model.pte name on phone> <local prompt file> [max_new_tokens=80] [cpu_threads=4]
# Expects llama_main, tokenizer.model and the .pte already in /data/local/tmp/shield
# (scripts/push_to_phone.sh puts them there).
# Env: ADB (default: adb), PHONE_DIR (default: /data/local/tmp/shield), TOKENIZER (default: tokenizer.model)
set -euo pipefail

usage() {
  echo "usage: $0 <model.pte name on phone> <local prompt file> [max_new_tokens=80] [cpu_threads=4]" >&2
  exit 2
}
die() { echo "phone_llm: $*" >&2; exit 1; }

[ $# -ge 2 ] && [ $# -le 4 ] || usage
MODEL="$1"; PROMPT="$2"; MAX_NEW="${3:-80}"; THREADS="${4:-4}"
ADB="${ADB:-adb}"
DIR="${PHONE_DIR:-/data/local/tmp/shield}"
TOKENIZER="${TOKENIZER:-tokenizer.model}"

# Everything below ends up in a remote `sh -c` string, so only allow plain names/paths and numbers.
safe='^[A-Za-z0-9._/+-]+$'
[[ "${MODEL}" =~ ${safe} ]] || die "model name '${MODEL}' must match ${safe}"
[[ "${TOKENIZER}" =~ ${safe} ]] || die "tokenizer name '${TOKENIZER}' must match ${safe}"
[[ "${DIR}" =~ ${safe} ]] || die "PHONE_DIR '${DIR}' must match ${safe}"
[[ "${MAX_NEW}" =~ ^[0-9]+$ ]] && [ "${MAX_NEW}" -gt 0 ] || die "max_new_tokens must be a positive integer"
[[ "${THREADS}" =~ ^-?[0-9]+$ ]] || die "cpu_threads must be an integer (-1 = runner heuristic)"
[ -f "${PROMPT}" ] || die "prompt file not found: ${PROMPT}"
command -v "${ADB}" >/dev/null || die "adb not found (set ADB=/path/to/adb)"
[ "$("${ADB}" get-state 2>/dev/null || true)" = "device" ] || die "no authorized phone connected (check 'adb devices')"

# Push under a sanitized name so spaces or shell characters in the local filename can't break the remote command.
NAME="$(basename "${PROMPT}")"; NAME="${NAME//[^A-Za-z0-9._-]/_}"
"${ADB}" push "${PROMPT}" "${DIR}/${NAME}" >/dev/null || die "adb push of the prompt to ${DIR} failed"

# Logs go to stderr on the phone; keep them out of the answer text.
set +e
out="$("${ADB}" shell "cd ${DIR} && ./llama_main --model_path ${MODEL} --tokenizer_path ${TOKENIZER} \
  --prompt_file ${NAME} --temperature 0 --max_new_tokens ${MAX_NEW} --cpu_threads ${THREADS} 2>llama_main.err")"
rc=$?
set -e
err="$("${ADB}" shell "cat ${DIR}/llama_main.err" 2>/dev/null || true)"
if [ "${rc}" -ne 0 ]; then
  echo "phone_llm: llama_main exited with status ${rc}; last lines of ${DIR}/llama_main.err:" >&2
  printf '%s\n' "${err}" | tail -n 15 >&2
  exit 1
fi

# stdout = echoed prompt, then the answer, then the PyTorchObserver stats line.
# The answer is everything after the LAST assistant header (the app's prefill is kept: it is part of the answer).
ERR_TEXT="${err}" python3 -c '
import json, os, re, sys
out = sys.stdin.read()
m = None
for m in re.finditer(r"^PyTorchObserver (\{.*\})\s*$", out, re.M):
    pass
body = out[: m.start()] if m else out
head = "assistant<|end_header_id|>"
body = body[body.rfind(head) + len(head):] if head in body else body
print(body.strip("\r\n"))
print("---")
if not m:
    sys.exit("phone_llm: no PyTorchObserver stats line in the runner output")
d = json.loads(m.group(1))
unit = d.get("SCALING_FACTOR_UNITS_PER_SECOND", 1000)
def rate(k):
    v = d.get(k)
    return "n/a" if v is None else "%.1f tok/s" % v
ttft = (d["first_token_ms"] - d["inference_start_ms"]) / unit
load = (d["model_load_end_ms"] - d["model_load_start_ms"]) / unit
line = "prompt %d tok | prefill %s | TTFT %.2f s | decode %s | generated %d | load %.2f s" % (
    d["prompt_tokens"], rate("prefill_token_per_sec"), ttft, rate("decode_token_per_sec"),
    d["generated_tokens"], load)
rss = re.search(r"RSS after loading model: ([0-9.]+) MiB", os.environ.get("ERR_TEXT", ""))
if rss and float(rss.group(1)) > 0:
    line += " | RSS after load %.0f MiB" % float(rss.group(1))
print(line)
' <<<"${out}"
