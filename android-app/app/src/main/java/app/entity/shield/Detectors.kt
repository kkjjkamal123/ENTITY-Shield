package app.entity.shield

import android.content.Context
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

enum class Band { SAFE, UNCERTAIN, SCAM }

/** Which fixed advice applies. Picked by rules from the message, never by the LLM. */
enum class Family { KYC, POWER, PARCEL, ARREST, JOB, PRIZE, UPI, OTP, LOAN, INVEST, FAMILY, LINK, GENERIC, NET_SCAN, NET_BEACON, NET_BRUTE, NET_ANOMALY }

data class Verdict(
    val kind: String,              // "sms" | "url" | "net"
    val band: Band,
    val score: Float,              // P(scam), max of the message and its links
    val family: Family,
    val evidence: List<String>,    // signal names that fired
    val worstHost: String?,        // riskiest link host, if any
    val millis: Double,            // featurize + model time
)

/** The two ExecuTorch detectors (sms.pte, url.pte), plus the allow-list. Thread-safe enough for one caller at a time. */
class Detectors private constructor(ctx: Context) {
    private val sms = Module.load(asset(ctx, "sms.pte"))
    private val url = Module.load(asset(ctx, "url.pte"))
    private val smsT = thresholds(ctx, "sms_meta.json")
    private val urlT = thresholds(ctx, "url_meta.json")
    val allow: Set<String> = ctx.assets.open("allowlist.txt").bufferedReader().readLines().filter { it.isNotBlank() }.toSet()

    fun score(kind: String, x: FloatArray): Float {
        val m = if (kind == "url") url else sms
        val out = m.forward(EValue.from(Tensor.fromBlob(x, longArrayOf(1, x.size.toLong()))))
        return out[0].toTensor().dataAsFloatArray[0]
    }

    @Synchronized
    fun checkUrl(link: String): Verdict {
        val t0 = System.nanoTime()
        val host = Featurizer.urlHost(if ("://" in link) link else "http://$link")
        val sig = Featurizer.urlSignals(host)
        val p = if (Featurizer.allowlisted(host, allow)) 0f else score("url", Featurizer.vector("url", host))
        var band = band(p, urlT)
        if (band == Band.SAFE && sig[Featurizer.URL_SIGNALS.indexOf("shortener")] > 0) band = Band.UNCERTAIN // can't see where it goes
        val ev = Featurizer.URL_SIGNALS.filterIndexed { i, _ -> sig[i] > 0 }
        return Verdict("url", band, p, Family.LINK, ev, host, (System.nanoTime() - t0) / 1e6)
    }

    @Synchronized
    fun checkSms(text: String, sender: String): Verdict {
        val t0 = System.nanoTime()
        val x = Featurizer.vector("sms", text, sender)
        val pSms = score("sms", x)
        var worst: String? = null
        var pUrl = 0f
        var urlBand = Band.SAFE
        for (h in Featurizer.riskyHosts(text)) {
            val p = if (Featurizer.allowlisted(h, allow)) 0f else score("url", Featurizer.vector("url", h))
            if (p >= pUrl) { pUrl = p; worst = h; urlBand = band(p, urlT) }
        }
        val sigs = x.copyOfRange(Featurizer.DIM, x.size)
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
