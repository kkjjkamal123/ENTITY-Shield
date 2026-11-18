package app.entity.shield

import android.content.Context
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

enum class Band { SAFE, UNCERTAIN, SCAM }

/** Which fixed advice applies. Picked by rules from the message, never by the LLM. */
enum class Family { KYC, POWER, PARCEL, ARREST, JOB, PRIZE, UPI, OTP, LOAN, INVEST, FAMILY, LINK, GENERIC, NET_SCAN, NET_BEACON, NET_BRUTE, NET_ANOMALY, NET_DOMAIN, NET_DGA, NET_TUNNEL }

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
    private val sms = Module.load(asset(ctx, "sms.pte"))
    private val url = Module.load(asset(ctx, "url.pte"))
    private val smsT = thresholds(ctx, "sms_meta.json")
    private val urlT = thresholds(ctx, "url_meta.json")
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
        val p = if (allowed) 0f else Featurizer.hashedSparse(host).let { (sl, v) -> urlIn.score(sl, v, sig) }
        var band = band(p, urlT)
        if (band == Band.SAFE && sig[Featurizer.URL_SIGNALS.indexOf("shortener")] > 0) band = Band.UNCERTAIN // can't see where it goes
        val ev = Featurizer.URL_SIGNALS.filterIndexed { i, _ -> sig[i] > 0 }
        return Verdict("url", band, p, Family.LINK, ev, host, (System.nanoTime() - t0) / 1e6, allowed)
    }

    /** A domain the phone is looking up (DNS guard). Local and infrastructure names are never scored. */
    fun checkDomain(name: String): Verdict {
        val host = name.trim('.').lowercase()
        val local = '.' !in host || LOCAL_SUFFIXES.any { host.endsWith(it) }
        val infra = dnsInfra.any { host == it || host.endsWith(".$it") }
        if (local || infra) return Verdict("url", Band.SAFE, 0f, Family.LINK, emptyList(), host, 0.0, true)
        return checkUrl(host)
    }

    @Synchronized
    fun checkSms(text: String, sender: String): Verdict {
        val t0 = System.nanoTime()
        val sigs = Featurizer.smsSignals(text, sender)
        val pSms = Featurizer.hashedSparse(text).let { (sl, v) -> smsIn.score(sl, v, sigs) }
        var worst: String? = null
        var pUrl = 0f
        var urlBand = Band.SAFE
        for (h in Featurizer.riskyHosts(text)) {
            val p = if (Featurizer.allowlisted(h, allow)) 0f else Featurizer.hashedSparse(h).let { (sl, v) -> urlIn.score(sl, v, Featurizer.urlSignals(h)) }
            if (p >= pUrl) { pUrl = p; worst = h; urlBand = band(p, urlT) }
        }
        val ev = Featurizer.SMS_SIGNALS.filterIndexed { i, _ -> sigs[i] > 0 }
        val b = maxOf(band(pSms, smsT), urlBand)
        return Verdict("sms", b, maxOf(pSms, pUrl), family(text, ev), ev, worst, (System.nanoTime() - t0) / 1e6)
    }

    private fun band(p: Float, t: Pair<Float, Float>) = when {
        p >= t.first -> Band.SCAM
        p >= t.second -> Band.UNCERTAIN
        else -> Band.SAFE
    }

    companion object {
        private val LOCAL_SUFFIXES = listOf(".arpa", ".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")
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
