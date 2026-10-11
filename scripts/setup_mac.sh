#!/usr/bin/env bash
# One-time toolchain setup on an Apple Silicon Mac.
# Installs: adb, Android command-line tools + NDK r28c, JDK 17, CMake, Ninja, uv,
# and a Python 3.11 venv (.venv) with ExecuTorch 1.5.1 for model export.
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

uv venv --python 3.11 "${REPO_ROOT}/.venv"
# The executorch wheel does not bundle or depend on torch; 2.14.0 is the version pinned in its torch_pin.py.
uv pip install --python "${REPO_ROOT}/.venv/bin/python" "executorch==${EXECUTORCH_VERSION}" "torch==2.14.0" huggingface_hub

cat <<EOF

Done. Add to your shell profile:
  export JAVA_HOME="${JAVA_HOME}"
  export ANDROID_HOME="${ANDROID_HOME}"
  export ANDROID_NDK="${ANDROID_HOME}/ndk/${NDK_VERSION}"
EOF
