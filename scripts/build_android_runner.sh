#!/usr/bin/env bash
# Cross-compiles ExecuTorch (XNNPACK + LLM kernels) and its `llama_main` runner for arm64 Android.
# Follows examples/models/llama/README.md "Step 4" of ExecuTorch v1.5.1.
# Output: third_party/executorch/cmake-out-android/examples/models/llama/llama_main
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ET="${REPO_ROOT}/third_party/executorch"
PY="${REPO_ROOT}/.venv/bin/python"
NDK_VERSION="28.2.13676358"   # NDK r28c, same as scripts/setup_mac.sh
if [ -z "${ANDROID_NDK:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ]; then
    ANDROID_NDK="${ANDROID_HOME}/ndk/${NDK_VERSION}"
  else
    ANDROID_NDK="$(brew --prefix 2>/dev/null)/share/android-commandlinetools/ndk/${NDK_VERSION}"
  fi
fi
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)}"

die() { echo "build_android_runner: $*" >&2; exit 1; }
[ -f "${ET}/CMakeLists.txt" ] || die "ExecuTorch checkout not found at ${ET} (run scripts/setup_mac.sh)"
[ -f "${ANDROID_NDK}/build/cmake/android.toolchain.cmake" ] || die "Android NDK not found at ${ANDROID_NDK} (set ANDROID_NDK)"
[ -x "${PY}" ] || die "Python venv not found at ${PY} (run scripts/setup_mac.sh)"
command -v cmake >/dev/null || die "cmake not found (brew install cmake)"

common=(
  -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-23
  -DCMAKE_INSTALL_PREFIX=cmake-out-android
  -DCMAKE_BUILD_TYPE=Release
  -DPYTHON_EXECUTABLE="${PY}"
  -DEXECUTORCH_BUILD_XNNPACK=ON
  -DEXECUTORCH_BUILD_KERNELS_OPTIMIZED=ON
  -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=ON
  -DEXECUTORCH_BUILD_KERNELS_LLM=ON
)

cd "${ET}"

# 1. ExecuTorch core + extensions
cmake "${common[@]}" \
  -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON \
  -DEXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR=ON \
  -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON \
  -DEXECUTORCH_BUILD_EXTENSION_TENSOR=ON \
  -DEXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP=ON \
  -DEXECUTORCH_BUILD_EXTENSION_LLM=ON \
  -DEXECUTORCH_BUILD_EXTENSION_LLM_RUNNER=ON \
  -DEXECUTORCH_ENABLE_LOGGING=1 \
  -Bcmake-out-android .
cmake --build cmake-out-android -j"${JOBS}" --target install --config Release

# 2. llama_main runner
cmake "${common[@]}" \
  -DSUPPORT_REGEX_LOOKAHEAD=ON \
  -Bcmake-out-android/examples/models/llama \
  examples/models/llama
cmake --build cmake-out-android/examples/models/llama -j"${JOBS}" --config Release

echo "Built: ${ET}/cmake-out-android/examples/models/llama/llama_main"
