#!/usr/bin/env bash
# One-time toolchain setup on an Apple Silicon Mac. Safe to re-run.
# Installs: adb, Android command-line tools + NDK r28c, JDK 17, CMake, Ninja, uv,
# a Python 3.11 venv (.venv) with ExecuTorch 1.5.1 for model export, and the ExecuTorch v1.5.1
# source with submodules in third_party/executorch (about 2 GB) for scripts/build_android_runner.sh.
# Set SKIP_EXECUTORCH_CLONE=1 to skip the source checkout.
set -euo pipefail

EXECUTORCH_VERSION="1.5.1"
NDK_VERSION="28.2.13676358"   # NDK r28c, the version ExecuTorch's Llama README is tested with
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

brew install cmake ninja uv openjdk@17
brew install --cask android-platform-tools android-commandlinetools

export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"

# `yes` dies of SIGPIPE once sdkmanager stops reading; with pipefail that would abort the script.
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "ndk;${NDK_VERSION}"

# --allow-existing: without it a second run aborts with "A virtual environment already exists".
uv venv --allow-existing --python 3.11 "${REPO_ROOT}/.venv"
# The executorch wheel does not bundle or depend on torch; 2.14.0 is the version pinned in its torch_pin.py.
uv pip install --python "${REPO_ROOT}/.venv/bin/python" "executorch==${EXECUTORCH_VERSION}" "torch==2.14.0" huggingface_hub

ET_DIR="${REPO_ROOT}/third_party/executorch"
if [ -n "${SKIP_EXECUTORCH_CLONE:-}" ]; then
  echo "Skipping the ExecuTorch source checkout (SKIP_EXECUTORCH_CLONE is set)."
elif [ -d "${ET_DIR}/.git" ]; then
  have="$(git -C "${ET_DIR}" describe --tags --exact-match 2>/dev/null || echo unknown)"
  [ "${have}" = "v${EXECUTORCH_VERSION}" ] || \
    echo "WARNING: ${ET_DIR} is at '${have}', not v${EXECUTORCH_VERSION}; the runner build expects v${EXECUTORCH_VERSION}." >&2
else
  mkdir -p "${REPO_ROOT}/third_party"
  git clone --depth 1 --branch "v${EXECUTORCH_VERSION}" --recurse-submodules --shallow-submodules \
    https://github.com/pytorch/executorch.git "${ET_DIR}"
fi

cat <<EOF

Done. Add to your shell profile:
  export JAVA_HOME="${JAVA_HOME}"
  export ANDROID_HOME="${ANDROID_HOME}"
  export ANDROID_NDK="${ANDROID_HOME}/ndk/${NDK_VERSION}"

Next: download the Llama 3.2 1B weights (gated: accept the license on Hugging Face first,
then '.venv/bin/hf auth login'); see "Getting started" in README.md.
EOF
