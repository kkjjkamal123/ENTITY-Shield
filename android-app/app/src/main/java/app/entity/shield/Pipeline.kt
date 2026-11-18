package app.entity.shield

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class Alert(
    val id: Long,
    val at: Long,                  // wall clock, ms
    val source: String,            // sms | demo | check | share | net
    val sender: String,
    val text: String,
    val v: Verdict,
    val warnMs: Double,            // receive -> notification posted (time to first warning)
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("source", source).put("sender", sender).put("text", text)
        .put("kind", v.kind).put("band", v.band.name).put("score", v.score.toDouble()).put("family", v.family.name)
        .put("evidence", JSONArray(v.evidence)).put("host", v.worstHost ?: "").put("ms", v.millis).put("warnMs", warnMs)

    companion object {
        fun fromJson(o: JSONObject) = Alert(o.getLong("id"), o.getLong("at"), o.getString("source"), o.getString("sender"), o.getString("text"),
            Verdict(o.getString("kind"), Band.valueOf(o.getString("band")), o.getDouble("score").toFloat(), Family.valueOf(o.getString("family")),
                o.getJSONArray("evidence").let { a -> List(a.length()) { a.getString(it) } }, o.getString("host").ifEmpty { null }, o.getDouble("ms")),
            o.getDouble("warnMs"))
    }
}

/** Detection path shared by SMS, demo, paste/share and network alerts. Never touches Llama. */
object Pipeline {
    private const val TAG = "Shield"
    const val CHANNEL = "alerts"
    val alerts = mutableStateListOf<Alert>()   // newest first; UI reads it
    private var loaded = false
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicLong(System.currentTimeMillis())

    private fun file(ctx: Context) = File(ctx.filesDir, "alerts.jsonl")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        val items = f.readLines().mapNotNull { runCatching { Alert.fromJson(JSONObject(it)) }.getOrNull() }.sortedByDescending { it.at }
        main.post { alerts.clear(); alerts.addAll(items) }
    }

    /** t0 = SystemClock.elapsedRealtimeNanos() when the message reached us. Safe items are kept too (shown greyed). */
    fun onSms(ctx: Context, sender: String, text: String, source: String, t0: Long = SystemClock.elapsedRealtimeNanos()): Alert {
        val v = Detectors.get(ctx).checkSms(text, sender)
        return record(ctx, source, sender, text, v, t0)
    }

    fun onUrl(ctx: Context, link: String, source: String, t0: Long = SystemClock.elapsedRealtimeNanos()): Alert {
        val v = Detectors.get(ctx).checkUrl(link)
        return record(ctx, source, "", link, v, t0)
    }

    fun onNet(ctx: Context, v: Verdict, summary: String, device: String, t0: Long = SystemClock.elapsedRealtimeNanos()) =
        record(ctx, "net", device, summary, v, t0)

    private fun record(ctx: Context, source: String, sender: String, text: String, v: Verdict, t0: Long): Alert {
        load(ctx)
        var a = Alert(ids.incrementAndGet(), System.currentTimeMillis(), source, sender, text, v, 0.0)
        if (v.band != Band.SAFE && (source == "sms" || source == "demo" || source == "net")) notify(ctx, a)
        a = a.copy(warnMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        // The user will probably open this alert next: load Llama and pre-fill the fixed system prompt now,
        // off the detection path, so the explanation only pays for the message-specific tokens.
        if (v.band != Band.SAFE && source != "bench" && ctx.getSharedPreferences("shield", 0).getBoolean("prewarm", true) && Explainer.get(ctx).available())
            Explainer.get(ctx).prewarm(Advice.explainPrefix(Prefs.lang(ctx)))
        Log.i(TAG, "verdict source=$source band=${v.band} score=${"%.3f".format(v.score)} family=${v.family} detectMs=${"%.3f".format(v.millis)} warnMs=${"%.2f".format(a.warnMs)}")
        synchronized(this) { file(ctx).appendText(a.toJson().toString() + "\n") }
        main.post { alerts.add(0, a) }
        return a
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scam alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notify(ctx: Context, a: Alert) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val lang = Prefs.lang(ctx)
        val title = Advice.headline(a.v, lang)
        val body = if (a.v.kind == "net") a.text else Advice.steps(a.v.family, lang).first()
        val open = PendingIntent.getActivity(ctx, a.id.toInt(), Intent(ctx, MainActivity::class.java).putExtra("alert", a.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText("${a.sender.ifEmpty { "" }}\n$body"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(a.id.toInt(), n)
    }

    /** Benchmark only: post the same notification an alert would, so the timing includes it. */
    fun benchNotify(ctx: Context, v: Verdict, id: Int) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val lang = Prefs.lang(ctx)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(Advice.headline(v, lang))
            .setContentText(Advice.steps(v.family, lang).first())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun cancel(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java).cancel(id)

    fun clear(ctx: Context) {
        file(ctx).delete()
        main.post { alerts.clear() }
    }
}

object Prefs {
    fun lang(ctx: Context) = if (ctx.getSharedPreferences("shield", 0).getString("lang", "EN") == "HI") Advice.Lang.HI else Advice.Lang.EN
    fun setLang(ctx: Context, l: Advice.Lang) = ctx.getSharedPreferences("shield", 0).edit().putString("lang", l.name).apply()
}
