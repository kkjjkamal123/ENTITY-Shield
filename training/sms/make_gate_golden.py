"""Writes training/sms/gate_golden.json: the scam gate's answers, which the app's ScamGate must match.

  .venv/Scripts/python training/sms/make_gate_golden.py

Inputs: the challenge set plus a sample of generated Indian SMS. Each message is checked with every
model band (SAFE/UNCERTAIN/SCAM) and with its links either all judged phishing by the URL model or not.
"""
import json
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import featurize as F  # noqa: E402
import challenge  # noqa: E402
import indian_sms  # noqa: E402

rows = [dict(text=r["text"], sender=r["sender"]) for r in challenge.rows()]
gen = indian_sms.generate()
rows += [dict(text=r["text"], sender=r["sender"]) for r in random.Random(11).sample(gen, 300)]
rows += [dict(text=t, sender=s) for s, t in [
    ("JT-JioPay-S", "ATTENTION!\n\n90% of daily data used as on 21-Nov-26 10:15 !\n\nJio Number : 9876543210"),
    ("+919876543210", "Dear Customer, +919812345678 is now available to take calls."),
    ("57575701", "Your Discord security code is: 482913"),
    ("+917654321098", "Airtel: you have won a Rs 5000 recharge in our lucky draw. Send the OTP you received to claim the prize."),
    ("JA-ISATHI-G", "Want to know number of SIMs in your name? Download Sanchar Saathi: https://sancharsaathi.gov.in/app"),
    ("", "आपका खाता आज ब्लॉक हो जाएगा, तुरंत OTP बताएं 9876543210 पर कॉल करें"),
    ("VM-BLRPWR", "Dear consumer, your electricity connection will be disconnected tonight. Contact officer 8167429301"),
    ("JD-MYNTRA-P", "मेगा सेल! 70% तक की छूट। अभी खरीदें: https://myntr.it/abc"),
    # v5 cues
    ("+916590705813", "प्रिय उपभोक्ता, आज रात आपकी बिजली काट दी जाएगी। बिल अपडेट नहीं है। तुरंत +916590705813 पर कॉल करें"),
    ("+919876543210", "Your WhatsApp will be banned in 2 hours. Send the 6 digit code you received to this number to verify"),
    ("", "Congratulations YOU'VE Won. You're a Winner in our August £1000 Prize Draw. Call 09066660100 NOW. Prize Code 2309"),
    ("", "Free entry in 2 a wkly comp to win FA Cup final tkts 21st May 2005. Text FA to 87121 to receive entry question"),
    ("", "Am on a train back from northampton so i'm afraid not!"),
    ("", "Did you get that police abstract?"),
    ("PAYTMB", "Rs 2500 sent to Divya from your UPI. Ref 582181509304. Not you? Call 1800-859-2959"),
    ("BYJUS", "Last chance to grab 60% OFF on annual plans! Offer expires in 24 hours. Upgrade: https://byjus.onelink.me/38755579"),
    ("", "Snitch की सेल शुरू! हर ऑर्डर पर 60% तक की छूट। अभी खरीदें: snitch.onelink.me/17633890"),
    ("", "TRAI never sends any message or makes any call for verification or disconnection of mobile numbers. Report such calls at https://sancharsaathi.gov.in"),
    ("+918888888888", "Rs 5000 credited to your account by mistake. Ref 12345. Please return it, call 9876543210"),
    ("", "Win cash now! Call 9876543210 to claim your prize"),
]]
uci = [l.rstrip("\n").split("\t", 1)[1] for l in open(HERE.parent.parent / "datasets" / "uci_sms" / "SMSSpamCollection", encoding="utf-8")]
rows += [dict(text=t, sender="") for t in random.Random(5).sample(uci, 400)]
out = []
for r in rows:
    sig = F.sms_signals(r["text"], r["sender"])
    sd = dict(zip(F.SMS_SIGNALS, sig))
    hosts = F.risky_hosts(r["text"])
    for band in ("SAFE", "UNCERTAIN", "SCAM"):
        for scam_hosts in ([], hosts) if hosts else ([],):
            b, fam = F.sms_gate(band, sd, scam_hosts, r["text"], r["sender"], hosts)
            out.append(dict(text=r["text"], sender=r["sender"], signals=sig, hosts=hosts, band_in=band,
                            scam_hosts=scam_hosts, band=b, promo=fam == "PROMO"))
(HERE / "gate_golden.json").write_text(json.dumps(out, ensure_ascii=False, indent=0), encoding="utf-8", newline="\n")
print(len(out), "cases")
