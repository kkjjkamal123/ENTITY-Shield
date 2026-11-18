# Third-party notices

## Llama 3.2 — Built with Llama

ENTITY Shield runs **Llama 3.2 1B Instruct** (Meta's quantization-aware-trained `QLORA_INT4_EO8`
checkpoint, and our own 8da4w export of the base instruct model) on the phone through ExecuTorch.

> Llama 3.2 is licensed under the Llama 3.2 Community License, Copyright © Meta Platforms, Inc. All
> Rights Reserved.

The model files are not stored in this repository. Anyone downloading them must accept the Llama 3.2
Community License and the Acceptable Use Policy on Hugging Face (`meta-llama/Llama-3.2-1B-Instruct`,
`meta-llama/Llama-3.2-1B-Instruct-QLORA_INT4_EO8`). The exported `.pte` files published with a release
carry the license text and this notice.

## Runtime

| Component | License | Use |
|---|---|---|
| [ExecuTorch](https://github.com/pytorch/executorch) 1.5.1 (`org.pytorch:executorch-android`) | BSD-3-Clause | On-device runtime for Llama and both detectors |
| XNNPACK | BSD-3-Clause | CPU backend inside ExecuTorch |
| Arm KleidiAI | Apache-2.0 | Arm-optimized micro-kernels used by XNNPACK |
| fbjni, SoLoader | Apache-2.0 / MIT | ExecuTorch Android dependencies |
| Jetpack Compose, AndroidX | Apache-2.0 | App UI |
| PyTorch 2.14, torchao | BSD-3-Clause | Training and export (development machine only) |

## Data (training and evaluation only; not redistributed in this repository)

| Dataset | License / terms | Use |
|---|---|---|
| [UCI SMS Spam Collection](https://archive.ics.uci.edu/dataset/228/sms+spam+collection) | CC BY 4.0 | SMS detector training/test |
| [PhishTank](https://phishtank.org) online-valid feed | PhishTank terms of use | URL detector (time-split) |
| [OpenPhish](https://openphish.com) community feed | OpenPhish community terms | Extra recall-only URL test |
| [Tranco list](https://tranco-list.eu) | Free for research | Benign domains and allow-list |
| Indian scam/legit SMS templates (`training/sms/indian_sms.py`) | Written by Team ENTITY (Apache-2.0) | Indian scam families, template-disjoint test |

## Reused code

Parts of the device probing, power measurement and benchmark method come from
[ENTITY](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge) (Apache-2.0),
the author's earlier on-device LLM runtime for the Arm Create AI Optimization Challenge.
