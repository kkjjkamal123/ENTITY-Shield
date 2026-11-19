package app.entity.shield.sandbox

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.entity.shield.core.Alert
import app.entity.shield.core.Band
import app.entity.shield.core.Detectors
import app.entity.shield.core.Family
import app.entity.shield.core.Featurizer
import app.entity.shield.core.Pipeline
import app.entity.shield.core.Verdict
import app.entity.shield.net.DnsPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Follow the link, don't guess": the phone visits the link itself and reads what is really there.
 *
 *  1. address  the ExecuTorch URL model on the link as written (what Shield did before)
 *  2. visit    SafeFetch, from the main process: no cookies, no cache, no JavaScript, private and
 *              local addresses refused, every redirect recorded, TLS certificate checked and kept
 *  3. read     the bytes go to SandboxService, an Android isolated process (no files, no network),
 *              where PageAnalyzer parses them: forms and the fields they ask for, brands, redirects,
 *              obfuscation, exfiltration to Telegram/Discord bots, APK downloads
 *  4. text     the ExecuTorch SMS model reads the page's own words (title + visible text)
 *  5. verdict  fixed, documented weights below; Llama only explains the result afterwards, and gets the
 *              findings as facts, never the page text (so a page cannot talk to the model)
 *
 * Shield never visits a link on its own: only when the user checks it, or opens it with Shield.
 */
object LinkInspector {
    const val PENDING = 0; const val RUNNING = 1; const val OK = 2; const val WARN = 3; const val BAD = 4
    data class Step(val key: String, val state: Int, val detail: String = "")

    // Page signals and their weight: p(page) = 1 - prod(1 - w). Tiers were set on a tuning sample of
    // live OpenPhish links and real Indian sites, then checked on a separate held-out sample
    // (scripts/eval_sandbox.py, benchmarks/results/sandbox_eval_*.json). Lessons from the tuning sample:
    // a lone password or OTP box, right-click blocking, empty React shells, a fresh Let's Encrypt
    // certificate (renewed every 60-90 days: says nothing about the domain's age) and broken certificate
    // chains are all common on honest Indian sites, so they only nudge.
    private val STRONG = setOf("upi_pin_field", "atm_pin_field", "mpin_field", "card_and_cvv", "form_to_messenger", "exfil_script",
        "brand_impersonation", "apk_download", "secret_offsite")
    private val HIGH = setOf("lookalike_domain")
    private val MEDIUM = setOf("aadhaar_field", "pan_field", "obfuscated_js", "fullpage_iframe_offsite", "no_tls_form",
        "cross_site_redirects", "app_redirect", "userinfo_in_url", "tls_invalid", "secret_on_free_host")
    private val WEAK = setOf("password_field", "otp_field", "card_field", "cvv_field", "form_offsite", "brand_mention_offdomain",
        "page_links_apk", "cloaked_empty", "new_certificate", "scam_words", "scam_words_with_form", "urgency_text", "reward_text",
        "kyc_text", "login_text", "hidden_iframe", "meta_refresh", "js_redirect", "anti_inspection", "free_host")
    private val SECRET_FIELDS = setOf("password_field", "otp_field", "card_field", "cvv_field", "upi_pin_field", "atm_pin_field", "mpin_field", "aadhaar_field", "pan_field")
    private const val W_STRONG = 0.93f
    private const val W_HIGH = 0.6f
    private const val W_MEDIUM = 0.35f
    private const val W_WEAK = 0.08f
    private fun shown(s: String) = s in STRONG || s in HIGH || s in MEDIUM

    private fun normalize(raw: String): String {
        val t = raw.trim()
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
    }

    fun inspect(ctx: Context, raw: String, source: String, onStep: (List<Step>) -> Unit = {}): Alert {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val url = normalize(raw)
        val keys = listOf("address", "visit", "read", "traps", "text", "verdict")
        val steps = keys.mapIndexed { i, k -> Step(k, if (i == 0) RUNNING else PENDING) }.toMutableList()
        fun set(i: Int, s: Step) { steps[i] = s; if (i + 1 < steps.size && steps[i + 1].state == PENDING) steps[i + 1] = steps[i + 1].copy(state = RUNNING); onStep(steps.toList()) }
        onStep(steps.toList())

        val det = Detectors.get(ctx)
        DnsPolicy.warm(ctx)   // the popularity list (Tranco top 1M), shared with Network Guard
        val lex = det.checkUrl(url)
        set(0, Step("address", stateOf(lex.band), "${lex.worstHost} · ${pct(lex.score)} · ${"%.1f".format(lex.millis)} ms"))

        // 2. visit (and follow one HTML/JS redirect the server-side chain did not show)
        var fetch = SafeFetch.fetch(url)
        var hops = fetch.hops
        val tVisit = fetch.totalMs
        val iso = SandboxClient.probe(ctx)
        set(1, Step("visit", if (fetch.error == null) OK else WARN, visitDetail(fetch, hops)))

        // 3. read, inside the isolated process
        var page: PageReport? = null
        var parseMs = 0.0
        if (fetch.body.isNotEmpty()) {
            SandboxClient.analyze(ctx, PageInput(fetch.finalUrl, fetch.contentType, fetch.contentDisposition, fetch.body, hops.map { it.url }))?.let { page = it.first; parseMs = it.second }
            val next = page?.metaRefresh ?: page?.jsRedirect
            if (next != null && next.startsWith("http") && hops.size < 8 && next != fetch.finalUrl) {
                val f2 = SafeFetch.fetch(next, maxHops = 8 - hops.size)
                if (f2.hops.isNotEmpty()) {
                    hops = hops + f2.hops
                    fetch = f2
                    if (f2.body.isNotEmpty()) SandboxClient.analyze(ctx, PageInput(f2.finalUrl, f2.contentType, f2.contentDisposition, f2.body, hops.map { it.url }))
                        ?.let { r -> page = r.first.copy(signals = (r.first.signals + (page?.signals.orEmpty().filter { it == "meta_refresh" || it == "js_redirect" })).distinct()); parseMs += r.second }
                }
            }
        }
        val p = page
        set(2, Step("read", if (p != null) OK else WARN,
            when {
                p != null -> listOfNotNull(p.title.ifEmpty { null }?.let { "\"${it.take(60)}\"" }, "${p.bytes / 1024} KB", "${p.forms} form(s)", "parsed in ${"%.0f".format(parseMs)} ms").joinToString(" · ")
                fetch.error != null -> "nothing to read (${fetch.error})"
                else -> "not a web page (${fetch.contentType.ifEmpty { "empty" }})"
            }))

        // 4. traps
        val finalHost = Featurizer.urlHost(fetch.finalUrl.ifEmpty { url })
        val freeHost = Featurizer.FREE_HOSTS.any { finalHost == it || finalHost.endsWith(".$it") }
        val allowed = Featurizer.allowlisted(finalHost, det.allow) || Featurizer.OFFICIAL.any { finalHost == it || finalHost.endsWith(".$it") } || Brands.restricted(finalHost)
        val popular = DnsPolicy.isPopular(finalHost) && !freeHost
        val sig = LinkedHashSet<String>()
        p?.signals?.let { sig.addAll(it) }
        if (fetch.error == "tls_invalid") sig += "tls_invalid"
        if (fetch.userinfoInUrl) sig += "userinfo_in_url"
        if (fetch.schemeRedirect != null) sig += "app_redirect"
        if ("card_field" in sig && "cvv_field" in sig) sig += "card_and_cvv"
        val secret = sig.any { it in SECRET_FIELDS }
        if (secret && "form_offsite" in sig) sig += "secret_offsite"
        if (secret && freeHost) sig += "secret_on_free_host"
        val firstHost = lex.worstHost ?: Featurizer.urlHost(url)
        (listOf(firstHost, finalHost).distinct().filter { !DnsPolicy.isPopular(it) || Featurizer.FREE_HOSTS.any { f -> it.endsWith(".$f") } }
            .firstNotNullOfOrNull { Brands.lookalike(it) })?.let { sig += "lookalike_domain"; sig += "imitates:$it" }
        val sites = hops.map { Featurizer.registrable(Featurizer.urlHost(it.url)) }.distinct()
        if (sites.size >= 3) sig += "cross_site_redirects"
        val certDays = fetch.cert?.ageDays
        if (certDays != null && certDays < 14 && !allowed) sig += "new_certificate"
        val traps = sig.filter { shown(it) }
        set(3, Step("traps", when { traps.any { it in STRONG } -> BAD; traps.isNotEmpty() -> WARN; else -> OK },
            if (traps.isEmpty()) "none found" else traps.joinToString(", ") { it.replace('_', ' ') }))

        // 5. the page's own words through the ExecuTorch text model
        var textP: Float? = null
        if (p != null && (p.title.length + p.textSample.length) > 20) {
            textP = det.scoreText("sms", (p.title + ". " + p.textSample).take(1500))
            if (textP >= det.smsScamThreshold) sig += if (secret) "scam_words_with_form" else "scam_words"
        }
        set(4, Step("text", if (textP == null) OK else if (textP >= det.smsScamThreshold) WARN else OK, textP?.let { "scam-likeness ${pct(it)}" } ?: "no text"))

        // 6. verdict
        val pPage = 1f - sig.fold(1f) { acc, s ->
            val w = when (s) { in STRONG -> W_STRONG; in HIGH -> if (popular) W_HIGH / 2 else W_HIGH; in MEDIUM -> if (popular) W_MEDIUM / 2 else W_MEDIUM; in WEAK -> W_WEAK; else -> 0f }
            acc * (1f - w)
        }
        // the address model on where the link really lands, unless that is a popular or trusted site
        val lexFinal = if (finalHost != lex.worstHost && !popular && !allowed) det.checkUrl(finalHost).score else 0f
        val pLex = if (allowed) 0f else maxOf(if (lex.allowlisted) 0f else lex.score, lexFinal)
        var score = maxOf(pLex, pPage)
        val compromised = sig.any { it in STRONG && it != "brand_impersonation" && it != "apk_download" }
        if (allowed && !compromised) score = minOf(score, 0.2f)
        val band = when { score >= 0.85f -> Band.SCAM; score >= 0.5f -> Band.UNCERTAIN; else -> Band.SAFE }
        val family = when {
            "upi_pin_field" in sig -> Family.UPI
            "aadhaar_field" in sig || "pan_field" in sig || "kyc_text" in sig -> Family.KYC
            "reward_text" in sig -> Family.PRIZE
            else -> Family.LINK
        }
        val evidence = (listOf("sandbox") + sig.filter { shown(it) || it.startsWith("imitates:") } + lex.evidence).distinct()
        val v = Verdict("url", band, score, family, evidence, finalHost, lex.millis, allowed)
        set(5, Step("verdict", stateOf(band), "${band.name.lowercase()} · ${pct(score)} · ${"%.1f".format((SystemClock.elapsedRealtimeNanos() - t0) / 1e9)} s"))

        val extra = JSONObject().put("sandbox", JSONObject()
            .put("url", url).put("final", fetch.finalUrl).put("status", fetch.status).put("error", fetch.error ?: "")
            .put("contentType", fetch.contentType).put("ms", tVisit).put("parseMs", parseMs)
            .put("hops", JSONArray().apply { hops.forEach { put(JSONObject().put("url", it.url).put("status", it.status).put("ip", it.ip).put("ms", it.ms)) } })
            .put("cert", fetch.cert?.let { JSONObject().put("subject", it.subject).put("issuer", it.issuer).put("ageDays", it.ageDays).put("notAfter", it.notAfter) } ?: JSONObject.NULL)
            .put("page", p?.let { Codec.report(it) } ?: JSONObject.NULL)
            .put("signals", JSONArray(sig.toList())).put("textScore", textP?.toDouble() ?: -1.0)
            .put("pageScore", pPage.toDouble()).put("lexScore", pLex.toDouble()).put("schemeRedirect", fetch.schemeRedirect ?: "")
            .put("isolation", iso?.let { JSONObject().put("uid", it.uid).put("isolated", it.isolated).put("filesDenied", it.filesDenied).put("netDenied", it.netDenied) } ?: JSONObject.NULL))
        Log.i("ShieldSandbox", "inspect lex0=${"%.3f".format(if (lex.allowlisted) 0f else lex.score)} url=$url final=${fetch.finalUrl} hops=${hops.size} error=${fetch.error} band=$band score=${"%.3f".format(score)} page=${"%.3f".format(pPage)} lex=${"%.3f".format(pLex)} signals=${sig.joinToString(",")} isolated=${iso?.isolated} uid=${iso?.uid} files_denied=${iso?.filesDenied} net_denied=${iso?.netDenied}")
        return Pipeline.onInspected(ctx, raw.trim(), v, extra.toString(), source, t0)
    }

    private fun stateOf(b: Band) = when (b) { Band.SCAM -> BAD; Band.UNCERTAIN -> WARN; Band.SAFE -> OK }
    private fun pct(p: Float) = "${(p * 100).toInt()}%"

    private fun visitDetail(f: FetchResult, hops: List<Hop>): String = when (f.error) {
        null -> "${hops.size} hop${if (hops.size == 1) "" else "s"} · HTTP ${f.status} · ${f.totalMs} ms" + (f.cert?.let { " · TLS by ${it.issuer.take(24)}, ${it.ageDays} days old" } ?: "")
        "dns" -> "the address does not exist (taken down or never real)"
        "private_ip" -> "points into a private network: refused"
        "tls_invalid" -> "its security certificate is not valid for this site"
        "timeout" -> "no answer within 10 s"
        "too_many_redirects" -> "redirects again and again"
        "bad_scheme" -> "not a web link"
        else -> "could not connect (${f.error}): offline?"
    }
}
