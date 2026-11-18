package app.entity.shield

import android.content.Context
import org.json.JSONArray

data class Sample(val title: String, val sender: String, val text: String)

val SAMPLES = listOf(
    Sample("Bank KYC scam (EN)", "+919876543210", "Dear customer your SBI account will be blocked today. Update KYC immediately at http://sbi-kyc-update.in/verify"),
    Sample("Digital arrest (HI)", "+918765432109", "आपके आधार पर मनी लॉन्ड्रिंग केस दर्ज है। गिरफ्तारी से बचने के लिए तुरंत 9123456780 पर कॉल करें"),
    Sample("Electricity cut", "+917012345678", "Dear Consumer, your electricity power will be disconnected tonight at 9:30 pm because your previous month bill was not updated. Please immediately contact our electricity officer 9876501234"),
    Sample("Part-time job (Hinglish)", "+916398765432", "Ghar baithe kamaye Rs 5000 roz. Simple task, rating dene ka kaam. Abhi join karein t.me/earnfast_jobs"),
    Sample("UPI refund trick", "+919988776655", "You have received Rs 4,999 refund. Enter your UPI PIN to receive the money in your account. bit.ly/rfnd49"),
    Sample("Courier / customs", "+917788990011", "India Post: your parcel is held at customs due to incomplete address. Pay Rs 25 redelivery fee within 12 hours: indiapost-redelivery.top/pay"),
    Sample("Real bank OTP (safe)", "VM-HDFCBK", "482913 is your OTP for transaction of Rs 1,999 at Amazon. Valid for 10 mins. Do not share with anyone. -HDFC Bank"),
    Sample("Real delivery (safe)", "JD-AMAZON", "Your Amazon order will be delivered today by 9 PM. Track: amazon.in/your-orders"),
    Sample("Friend (safe)", "+919845012345", "Bhai kal ka plan cancel, office me late ho jayega. Sunday ko milte hain"),
)

/** On-device proof: golden vectors through the Kotlin featurizer + ExecuTorch must give the Python scores. */
fun runSelfTest(ctx: Context): String {
    val d = Detectors.get(ctx)
    val sb = StringBuilder()
    for (kind in listOf("sms", "url")) {
        val g = JSONArray(ctx.assets.open("${kind}_golden.json").bufferedReader().readText())
        var ok = 0
        var worst = 0.0
        for (i in 0 until g.length()) {
            val row = g.getJSONObject(i)
            val p = d.score(kind, Featurizer.vector(kind, row.getString("text"), row.getString("sender")))
            val diff = kotlin.math.abs(p - row.getDouble("score"))
            worst = maxOf(worst, diff)
            if (diff < 1e-4) ok++
        }
        sb.append("$kind golden: $ok/${g.length()} match (max diff ${"%.1e".format(worst)})\n")
    }
    val msg = SAMPLES[0]
    repeat(50) { d.checkSms(msg.text, msg.sender) }
    val ms = List(1000) { d.checkSms(msg.text, msg.sender).millis }.sorted()
    sb.append("SMS check, featurize + models, n=1000: p50 ${"%.3f".format(ms[500])} ms · p95 ${"%.3f".format(ms[950])} ms\n")
    val ums = List(1000) { d.checkUrl("https://hdfc-netbanking-verify.top/login").millis }.sorted()
    sb.append("URL check, n=1000: p50 ${"%.3f".format(ums[500])} ms · p95 ${"%.3f".format(ums[950])} ms\n")
    android.util.Log.i("Shield", "selftest " + sb.toString().replace("\n", " | "))
    return sb.toString()
}
