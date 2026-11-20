# Patched ExecuTorch 1.5.1 (backup, not used by the app)

`executorch-android-1.5.1-shield.aar` is ExecuTorch 1.5.1 built by
`.github/workflows/executorch-android.yml` with `scripts/executorch/patch_threads.py`
(thread count from `ET_NUM_THREADS`, big-core pinning with `ET_PIN_BIG=1`).

Not used since 0.5.1. In the app on the Infinix X6878 it measured 7.8-11 tok/s decode and
58-87 tok/s prefill, slower than 0.4's stock library. Android resets a thread's core mask
when the app's cpuset changes (foreground/background), so the pinning set at load did not last.

To try it again, copy it to `android-app/app/libs/`; `app/build.gradle.kts` picks it up when present.
