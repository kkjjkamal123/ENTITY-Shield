"""Shared featurizer for the SMS and URL detectors.

The Android app re-implements this exactly (golden vectors check it), so every step is chosen to port
bit-for-bit: ASCII-only lowercasing, ASCII digits -> '0', code-point n-grams, FNV-1a over UTF-8,
log1p counts, L2 norm, then named 0/1 signals appended. Change anything here -> regenerate goldens.
"""
import math
import re

DIM = 1 << 15          # hashed n-gram slots
NGRAMS = (2, 3, 4, 5)

# ---------------------------------------------------------------- text -> hashed n-grams

def normalize(s: str) -> str:
    out = []
    for ch in s:
        o = ord(ch)
        if 65 <= o <= 90:
            out.append(chr(o + 32))
        elif 48 <= o <= 57:
            out.append("0")
        elif ch in " \t\r\n":
            out.append(" ")
        else:
            out.append(ch)
    return " " + re.sub(" +", " ", "".join(out)).strip(" ") + " "


def fnv1a32(b: bytes) -> int:
    h = 0x811C9DC5
    for x in b:
        h = ((h ^ x) * 0x01000193) & 0xFFFFFFFF
    return h


def hashed(s: str) -> dict:
    t = normalize(s)
    counts = {}
    for n in NGRAMS:
        for i in range(len(t) - n + 1):
            k = fnv1a32(t[i:i + n].encode("utf-8")) % DIM
            counts[k] = counts.get(k, 0) + 1
    vals = {k: math.log1p(c) for k, c in counts.items()}
    norm = math.sqrt(sum(v * v for v in vals.values())) or 1.0
    return {k: v / norm for k, v in vals.items()}

# ---------------------------------------------------------------- shared word lists

BRANDS = {  # brand token -> official registrable domains
    "sbi": ["sbi.co.in", "onlinesbi.sbi", "sbi.bank.in", "sbicard.com", "yonosbi.sbi"],
    "hdfc": ["hdfcbank.com", "hdfc.com", "hdfcbank.bank.in"],
    "icici": ["icicibank.com", "icici.bank.in"],
    "axis": ["axisbank.com", "axis.bank.in"],
    "kotak": ["kotak.com", "kotak.bank.in"],
    "pnb": ["pnbindia.in", "pnb.bank.in"],
    "paytm": ["paytm.com", "paytmbank.com"],
    "phonepe": ["phonepe.com"],
    "gpay": ["pay.google.com"],
    "irctc": ["irctc.co.in"],
    "indiapost": ["indiapost.gov.in"],
    "amazon": ["amazon.in", "amazon.com"],
    "flipkart": ["flipkart.com"],
    "uidai": ["uidai.gov.in"],
    "aadhaar": ["uidai.gov.in"],
    "incometax": ["incometax.gov.in"],
    "epfo": ["epfindia.gov.in"],
    "npci": ["npci.org.in"],
    "jio": ["jio.com"],
    "airtel": ["airtel.in"],
    "bescom": ["bescom.co.in"],
    "tneb": ["tnebltd.gov.in"],
    "fedex": ["fedex.com"],
    "dhl": ["dhl.com"],
    "bluedart": ["bluedart.com"],
    "whatsapp": ["whatsapp.com"],
    "netflix": ["netflix.com"],
    "paypal": ["paypal.com"],
    "apple": ["apple.com"],
    "microsoft": ["microsoft.com"],
}

SHORTENERS = {"bit.ly", "tinyurl.com", "t.co", "is.gd", "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc",
              "ow.ly", "t.ly", "s.id", "v.gd", "goo.su", "bitly.ws", "shrtco.de", "rebrand.ly"}
FREE_HOSTS = ("workers.dev", "pages.dev", "blogspot.com", "github.io", "vercel.app", "netlify.app",
              "web.app", "firebaseapp.com", "weebly.com", "wixsite.com", "000webhostapp.com",
              "glitch.me", "ngrok.io", "ngrok-free.app", "herokuapp.com", "r2.dev", "webflow.io",
              "godaddysites.com", "square.site", "carrd.co", "framer.app", "replit.app")
BAD_TLDS = {"top", "xyz", "icu", "live", "shop", "click", "buzz", "cfd", "sbs", "rest", "cc", "tk",
            "ml", "ga", "cf", "gq", "online", "site", "website", "support", "vip", "cyou", "bond",
            "lat", "monster", "quest", "life", "info", "work", "fun", "pw", "ws"}
LURE_WORDS = ("login", "verify", "secure", "update", "kyc", "account", "support", "wallet",
              "reward", "gift", "bonus", "refund", "claim", "confirm", "auth", "signin", "unlock",
              "recover", "billing", "service", "helpdesk", "customer", "offer", "free")

# ---------------------------------------------------------------- URL host signals

URL_SIGNALS = [
    "ip_host", "punycode", "many_subdomains", "long_host", "digits_in_name", "many_hyphens",
    "suspicious_tld", "shortener", "free_hosting", "brand_not_official", "lure_word",
]


def registrable(host: str) -> str:
    # shortcut: last-two-labels (three for .co.in/.gov.in style), not the Public Suffix List; fine for scoring
    parts = host.split(".")
    if len(parts) >= 3 and parts[-2] in {"co", "gov", "org", "net", "ac", "bank", "com", "nic"} and len(parts[-1]) == 2:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


def url_host(url: str) -> str:
    u = url.strip().lower()
    u = re.sub(r"^[a-z][a-z0-9+.-]*://", "", u)
    u = u.split("/")[0].split("?")[0].split("#")[0]
    u = u.rsplit("@", 1)[-1].split(":")[0]
    return u.strip(".")


def url_signals(host: str) -> list:
    labels = host.split(".")
    reg = registrable(host)
    name = labels[0] if len(labels) < 3 else ".".join(labels[:-2])
    official = {d for ds in BRANDS.values() for d in ds}
    brand_hit = any(b in host.replace("-", "").replace(".", "") for b in BRANDS)
    on_official = any(host == d or host.endswith("." + d) for d in official)
    return [
        1 if re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", host) else 0,
        1 if "xn--" in host else 0,
        1 if len(labels) >= 4 else 0,
        1 if len(host) > 30 else 0,
        1 if re.search(r"\d", reg.split(".")[0]) else 0,
        1 if host.count("-") >= 2 else 0,
        1 if labels[-1] in BAD_TLDS else 0,
        1 if reg in SHORTENERS or host in SHORTENERS else 0,
        1 if any(host == f or host.endswith("." + f) for f in FREE_HOSTS) else 0,
        1 if brand_hit and not on_official else 0,
        1 if any(w in name or w in reg.split(".")[0] for w in LURE_WORDS) else 0,
    ]

def allowlisted(host: str, allow: set) -> bool:
    """Known-good registrable domain -> skip the model. Never for free-hosting platforms, where anyone owns a subdomain."""
    if any(host == f or host.endswith("." + f) for f in FREE_HOSTS):
        return False
    return host in allow or registrable(host) in allow


# ---------------------------------------------------------------- SMS signals

SMS_SIGNALS = [
    "has_link", "urgency", "asks_secret", "money_request", "account_threat", "prize_lottery",
    "job_task", "authority_threat", "kyc_identity", "brand_link_mismatch", "shortener_link",
    "sender_mobile", "sender_dlt", "dont_share_otp", "call_number",
]

URL_RE = re.compile(r"(?:https?://|www\.)\S+|\b\d{1,3}(?:\.\d{1,3}){3}(?:/\S*)?|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|in|net|org|xyz|top|info|co|me|ly|gd|io|app|dev|site|online|live|shop|click|cc|tk)(?:/\S*)?", re.I)

_KW = {
    "urgency": r"urgent|immediately|today|within 24|in 24 ?h|last chance|expire|turant|abhi|jaldi|aaj hi|तुरंत|आज ही|अभी",
    "asks_secret": r"\botp\b|\bpin\b|\bcvv\b|password|passcode|card number|ओटीपी|पिन",
    "money_request": r"pay |payment|transfer|deposit|send rs|\bfee\b|charge|refundable|processing fee|upi|collect request|भुगतान|पैसे",
    "account_threat": r"block|suspend|deactivat|disconnect|close[d]? |freeze|band ho|बंद|ब्लॉक",
    "prize_lottery": r"\bwon\b|winner|lottery|prize|lucky draw|cashback of|reward|congratulation|जीत|इनाम|लॉटरी",
    "job_task": r"part.?time|work from home|daily income|earn rs|per day|\btasks?\b|like (and|&) subscribe|telegram|कमाएं|घर बैठे",
    "authority_threat": r"police|cbi|customs|court|arrest|narcotics|cyber cell|rbi|trai|ed officer|गिरफ्तार|पुलिस",
    "kyc_identity": r"\bkyc\b|\bpan\b|aadhaa?r|e-?kyc|केवाईसी|आधार",
}


def sms_signals(text: str, sender: str = "") -> list:
    t = text.lower()
    links = URL_RE.findall(text)
    official = {d for ds in BRANDS.values() for d in ds}
    hosts = [url_host(l if "://" in l else "http://" + l) for l in links]
    hosts = [h for h in hosts if not any(h == d or h.endswith("." + d) for d in official)]  # official links aren't a risk
    s = sender.strip().upper()
    kw = {k: 1 if re.search(p, t) else 0 for k, p in _KW.items()}
    return [
        1 if hosts else 0,
        kw["urgency"], kw["asks_secret"], kw["money_request"], kw["account_threat"],
        kw["prize_lottery"], kw["job_task"], kw["authority_threat"], kw["kyc_identity"],
        1 if any(url_signals(h)[URL_SIGNALS.index("brand_not_official")] for h in hosts) else 0,
        1 if any(url_signals(h)[URL_SIGNALS.index("shortener")] for h in hosts) else 0,
        1 if re.fullmatch(r"\+?(91)?[6-9]\d{9}", s.replace(" ", "")) else 0,
        1 if re.fullmatch(r"[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?", s) else 0,
        1 if re.search(r"(do not|don't|never) share|किसी के साथ साझा न", t) else 0,
        1 if re.search(r"call\s*(on|at|us)?\s*:?\s*\+?\d[\d -]{8,}", t) else 0,
    ]

# ---------------------------------------------------------------- full vectors

def vector(kind: str, text: str, sender: str = ""):
    """Returns (hashed dict, signals list). kind: 'sms' (text + sender) or 'url' (host only)."""
    if kind == "url":
        h = url_host(text)
        return hashed(h), url_signals(h)
    return hashed(text), sms_signals(text, sender)


def signal_names(kind: str) -> list:
    return URL_SIGNALS if kind == "url" else SMS_SIGNALS


if __name__ == "__main__":
    assert fnv1a32(b"") == 0x811C9DC5 and fnv1a32(b"a") == 0xE40C292C
    assert normalize("Pay Rs 4999 NOW") == " pay rs 0000 now "
    assert url_host("HTTPS://user@Sbi-KYC.top:8080/a?b") == "sbi-kyc.top"
    s = url_signals("sbi-kyc-update.in")
    assert s[URL_SIGNALS.index("brand_not_official")] == 1 and s[URL_SIGNALS.index("lure_word")] == 1
    assert url_signals("onlinesbi.sbi")[URL_SIGNALS.index("brand_not_official")] == 0
    sig = sms_signals("Dear customer your SBI account will be blocked today. Update KYC at http://sbi-kyc-update.in/verify", "+919876543210")
    on = {n for n, v in zip(SMS_SIGNALS, sig) if v}
    assert {"has_link", "urgency", "account_threat", "kyc_identity", "brand_link_mismatch", "sender_mobile"} <= on, on
    assert sms_signals("x", "VM-HDFCBK")[SMS_SIGNALS.index("sender_dlt")] == 1
    assert sms_signals("Pay via bescom.co.in")[0] == 0 and sms_signals("details at 42.9.66.1/x")[0] == 1
    allow = {"google.com", "blogspot.com", "sbi.bank.in"}
    assert allowlisted("pay.google.com", allow) and allowlisted("sbi.bank.in", allow)
    assert not allowlisted("sbi-kyc.blogspot.com", allow) and not allowlisted("google.com.verify.top", allow)
    print("featurize self-check ok")
