package app.entity.shield

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * In-app benchmark of record (ENTITY protocol: state the charging/screen state, several runs, raw CSV).
 *  1. Detector parity + latency (golden vectors, n = 1000).
 *  2. Time to first warning: message in -> detectors -> notification posted (n = 20).
 *  3. Llama explanation, cold vs pre-warmed system prompt (n runs each): TTFT, prefill, decode, energy.
 */
object Bench {
    data class Row(val test: String, val arm: String, val run: Int, val metric: String, val value: Double, val unit: String)

    val rows = mutableStateListOf<Row>()
    val log = mutableStateListOf<String>()
    var running by mutableStateOf(false)
    var lastCsv by mutableStateOf("")
    private val main = Handler(Looper.getMainLooper())

    private fun say(s: String) { Log.i("ShieldBench", s); main.post { log.add(s) } }
    private fun add(r: Row) = main.post { rows.add(r) }

    fun run(ctx: Context, llmRuns: Int = 3) {
        if (running) return
        running = true
        main.post { rows.clear(); log.clear() }
        Thread {
            try {
                val plugged = Power.plugged(ctx)
                say("Device: ${DeviceInfo.get(ctx).model} · ${DeviceInfo.get(ctx).soc} · ${if (plugged) "CHARGING (power columns invalid)" else "on battery"}")

                // 1. detectors
                say("Detectors: golden parity + latency…")
                say(runSelfTest(ctx).trim().replace("\n", "  ·  "))

                // 2. time to first warning (no history entries; the notifications are removed again)
                val d = Detectors.get(ctx)
                val warn = mutableListOf<Double>()
                repeat(20) { i ->
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val v = d.checkSms(SAMPLE_SCAM, "+919876543210")
                    Pipeline.benchNotify(ctx, v, 990_000 + i)
                    warn += (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    Pipeline.cancel(ctx, 990_000 + i)
                }
                warn.sort()
                warn.forEachIndexed { i, w -> add(Row("warning", "sms->notification", i, "ms", w, "ms")) }
                say("Time to first warning (detect + notification), n=20: p50 ${"%.1f".format(warn[10])} ms · p95 ${"%.1f".format(warn[19])} ms")

                // 3. Llama, cold vs warm
                val ex = Explainer.get(ctx)
                if (!ex.available()) { say("Llama: model not installed, skipped"); return@Thread }
                val v = d.checkSms(SAMPLE_SCAM, "+919876543210")
                val prompt = Advice.explainPrompt(v, SAMPLE_SCAM, Advice.Lang.EN)
                val prefix = Advice.explainPrefix(Advice.Lang.EN)
                for (arm in listOf("cold", "prewarmed")) {
                    for (r in 1..llmRuns) {
                        if (arm == "prewarmed") { ex.prewarm(prefix); Thread.sleep(200) }
                        val latch = CountDownLatch(1)
                        var stats: Explainer.Stats? = null
                        var text = ""
                        Power.start(ctx)
                        ex.run(prompt, 60, object : Explainer.Listener {
                            override fun onToken(piece: String) { text += piece }
                            override fun onDone(s: Explainer.Stats) { stats = s; latch.countDown() }
                            override fun onError(msg: String) { say("Llama error: $msg"); latch.countDown() }
                        }, if (arm == "prewarmed") prefix else null)
                        latch.await(180, TimeUnit.SECONDS)
                        val e = Power.stop()
                        val s = stats ?: continue
                        add(Row("llama", arm, r, "ttft", s.ttftMs.toDouble(), "ms"))
                        add(Row("llama", arm, r, "prefill", s.prefillTps ?: 0.0, "tok/s"))
                        add(Row("llama", arm, r, "decode", s.decodeTps ?: 0.0, "tok/s"))
                        add(Row("llama", arm, r, "total", s.totalMs.toDouble(), "ms"))
                        add(Row("llama", arm, r, "tokens", s.tokens.toDouble(), "tok"))
                        if (s.loadMs > 0) add(Row("llama", arm, r, "load", s.loadMs.toDouble(), "ms"))
                        if (!plugged && e != null) add(Row("llama", arm, r, "energy", e.first, "J"))
                        say("Llama $arm #$r: first token ${s.ttftMs} ms · prefill ${"%.1f".format(s.prefillTps ?: 0.0)} tok/s · decode ${"%.1f".format(s.decodeTps ?: 0.0)} tok/s · ${s.tokens} tok" +
                            (if (!plugged && e != null) " · ${"%.1f".format(e.first)} J" else "") + (if (s.loadMs > 0) " · load ${s.loadMs} ms" else ""))
                        if (r == 1) say("  “${text.trim().take(140)}”")
                    }
                }
            } catch (t: Throwable) {
                say("Bench failed: ${t.message}")
            } finally {
                val f = export(ctx)
                main.post { lastCsv = f.absolutePath; running = false }
                say("CSV: ${f.absolutePath}")
            }
        }.start()
    }

    fun export(ctx: Context): File {
        val dir = ctx.getExternalFilesDir("bench")!!.apply { mkdirs() }
        val info = DeviceInfo.get(ctx)
        val f = File(dir, "bench_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv")
        f.bufferedWriter().use { w ->
            w.write("device,soc,charging,model,test,arm,run,metric,value,unit\n")
            val model = Explainer.get(ctx).files()?.first?.name ?: ""
            val ch = Power.plugged(ctx)
            for (r in rows.toList()) w.write("\"${info.model}\",\"${info.soc}\",$ch,$model,${r.test},${r.arm},${r.run},${r.metric},${"%.4f".format(Locale.US, r.value)},${r.unit}\n")
        }
        return f
    }

    const val SAMPLE_SCAM = "Dear customer your SBI account will be blocked today. Update KYC immediately at http://sbi-kyc-update.in/verify"
}

/** Battery power while a workload runs (ENTITY's PowerMath idea). Only meaningful when unplugged. */
object Power {
    private var thread: Thread? = null
    @Volatile private var on = false
    private var joules = 0.0
    private var watts = mutableListOf<Double>()

    fun plugged(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun volts(ctx: Context): Double {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
    }

    fun start(ctx: Context) {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        on = true; joules = 0.0; watts = mutableListOf()
        thread = Thread {
            var last = SystemClock.elapsedRealtimeNanos()
            while (on) {
                Thread.sleep(100)
                val now = SystemClock.elapsedRealtimeNanos()
                val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble()
                val amps = if (abs(raw) > 10_000) abs(raw) / 1e6 else abs(raw) / 1e3 // µA on most phones, mA on some
                val w = amps * volts(ctx)
                watts += w
                joules += w * (now - last) / 1e9
                last = now
            }
        }.apply { start() }
    }

    /** (joules, mean watts), or null if nothing was sampled. */
    fun stop(): Pair<Double, Double>? {
        on = false
        thread?.join(500)
        return if (watts.isEmpty()) null else joules to watts.average()
    }
}
