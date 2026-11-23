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
    "hdfc": ["hdfcbank.com", "hdfc.com", "hdfcbank.bank.in", "hdfc.bank.in"],
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
              "godaddysites.com", "square.site", "carrd.co", "framer.app", "replit.app",
              # v4 (0.5.4): free hosting, dynamic DNS and tunnels, where anyone can own a subdomain
              "duckdns.org", "ddns.net", "hopto.org", "zapto.org", "sytes.net", "serveo.net", "no-ip.org", "myftp.org", "twil.io", "cpanel.site", "edgeone.app", "edgeone.dev", "wasmer.app", "surge.sh", "mybluehost.me", "mytemp.website", "bolt.host", "craftum.io", "wapka.site", "us.cc", "eu.cc", "onrender.com", "fly.dev", "railway.app", "backblazeb2.com", "ipfs.dweb.link", "jimdosite.com", "webnode.page", "mystrikingly.com", "yolasite.com", "wixstudio.io", "notion.site", "gitbook.io", "typedream.app", "lovable.app", "netlify.com", "deno.dev", "trycloudflare.com", "loca.lt", "web.core.windows.net")
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
    "brand_lure",  # v3 (2026-11-19): brand as its own word + a lure word, cheap TLD or free host
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
        1 if brand_lure(host, labels, on_official) else 0,
    ]


def brand_lure(host: str, labels: list, on_official: bool) -> bool:
    """A brand written as its own word (sbi-kyc, hdfcbank-login, secure-paypal) on a domain the brand does
    not own, together with a lure word, a cheap TLD or a free host. Sharper than brand_not_official,
    which also fires on 'axis' inside 'taxis' and is common in real DNS names."""
    if on_official:
        return False
    toks = re.split(r"[.-]", host)
    body = re.split(r"[.-]", ".".join(labels[:-1]))
    if not any(t == b or t.startswith(b) or t.endswith(b) for t in toks for b in BRANDS if t):
        return False
    lure = any(w in t for t in body for w in LURE_WORDS)
    free = any(host == f or host.endswith("." + f) for f in FREE_HOSTS)
    return lure or labels[-1] in BAD_TLDS or free

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
    "prize_lottery": r"\bwon\b(?!')|winner|lottery|prize|lucky draw|cashback of|reward|congratulation|जीत|इनाम|लॉटरी",
    "job_task": r"part.?time|work from home|daily income|earn rs|per day|\btasks?\b|like (and|&) subscribe|telegram|कमाएं|घर बैठे",
    "authority_threat": r"police|cbi|customs|court|arrest|narcotics|cyber cell|rbi|trai|ed officer|गिरफ्तार|पुलिस",
    "kyc_identity": r"\bkyc\b|\bpan\b|aadhaa?r|e-?kyc|केवाईसी|आधार",
}


def is_business_sender(s: str) -> bool:
    """A registered sender ID, not a phone number: "JT-JIOPAY-S", "JM-KVBANK", or the bare "JIOPAY" some
    phones show. In India only DLT-registered businesses can send from letter IDs (v4, 2026-11-21: v3
    required the "XX-" prefix, so the same Jio alert scored as a scam on phones that drop it)."""
    s = s.strip().upper()
    return bool(re.fullmatch(r"([A-Z]{2}-)?[A-Z0-9]{3,11}(-[A-Z])?", s)) and bool(re.search(r"[A-Z]{3}", s))


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
        1 if is_business_sender(s) else 0,
        1 if re.search(r"(do not|don't|never) share|किसी के साथ साझा न", t) else 0,
        1 if re.search(r"call\s*(on|at|us)?\s*:?\s*\+?\d[\d -]{8,}", t) else 0,
    ]

def risky_hosts(text: str) -> list:
    """Link hosts in a message that are not an official brand domain (same as Featurizer.riskyHosts)."""
    official = {d for ds in BRANDS.values() for d in ds}
    hosts = [url_host(l if "://" in l else "http://" + l) for l in URL_RE.findall(text)]
    return [h for h in hosts if not any(h == d or h.endswith("." + d) for d in official)]


SECRET_REQUEST = re.compile(r"(?:\b(?:share|send|tell|give|provide|forward|enter|reply with|confirm|batao|bata do|bhejo)\b|दें|बताएं|भेजें)"
                            r"[^.!?\n]{0,40}(?:\b(?:otp|pin|cvv|password|passcode|code|card number)\b|ओटीपी|पिन)", re.I)
CODE_DELIVERED = re.compile(r"(otp|code|password|passcode|pin|कोड|ओटीपी)[^\n.!?]{0,40}?(?<![\d#,.₹])(?<!rs )(?<!rs\.)(?<!inr )(G-)?\d{4,8}(?![\d,])|(?<![\d#,.₹])(?<!rs )(?<!rs\.)(?<!inr )(G-)?\d{4,8}(?![\d,])[^\n.!?]{0,40}?(otp|code|password|passcode|pin|कोड|ओटीपी)|\b\d{3}-\d{3}\b", re.I)
CALL_TO_MOBILE = re.compile(r"(call|contact|whatsapp|dial|ring|reach|message|msg|sampark|संपर्क|कॉल)[^\d\n]{0,25}(?:\+?91[\s-]?)?[6-9]\d{4}\s?\d{5}(?!\d)", re.I)
# v5 (2026-11-23), gate only (the model's signals are unchanged). Found on the validation split:
# Hindi puts the number before the verb ("{num} पर कॉल करें"); Hindi service-cut threats; "send the code
# you received" (WhatsApp takeover); premium-rate prize scams ("call 09066...", "txt WIN to 87121");
# "Prize Code 2" read as an OTP; and "train"/"training" matching TRAI.
MOBILE_THEN_CALL = re.compile(r"(?<!\d)(?:\+?91[\s-]?)?[6-9]\d{4}\s?\d{5}(?!\d)[^\d\n]{0,12}(?:पर|pe|par|on)\s*(?:कॉल|संपर्क|व्हाट्सएप|call|contact|whatsapp)", re.I)
SERVICE_CUT = re.compile(r"काट दी|काटी जाएगी|काट दिया|कट जाएगी|बंद कर दी|बंद हो जाएगी|cut off|will be cut|banned|kaat d", re.I)
SEND_CODE = re.compile(r"\b(?:share|send|tell|give|forward|bhejo|batao)\b[^.!?\n]{0,40}\bcode\b", re.I)
PRIZE_CLAIM = re.compile(r"\b(?:win|won|winner|prize|awarded|claim|cash|jackpot|free entry|selected)\b|£\s?\d|congrat|जीत|इनाम|लॉटरी", re.I)
PREMIUM_CALL = re.compile(r"(?<!\d)0(?:9\d{9}|8\d{9,10})(?!\d)|\b(?:txt|text|send|reply)\b\W+\w+\W+(?:to\W+)?\d{5}(?!\d)", re.I)
AUTHORITY = re.compile(r"police|\bcbi\b|customs|\bcourt\b|arrest|narcotics|cyber cell|\brbi\b|\btrai\b|ed officer|गिरफ्तार|पुलिस", re.I)
PRESS_KEY = re.compile(r"\bpress \d\b|\bdial \d\b|\btalk to\b", re.I)  # IVR scams: "press 1 to talk to the officer"
CONTACT_ASK = re.compile(r"\b(?:reply|send|whatsapp|contact|sms|text|txt|click|visit|open|download|install)\b|भेजें|संपर्क|क्लिक", re.I)
TXN_ALERT = re.compile(r"\b(?:sent|debited|credited|received|paid|spent)\b[^\n]{0,60}\b(?:ref|txn|utr|upi ref|avl bal|a/c)\b", re.I)
ADVISORY = re.compile(r"\bnever\b[^.!?\n]{0,30}\b(?:sends?|asks?|calls?|makes?|requests?)\b|कभी नहीं", re.I)
DEEP_LINKS = ("app.link", "onelink.me", "page.link", "smart.link", "go.link", "lnk.to", "branch.io", "adj.st")


def brand_owns(host: str, sender: str, text: str) -> bool:
    """The link belongs to the brand that sent or names the message: a bare sender ID ("BYJUS" ->
    byjus.onelink.me, "PEPPER" -> pepperfry.onelink.me) or a deep link whose subdomain is the brand the
    text names ("Snitch की सेल" -> snitch.onelink.me). Never true for shorteners."""
    h = host.lower()
    sub = h.split(".")[0]
    if any(h == d or h.endswith("." + d) for d in DEEP_LINKS):
        name = sub
    else:
        name = registrable(h).split(".")[0]
    if len(name) < 3:
        return False
    tag = sender.strip().upper()
    tag = re.sub(r"^[A-Z]{2}-", "", tag)
    tag = re.sub(r"-[A-Z]$", "", tag).lower()
    if is_business_sender(sender) and len(tag) >= 3:
        k = min(4, len(tag), len(name))
        if name.startswith(tag[:k]) or tag.startswith(name[:k]):
            return True
    if any(h == d or h.endswith("." + d) for d in DEEP_LINKS):
        return re.search(r"(?<![a-z])" + re.escape(name) + r"(?![a-z])", text.lower()) is not None
    return False


PROMO_WORDS = re.compile(r"\b(?:sale|offers?|discount|cashback|coupon|code|deals?|shop|order now|free delivery|t&c|"
                         r"opt.?out|sms stop|bogo|buy 1|recharge|subscription|apply now|book now)\b|% ?off|%|छूट|सेल", re.I)


# App deep-link and attribution services: brands' marketing links go through them. Like a shortener,
# the destination is not visible, so on their own they are not evidence of fraud.
LINK_SERVICES = ("app.link", "onelink.me", "page.link", "smart.link", "go.link", "lnk.to", "branch.io", "adj.st")


GOV_DOMAINS = ("gov.in", "nic.in")  # only government bodies can register these


def sender_owns(host: str, sender: str) -> bool:
    """A DLT header (VM-HDFCBK-S) and a link on the same brand's own short domain (hdfcbk.io)."""
    parts = sender.strip().upper().split("-")
    if len(parts) < 2 or not re.fullmatch(r"[A-Z]{2}", parts[0]):
        return False
    tag = parts[1].lower()
    name = registrable(host).split(".")[0]
    k = min(4, len(tag), len(name))
    return k >= 3 and (name.startswith(tag[:k]) or tag.startswith(name[:k]))


def sms_gate(band: str, sig: dict, scam_hosts: list, text: str, sender: str = "", hosts: list = ()):
    """v4 (2026-11-21): SCAM needs a fraud-specific cue, not just "sounds like marketing".

    The model scores promotional spam high: links, offers, "win", deadlines and phone numbers are in
    promos as much as in fraud. Fraud is something more specific: asking for a secret, a threat (police,
    block, disconnect) tied to a link or a personal number, a brand look-alike link, a task job, a
    risky link by the URL model. Without one of those:
      - a registered business sender (DLT header) is SAFE, labelled PROMO when it reads like marketing
      - any other sender is at most UNCERTAIN (still listed, no "scam" alarm)
    Returns (band, family or None). Same rules as Detectors.gate() in the app.
    """
    t = text.lower()
    has_link = sig["has_link"]
    own = lambda h: sender_owns(h, sender) or any(h == d or h.endswith("." + d) for d in LINK_SERVICES + GOV_DOMAINS)
    link_scam = any(not own(h) for h in scam_hosts)
    lookalike = sig["brand_link_mismatch"] and any(not own(h) for h in hosts)
    # The business shortcut needs the full DLT form ("JT-JIOPAY-S"). A bare letter ID ("JIOPAY") still
    # counts for the model, but international gateways can spoof those, so it goes the stricter way.
    dlt = sig["sender_dlt"] and bool(re.match(r"[A-Z]{2}-", sender.strip().upper()))
    mobile_no = bool(CALL_TO_MOBILE.search(text) or MOBILE_THEN_CALL.search(text))  # a personal number to call: not the user's own number in an alert
    prize = bool(PRIZE_CLAIM.search(text))
    # an OTP given to you is normal; asking you to hand one over is the fraud
    delivered = bool(CODE_DELIVERED.search(text)) and not prize  # the code itself is in the message ("Prize Code 2" is not one)
    secret = not sig["dont_share_otp"] and not delivered and ((sig["asks_secret"] and bool(SECRET_REQUEST.search(t))) or bool(SEND_CODE.search(t)))
    advisory = bool(ADVISORY.search(text))  # "TRAI never calls to disconnect...": a warning, not a threat
    threat = (sig["account_threat"] or bool(SERVICE_CUT.search(text))) and not advisory
    pressure = threat or sig["money_request"] or sig["prize_lottery"] or sig["kyc_identity"] or sig["authority_threat"] or "refund" in t
    # police/court words only count with something to do: chat mentions "police" and "train"
    authority = bool(AUTHORITY.search(text)) and not advisory and (has_link or sig["call_number"] or mobile_no or sig["money_request"] or secret or sig["urgency"] or sig["kyc_identity"] or bool(PRESS_KEY.search(text)))
    premium_prize = prize and bool(PREMIUM_CALL.search(text))
    if dlt:  # registered senders: advisories and bank alerts name TRAI, KYC, PIN all the time
        strong = secret or lookalike or link_scam or (mobile_no and pressure)
    else:
        strong = (secret or authority or premium_prize or lookalike or link_scam or sig["job_task"]
                  or (sig["kyc_identity"] and (has_link or sig["call_number"] or mobile_no))
                  or (threat and has_link)
                  or (mobile_no and pressure))
    promo = "PROMO" if PROMO_WORDS.search(t) else None
    # v5: cues that only fraud has. Not from a registered sender, they make it a scam whatever the model says:
    # asking you to hand over a code, a look-alike or phishing link, a prize behind a premium-rate number,
    # a service-cut threat with a personal number to call.
    certain = not dlt and (secret or lookalike or link_scam or premium_prize or (mobile_no and threat))
    if certain:
        return "SCAM", None
    if strong:  # a fraud-specific cue: never fully SAFE, even when the model is unsure
        return ("UNCERTAIN" if band == "SAFE" else band), None
    if band == "SAFE":
        return band, None
    # v5: no link, no number, nothing to reply to or install, no prize: nothing a scammer could use
    # ("Your Jio plan expires on 16 Nov. Recharge with Rs 299 on MyJio app")
    if not has_link and not sig["call_number"] and not mobile_no and not prize and not sig["prize_lottery"] and not CONTACT_ASK.search(text):
        return "SAFE", None
    # v5: a payment receipt ("Rs 500 sent to X. Ref 1234. Not you? Call 1800...") reports, it asks for nothing
    if TXN_ALERT.search(text) and not has_link and not mobile_no:
        return "SAFE", None
    # v5: every link is a government site (gov.in, nic.in: only government bodies can register those)
    if has_link and hosts and all(any(h == d or h.endswith("." + d) for d in GOV_DOMAINS) for h in hosts):
        return "SAFE", None
    # v5: every link goes to the brand that sent or names the message (byjus.onelink.me from "BYJUS")
    if has_link and hosts and not sig["shortener_link"] and all(brand_owns(h, sender, text) for h in hosts):
        return "SAFE", promo
    if delivered and not has_link:  # "Your Discord code is 123456": a login code, from any kind of sender
        return "SAFE", None
    if dlt:
        return "SAFE", promo
    weak = sig["prize_lottery"] or sig["money_request"] or (sig["urgency"] and has_link) or (sig["sender_mobile"] and has_link)
    if not weak and not has_link and not sig["call_number"]:  # nothing to click, call or pay: an ordinary message
        return "SAFE", None
    if band == "SCAM" and not weak:
        return "UNCERTAIN", promo
    return band, None


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
