#!/usr/bin/env bash
# Exports Llama 3.2 1B Instruct to an ExecuTorch .pte for the XNNPACK backend.
# Default: Meta's bf16 checkpoint, post-training quantized to 8da4w (group 128) + 4-bit embeddings (group 32).
# QAT+LoRA:  CONFIG=llama_xnnpack_qat.yaml SRC=models/llama-3.2-1b-instruct-qlora \
#            OUT_NAME=llama3_2_1b_instruct_qlora_ctx2048.pte ./training/llm/export_llama.sh
# Context: 2048 tokens; prefill in chunks of up to 512 (matches ENTITY's PP512/TG128 workload).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ET="${REPO_ROOT}/third_party/executorch"
PY="${REPO_ROOT}/.venv/bin/python"
CONFIG="${CONFIG:-llama_xnnpack.yaml}"
case "${CONFIG}" in /*) ;; *) CONFIG="${ET}/examples/models/llama/config/${CONFIG}" ;; esac
SRC="${SRC:-models/llama-3.2-1b-instruct/original}"
case "${SRC}" in /*) ;; *) SRC="${REPO_ROOT}/${SRC}" ;; esac
OUT_DIR="${REPO_ROOT}/models"
OUT_NAME="${OUT_NAME:-llama3_2_1b_instruct_8da4w_ctx2048.pte}"
MAX_SEQ="${MAX_SEQ:-512}"
MAX_CTX="${MAX_CTX:-2048}"

die() { echo "export_llama: $*" >&2; exit 1; }
[ -x "${PY}" ] || die "Python venv not found at ${PY} (run scripts/setup_mac.sh)"
[ -f "${CONFIG}" ] || die "export config not found: ${CONFIG}"
for f in consolidated.00.pth params.json; do
  [ -f "${SRC}/${f}" ] || die "${SRC}/${f} not found. Download the weights first (see README, Getting started), e.g.
  .venv/bin/hf download meta-llama/Llama-3.2-1B-Instruct --include 'original/*' --local-dir models/llama-3.2-1b-instruct"
done
[[ "${MAX_SEQ}" =~ ^[0-9]+$ && "${MAX_CTX}" =~ ^[0-9]+$ && "${MAX_SEQ}" -le "${MAX_CTX}" ]] || \
  die "MAX_SEQ (${MAX_SEQ}) and MAX_CTX (${MAX_CTX}) must be integers with MAX_SEQ <= MAX_CTX"

# export_llm writes the .pte (and Hydra's outputs/ logs) into the working directory and
# ignores export.output_dir, so run from the gitignored models/ folder.
cd "${OUT_DIR}"
"${PY}" -m executorch.extension.llm.export.export_llm \
  --config "${CONFIG}" \
  ++base.model_class="llama3_2" \
  ++base.checkpoint="${SRC}/consolidated.00.pth" \
  ++base.params="${SRC}/params.json" \
  ++export.max_seq_length="${MAX_SEQ}" \
  ++export.max_context_length="${MAX_CTX}" \
  ++export.output_name="${OUT_NAME}"

ls -la "${OUT_DIR}/${OUT_NAME}"
