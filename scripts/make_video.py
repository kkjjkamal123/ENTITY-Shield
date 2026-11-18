"""Assembles the Phase 1 demo video from the raw phone recordings (scripts/record_demo.py).

  python scripts/make_video.py        -> demo/ENTITY-Shield-demo.mp4 (1920x1080, H.264)

Layout: the real phone screen on the left, timed captions on the right, title and end cards.
Every frame of the phone is a real recording from the phones; nothing is mocked.
"""
import os
import shutil
import subprocess
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "demo" / "raw"
WORK = ROOT / "demo" / "build"
OUT = ROOT / "demo" / "ENTITY-Shield-demo.mp4"
FFMPEG = os.environ.get("FFMPEG") or shutil.which("ffmpeg") or str(next(Path.home().glob(
    "AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg*/ffmpeg-*/bin/ffmpeg.exe")))
BG = "0x070B16"

# scene: clip, keep-ranges (seconds), captions [(start, end, title, body)], device line
SCENES = [
    ("s1_sms_alert.mp4", [(0, 42)], "Infinix X6878 · Snapdragon 7s Gen 3", [
        (0, 4.5, "Airplane mode on", "Everything below runs on this phone's Arm CPU. No cloud, no network."),
        (4.5, 10, "A scam message comes in", "A test SMS sent through the app's own receive pipeline (no carrier in airplane mode). Two ExecuTorch detectors flag it in milliseconds."),
        (10, 22, "Llama 3.2 1B explains, on the phone", "Runs through ExecuTorch (XNNPACK) inside the app. First token in about one second."),
        (22, 34, "The same alert in Hindi", "Llama writes only the 'why'. The verdict and the steps are fixed, checked text."),
        (34, 42, "Steps that act", "Block the sender, call 1930, report on cybercrime.gov.in, or ask a follow-up question."),
    ]),
    ("s2_network.mp4", [(0, 19), (31, 51)], "Infinix X6878 · Snapdragon 7s Gen 3", [
        (0, 8, "Network Guard", "A local DNS-only VPN. Every app's lookups are checked on the phone, and named by app."),
        (8, 19, "A test app acts like malware", "'Beacon Simulator (test)' fires a DGA burst and hides data in DNS lookups. Made-up names only."),
        (19, 39, "Caught and named", "Shield blocks the lookups, names the app, explains the threat, and offers to remove the app."),
    ]),
    ("s3_tripwire.mp4", [(2, 35)], "CMF Phone 1 · Dimensity 7300", [
        (0, 9, "LAN tripwire", "Decoy ports a phone never uses. Any contact means someone on the Wi-Fi is probing it."),
        (9, 33, "This laptop scans the phone", "Port scan over the shared Wi-Fi: the dashboard turns red and the alert names the attacker's IP."),
    ]),
    ("s4_bench.mp4", [(0, 20)], "Infinix X6878 · in-app benchmark", [
        (0, 20, "Measured on the phone", "Detector parity 43/43 and 40/40. Warning posted in 12 ms (p50). Llama first token 1.0 s pre-warmed vs 1.9 s cold."),
    ]),
]
TITLE = ("ENTITY Shield", "On-device threat, phishing and scam detection on Arm",
         "Bharat AI-SoC Challenge 2026–27 · Problem Statement 3 · Team ENTITY")
END = ("Detectors decide. Llama explains.", "Nothing leaves the phone.",
       "Llama 3.2 1B on ExecuTorch · Snapdragon 7s Gen 3 · Dimensity 7300", "github.com/kkjjkamal123/ENTITY-Shield   ·   Built with Llama")


def txt(name, s):
    (WORK / name).write_text(s, encoding="utf-8", newline="\n")
    return name


def run(args):
    r = subprocess.run([FFMPEG, "-v", "error", "-y", *args], cwd=WORK, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(r.stderr[-2000:])


def card(name, lines, secs):
    f = [f"drawtext=fontfile=segoeuib.ttf:textfile={txt(name + '_0.txt', lines[0])}:fontsize=88:fontcolor=white:x=(w-text_w)/2:y=380"]
    y = 520
    for i, l in enumerate(lines[1:], 1):
        f.append(f"drawtext=fontfile=segoeui.ttf:textfile={txt(f'{name}_{i}.txt', l)}:fontsize={46 if i == 1 else 34}:"
                 f"fontcolor={'0x22D3EE' if i == 1 else '0x9AA6C0'}:x=(w-text_w)/2:y={y}")
        y += 80 if i == 1 else 60
    run(["-f", "lavfi", "-i", f"color=c={BG}:s=1920x1080:r=30:d={secs}", "-vf", ",".join(f) + ",fade=t=in:st=0:d=0.6,fade=t=out:st=" + str(secs - 0.6) + ":d=0.6",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", f"{name}.mp4"])
    return f"{name}.mp4"


def scene(k, clip, keep, device, caps):
    sel = "+".join(f"between(t,{a},{b})" for a, b in keep)
    dur = sum(b - a for a, b in keep)
    f = [f"[0:v]fps=30,select='{sel}',setpts=N/30/TB,scale=-2:1000,setsar=1[ph]",
         f"[1:v][ph]overlay=x=200:y=40:shortest=1",
         f"drawtext=fontfile=segoeuib.ttf:textfile={txt(f's{k}_hdr.txt', 'ENTITY SHIELD')}:fontsize=30:fontcolor=0x22D3EE:x=820:y=110",
         f"drawtext=fontfile=segoeui.ttf:textfile={txt(f's{k}_dev.txt', device)}:fontsize=26:fontcolor=0x5F6B85:x=820:y=960"]
    for j, (a, b, title, body) in enumerate(caps):
        en = f"enable='between(t,{a},{b})'"
        f.append(f"drawtext=fontfile=segoeuib.ttf:textfile={txt(f's{k}_{j}_t.txt', title)}:fontsize=60:fontcolor=white:x=820:y=210:{en}")
        f.append(f"drawtext=fontfile=segoeui.ttf:textfile={txt(f's{k}_{j}_b.txt', chr(10).join(textwrap.wrap(body, 40)))}:fontsize=40:"
                 f"fontcolor=0xC9D1E3:line_spacing=16:x=820:y=320:{en}")
    run(["-i", str(RAW / clip), "-f", "lavfi", "-i", f"color=c={BG}:s=1920x1080:r=30:d={dur}", "-filter_complex", ";".join(f[:1]) + ";" + ",".join(f[1:]),
         "-t", str(dur), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", "-an", f"scene{k}.mp4"])
    return f"scene{k}.mp4"


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    for fnt in ("segoeui.ttf", "segoeuib.ttf"):
        shutil.copy(Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / fnt, WORK / fnt)
    parts = [card("title", TITLE, 4)]
    for k, (clip, keep, device, caps) in enumerate(SCENES, 1):
        if (RAW / clip).exists():
            parts.append(scene(k, clip, keep, device, caps))
            print("built", parts[-1])
    parts.append(card("end", END, 6))
    (WORK / "list.txt").write_text("".join(f"file '{p}'\n" for p in parts), encoding="utf-8")
    run(["-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy", str(OUT)])
    print("video:", OUT)


if __name__ == "__main__":
    main()
