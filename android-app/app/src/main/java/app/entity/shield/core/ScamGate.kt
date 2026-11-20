package app.entity.shield.core

/**
 * v4 (2026-11-21): SCAM needs a fraud-specific cue, not just "sounds like marketing".
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
    data class Result(val band: Band, val promo: Boolean)

    private val I = setOf(RegexOption.IGNORE_CASE)
    val SECRET_REQUEST = Regex("(?:\\b(?:share|send|tell|give|provide|forward|enter|reply with|confirm|batao|bata do|bhejo)\\b|दें|बताएं|भेजें)" +
        "[^.!?\\n]{0,40}(?:\\b(?:otp|pin|cvv|password|passcode|code|card number)\\b|ओटीपी|पिन)", I)
    private const val KW = "(otp|code|password|passcode|pin|कोड|ओटीपी)"
    private const val NUM = "(?<![\\d#,.₹])(?<!rs )(?<!rs\\.)(?<!inr )(G-)?\\d{4,8}(?![\\d,])"
    private const val GAP = "[^\\n.!?]{0,40}?"
    val CODE_DELIVERED = Regex("$KW$GAP$NUM|$NUM$GAP$KW|\\b\\d{3}-\\d{3}\\b", I)
    val CALL_TO_MOBILE = Regex("(call|contact|whatsapp|dial|ring|reach|message|msg|sampark|संपर्क|कॉल)[^\\d\\n]{0,25}(?:\\+?91[\\s-]?)?[6-9]\\d{4}\\s?\\d{5}(?!\\d)", I)
    val PROMO_WORDS = Regex("\\b(?:sale|offers?|discount|cashback|coupon|code|deals?|shop|order now|free delivery|t&c|" +
        "opt.?out|sms stop|bogo|buy 1|recharge|subscription|apply now|book now)\\b|% ?off|%|छूट|सेल", I)

    /** App deep-link services (destination not visible, like a shortener) and government-only domains. */
    private val TRUSTED_LINKS = listOf("app.link", "onelink.me", "page.link", "smart.link", "go.link", "lnk.to", "branch.io", "adj.st", "gov.in", "nic.in")
    private val HEADER_PREFIX = Regex("[A-Z]{2}")

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
        val dlt = s(sig, "sender_dlt")
        val mobileNo = CALL_TO_MOBILE.containsMatchIn(text)
        val delivered = CODE_DELIVERED.containsMatchIn(text)
        val secret = s(sig, "asks_secret") && !s(sig, "dont_share_otp") && !delivered && SECRET_REQUEST.containsMatchIn(t)
        val pressure = s(sig, "account_threat") || s(sig, "money_request") || s(sig, "prize_lottery") || s(sig, "kyc_identity") ||
            s(sig, "authority_threat") || "refund" in t
        val strong = if (dlt) secret || lookalike || linkScam || (mobileNo && pressure)
        else secret || s(sig, "authority_threat") || lookalike || linkScam || s(sig, "job_task") ||
            (s(sig, "kyc_identity") && (hasLink || s(sig, "call_number") || mobileNo)) ||
            (s(sig, "account_threat") && hasLink) || (mobileNo && pressure)
        val promo = PROMO_WORDS.containsMatchIn(t)
        if (strong) return Result(if (band == Band.SAFE) Band.UNCERTAIN else band, false)
        if (band == Band.SAFE) return Result(band, false)
        if (delivered && !hasLink) return Result(Band.SAFE, false)
        if (dlt) return Result(Band.SAFE, promo)
        val weak = s(sig, "prize_lottery") || s(sig, "money_request") || (s(sig, "urgency") && hasLink) || (s(sig, "sender_mobile") && hasLink)
        if (!weak && !hasLink && !s(sig, "call_number")) return Result(Band.SAFE, false)
        if (band == Band.SCAM && !weak) return Result(Band.UNCERTAIN, promo)
        return Result(band, false)
    }
}
