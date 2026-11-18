package app.entity.shield

import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * Exact port of training/featurize.py. Golden vectors (FeaturizerTest, in-app self-test) check it.
 * Any change here must be made in the Python file too, then the detectors retrained.
 */
object Featurizer {
    const val DIM = 1 shl 15
    private val NGRAMS = intArrayOf(2, 3, 4, 5)
    private val SPACES = Regex(" +")

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length + 2)
        s.codePoints().forEach { cp ->
            when {
                cp in 65..90 -> sb.appendCodePoint(cp + 32)
                cp in 48..57 -> sb.append('0')
                cp == 32 || cp == 9 || cp == 13 || cp == 10 -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        return " " + SPACES.replace(sb.toString(), " ").trim(' ') + " "
    }

    fun fnv1a32(b: ByteArray): Long {
        var h = 0x811C9DC5L
        for (x in b) h = ((h xor (x.toLong() and 0xFF)) * 0x01000193L) and 0xFFFFFFFFL
        return h
    }

    /** Slot -> value, in first-seen order (same order as the Python dict, so the norm sums identically). */
    fun hashed(s: String): LinkedHashMap<Int, Double> {
        val cps = normalize(s).codePoints().toArray()
        val counts = LinkedHashMap<Int, Int>()
        for (n in NGRAMS) for (i in 0..cps.size - n) {
            val k = (fnv1a32(String(cps, i, n).toByteArray(Charsets.UTF_8)) % DIM).toInt()
            counts[k] = (counts[k] ?: 0) + 1
        }
        val vals = LinkedHashMap<Int, Double>()
        var sq = 0.0
        for ((k, c) in counts) ln1p(c.toDouble()).also { vals[k] = it }
        for (v in vals.values) sq += v * v
        val norm = sqrt(sq).let { if (it == 0.0) 1.0 else it }
        for (k in vals.keys) vals[k] = vals[k]!! / norm
        return vals
    }

    // ------------------------------------------------------------ fast path (production)

    private val scratch = ThreadLocal.withInitial { IntArray(DIM) }

    /**
     * Same slots and values as [hashed], in the same first-seen order, but without a String and a
     * ByteArray per n-gram: the FNV-1a hash is fed the UTF-8 bytes of each code point directly and the
     * counts live in a reused scratch array. This took the SMS check from ~9 ms to ~1 ms on the A78.
     */
    fun hashedSparse(s: String): Pair<IntArray, DoubleArray> {
        val cps = normalize(s).codePoints().toArray()
        val counts = scratch.get()
        val order = IntArray(cps.size * NGRAMS.size)
        var m = 0
        for (n in NGRAMS) for (i in 0..cps.size - n) {
            var h = 0x811C9DC5L
            for (k in i until i + n) {
                val cp = cps[k]
                if (cp < 0x80) {
                    h = ((h xor cp.toLong()) * 0x01000193L) and 0xFFFFFFFFL
                } else if (cp < 0x800) {
                    h = ((h xor (0xC0 or (cp shr 6)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                    h = ((h xor (0x80 or (cp and 0x3F)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                } else if (cp in 0xD800..0xDFFF) {
                    h = ((h xor 0x3FL) * 0x01000193L) and 0xFFFFFFFFL // lone surrogate encodes as '?', like String.toByteArray
                } else if (cp < 0x10000) {
                    h = ((h xor (0xE0 or (cp shr 12)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                    h = ((h xor (0x80 or ((cp shr 6) and 0x3F)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                    h = ((h xor (0x80 or (cp and 0x3F)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                } else {
                    h = ((h xor (0xF0 or (cp shr 18)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                    h = ((h xor (0x80 or ((cp shr 12) and 0x3F)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                    h = ((h xor (0x80 or ((cp shr 6) and 0x3F)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                    h = ((h xor (0x80 or (cp and 0x3F)).toLong()) * 0x01000193L) and 0xFFFFFFFFL
                }
            }
            val slot = (h % DIM).toInt()
            if (counts[slot] == 0) order[m++] = slot
            counts[slot]++
        }
        val vals = DoubleArray(m)
        var sq = 0.0
        for (j in 0 until m) vals[j] = ln1p(counts[order[j]].toDouble())
        for (j in 0 until m) sq += vals[j] * vals[j]
        val norm = sqrt(sq).let { if (it == 0.0) 1.0 else it }
        for (j in 0 until m) { vals[j] = vals[j] / norm; counts[order[j]] = 0 }
        return order.copyOf(m) to vals
    }

    // ------------------------------------------------------------ word lists (same as Python)

    val BRANDS: Map<String, List<String>> = linkedMapOf(
        "sbi" to listOf("sbi.co.in", "onlinesbi.sbi", "sbi.bank.in", "sbicard.com", "yonosbi.sbi"),
        "hdfc" to listOf("hdfcbank.com", "hdfc.com", "hdfcbank.bank.in"),
        "icici" to listOf("icicibank.com", "icici.bank.in"),
        "axis" to listOf("axisbank.com", "axis.bank.in"),
        "kotak" to listOf("kotak.com", "kotak.bank.in"),
        "pnb" to listOf("pnbindia.in", "pnb.bank.in"),
        "paytm" to listOf("paytm.com", "paytmbank.com"),
        "phonepe" to listOf("phonepe.com"),
        "gpay" to listOf("pay.google.com"),
        "irctc" to listOf("irctc.co.in"),
        "indiapost" to listOf("indiapost.gov.in"),
        "amazon" to listOf("amazon.in", "amazon.com"),
        "flipkart" to listOf("flipkart.com"),
        "uidai" to listOf("uidai.gov.in"),
        "aadhaar" to listOf("uidai.gov.in"),
        "incometax" to listOf("incometax.gov.in"),
        "epfo" to listOf("epfindia.gov.in"),
        "npci" to listOf("npci.org.in"),
        "jio" to listOf("jio.com"),
        "airtel" to listOf("airtel.in"),
        "bescom" to listOf("bescom.co.in"),
        "tneb" to listOf("tnebltd.gov.in"),
        "fedex" to listOf("fedex.com"),
        "dhl" to listOf("dhl.com"),
        "bluedart" to listOf("bluedart.com"),
        "whatsapp" to listOf("whatsapp.com"),
        "netflix" to listOf("netflix.com"),
        "paypal" to listOf("paypal.com"),
        "apple" to listOf("apple.com"),
        "microsoft" to listOf("microsoft.com"),
    )
    val OFFICIAL: Set<String> = BRANDS.values.flatten().toSet()

    private val SHORTENERS = setOf("bit.ly", "tinyurl.com", "t.co", "is.gd", "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc",
        "ow.ly", "t.ly", "s.id", "v.gd", "goo.su", "bitly.ws", "shrtco.de", "rebrand.ly")
    val FREE_HOSTS = listOf("workers.dev", "pages.dev", "blogspot.com", "github.io", "vercel.app", "netlify.app",
        "web.app", "firebaseapp.com", "weebly.com", "wixsite.com", "000webhostapp.com",
        "glitch.me", "ngrok.io", "ngrok-free.app", "herokuapp.com", "r2.dev", "webflow.io",
        "godaddysites.com", "square.site", "carrd.co", "framer.app", "replit.app")
    private val BAD_TLDS = setOf("top", "xyz", "icu", "live", "shop", "click", "buzz", "cfd", "sbs", "rest", "cc", "tk",
        "ml", "ga", "cf", "gq", "online", "site", "website", "support", "vip", "cyou", "bond",
        "lat", "monster", "quest", "life", "info", "work", "fun", "pw", "ws")
    private val LURE_WORDS = listOf("login", "verify", "secure", "update", "kyc", "account", "support", "wallet",
        "reward", "gift", "bonus", "refund", "claim", "confirm", "auth", "signin", "unlock",
        "recover", "billing", "service", "helpdesk", "customer", "offer", "free")

    // ------------------------------------------------------------ URL host signals

    val URL_SIGNALS = listOf("ip_host", "punycode", "many_subdomains", "long_host", "digits_in_name", "many_hyphens",
        "suspicious_tld", "shortener", "free_hosting", "brand_not_official", "lure_word")

    private val TWO_LEVEL = setOf("co", "gov", "org", "net", "ac", "bank", "com", "nic")
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://")
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val DIGIT = Regex("\\d")

    fun registrable(host: String): String {
        val p = host.split(".")
        if (p.size >= 3 && p[p.size - 2] in TWO_LEVEL && p.last().length == 2) return p.takeLast(3).joinToString(".")
        return p.takeLast(2).joinToString(".")
    }

    fun urlHost(url: String): String {
        var u = url.trim().lowercase()
        u = SCHEME.replaceFirst(u, "")
        u = u.substringBefore("/").substringBefore("?").substringBefore("#")
        u = u.substringAfterLast("@").substringBefore(":")
        return u.trim('.')
    }

    private fun onDomain(host: String, d: String) = host == d || host.endsWith(".$d")

    fun urlSignals(host: String): FloatArray {
        val labels = host.split(".")
        val reg = registrable(host)
        val name = if (labels.size < 3) labels[0] else labels.dropLast(2).joinToString(".")
        val squashed = host.replace("-", "").replace(".", "")
        val brandHit = BRANDS.keys.any { it in squashed }
        val onOfficial = OFFICIAL.any { onDomain(host, it) }
        val regName = reg.split(".")[0]
        return floatArrayOf(
            b(IPV4.matches(host)),
            b("xn--" in host),
            b(labels.size >= 4),
            b(host.length > 30),
            b(DIGIT.containsMatchIn(regName)),
            b(host.count { it == '-' } >= 2),
            b(labels.last() in BAD_TLDS),
            b(reg in SHORTENERS || host in SHORTENERS),
            b(FREE_HOSTS.any { onDomain(host, it) }),
            b(brandHit && !onOfficial),
            b(LURE_WORDS.any { it in name || it in regName }),
        )
    }

    fun allowlisted(host: String, allow: Set<String>): Boolean {
        if (FREE_HOSTS.any { onDomain(host, it) }) return false
        return host in allow || registrable(host) in allow
    }

    // ------------------------------------------------------------ SMS signals

    val SMS_SIGNALS = listOf("has_link", "urgency", "asks_secret", "money_request", "account_threat", "prize_lottery",
        "job_task", "authority_threat", "kyc_identity", "brand_link_mismatch", "shortener_link",
        "sender_mobile", "sender_dlt", "dont_share_otp", "call_number")

    val URL_RE = Regex("(?:https?://|www\\.)\\S+|\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\S*)?|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|in|net|org|xyz|top|info|co|me|ly|gd|io|app|dev|site|online|live|shop|click|cc|tk)(?:/\\S*)?", RegexOption.IGNORE_CASE)

    private val KW = listOf(
        "urgency" to "urgent|immediately|today|within 24|in 24 ?h|last chance|expire|turant|abhi|jaldi|aaj hi|तुरंत|आज ही|अभी",
        "asks_secret" to "\\botp\\b|\\bpin\\b|\\bcvv\\b|password|passcode|card number|ओटीपी|पिन",
        "money_request" to "pay |payment|transfer|deposit|send rs|\\bfee\\b|charge|refundable|processing fee|upi|collect request|भुगतान|पैसे",
        "account_threat" to "block|suspend|deactivat|disconnect|close[d]? |freeze|band ho|बंद|ब्लॉक",
        "prize_lottery" to "\\bwon\\b|winner|lottery|prize|lucky draw|cashback of|reward|congratulation|जीत|इनाम|लॉटरी",
        "job_task" to "part.?time|work from home|daily income|earn rs|per day|\\btasks?\\b|like (and|&) subscribe|telegram|कमाएं|घर बैठे",
        "authority_threat" to "police|cbi|customs|court|arrest|narcotics|cyber cell|rbi|trai|ed officer|गिरफ्तार|पुलिस",
        "kyc_identity" to "\\bkyc\\b|\\bpan\\b|aadhaa?r|e-?kyc|केवाईसी|आधार",
    ).map { it.first to Regex(it.second) }
    private val MOBILE = Regex("\\+?(91)?[6-9]\\d{9}")
    private val DLT = Regex("[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?")
    private val DONT_SHARE = Regex("(do not|don't|never) share|किसी के साथ साझा न")
    private val CALL_NUM = Regex("call\\s*(on|at|us)?\\s*:?\\s*\\+?\\d[\\d -]{8,}")

    /** Hosts of the non-official links in a message. */
    fun riskyHosts(text: String): List<String> =
        URL_RE.findAll(text).map { it.value }
            .map { urlHost(if ("://" in it) it else "http://$it") }
            .filter { h -> OFFICIAL.none { onDomain(h, it) } }
            .toList()

    fun smsSignals(text: String, sender: String = ""): FloatArray {
        val t = text.lowercase()
        val hosts = riskyHosts(text)
        val s = sender.trim().uppercase()
        val kw = KW.associate { (k, re) -> k to b(re.containsMatchIn(t)) }
        val brandIdx = URL_SIGNALS.indexOf("brand_not_official")
        val shortIdx = URL_SIGNALS.indexOf("shortener")
        return floatArrayOf(
            b(hosts.isNotEmpty()),
            kw["urgency"]!!, kw["asks_secret"]!!, kw["money_request"]!!, kw["account_threat"]!!,
            kw["prize_lottery"]!!, kw["job_task"]!!, kw["authority_threat"]!!, kw["kyc_identity"]!!,
            b(hosts.any { urlSignals(it)[brandIdx] > 0 }),
            b(hosts.any { urlSignals(it)[shortIdx] > 0 }),
            b(MOBILE.matches(s.replace(" ", ""))),
            b(DLT.matches(s)),
            b(DONT_SHARE.containsMatchIn(t)),
            b(CALL_NUM.containsMatchIn(t)),
        )
    }

    /** Dense model input [hashed | signals]. kind: "sms" or "url" (url: input is reduced to its host). */
    fun vector(kind: String, text: String, sender: String = ""): FloatArray {
        val input = if (kind == "url") urlHost(text) else text
        val h = hashed(input)
        val sig = if (kind == "url") urlSignals(input) else smsSignals(text, sender)
        val x = FloatArray(DIM + sig.size)
        for ((k, v) in h) x[k] = v.toFloat()
        sig.copyInto(x, DIM)
        return x
    }

    private fun b(v: Boolean) = if (v) 1f else 0f
}
