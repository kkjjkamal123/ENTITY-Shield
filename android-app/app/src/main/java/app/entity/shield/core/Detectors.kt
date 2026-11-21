package app.entity.shield.core

import android.content.Context
import app.entity.shield.net.DnsPolicy
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

enum class Band { SAFE, UNCERTAIN, SCAM }

/** Which fixed advice applies. Picked by rules from the message, never by the LLM. */
enum class Family { PROMO, KYC, POWER, PARCEL, ARREST, JOB, PRIZE, UPI, OTP, LOAN, INVEST, FAMILY, LINK, GENERIC, NET_SCAN, NET_BEACON, NET_BRUTE, NET_ANOMALY, NET_DOMAIN, NET_DGA, NET_TUNNEL }

data class Verdict(
    val kind: String,              // "sms" | "url" | "net"
    val band: Band,
    val score: Float,              // P(scam), max of the message and its links
    val family: Family,
    val evidence: List<String>,    // signal names that fired
    val worstHost: String?,        // riskiest link host, if any
    val millis: Double,            // featurize + model time
    val allowlisted: Boolean = false, // decided by an allow-list, the model was not consulted
)

/** The two ExecuTorch detectors (sms.pte, url.pte), plus the allow-list. Thread-safe enough for one caller at a time. */
class Detectors private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val sms = Module.load(asset(ctx, "sms.pte"))
    private val url = Module.load(asset(ctx, "url.pte"))
    private val smsT = thresholds(ctx, "sms_meta.json")
    private val urlT = thresholds(ctx, "url_meta.json")
    val smsScamThreshold: Float get() = smsT.first
    /** Network Guard's stricter operating point (0.1% false alarms on real DNS names, training/url/meta.json). */
    val dnsBlockThreshold: Float = runCatching {
        JSONObject(ctx.assets.open("url_meta.json").bufferedReader().readText()).getJSONObject("thresholds").getDouble("dns_block").toFloat()
    }.getOrDefault(0.99f)
    val allow: Set<String> = ctx.assets.open("allowlist.txt").bufferedReader().readLines().filter { it.isNotBlank() }.toSet()
    /** Extra allow-list for the DNS guard only: app/CDN infrastructure that phishers also abuse (so it is
     *  not in the URL allow-list), but that the phone's own apps look up all day. Links are still judged. */
    private val dnsInfra: Set<String> = runCatching {
        ctx.assets.open("dns_infra.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
    }.getOrDefault(emptySet())

    /** Reference path (golden tests): a dense FloatArray copied into a fresh tensor. */
    fun score(kind: String, x: FloatArray): Float {
        val m = if (kind == "url") url else sms
        val out = m.forward(EValue.from(Tensor.fromBlob(x, longArrayOf(1, x.size.toLong()))))
        return out[0].toTensor().dataAsFloatArray[0]
    }

    /** Production path: one direct buffer per model, wrapped once; only the touched slots are written
     *  and cleared again, so no 131 KB vector is allocated or copied per message. */
    private class Input(val m: Module, n: Int) {
        val buf: java.nio.FloatBuffer = Tensor.allocateFloatBuffer(n)
        val t: Tensor = Tensor.fromBlob(buf, longArrayOf(1, n.toLong()))
        fun score(slots: IntArray, vals: DoubleArray, sig: FloatArray): Float {
            for (j in slots.indices) buf.put(slots[j], vals[j].toFloat())
            for (j in sig.indices) buf.put(Featurizer.DIM + j, sig[j])
            val p = m.forward(EValue.from(t))[0].toTensor().dataAsFloatArray[0]
            for (sl in slots) buf.put(sl, 0f)
            for (j in sig.indices) buf.put(Featurizer.DIM + j, 0f)
            return p
        }
    }
    private val smsIn = Input(sms, Featurizer.DIM + Featurizer.SMS_SIGNALS.size)
    private val urlIn = Input(url, Featurizer.DIM + Featurizer.URL_SIGNALS.size)

    @Synchronized
    fun scoreText(kind: String, text: String, sender: String = ""): Float {
        val input = if (kind == "url") Featurizer.urlHost(text) else text
        val (slots, vals) = Featurizer.hashedSparse(input)
        return if (kind == "url") urlIn.score(slots, vals, Featurizer.urlSignals(input))
        else smsIn.score(slots, vals, Featurizer.smsSignals(text, sender))
    }

    @Synchronized
    fun checkUrl(link: String): Verdict {
        val t0 = System.nanoTime()
        val host = Featurizer.urlHost(if ("://" in link) link else "http://$link")
        val sig = Featurizer.urlSignals(host)
        val allowed = Featurizer.allowlisted(host, allow)
        val p = if (allowed || DnsPolicy.userAllows(app, host)) 0f else Featurizer.hashedSparse(host).let { (sl, v) -> urlIn.score(sl, v, sig) }
        var band = band(p, urlT)
        if (band == Band.SAFE && sig[Featurizer.URL_SIGNALS.indexOf("shortener")] > 0) band = Band.UNCERTAIN // can't see where it goes
        val ev = Featurizer.URL_SIGNALS.filterIndexed { i, _ -> sig[i] > 0 }
        return Verdict("url", band, p, Family.LINK, ev, host, (System.nanoTime() - t0) / 1e6, allowed)
    }

    /** OS, CDN and SDK infrastructure the phone looks up all day (assets/dns_infra.txt): never scored by Network Guard. */
    fun isInfra(host: String) = dnsInfra.any { host == it || host.endsWith(".$it") }

    /**
     * SMS verdict, in order: the two models (message, and every non-official link), the scam gate
     * (ScamGate: SCAM needs a fraud-specific cue), then what the user told Shield: a saved contact is
     * never a scam, and a pattern the user labelled decides on its own ([SmsTrust]).
     */
    @Synchronized
    fun checkSms(text: String, sender: String): Verdict {
        val t0 = System.nanoTime()
        val sigs = Featurizer.smsSignals(text, sender)
        val pSms = Featurizer.hashedSparse(text).let { (sl, v) -> smsIn.score(sl, v, sigs) }
        var worst: String? = null
        var pUrl = 0f
        var urlBand = Band.SAFE
        val hosts = Featurizer.riskyHosts(text)
        val scamHosts = ArrayList<String>(2)
        for (h in hosts) {
            val p = if (Featurizer.allowlisted(h, allow) || DnsPolicy.userAllows(app, h)) 0f else Featurizer.hashedSparse(h).let { (sl, v) -> urlIn.score(sl, v, Featurizer.urlSignals(h)) }
            if (band(p, urlT) == Band.SCAM) scamHosts += h
            if (p >= pUrl) { pUrl = p; worst = h; urlBand = band(p, urlT) }
        }
        val ev = Featurizer.SMS_SIGNALS.filterIndexed { i, _ -> sigs[i] > 0 }.toMutableList()
        val g = ScamGate.apply(maxOf(band(pSms, smsT), urlBand), sigs, scamHosts, text, sender, hosts)
        var b = g.band
        var fam = if (g.promo) Family.PROMO else family(text, ev)
        val trust = SmsTrust.get(app)
        if (b != Band.SAFE && trust.isContact(sender)) {
            // A saved contact is never "scam". Still "be careful" when the message itself carries a fraud
            // cue (phishing link, asks for a code, threat with a number): their account may be hacked.
            b = if (g.strong) Band.UNCERTAIN else Band.SAFE
            ev += if (b == Band.UNCERTAIN) "in_contacts" else "contact"
        }
        when (trust.label(text, sender)) {
            true -> { b = Band.SCAM; ev += "your_label"; if (fam == Family.PROMO) fam = family(text, ev) }
            false -> { b = Band.SAFE; ev += "your_label" }
            null -> {}
        }
        return Verdict("sms", b, shown(maxOf(pSms, pUrl), b), fam, ev, worst, (System.nanoTime() - t0) / 1e6)
    }

    /**
     * The risk number shown with a verdict. The raw model score can disagree with the final band once the
     * gate, a contact or the user's label has decided (0.5.1 showed "64% risk" on a message the user had
     * marked safe), so it is kept inside the band's range: SAFE below the "be careful" line, UNCERTAIN
     * between the lines, SCAM at or above the scam line.
     */
    private fun shown(raw: Float, b: Band): Float {
        val (scam, unsure) = smsT
        return when (b) {
            Band.SAFE -> minOf(raw, unsure * 0.95f)
            Band.UNCERTAIN -> raw.coerceIn(unsure, scam * 0.99f)
            Band.SCAM -> maxOf(raw, scam)
        }
    }

    private fun band(p: Float, t: Pair<Float, Float>) = when {
        p >= t.first -> Band.SCAM
        p >= t.second -> Band.UNCERTAIN
        else -> Band.SAFE
    }

    companion object {
        @Volatile private var inst: Detectors? = null
        fun get(ctx: Context): Detectors = inst ?: synchronized(this) { inst ?: Detectors(ctx.applicationContext).also { inst = it } }

        private fun asset(ctx: Context, name: String): String {
            // ExecuTorch loads from a file path; copy once per install (assets are read-only inside the APK).
            val f = File(ctx.filesDir, name)
            val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
            if (!f.exists() || f.lastModified() < ver) ctx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
            return f.absolutePath
        }

        private fun thresholds(ctx: Context, name: String): Pair<Float, Float> {
            val t = JSONObject(ctx.assets.open(name).bufferedReader().readText()).getJSONObject("thresholds")
            return t.getDouble("scam").toFloat() to t.getDouble("uncertain").toFloat()
        }

        private val FAMILY_RULES = listOf(
            Family.ARREST to Regex("police|cbi|customs|court|arrest|narcotics|cyber cell|trai|गिरफ्तार|पुलिस"),
            Family.POWER to Regex("electricity|power|bijli|bill not updated|बिजली"),
            Family.PARCEL to Regex("parcel|courier|shipment|package|india post|fedex|redelivery|पार्सल"),
            Family.JOB to Regex("part.?time|work from home|daily income|per day|task|telegram|कमाएं|घर बैठे"),
            Family.INVEST to Regex("invest|stock|crypto|returns|share market|शेयर|मुनाफा"),
            Family.LOAN to Regex("loan|cibil|लोन"),
            Family.PRIZE to Regex("won|winner|lottery|prize|lucky draw|cashback|reward|जीत|लॉटरी"),
            Family.FAMILY to Regex("new number|naya number|mom|papa|mummy|didi|bhai"),
            Family.OTP to Regex("\\botp\\b|\\bcode\\b|cvv|\\bpin\\b|ओटीपी"),
            Family.KYC to Regex("kyc|pan|aadhaa?r|account|block|suspend|केवाईसी|खाता"),
            Family.UPI to Regex("upi|refund|collect request|पैसे"),
        )

        fun family(text: String, evidence: List<String>): Family {
            val t = text.lowercase()
            FAMILY_RULES.firstOrNull { it.second.containsMatchIn(t) }?.let { return it.first }
            return if ("has_link" in evidence) Family.LINK else Family.GENERIC
        }
    }
}
