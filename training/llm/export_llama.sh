#!/usr/bin/env bash
# Exports Llama 3.2 1B Instruct to an ExecuTorch .pte for the XNNPACK backend.
# Default: Meta's bf16 checkpoint, post-training quantized to 8da4w (group 128) + 4-bit embeddings (group 32).
# QAT+LoRA:  CONFIG=llama_xnnpack_qat.yaml SRC=models/llama-3.2-1b-instruct-qlora \
#            OUT_NAME=llama3_2_1b_instruct_qlora_ctx2048.pte ./training/llm/export_llama.sh
# Context: 2048 tokens; prefill in chunks of up to 512 (matches ENTITY's PP512/TG128 workload).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ET="${REPO_ROOT}/third_party/executorch"
CONFIG="${ET}/examples/models/llama/config/${CONFIG:-llama_xnnpack.yaml}"
SRC="$(cd "${REPO_ROOT}" && cd "${SRC:-models/llama-3.2-1b-instruct/original}" && pwd)"
OUT_DIR="${REPO_ROOT}/models"
OUT_NAME="${OUT_NAME:-llama3_2_1b_instruct_8da4w_ctx2048.pte}"
MAX_SEQ="${MAX_SEQ:-512}"
MAX_CTX="${MAX_CTX:-2048}"

# export_llm writes the .pte (and Hydra's outputs/ logs) into the working directory and
# ignores export.output_dir, so run from the gitignored models/ folder.
cd "${OUT_DIR}"
"${REPO_ROOT}/.venv/bin/python" -m executorch.extension.llm.export.export_llm \
  --config "${CONFIG}" \
  ++base.model_class="llama3_2" \
  ++base.checkpoint="${SRC}/consolidated.00.pth" \
  ++base.params="${SRC}/params.json" \
  ++export.max_seq_length="${MAX_SEQ}" \
  ++export.max_context_length="${MAX_CTX}" \
  ++export.output_name="${OUT_NAME}"

ls -la "${OUT_DIR}/${OUT_NAME}"
