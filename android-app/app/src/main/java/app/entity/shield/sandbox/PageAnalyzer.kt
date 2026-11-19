package app.entity.shield.sandbox

import java.net.IDN
import java.net.URI
import java.nio.charset.Charset
import java.util.Base64
import java.util.regex.Pattern

data class PageInput(
    val finalUrl: String, val contentType: String, val contentDisposition: String,
    val body: ByteArray, val hopUrls: List<String>,
)

/** One form control. kind: upi_pin, atm_pin, mpin, otp, cvv, expiry, aadhaar, pan, card, password, netbanking_id,
 *  account, ifsc, phone, email, dob or other. label: the best human-readable name found for it. */
data class Field(val kind: String, val label: String)

data class PageReport(
    val title: String, val textSample: String, val fields: List<Field>, val forms: Int, val formActions: List<String>,
    val brands: List<String>, val metaRefresh: String?, val jsRedirect: String?, val scripts: Int, val externalScripts: Int,
    val iframes: Int, val links: Int, val externalLinkRatio: Float, val signals: List<String>, val isHtml: Boolean, val bytes: Int,
    val devanagari: Boolean,
)

/**
 * Static phishing analysis of a fetched page: no JavaScript is run and nothing is rendered. A small linear-time,
 * indexOf-based tokenizer (no regex over the whole document) pulls out the title, visible text, form controls,
 * forms, links, scripts and iframes; keyword and structure checks turn them into named signals.
 * Pure Kotlin/JDK with no I/O, so it can run inside an isolatedProcess service and in JVM unit tests.
 * Never throws: on any internal failure it returns a minimal report whose only signal is "analysis_failed".
 */
object PageAnalyzer {
    fun analyze(
        input: PageInput, brands: Map<String, List<String>> /* brand token -> official registrable domains */,
        freeHosts: List<String>, registrable: (String) -> String,
    ): PageReport = try {
        Scan(input, brands, freeHosts, registrable).run()
    } catch (t: Throwable) {
        PageReport("", "", emptyList(), 0, emptyList(), emptyList(), null, null, 0, 0, 0, 0, 0f,
            listOf("analysis_failed"), false, input.body.size, false)
    }

    private const val MAX_BODY = 2 * 1024 * 1024
    private const val TEXT_CAP = 256 * 1024
    private const val SCRIPT_CAP = 200 * 1024
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val CT_CHARSET = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]{1,40})", RegexOption.IGNORE_CASE)
    private val META_CHARSET = Regex("<meta[^>]{0,512}?charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_.:-]{1,40})", RegexOption.IGNORE_CASE)
    private val DISPOSITION_NAME = Regex("filename\\*?\\s*=\\s*(?:[A-Za-z0-9_-]+'[^']*')?[\"']?([^\"';]+)", RegexOption.IGNORE_CASE)
    private const val LIT = "(?:\"([^\"\\r\\n]{1,500})\"|'([^'\\r\\n]{1,500})'|`([^`\\r\\n]{1,500})`)"
    private val JS_REDIRECT: Pattern =   // applied with lookingAt() to a <= 600-char window at each "location"
        Pattern.compile("location(?:\\.href)?\\s*=(?!=)\\s*$LIT|location\\.(?:replace|assign)\\s*\\(\\s*$LIT", Pattern.CASE_INSENSITIVE)

    private val INLINE = setOf("a", "abbr", "b", "bdi", "bdo", "big", "cite", "code", "em", "font", "i", "kbd", "label", "mark",
        "nobr", "q", "s", "samp", "small", "span", "strike", "strong", "sub", "sup", "time", "tt", "u", "var", "wbr")
    private val SNIFF_TAGS = listOf("!doctype html", "html", "head", "body", "script", "iframe", "h1", "div", "font", "table",
        "a", "style", "title", "b", "br", "p", "meta", "form")
    private val NO_KIND_TYPES =setOf("hidden", "submit", "button", "checkbox", "radio", "reset", "image")
    private val SENSITIVE = setOf("password", "otp", "card", "cvv", "upi_pin", "atm_pin", "mpin", "aadhaar", "pan", "netbanking_id")
    private val PIN_LIKE = setOf("upi_pin", "atm_pin", "mpin", "otp", "cvv")
    private val FIELD_SIGNALS = linkedMapOf("password" to "password_field", "otp" to "otp_field", "card" to "card_field",
        "cvv" to "cvv_field", "upi_pin" to "upi_pin_field", "atm_pin" to "atm_pin_field", "mpin" to "mpin_field",
        "aadhaar" to "aadhaar_field", "pan" to "pan_field")

    private class Rule(val kind: String, subs: List<String>, val words: List<String> = emptyList()) { val subs = subs.map(::norm) }

    /** First match wins. expiry, aadhaar and pan come before card so "Debit card expiry" / "PAN card number" classify right. */
    private val KINDS = listOf(
        Rule("upi_pin", listOf("upi pin", "upipin", "upi_pin", "यूपीआई पिन")),
        Rule("atm_pin", listOf("atm pin", "atmpin", "debit card pin", "card pin", "cardpin", "एटीएम पिन")),
        Rule("mpin", listOf("mpin", "m-pin", "एमपिन")),
        Rule("otp", listOf("otp", "one time password", "one-time password", "one time code", "verification code", "ओटीपी")),
        Rule("cvv", listOf("cvv", "cvc", "security code", "card verification", "cc-csc", "सीवीवी")),
        Rule("expiry", listOf("expiry", "exp date", "expiration", "valid thru", "valid through", "valid till", "cc-exp", "mm/yy", "समाप्ति")),
        Rule("aadhaar", listOf("aadhaar", "aadhar", "adhaar", "uidai", "आधार")),
        Rule("pan", listOf("pan card", "pancard", "pan number", "pannumber", "pan no", "panno", "पैन"), listOf("pan")),
        Rule("card", listOf("card number", "cardnumber", "card no", "cardno", "cc-number", "ccnum", "debit card", "credit card",
            "कार्ड नंबर", "कार्ड संख्या")),
        Rule("password", listOf("password", "passcode", "passwd", "पासवर्ड"), listOf("pwd", "pass")),
        Rule("netbanking_id", listOf("user id", "userid", "customer id", "customerid", "login id", "loginid", "username",
            "user name", "cust id", "यूजर आईडी", "ग्राहक आईडी")),
        Rule("account", listOf("account number", "accountnumber", "account no", "accountno", "a/c no", "a/c number", "acc no",
            "acct no", "खाता संख्या", "खाता नंबर")),
        Rule("ifsc", listOf("ifsc")),
        Rule("phone", listOf("mobile", "phone", "मोबाइल", "फोन", "फ़ोन"), listOf("tel", "msisdn")),
        Rule("email", listOf("email", "e-mail", "ईमेल")),
        Rule("dob", listOf("date of birth", "dateofbirth", "birth date", "birthdate", "bday", "जन्म तिथि", "जन्मतिथि"), listOf("dob")),
    )

    private val URGENCY = listOf("within 24 hours", "within 24 hrs", "within 48 hours", "immediately", "urgent", "will be blocked",
        "will be suspended", "suspended", "deactivated", "will be closed", "expire", "last chance", "act now", "final notice",
        "तुरंत", "ब्लॉक", "बंद हो", "निलंबित", "24 घंटे", "turant", "block ho", "band ho", "jaldi")
    private val REWARD = listOf("congratulations", "you have won", "you've won", "you won", "cashback", "cash back",
        "reward points", "claim now", "claim your", "lucky draw", "lucky winner", "free gift", "jackpot", "lottery",
        "इनाम", "बधाई", "उपहार", "जीत", "inaam", "badhai", "jeeto")
    private val KYC = listOf("kyc", "pan card update", "update pan", "update your pan", "aadhaar link", "link aadhaar",
        "link your aadhaar", "केवाईसी", "पैन अपडेट")
    private val LOGIN = listOf("login", "log in", "sign in", "signin", "net banking", "netbanking", "internet banking",
        "verify your account", "लॉगिन", "लॉग इन")
    private val PARKED = listOf("domain is for sale", "domain for sale", "may be for sale", "buy this domain", "domain is parked",
        "parked free", "parked domain", "domain parking", "inquire about this domain", "make an offer on this domain")
    private val PARKING_HOSTS = listOf("sedoparking.com", "parkingcrew.net", "bodis.com", "parklogic", "domainmarket.com",
        "hugedomains.com", "afternic.com", "dan.com/")
    private val ERROR_WORDS = listOf("404", "not found", "account suspended", "account has been suspended",
        "this site has been suspended", "site not found", "page not found", "403 forbidden", "site is suspended",
        "service unavailable", "no longer available")
    private val MESSENGERS = listOf("api.telegram.org", "t.me/", "discord.com/api/webhooks", "discordapp.com/api/webhooks", "wa.me/")
    private val EXFIL = listOf("api.telegram.org/bot", "discord.com/api/webhooks", "discordapp.com/api/webhooks", "sendmessage?chat_id")
    private val F12 = listOf("keycode==123", "keycode===123", ".which==123", ".which===123", "=='f12'", "==='f12'", "==\"f12\"", "===\"f12\"")
    private val CTRL_U = listOf("keycode==85", "keycode===85", ".which==85", ".which===85", "=='u'", "==='u'", "==\"u\"", "===\"u\"")
    private val DEVTOOLS = listOf("devtools-detect", "disable-devtool", "devtoolschange", "isdevtoolsopen", "devtools.open", "devtools.isopen")
    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122", "hellip" to "\u2026", "ndash" to "\u2013", "mdash" to "\u2014",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D", "bull" to "\u2022", "middot" to "\u00B7",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "times" to "\u00D7", "euro" to "\u20AC", "pound" to "\u00A3", "rarr" to "\u2192")

    private fun isWs(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C'
    private fun isAlpha(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isWordChar(c: Char) = Character.isLetterOrDigit(c) || Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Lower-case, split camelCase, turn _ - . : etc. into spaces, collapse whitespace. */
    private fun norm(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var prevLower = false
        for (c in s) {
            if (c.isUpperCase() && prevLower) sb.append(' ')
            prevLower = c.isLowerCase()
            sb.append(if (c in "_-.:[]()*#,|") ' ' else c.lowercaseChar())
        }
        return collapse(sb.toString())
    }

    private fun collapse(s: String): String {
        val sb = StringBuilder(s.length)
        var space = false
        for (c in s) {
            if (isWs(c) || c == '\u00A0') space = true
            else { if (space && sb.isNotEmpty()) sb.append(' '); space = false; sb.append(c) }
        }
        return sb.toString()
    }

    /** [w] occurs in [hay] with no letter/digit/mark directly on either side. */
    private fun hasWord(hay: String, w: String): Boolean {
        var i = hay.indexOf(w)
        while (i >= 0) {
            val end = i + w.length
            if ((i == 0 || !isWordChar(hay[i - 1])) && (end >= hay.length || !isWordChar(hay[end]))) return true
            i = hay.indexOf(w, i + 1)
        }
        return false
    }

    /** [host] occurs in [hay] as a host name (not as the tail of a longer name like "chat.me/"). */
    private fun hasHost(hay: String, host: String): Boolean {
        var i = hay.indexOf(host)
        while (i >= 0) {
            if (i == 0 || hay[i - 1].let { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) return true
            i = hay.indexOf(host, i + 1)
        }
        return false
    }

    private fun kindOf(hay: String): String {
        for (r in KINDS) if (r.subs.any { it in hay } || r.words.any { hasWord(hay, it) }) return r.kind
        return "other"
    }

    /** &amp; &lt; &gt; &quot; &#39; &nbsp; &#NNN; &#xHH; and a few more; unknown or malformed entities stay as they are. */
    private fun decodeEntities(s: String): String {
        var amp = s.indexOf('&')
        if (amp < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (amp >= 0) {
            sb.append(s, i, amp)
            i = amp + 1
            if (i < s.length && s[i] == '#') {
                var k = i + 1
                val hex = k < s.length && (s[k] == 'x' || s[k] == 'X')
                if (hex) k++
                val digits = k
                while (k < s.length && k - digits < 8 && (s[k] in '0'..'9' || (hex && (s[k] in 'a'..'f' || s[k] in 'A'..'F')))) k++
                if (k > digits) {
                    val cp = s.substring(digits, k).toLong(if (hex) 16 else 10)
                    if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) sb.appendCodePoint(cp.toInt()) else sb.append('\uFFFD')
                    i = if (k < s.length && s[k] == ';') k + 1 else k
                } else sb.append('&')
            } else {
                var k = i
                while (k < s.length && k - i < 8 && isAlpha(s[k])) k++
                val rep = if (k < s.length && s[k] == ';') ENTITIES[s.substring(i, k).lowercase()] else null
                if (rep != null) { sb.append(rep); i = k + 1 } else sb.append('&')
            }
            amp = s.indexOf('&', i)
        }
        sb.append(s, i, s.length)
        return sb.toString()
    }

    private fun encode(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        val hex = "0123456789ABCDEF"
        fun isHex(i: Int) = i < b.size && b[i].toInt().toChar().let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        val sb = StringBuilder(b.size + 16)
        for (i in b.indices) {
            val c = b[i].toInt() and 0xFF
            if (c <= 0x20 || c >= 0x7F || (c == '%'.code && !(isHex(i + 1) && isHex(i + 2))) || c.toChar() in "\"<>\\^`{|}[]")
                sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
            else sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** "http://a.com" -> "http://a.com/" (java.net.URI resolves "x" against the former as "http://a.comx"). */
    private fun withSlash(u: String): String {
        val m = u.indexOf("://")
        if (m < 0) return u
        val cut = u.indexOfAny(charArrayOf('/', '?', '#'), m + 3)
        return when { cut < 0 -> "$u/"; u[cut] == '/' -> u; else -> u.substring(0, cut) + "/" + u.substring(cut) }
    }

    private fun hostOf(url: String): String? {
        val m = url.indexOf("://")
        if (m <= 0 || !url.substring(0, m).lowercase().let { it == "http" || it == "https" }) return null
        var a = url.substring(m + 3)
        val cut = a.indexOfAny(charArrayOf('/', '?', '#', '\\'))
        if (cut >= 0) a = a.substring(0, cut)
        a = a.substringAfterLast('@')
        val h = if (a.startsWith("[")) a.substringBefore(']').removePrefix("[") else a.substringBefore(':')
        val ascii = try { IDN.toASCII(h, IDN.ALLOW_UNASSIGNED) } catch (e: Exception) { h }
        return ascii.lowercase().trimEnd('.').ifEmpty { null }
    }

    private fun isApkUrl(u: String) = u.substringBefore('#').substringBefore('?').trimEnd().lowercase().endsWith(".apk")

    private fun charsetFor(name: String?, fromMeta: Boolean): Charset? {
        val n = name?.trim()?.lowercase() ?: return null
        if (fromMeta && n.startsWith("utf-16")) return Charsets.UTF_8   // a meta tag can't really mean UTF-16 (WHATWG)
        val mapped = when (n) { "iso-8859-1", "iso8859-1", "latin1", "l1", "us-ascii", "ascii", "cp1252" -> "windows-1252"; else -> n }
        return try { Charset.forName(mapped) } catch (e: Exception) { null }
    }

    private class RawField(val tag: String, val a: Map<String, String>, val near: String) { var wrapLabel: String? = null }

    /** All mutable state of one analyze() call. */
    private class Scan(
        val input: PageInput, val brands: Map<String, List<String>>, freeHosts: List<String>, val registrable: (String) -> String,
    ) {
        val freeHosts = freeHosts.map { it.trim().trim('.').lowercase() }.filter { it.isNotEmpty() }
        val finalUrl = input.finalUrl.trim()
        val finalHost = hostOf(finalUrl) ?: ""
        val siteCache = HashMap<String, String>()
        val finalSite = site(finalHost)
        var base = withSlash(finalUrl)
        var baseSet = false

        var title: String? = null; var ogTitle = ""
        val text = StringBuilder(); var space = false
        val headings = StringBuilder(); var inHeading = false
        var label: StringBuilder? = null; var labelFor: String? = null; var labelStart = 0
        val labelsById = HashMap<String, String>()
        val near = StringBuilder()
        val rawFields = ArrayList<RawField>()
        val script = StringBuilder()
        val scriptSrcs = ArrayList<String>()
        val logos = ArrayList<String>()
        val actions = ArrayList<String>()
        var forms = 0; var scripts = 0; var externalScripts = 0; var iframes = 0; var links = 0; var external = 0
        var metaRefresh: String? = null; var linksApk = false; var antiInspection = false
        var hiddenIframe = false; var fullIframeOffsite = false

        /** Registrable domain, except on free hosting where each customer subdomain is its own site. */
        fun site(host: String): String = if (host.isEmpty()) "" else siteCache.getOrPut(host) {
            val fh = freeHosts.firstOrNull { host == it || host.endsWith(".$it") }
            when {
                fh == null -> try { registrable(host).lowercase() } catch (e: Exception) { host }
                host == fh -> fh
                else -> host.substring(0, host.length - fh.length - 1).substringAfterLast('.') + "." + fh
            }
        }

        fun resolve(ref: String): String {
            val r = ref.filter { it != '\n' && it != '\r' && it != '\t' }.trim()
            if (r.isEmpty()) return base
            if (SCHEME.containsMatchIn(r)) return r
            return try { URI(base).resolve(URI(encode(r))).toString() } catch (e: Exception) { r }
        }

        fun run(): PageReport {
            val body = input.body
            val len = minOf(body.size, MAX_BODY)
            val ct = input.contentType.trim().lowercase()
            val mime = ct.substringBefore(';').trim()
            val signals = LinkedHashSet<String>()

            val filename = DISPOSITION_NAME.find(input.contentDisposition.take(1000))?.groupValues?.get(1)?.trim() ?: ""
            if ("application/vnd.android.package-archive" in mime || filename.lowercase().endsWith(".apk") ||
                isApkUrl(finalUrl) || input.hopUrls.any(::isApkUrl)) signals += "apk_download"
            if (freeHosts.any { finalHost == it || finalHost.endsWith(".$it") }) signals += "free_host"

            val isHtml = when {
                "html" in mime -> true
                mime.isEmpty() || mime == "text/plain" || mime == "application/octet-stream" -> sniffHtml(body, len)
                else -> false
            }
            if (isHtml || mime.startsWith("text/")) {
                var skip = 0
                val cs: Charset = when {
                    len >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte() -> { skip = 3; Charsets.UTF_8 }
                    len >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte() -> { skip = 2; Charsets.UTF_16BE }
                    len >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() -> { skip = 2; Charsets.UTF_16LE }
                    else -> charsetFor(CT_CHARSET.find(ct.take(500))?.groupValues?.get(1), false)
                        ?: (if (isHtml) charsetFor(META_CHARSET.find(String(body, 0, minOf(len, 4096), Charsets.ISO_8859_1))
                            ?.groupValues?.get(1), true) else null)
                        ?: Charsets.UTF_8
                }
                val doc = String(body, skip, len - skip, cs)   // malformed bytes become U+FFFD, never an exception
                if (isHtml) tokenize(doc) else { for (c in doc) if (isWs(c)) space = true else put(c) }
            }
            closeLabel()

            // Form controls.
            val fields = rawFields.take(64).map(::classify)
            val kinds = fields.mapTo(HashSet()) { it.kind }
            for ((k, s) in FIELD_SIGNALS) if (k in kinds) signals += s
            val sensitive = kinds.any { it in SENSITIVE }

            // Forms.
            if (actions.any { a -> hostOf(a)?.let { site(it) != finalSite } == true }) signals += "form_offsite"
            val scriptText = script.toString()
            val scriptLow = scriptText.lowercase()
            if (actions.any { a -> a.lowercase().let { al -> MESSENGERS.any { hasHost(al, it) } } } ||
                MESSENGERS.any { hasHost(scriptLow, it) }) signals += "form_to_messenger"
            val flat = scriptLow.filterNot { it.isWhitespace() }
            if (EXFIL.any { it in flat }) signals += "exfil_script"

            // Brands: whole-word token matches in bounded strings only.
            val titleText = (title?.takeIf { it.isNotBlank() } ?: ogTitle).take(160)
            val sample = text.substring(0, minOf(text.length, 1200))
            val strong = listOf(titleText, ogTitle, headings.toString(), logos.joinToString(" "))
            val found = ArrayList<String>()
            var impersonation = false; var offDomainMention = false
            for ((token, officials) in brands) {
                val t = token.trim()
                if (t.isEmpty()) continue
                val re = Regex("(?<![\\p{L}\\p{N}\\p{M}])" + Regex.escape(t) + "(?![\\p{L}\\p{N}\\p{M}])", RegexOption.IGNORE_CASE)
                val inStrong = strong.any { re.containsMatchIn(it) }
                if (!inStrong && !re.containsMatchIn(sample)) continue
                found += token
                val reg = try { registrable(finalHost).lowercase() } catch (e: Exception) { finalHost }
                val official = officials.any { o ->
                    val d = o.trim().trim('.').lowercase()
                    d.isNotEmpty() && (finalHost == d || finalHost.endsWith(".$d") || reg == d)
                }
                if (official) continue
                if (inStrong && sensitive) impersonation = true
                if (re.containsMatchIn(titleText) || re.containsMatchIn(ogTitle)) offDomainMention = true
            }
            if (impersonation) signals += "brand_impersonation"
            if (offDomainMention) signals += "brand_mention_offdomain"

            // Redirects.
            if (metaRefresh != null) signals += "meta_refresh"
            val jsRedirect = jsRedirect(scriptText)
            if (jsRedirect != null) signals += "js_redirect"

            // Scripts.
            if (("eval(" in flat && ("atob(" in flat || "unescape(" in flat || "fromcharcode" in flat)) ||
                longBase64Literal(scriptText) || hexEscapes(scriptText) > 200) signals += "obfuscated_js"
            if (antiInspection || antiInspection(flat)) signals += "anti_inspection"

            // Frames, links, transport, emptiness.
            if (hiddenIframe) signals += "hidden_iframe"
            if (fullIframeOffsite) signals += "fullpage_iframe_offsite"
            if (linksApk) signals += "page_links_apk"
            if (sensitive && finalUrl.startsWith("http://", ignoreCase = true)) signals += "no_tls_form"
            if (isHtml && text.length < 40 && scripts > 0) signals += "cloaked_empty"

            // Wording.
            val hay = (titleText + " " + ogTitle + " " + text).lowercase()
            if (URGENCY.any { it in hay }) signals += "urgency_text"
            if (REWARD.any { it in hay }) signals += "reward_text"
            if (KYC.any { it in hay }) signals += "kyc_text"
            if (LOGIN.any { it in hay }) signals += "login_text"
            if (PARKED.any { it in hay } || (scriptSrcs + scriptLow).any { s -> PARKING_HOSTS.any { it in s.lowercase() } }) signals += "parked"
            if (isHtml && text.length < 600 && ERROR_WORDS.any { it in hay }) signals += "error_page"

            val devanagari = hay.any { it in '\u0900'..'\u097F' }
            return PageReport(titleText, sample, fields, forms, actions.toList(), found, metaRefresh, jsRedirect, scripts,
                externalScripts, iframes, links, if (links == 0) 0f else external.toFloat() / links, signals.toList(), isHtml,
                body.size, devanagari)
        }

        /** WHATWG-style sniff for an unlabeled body: it starts with a common HTML tag (or has <html / <form early on). */
        fun sniffHtml(body: ByteArray, len: Int): Boolean {
            val head = String(body, 0, minOf(len, 1024), Charsets.ISO_8859_1)
                .trimStart { isWs(it) || it == '\u00EF' || it == '\u00BB' || it == '\u00BF' }.lowercase()
            if ("<html" in head || "<form" in head || head.startsWith("<!--")) return true
            return head.startsWith("<") && SNIFF_TAGS.any { t ->
                head.startsWith(t, 1) && head.getOrNull(1 + t.length).let { it == null || isWs(it) || it == '>' || it == '/' }
            }
        }

        // ---------------------------------------------------------------- tokenizer

        /** One pass over the document; every branch moves [i] forward, so the whole scan is linear. */
        fun tokenize(s: String) {
            val n = s.length
            var i = 0
            while (i < n) {
                val lt = s.indexOf('<', i)
                if (lt < 0) { emit(s, i, n); return }
                if (lt > i) emit(s, i, lt)
                i = lt + 1
                if (i >= n) { put('<'); return }
                val c = s[i]
                i = when {
                    s.startsWith("!--", i) -> s.indexOf("-->", i + 1).let { if (it < 0) n else it + 3 }
                    c == '!' || c == '?' || (c == '/' && (i + 1 >= n || !isAlpha(s[i + 1]))) ->   // doctype, PI, bogus comment
                        s.indexOf('>', i).let { if (it < 0) n else it + 1 }
                    c == '/' -> endTag(s, i + 1)
                    isAlpha(c) -> startTag(s, i)
                    else -> { put('<'); i }   // a lone '<' is text
                }
            }
        }

        fun tagNameEnd(s: String, from: Int): Int {
            var j = from
            while (j < s.length && !isWs(s[j]) && s[j] != '/' && s[j] != '>') j++
            return j
        }

        fun endTag(s: String, from: Int): Int {
            val j = tagNameEnd(s, from)
            val name = s.substring(from, j).lowercase()
            if (name !in INLINE) space = true
            when (name) { "h1", "h2" -> inHeading = false; "label" -> closeLabel() }
            return s.indexOf('>', j).let { if (it < 0) s.length else it + 1 }
        }

        /** Tag name + attributes (double-, single- or unquoted). An unclosed quote or tag eats the rest, as in a browser. */
        fun startTag(s: String, from: Int): Int {
            val n = s.length
            var j = tagNameEnd(s, from)
            val name = s.substring(from, j).lowercase()
            val attrs = HashMap<String, String>(8)
            while (true) {
                if (j >= n) return n
                val c = s[j]
                if (c == '>') { j++; break }
                if (isWs(c) || c == '/') { j++; continue }
                val ns = j++
                while (j < n && !isWs(s[j]) && s[j] != '/' && s[j] != '>' && s[j] != '=') j++
                val an = s.substring(ns, j).lowercase()
                while (j < n && isWs(s[j])) j++
                var v = ""
                if (j < n && s[j] == '=') {
                    j++
                    while (j < n && isWs(s[j])) j++
                    if (j < n && (s[j] == '"' || s[j] == '\'')) {
                        val q = s.indexOf(s[j], j + 1)
                        if (q < 0) return n
                        v = s.substring(j + 1, q); j = q + 1
                    } else {
                        val vs = j
                        while (j < n && !isWs(s[j]) && s[j] != '>') j++
                        v = s.substring(vs, j)
                    }
                }
                if (attrs.size < 64 && an !in attrs) attrs[an] = decodeEntities(v)
            }
            return openTag(name, attrs, s, j)
        }

        /** Raw text of script/style/title/... up to its end tag; returns (content, index after the end tag). */
        fun rawText(s: String, from: Int, name: String): Pair<String, Int> {
            var p = s.indexOf("</", from)
            while (p >= 0 && !s.regionMatches(p + 2, name, 0, name.length, ignoreCase = true)) p = s.indexOf("</", p + 2)
            if (p < 0) return s.substring(from) to s.length
            return s.substring(from, p) to s.indexOf('>', p).let { if (it < 0) s.length else it + 1 }
        }

        fun openTag(name: String, a: Map<String, String>, s: String, after: Int): Int {
            for ((k, v) in a) if (k.length > 2 && k.startsWith("on")) {
                addScript(v)
                if (k == "oncontextmenu" && "returnfalse" in v.lowercase().filterNot { it.isWhitespace() }) antiInspection = true
            }
            if (name !in INLINE) space = true
            when (name) {
                "title" -> {
                    val (content, next) = rawText(s, after, name)
                    if (title == null) title = collapse(decodeEntities(content.take(4096))).take(160)
                    return next
                }
                "script" -> {
                    scripts++
                    a["src"]?.let { externalScripts++; if (scriptSrcs.size < 50) scriptSrcs += resolve(it) }
                    val (content, next) = rawText(s, after, name)
                    val type = a["type"]?.trim()?.lowercase() ?: ""
                    if (type.isEmpty() || "javascript" in type || "ecmascript" in type || type == "module") addScript(content)
                    return next
                }
                "style", "noscript", "xmp", "noembed", "noframes" -> return rawText(s, after, name).second
                "textarea" -> { field(name, a); return rawText(s, after, name).second }
                "iframe" -> { iframe(a); return rawText(s, after, name).second }
                "input", "select" -> field(name, a)
                "label" -> { closeLabel(); label = StringBuilder(); labelFor = a["for"]?.trim(); labelStart = rawFields.size }
                "form" -> { forms++; if (actions.size < 20) actions += resolve(a["action"] ?: "") }
                "a" -> a["href"]?.let { anchor(it, a["download"]) }
                "img" -> img(a)
                "meta" -> meta(a)
                "base" -> if (!baseSet) a["href"]?.let { baseSet = true; base = withSlash(resolve(it)) }
                "h1", "h2" -> inHeading = true
            }
            return after
        }

        // ---------------------------------------------------------------- text

        fun emit(s: String, from: Int, to: Int) {
            val chunk = s.substring(from, to)
            for (c in if ('&' in chunk) decodeEntities(chunk) else chunk) if (isWs(c) || c == '\u00A0') space = true else put(c)
        }

        /** One visible, non-space character: to the page text, the open heading / label, and the text since the last field. */
        fun put(c: Char) {
            val sep = space
            space = false
            if (text.length < TEXT_CAP) { if (sep && text.isNotEmpty()) text.append(' '); text.append(c) }
            if (inHeading && headings.length < 2000) { if (sep) headings.append(' '); headings.append(c) }
            label?.let { if (it.length < 200) { if (sep) it.append(' '); it.append(c) } }
            if (sep) near.append(' ')
            near.append(c)
            if (near.length > 400) near.delete(0, near.length - 120)
        }

        fun addScript(t: String) {
            if (script.length >= SCRIPT_CAP || t.isBlank()) return
            script.append(t, 0, minOf(t.length, SCRIPT_CAP - script.length)).append('\n')
        }

        // ---------------------------------------------------------------- elements

        fun field(tag: String, a: Map<String, String>) {
            val n = near.toString().trim()
            near.setLength(0)
            if (rawFields.size < 200) rawFields += RawField(tag, a, n.takeLast(80))
        }

        fun closeLabel() {
            val l = label ?: return
            val t = l.toString().trim()
            val f = labelFor
            if (!f.isNullOrEmpty()) { if (labelsById.size < 200) labelsById.putIfAbsent(f, t) }
            else for (k in labelStart until rawFields.size) if (rawFields[k].wrapLabel == null) rawFields[k].wrapLabel = t
            label = null; labelFor = null
        }

        fun classify(f: RawField): Field {
            val a = f.a
            val type = (a["type"] ?: if (f.tag == "input") "text" else f.tag).trim().lowercase()
            val lbl = a["id"]?.trim()?.let { labelsById[it] } ?: f.wrapLabel
            val shown = collapse(listOfNotNull(lbl, a["aria-label"], a["placeholder"], a["title"], a["name"], a["id"])
                .firstOrNull { it.isNotBlank() } ?: type).take(80)
            if (type in NO_KIND_TYPES) return Field("other", shown)
            val described = norm(listOfNotNull(a["name"], a["id"], a["placeholder"], a["aria-label"], a["autocomplete"], a["title"], lbl)
                .joinToString(" "))
            val byAttrs = kindOf(norm(type) + " " + described)
            val byNear = if (f.near.isEmpty()) "other" else kindOf(norm(f.near))
            val kind = when {
                byAttrs == "other" -> byNear
                // <p>UPI PIN</p><input type=password name=p1>: only the type said "password", the nearby text says more
                byAttrs == "password" && kindOf(described) == "other" && byNear in PIN_LIKE -> byNear
                else -> byAttrs
            }
            return Field(kind, shown)
        }

        fun iframe(a: Map<String, String>) {
            iframes++
            val style = (a["style"] ?: "").lowercase().filterNot { it.isWhitespace() }
            val w = (a["width"] ?: "").trim().lowercase(); val h = (a["height"] ?: "").trim().lowercase()
            fun tiny(v: String) = v == "0" || v == "1" || v == "0px" || v == "1px"
            if (tiny(w) || tiny(h) || "display:none" in style || "visibility:hidden" in style ||
                listOf("width:0;", "height:0;", "width:1px", "height:1px", "width:0px", "height:0px").any { it in "$style;" }) hiddenIframe = true
            val fullW = w == "100%" || "width:100%" in style || "width:100vw" in style
            val fullH = h == "100%" || "height:100%" in style || "height:100vh" in style
            val src = a["src"]
            if (fullW && fullH && src != null) hostOf(resolve(src))?.let { if (site(it) != finalSite) fullIframeOffsite = true }
        }

        fun anchor(href: String, download: String?) {
            links++
            val abs = resolve(href)
            if (isApkUrl(abs) || download?.trim()?.lowercase()?.endsWith(".apk") == true) linksApk = true
            if (abs.startsWith("javascript:", ignoreCase = true)) addScript(abs.substring(11))
            hostOf(abs)?.let { if (site(it) != finalSite) external++ }
        }

        fun img(a: Map<String, String>) {
            if (logos.size >= 20) return
            val src = a["src"] ?: a["data-src"] ?: ""
            val alt = a["alt"] ?: ""
            if ("logo" !in "$src $alt ${a["class"] ?: ""} ${a["id"] ?: ""}".lowercase()) return
            val file = if (src.startsWith("data:")) "" else src.substringBefore('?').substringBefore('#').substringAfterLast('/').substringBeforeLast('.')
            logos += alt.take(100) + " " + file.take(100).lowercase().replace("logo", " ")
        }

        fun meta(a: Map<String, String>) {
            if (a["http-equiv"]?.trim()?.lowercase() == "refresh" && metaRefresh == null) metaRefresh = refreshTarget(a["content"] ?: "")
            val prop = (a["property"] ?: a["name"] ?: "").trim().lowercase()
            if (prop == "og:title" && ogTitle.isEmpty()) ogTitle = collapse(a["content"] ?: "").take(160)
        }

        /** content="5; URL='/next'" -> absolute target, or null when it only reloads the page. */
        fun refreshTarget(content: String): String? {
            var c = content.trim().trimStart { it.isDigit() || it == '.' }.trimStart { it == ';' || it == ',' || isWs(it) }
            if (c.length >= 3 && c.regionMatches(0, "url", 0, 3, ignoreCase = true)) {
                val rest = c.substring(3).trimStart()
                if (rest.startsWith("=")) c = rest.substring(1).trim()
            }
            if (c.startsWith("'") || c.startsWith("\"")) c = c.substring(1).substringBefore(c[0])
            return c.trim().takeIf { it.isNotEmpty() }?.let { resolve(it) }
        }

        // ---------------------------------------------------------------- script checks

        /** First string-literal target of window.location(.href)= / location.replace|assign(...) / top.location=, made absolute. */
        fun jsRedirect(js: String): String? {
            var k = js.indexOf("location", ignoreCase = true)
            var tries = 0
            while (k >= 0 && tries++ < 500) {
                val m = JS_REDIRECT.matcher(js.substring(k, minOf(js.length, k + 600)))
                if (m.lookingAt()) {
                    val target = (1..m.groupCount()).firstNotNullOfOrNull { m.group(it) }?.trim() ?: ""
                    val low = target.lowercase()
                    if (target.isNotEmpty() && !target.startsWith("#") && !low.startsWith("javascript:") && !low.startsWith("about:"))
                        return resolve(target)
                }
                k = js.indexOf("location", k + 8, ignoreCase = true)
            }
            return null
        }

        /** F12 / Ctrl+U blocking, devtools detection, right-click blocking, or a debugger trap inside setInterval. */
        fun antiInspection(flat: String): Boolean {
            if (F12.any { it in flat } || DEVTOOLS.any { it in flat }) return true
            if ("ctrlkey" in flat && CTRL_U.any { it in flat }) return true
            return within(flat, "oncontextmenu=", "returnfalse", 80) || within(flat, "\"contextmenu\"", "preventdefault", 150) ||
                within(flat, "'contextmenu'", "preventdefault", 150) || within(flat, "setinterval(", "debugger", 400)
        }

        /** Some occurrence of [b] starts less than [dist] chars after an occurrence of [a]. */
        fun within(h: String, a: String, b: String, dist: Int): Boolean {
            var i = h.indexOf(a)
            var n = 0
            while (i >= 0 && n++ < 100) {
                val j = h.indexOf(b, i + a.length)
                if (j < 0) return false
                if (j - i < dist) return true
                i = h.indexOf(a, i + a.length)
            }
            return false
        }

        /** A quoted run of > 2000 base64 characters that decodes to text: an encoded page or script. Binary blobs
         *  (images, protobuf configs as on google.com) and data: URIs (preceded by ',' not a quote) don't count. */
        fun longBase64Literal(js: String): Boolean {
            var run = 0
            var checks = 0
            for (i in js.indices) {
                val c = js[i]
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' || c == '-' || c == '_') {
                    run++
                    if (run == 2001 && i - run >= 0 && js[i - run] in "\"'`" && checks++ < 20 && decodesToText(js.substring(i - 2000, i - 1200)))
                        return true
                } else run = 0
            }
            return false
        }

        fun decodesToText(chunk: String): Boolean {
            val bytes = try {
                (if ('-' in chunk || '_' in chunk) Base64.getUrlDecoder() else Base64.getDecoder()).decode(chunk)
            } catch (e: IllegalArgumentException) { return false }
            return bytes.count { val b = it.toInt() and 0xFF; b in 0x20..0x7E || b == 9 || b == 10 || b == 13 } >= bytes.size * 0.85
        }

        /** \xHH escapes that sit in runs of 4+ (strings spelled out in hex), not the odd escaped '=' or '"'. */
        fun hexEscapes(js: String): Int {
            var count = 0
            var run = 0
            var runEnd = -1
            var i = js.indexOf("\\x")
            while (i >= 0 && count <= 200) {
                if (i + 3 < js.length && js[i + 2].isHex() && js[i + 3].isHex()) {
                    run = if (i == runEnd) run + 1 else 1
                    runEnd = i + 4
                    if (run == 4) count += 4 else if (run > 4) count++
                    i = js.indexOf("\\x", i + 4)
                } else i = js.indexOf("\\x", i + 2)
            }
            return count
        }

        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
