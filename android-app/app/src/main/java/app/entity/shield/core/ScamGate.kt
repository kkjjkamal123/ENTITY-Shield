package app.entity.shield.core

/**
 * v4 (2026-11-21): SCAM needs a fraud-specific cue, not just "sounds like marketing".
 * v5 (2026-11-23): more fraud cues and more ways to recognise a harmless message, found on the validation split.
 * Same rules as sms_gate() in training/featurize.py; ScamGateTest checks both agree (training/sms/gate_golden.json).
 *
 * The SMS model scores promotional spam high: links, offers, "win", deadlines and phone numbers are in
 * promos as much as in fraud. On a real 4,620-message inbox the v3 app called 537 messages a scam and
 * 1,573 suspicious (Jio data alerts, missed-call alerts, bank login alerts, TRAI advisories). Fraud is
 * something more specific: asking you to hand over a secret, a threat tied to a link or a personal
 * number to call, a brand look-alike link, a task job, or a link the URL model calls phishing.
 * Without one of those:
 *   - a registered business sender (DLT ID) is SAFE, labelled PROMO when it reads like marketing
 *   - a login code being delivered is SAFE
 *   - a message with nothing to click, call or pay is SAFE
 *   - anything else is at most UNCERTAIN (listed, no scam alarm)
 * A strong cue lifts a SAFE model score to UNCERTAIN.
 */
object ScamGate {
    /** [strong]: a fraud-specific cue fired (asks for a code, threat with a number, look-alike or phishing link). */
    data class Result(val band: Band, val promo: Boolean, val strong: Boolean = false)

    private val I = setOf(RegexOption.IGNORE_CASE)
    val SECRET_REQUEST = Regex("(?:\\b(?:share|send|tell|give|provide|forward|enter|reply with|confirm|batao|bata do|bhejo)\\b|दें|बताएं|भेजें)" +
        "[^.!?\\n]{0,40}(?:\\b(?:otp|pin|cvv|password|passcode|code|card number)\\b|ओटीपी|पिन)", I)
    private const val KW = "(otp|code|password|passcode|pin|कोड|ओटीपी)"
    private const val NUM = "(?<![\\d#,.₹])(?<!rs )(?<!rs\\.)(?<!inr )(G-)?\\d{4,8}(?![\\d,])"
    private const val GAP = "[^\\n.!?]{0,40}?"
    val CODE_DELIVERED = Regex("$KW$GAP$NUM|$NUM$GAP$KW|\\b\\d{3}-\\d{3}\\b", I)
    val CALL_TO_MOBILE = Regex("(call|contact|whatsapp|dial|ring|reach|message|msg|sampark|संपर्क|कॉल)[^\\d\\n]{0,25}(?:\\+?91[\\s-]?)?[6-9]\\d{4}\\s?\\d{5}(?!\\d)", I)
    // v5 (2026-11-23), found on the validation split: Hindi number-before-verb, Hindi service cuts,
    // "send the code you received", premium-rate prize scams, payment receipts, advisories, brand deep links.
    val MOBILE_THEN_CALL = Regex("(?<!\\d)(?:\\+?91[\\s-]?)?[6-9]\\d{4}\\s?\\d{5}(?!\\d)[^\\d\\n]{0,12}(?:पर|pe|par|on)\\s*(?:कॉल|संपर्क|व्हाट्सएप|call|contact|whatsapp)", I)
    val SERVICE_CUT = Regex("काट दी|काटी जाएगी|काट दिया|कट जाएगी|बंद कर दी|बंद हो जाएगी|cut off|will be cut|banned|kaat d", I)
    val SEND_CODE = Regex("\\b(?:share|send|tell|give|forward|bhejo|batao)\\b[^.!?\\n]{0,40}\\bcode\\b", I)
    val PRIZE_CLAIM = Regex("\\b(?:win|won|winner|prize|awarded|claim|cash|jackpot|free entry|selected)\\b|£\\s?\\d|congrat|जीत|इनाम|लॉटरी", I)
    val PREMIUM_CALL = Regex("(?<!\\d)0(?:9\\d{9}|8\\d{9,10})(?!\\d)|\\b(?:txt|text|send|reply)\\b\\W+\\w+\\W+(?:to\\W+)?\\d{5}(?!\\d)", I)
    val AUTHORITY = Regex("police|\\bcbi\\b|customs|\\bcourt\\b|arrest|narcotics|cyber cell|\\brbi\\b|\\btrai\\b|ed officer|गिरफ्तार|पुलिस", I)
    val PRESS_KEY = Regex("\\bpress \\d\\b|\\bdial \\d\\b|\\btalk to\\b", I)
    val CONTACT_ASK = Regex("\\b(?:reply|send|whatsapp|contact|sms|text|txt|click|visit|open|download|install)\\b|भेजें|संपर्क|क्लिक", I)
    private val GOV = listOf("gov.in", "nic.in")
    val TXN_ALERT = Regex("\\b(?:sent|debited|credited|received|paid|spent)\\b[^\\n]{0,60}\\b(?:ref|txn|utr|upi ref|avl bal|a/c)\\b", I)
    val ADVISORY = Regex("\\bnever\\b[^.!?\\n]{0,30}\\b(?:sends?|asks?|calls?|makes?|requests?)\\b|कभी नहीं", I)
    private val DEEP_LINKS = listOf("app.link", "onelink.me", "page.link", "smart.link", "go.link", "lnk.to", "branch.io", "adj.st")
    private val DLT_TAG_PRE = Regex("^[A-Z]{2}-")
    private val DLT_TAG_SUF = Regex("-[A-Z]$")

    /** The link belongs to the brand that sent or names the message (BYJUS -> byjus.onelink.me). Same as brand_owns(). */
    fun brandOwns(host: String, sender: String, text: String): Boolean {
        val h = host.lowercase()
        val deep = DEEP_LINKS.any { h == it || h.endsWith(".$it") }
        val name = if (deep) h.substringBefore('.') else Featurizer.registrable(h).substringBefore('.')
        if (name.length < 3) return false
        val tag = DLT_TAG_SUF.replace(DLT_TAG_PRE.replace(sender.trim().uppercase(), ""), "").lowercase()
        if (Featurizer.isBusinessSender(sender) && tag.length >= 3) {
            val n = minOf(4, tag.length, name.length)
            if (name.startsWith(tag.take(n)) || tag.startsWith(name.take(n))) return true
        }
        if (deep) return Regex("(?<![a-z])" + Regex.escape(name) + "(?![a-z])").containsMatchIn(text.lowercase())
        return false
    }

    val PROMO_WORDS = Regex("\\b(?:sale|offers?|discount|cashback|coupon|code|deals?|shop|order now|free delivery|t&c|" +
        "opt.?out|sms stop|bogo|buy 1|recharge|subscription|apply now|book now)\\b|% ?off|%|छूट|सेल", I)

    /** App deep-link services (destination not visible, like a shortener) and government-only domains. */
    private val TRUSTED_LINKS = listOf("app.link", "onelink.me", "page.link", "smart.link", "go.link", "lnk.to", "branch.io", "adj.st", "gov.in", "nic.in")
    private val HEADER_PREFIX = Regex("[A-Z]{2}")
    private val DLT_PREFIX = Regex("^[A-Z]{2}-")

    /** A DLT header (VM-HDFCBK-S) and a link on the same brand's own short domain (hdfcbk.io). */
    fun senderOwns(host: String, sender: String): Boolean {
        val parts = sender.trim().uppercase().split("-")
        if (parts.size < 2 || !HEADER_PREFIX.matches(parts[0])) return false
        val tag = parts[1].lowercase()
        val name = Featurizer.registrable(host).substringBefore('.')
        val k = minOf(4, tag.length, name.length)
        return k >= 3 && (name.startsWith(tag.take(k)) || tag.startsWith(name.take(k)))
    }

    private fun s(sig: FloatArray, name: String) = sig[Featurizer.SMS_SIGNALS.indexOf(name)] > 0

    fun apply(band: Band, sig: FloatArray, scamHosts: List<String>, text: String, sender: String, hosts: List<String>): Result {
        val t = text.lowercase()
        val hasLink = s(sig, "has_link")
        val own = { h: String -> senderOwns(h, sender) || TRUSTED_LINKS.any { h == it || h.endsWith(".$it") } }
        val linkScam = scamHosts.any { !own(it) }
        val lookalike = s(sig, "brand_link_mismatch") && hosts.any { !own(it) }
        // The business shortcut needs the full DLT form ("JT-JIOPAY-S"). A bare letter ID ("JIOPAY") still
        // counts for the model, but international gateways can spoof those, so it goes the stricter way.
        val dlt = s(sig, "sender_dlt") && DLT_PREFIX.containsMatchIn(sender.trim().uppercase())
        val mobileNo = CALL_TO_MOBILE.containsMatchIn(text) || MOBILE_THEN_CALL.containsMatchIn(text)
        val prize = PRIZE_CLAIM.containsMatchIn(text)
        val delivered = CODE_DELIVERED.containsMatchIn(text) && !prize
        val secret = !s(sig, "dont_share_otp") && !delivered &&
            ((s(sig, "asks_secret") && SECRET_REQUEST.containsMatchIn(t)) || SEND_CODE.containsMatchIn(t))
        val advisory = ADVISORY.containsMatchIn(text)
        val threat = (s(sig, "account_threat") || SERVICE_CUT.containsMatchIn(text)) && !advisory
        val pressure = threat || s(sig, "money_request") || s(sig, "prize_lottery") || s(sig, "kyc_identity") ||
            s(sig, "authority_threat") || "refund" in t
        val authority = AUTHORITY.containsMatchIn(text) && !advisory && (hasLink || s(sig, "call_number") || mobileNo ||
            s(sig, "money_request") || secret || s(sig, "urgency") || s(sig, "kyc_identity") || PRESS_KEY.containsMatchIn(text))
        val premiumPrize = prize && PREMIUM_CALL.containsMatchIn(text)
        val strong = if (dlt) secret || lookalike || linkScam || (mobileNo && pressure)
        else secret || authority || premiumPrize || lookalike || linkScam || s(sig, "job_task") ||
            (s(sig, "kyc_identity") && (hasLink || s(sig, "call_number") || mobileNo)) ||
            (threat && hasLink) || (mobileNo && pressure)
        val promo = PROMO_WORDS.containsMatchIn(t)
        // cues only fraud has: from an unregistered sender they make it a scam whatever the model says
        val certain = !dlt && (secret || lookalike || linkScam || premiumPrize || (mobileNo && threat))
        if (certain) return Result(Band.SCAM, false, true)
        if (strong) return Result(if (band == Band.SAFE) Band.UNCERTAIN else band, false, true)
        if (band == Band.SAFE) return Result(band, false)
        if (!hasLink && !s(sig, "call_number") && !mobileNo && !prize && !s(sig, "prize_lottery") && !CONTACT_ASK.containsMatchIn(text))
            return Result(Band.SAFE, false)
        if (TXN_ALERT.containsMatchIn(text) && !hasLink && !mobileNo) return Result(Band.SAFE, false)
        if (hasLink && hosts.isNotEmpty() && hosts.all { h -> GOV.any { h == it || h.endsWith(".$it") } }) return Result(Band.SAFE, false)
        if (hasLink && hosts.isNotEmpty() && !s(sig, "shortener_link") && hosts.all { brandOwns(it, sender, text) }) return Result(Band.SAFE, promo)
        if (delivered && !hasLink) return Result(Band.SAFE, false)
        if (dlt) return Result(Band.SAFE, promo)
        val weak = s(sig, "prize_lottery") || s(sig, "money_request") || (s(sig, "urgency") && hasLink) || (s(sig, "sender_mobile") && hasLink)
        if (!weak && !hasLink && !s(sig, "call_number")) return Result(Band.SAFE, false)
        if (band == Band.SCAM && !weak) return Result(Band.UNCERTAIN, promo)
        return Result(band, false)
    }
}
