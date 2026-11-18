package app.entity.shield

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real-inbox audit: the trust number. Runs the detectors over the phone's own SMS inbox, then the owner
 * marks each flagged message (and a random sample of unflagged ones) as scam or not. Messages never
 * leave the phone; only the counts are exported (audit_<date>.json) for the report.
 */
object InboxAudit {
    data class Item(val id: Long, val sender: String, val text: String, val date: Long, val v: Verdict)

    val flagged = mutableStateListOf<Item>()
    val sample = mutableStateListOf<Item>()          // random unflagged messages to spot-check for misses
    val labels = mutableStateMapOf<Long, Boolean>()   // id -> owner says it IS a scam
    var scanned by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var msPerMsg by mutableStateOf(0.0)
    private val main = Handler(Looper.getMainLooper())

    fun scan(ctx: Context, limit: Int = 1000, sampleSize: Int = 40) {
        if (running) return
        running = true
        Thread {
            val d = Detectors.get(ctx)
            val all = mutableListOf<Item>()
            var total = 0.0
            runCatching {
                ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id", "address", "body", "date"), null, null, "date DESC")?.use { c ->
                    while (c.moveToNext() && all.size < limit) {
                        val body = c.getString(2) ?: continue
                        val sender = c.getString(1) ?: ""
                        val v = d.checkSms(body, sender)
                        total += v.millis
                        all += Item(c.getLong(0), sender, body, c.getLong(3), v)
                    }
                }
            }
            val fl = all.filter { it.v.band != Band.SAFE }
            val safe = all.filter { it.v.band == Band.SAFE }.shuffled(java.util.Random(7)).take(sampleSize)
            main.post {
                flagged.clear(); flagged.addAll(fl); sample.clear(); sample.addAll(safe)
                scanned = all.size; msPerMsg = if (all.isEmpty()) 0.0 else total / all.size; running = false
            }
        }.start()
    }

    data class Summary(val scanned: Int, val flagged: Int, val flaggedLabelled: Int, val confirmed: Int, val falseAlarms: Int,
                       val sampleLabelled: Int, val missed: Int) {
        val precision get() = if (flaggedLabelled == 0) null else confirmed.toDouble() / flaggedLabelled
        /** False alarms over all messages that are not scams (estimated: flagged FPs + unflagged assumed ham). */
        val fpr get() = if (scanned == 0) null else falseAlarms.toDouble() / maxOf(1, scanned - confirmed)
        val missRate get() = if (sampleLabelled == 0) null else missed.toDouble() / sampleLabelled
    }

    fun summary(): Summary {
        val fl = flagged.filter { labels.containsKey(it.id) }
        val sm = sample.filter { labels.containsKey(it.id) }
        return Summary(scanned, flagged.size, fl.size, fl.count { labels[it.id] == true }, fl.count { labels[it.id] == false }, sm.size, sm.count { labels[it.id] == true })
    }

    /** Counts only. No message text, no sender, no dates of individual messages. */
    fun export(ctx: Context): File {
        val s = summary()
        val o = JSONObject().put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("scanned", s.scanned).put("flagged", s.flagged).put("flagged_labelled", s.flaggedLabelled)
            .put("confirmed_scams", s.confirmed).put("false_alarms", s.falseAlarms)
            .put("unflagged_sample_labelled", s.sampleLabelled).put("missed_in_sample", s.missed)
            .put("precision", s.precision ?: JSONObject.NULL).put("fpr_estimate", s.fpr ?: JSONObject.NULL)
            .put("ms_per_message", msPerMsg)
        val dir = ctx.getExternalFilesDir("audit")!!.apply { mkdirs() }
        return File(dir, "audit_${o.getString("date")}.json").apply { writeText(o.toString(1)) }
    }
}
