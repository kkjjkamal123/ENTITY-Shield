# ENTITY Shield

**On-device threat, phishing and scam detection on Arm, fully offline.**

Bharat AI-SoC Challenge 2026–27 · Problem Statement 3 · Cybersecurity & Digital Safety

![Status](https://img.shields.io/badge/status-early%20build-orange)
![Dev host](https://img.shields.io/badge/dev%20host-macOS%2027%20(Apple%20silicon)-black)
![Targets](https://img.shields.io/badge/targets-Android%20arm64%20%7C%20Raspberry%20Pi%205-green)
![Runtime](https://img.shields.io/badge/runtime-ExecuTorch%201.5.1-blue)

> **Status:** the plan is written and the first on-device milestone is done: **Llama 3.2 1B runs on the phone through ExecuTorch**, with English and Hindi explanations tested. The detectors, Android app and Pi gateway are not built yet. The full plan and progress log are in [`docs/PLAN.md`](docs/PLAN.md).

## What it will do

- **Scam SMS detection** on an Arm Android phone
- **Phishing URL detection** on the phone, with no lookups
- **Network intrusion and anomaly detection** on a Raspberry Pi 5 home gateway
- **Plain-language explanations** of every threat (English and Hindi) from **Llama 3.2 running on ExecuTorch**
- Everything runs locally, with **no cloud dependency**, and the phone stays fully usable while the model runs

## Hardware

| Device | Role |
|---|---|
| CMF Phone 1 (MediaTek Dimensity 7300, 6 GB) | Main app: SMS/URL detectors, Llama 3.2 1B explanations |
| Raspberry Pi 5 (8 GB) | Network gateway: capture, intrusion/anomaly models, alerts to the phone |
| Apple silicon Mac (macOS 27) | Development host only: model export, cross-compiling, benchmarking over `adb` |

## Results so far

First run of Llama 3.2 1B Instruct on the CMF Phone 1 (XNNPACK, context 2048, 4 threads). These are single runs with the phone charging, **not a benchmark of record**.

| | 8da4w (post-training quantized) | QAT+LoRA (Meta) |
|---|---:|---:|
| Model size | 803 MB | 1.12 GB |
| Prefill | 105–130 tok/s | 81–90 tok/s |
| Decode | 18–20 tok/s | 11–14 tok/s |
| Time to first token (165-token prompt) | 1.27 s | 2.04 s |
| Hindi explanation | repeats itself | fluent and correct |

The explanation design that came out of this: the app writes the detector's verdict as the first sentence ("verdict prefill"), Llama explains *why* for that specific message, and the "what to do" steps are fixed, pre-checked text. This avoids model refusals and means Llama can never contradict the detector. Details are in [`docs/PLAN.md` §0](docs/PLAN.md#0-progress-log).

## Getting started (macOS, Apple silicon)

The toolchain scripts target **macOS on Apple silicon** with [Homebrew](https://brew.sh). Linux should work with small path changes; Windows is not supported.

```bash
# 1. One-time setup: adb, Android cmdline tools + NDK r28c, JDK 17, CMake, Ninja, uv,
#    and a Python 3.11 venv with ExecuTorch 1.5.1
./scripts/setup_mac.sh

# 2. Export Llama 3.2 1B Instruct to an ExecuTorch .pte (weights from Meta / Hugging Face,
#    placed under models/, which is gitignored)
./training/llm/export_llama.sh

# 3. Cross-compile the ExecuTorch llama runner for arm64 Android
./scripts/build_android_runner.sh

# 4. Run one prompt on a connected phone and print the answer + prefill / TTFT / decode stats
./scripts/phone_llm.sh llama3_2_1b_instruct_8da4w_ctx2048.pte training/llm/prompts/2026-10-08_v4_en_factsheet_prefill.txt
```

Model weights, exported `.pte` files, datasets and the ExecuTorch checkout are **not committed** (see `.gitignore`); the scripts recreate them.

## Repository layout

| Path | Purpose |
|---|---|
| `android-app/` | Android app (not started) |
| `pi-gateway/` | Raspberry Pi 5 gateway (not started) |
| `training/llm/` | Llama export script and the dated prompt experiments |
| `training/sms`, `training/url`, `training/network` | Detector training (not started) |
| `models/` | Exported `.pte` models (not committed) |
| `datasets/` | Datasets (not committed; download scripts will be) |
| `benchmarks/` | Benchmark results and raw CSVs |
| `scripts/` | Mac setup, Android runner build, phone runner |
| `docs/` | Plan, architecture, progress log |

## Built on

Builds on [ENTITY](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge) (the author's earlier Arm on-device LLM runtime, Apache-2.0). Its runtime policy and measurement code are being ported to ExecuTorch for this project.

## Author

**Kamalesh VS** · [github.com/kkjjkamal123](https://github.com/kkjjkamal123) · [kkjj.me](https://kkjj.me)
