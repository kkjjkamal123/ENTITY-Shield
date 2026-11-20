"""Patch ExecuTorch v1.5.1's Android LLM JNI so the app can choose the thread count and pin to big cores.

Upstream (extension/android/jni/jni_layer_llama.cpp) sizes the thread pool to "performant cores - 1"
when an LlmModule is created. On the CMF Phone 1 (Dimensity 7300) that is 3 threads, and the thread
ablation on the Infinix X6878 showed 4 threads pinned to the big cores is the fastest arm
(185 tok/s prefill vs 152 for 3 pinned and 168 for the default).

The patch keeps the Java API unchanged. Before the pool is reset it reads two environment variables,
which the app sets with android.system.Os.setenv() before creating the LlmModule:

  ET_NUM_THREADS=<n>   pool size (calling thread included), 1..16; unset = upstream heuristic
  ET_PIN_BIG=1         pin the calling thread to the n fastest cores (by cpuinfo_max_freq) first;
                       the pool's worker threads are created by the reset and inherit that mask

Usage: python patch_threads.py <executorch checkout>
"""
import pathlib
import sys

src = pathlib.Path(sys.argv[1]) / "extension/android/jni/jni_layer_llama.cpp"
s = src.read_text(encoding="utf-8")

old = """#if defined(ET_USE_THREADPOOL)
      // Reserve 1 thread for the main thread.
      int32_t num_performant_cores =
          ::executorch::extension::cpuinfo::get_num_performant_cores() - 1;
      if (num_performant_cores > 0) {
        ET_LOG(
            Info, "Resetting threadpool to %d threads", num_performant_cores);
        ::executorch::extension::threadpool::get_threadpool()
            ->_unsafe_reset_threadpool(num_performant_cores);
      }
#endif"""

new = """#if defined(ET_USE_THREADPOOL)
      // Reserve 1 thread for the main thread.
      int32_t num_performant_cores =
          ::executorch::extension::cpuinfo::get_num_performant_cores() - 1;
      // ENTITY Shield patch: thread count and big-core pinning chosen by the app.
      if (const char* env_n = std::getenv("ET_NUM_THREADS")) {
        int v = std::atoi(env_n);
        if (v > 0 && v <= 16) {
          num_performant_cores = v;
        }
      }
      if (const char* env_pin = std::getenv("ET_PIN_BIG")) {
        if (env_pin[0] == '1' && num_performant_cores > 0) {
          shield_pin_fastest_cores(num_performant_cores);
        }
      }
      if (num_performant_cores > 0) {
        ET_LOG(
            Info, "Resetting threadpool to %d threads", num_performant_cores);
        ::executorch::extension::threadpool::get_threadpool()
            ->_unsafe_reset_threadpool(num_performant_cores);
      }
#endif"""

helper = """
#if defined(ET_USE_THREADPOOL)
#include <sched.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <utility>
// ENTITY Shield patch: pin the calling thread to the n cores with the highest cpuinfo_max_freq.
// Threads created afterwards (the XNNPACK pool workers) inherit this affinity mask.
static void shield_pin_fastest_cores(int n) {
  std::vector<std::pair<long, int>> cores;
  for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* f = std::fopen(path, "r");
    if (f == nullptr) {
      if (cpu >= 64) break;
      continue;
    }
    long khz = 0;
    if (std::fscanf(f, "%ld", &khz) != 1) khz = 0;
    std::fclose(f);
    cores.emplace_back(khz, cpu);
  }
  if (cores.empty() || n <= 0) return;
  std::stable_sort(cores.begin(), cores.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int i = 0; i < n && i < (int)cores.size(); ++i) CPU_SET(cores[i].second, &set);
  if (sched_setaffinity(0, sizeof(set), &set) == 0) {
    ET_LOG(Info, "Pinned to the %d fastest cores", std::min(n, (int)cores.size()));
  } else {
    ET_LOG(Info, "Could not pin to the fastest cores");
  }
}
#endif
"""

anchor = "#include <fbjni/ByteBuffer.h>"
assert s.count(old) == 1, "threadpool block not found: ExecuTorch version changed?"
assert s.count(anchor) == 1
s = s.replace(old, new).replace(anchor, helper + "\n" + anchor)
src.write_text(s, encoding="utf-8")
print("patched", src)
