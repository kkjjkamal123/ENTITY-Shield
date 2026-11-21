# Demo: video script (Phase 1) and live-finals script

Target length **3:50**. Real phone screen (`adb shell screenrecord`), no mock-ups. Every number shown on
screen is measured live. Captions are burned in, so the video works without sound; voice-over is optional.

## Setup (before recording)

- **Phones:**
  - Infinix X6878 (SIMs, real SMS): Shield installed, the Llama model in `/data/local/tmp/shield`.
  - CMF Phone 1: Shield + `beaconsim`, for the network shots.
- **Both phones:**
  - Screen lock off; battery above 60%.
  - Do Not Disturb off; notification sounds on.
  - Language EN; Network Guard off at the start.
- **Laptop:** on the same Wi-Fi as the CMF, with `scripts/lan_scan_demo.py` ready.
- **Record each scene separately** with `adb shell screenrecord --bit-rate 8000000 /sdcard/sceneN.mp4`
  (180 s limit per file), then `adb pull` and cut with ffmpeg.

## Video shot list

| # | Time | Shot (what happens on screen) | Caption |
|---|---|---|---|
| 1 | 0:00–0:15 | Home dashboard: shield orb, "You're protected", guards, the on-device AI card (SoC, cores, i8mm, ExecuTorch backends). Pull down quick settings: **airplane mode ON**. | "ENTITY Shield · every model runs on this phone · airplane mode on" |
| 2 | 0:15–0:50 | A real scam SMS arrives (sent from the second SIM: KYC/SBI lookalike link). The notification pops within a second; tap it. Red banner, score ring, the highlighted link and urgency words, warning-sign chips. The "Why" card streams Llama's sentence. | "Flagged in milliseconds by an ExecuTorch detector · explained by Llama 3.2 1B on ExecuTorch, offline" |
| 3 | 0:50–1:10 | Tap **EN · हिं**: the same alert in Hindi. Tap **Read aloud**: Hindi TTS. Scroll: fixed steps, Block sender, Call 1930, Report. | "Hindi explanation · the steps are fixed and checked, never generated" |
| 4 | 1:10–1:30 | Ask Shield: "I already clicked the link. What now?" The answer streams. | "Follow-up answers stay inside the checked advice" |
| 5 | 1:30–1:50 | WhatsApp or Chrome: long-press a suspicious link → Share / **Check link (Shield)**: verdict. A real bank link → green. | "Check any link or text from any app" |
| 5b | 1:50–2:10 | A normal OTP SMS and a Jio recharge alert arrive: no alert, both listed as safe. Open a flagged promo → **Not a scam**: the next message of that pattern is not flagged. Message from a saved contact: safe unless it carries a known-scam link. | "Built to stay quiet on OTPs, carrier alerts and your contacts · learns from your corrections, on the phone" |
| 5c | 2:10–2:20 | More → **Always-on protection**: battery, auto-start and Always-on VPN all green. | "Keeps protecting when the app is closed, after a reboot and after an update" |
| 6 | 2:20–2:50 | Airplane mode OFF, Wi-Fi on. Network screen → **Turn on Network Guard** (radar). Open **Beacon Simulator (test)** → "DGA burst" → back to Shield: live lookups go red (BLOCK), alert "Beacon Simulator (test) hunting for a hidden server" → **Remove app**. | "Network Guard: every app's DNS lookups checked on the phone · malware behaviour caught and named" |
| 7 | 2:50–3:10 | Laptop on the same Wi-Fi runs `lan_scan_demo.py <phone-ip>` (split screen). The tripwire alert "Someone on this Wi-Fi is probing your phone" arrives within seconds. | "LAN tripwire: port scans and ADB-worm probes on public Wi-Fi" |
| 8 | 3:10–3:30 | Lab → Bench result card (time to first warning, Llama TTFT cold vs pre-warmed); the thread-ablation chart. | "Measured on two Arm SoCs: Dimensity 7300 and Snapdragon 7s Gen 3" |
| 9 | 3:30–3:50 | Architecture diagram + "Nothing leaves the phone". End card: repo URL. | "ENTITY Shield · Team ENTITY · Bharat AI-SoC 2026–27 · PS3" |

## Live finals (15–16 Feb): script and backups

1. **Start on the Home screen in airplane mode** (30 s): name the thesis and point to the AI card.
2. **Demo SMS** from Lab → Demo, *not* a real SMS. It's the same pipeline but doesn't depend on the
   carrier during a live call. Then switch to Hindi and read aloud.
3. **Network Guard** with `beaconsim` (a DGA burst takes 10 s).
4. **Tripwire** only if the venue Wi-Fi allows peer traffic. Otherwise show the recorded clip.
5. **Numbers** in Lab → Bench, run live (about 60 s).

| Backup for… | Plan |
|---|---|
| Phone dies | Second phone with the same build and model |
| Llama too slow because the phone is hot | 8da4w model (faster), then the recorded clip |
| Screen-share failure | scrcpy over USB on the laptop, or the recorded video |

### Likely jury questions (short answers)

- **Why not let Llama classify?** A 1B LLM is slower (seconds vs milliseconds) and harder to calibrate.
  It never sees most messages. The detectors are measured at ≤ 1% false alarms on validation. Llama's
  job is the part people need: *why*, in their language.
- **Can Llama make up advice?** The steps are fixed text per scam family. The verdict is written by the
  app before Llama starts (verdict prefill). An automatic check flags invented links or numbers
  (`training/llm/eval_explanations.py`).
- **Why is the network part on the phone?** Most Indian users' only computer is the phone. Network
  Guard sees the phone's own apps (which app looked up what) and detects LAN probes. A home gateway
  is Phase 2.
- **Battery and heat?** Detection costs about 1–5 ms per message. Llama runs only when you open an
  alert. The DNS VPN forwards only lookups. Sustained back-to-back Llama answers (speed, CPU and
  battery temperature, memory) are in the report, §7b (`scripts/bench_sustained.py`).
- **Is my data kept?** Message text stays on the phone and deletes itself (safe after 7 days, alerts
  after 30). Nothing is uploaded. See `docs/PRIVACY.md`.
- **Why ExecuTorch and not llama.cpp?** PS3 requires ExecuTorch for the Llama component. We measured
  both on the same phone. ExecuTorch is level on decode, and its XNNPACK/KleidiAI path benefits from
  i8mm on the Armv9 phone.
- **False positives?** These numbers are measured, not claimed:
  - held-out Indian templates the model never saw: when the app says "scam" it is right 97% of the
    time, 1.0% of normal messages are called scams (4.9% counting "be careful"); UCI: 0.55%
    (`scripts/eval_sms_heldout.py`, F1 and confusion matrices in the report);
  - a time-split phishing feed;
  - 41 official Indian domains: 0 false alarms;
  - the real-inbox audit on the team's own phone.
