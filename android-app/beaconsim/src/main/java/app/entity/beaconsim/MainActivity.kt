package app.entity.beaconsim

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * "Beacon Simulator (test)": behaves like an infected app on the network, harmlessly. Every action is a
 * DNS lookup of a made-up name (they do not exist), which is exactly what ENTITY Shield's Network Guard
 * watches. Modes can also be started over adb: am start -n app.entity.beaconsim/.MainActivity --es mode beacon
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 120, 40, 40); setBackgroundColor(Color.rgb(20, 20, 28)) }
        col.addView(TextView(this).apply { text = "Beacon Simulator (test)"; textSize = 22f; setTextColor(Color.WHITE) })
        col.addView(TextView(this).apply {
            text = "Imitates malware network behaviour for the ENTITY Shield demo. Only DNS lookups of made-up names; no data is sent."
            setTextColor(Color.LTGRAY); setPadding(0, 10, 0, 30)
        })
        fun btn(label: String, f: () -> Unit) = col.addView(Button(this).apply { text = label; setOnClickListener { f() } })
        btn("C2 beacon: check in every 10 s") { start("beacon") }
        btn("DGA burst: 20 random domains") { start("dga") }
        btn("DNS exfiltration: long encoded names") { start("exfil") }
        btn("Stop") { running = false; say("stopped") }
        log = TextView(this).apply { setTextColor(Color.rgb(120, 255, 200)); typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; gravity = Gravity.START }
        col.addView(ScrollView(this).apply { addView(log) })
        setContentView(col)
        intent?.getStringExtra("mode")?.let { start(it) }
    }

    private fun say(s: String) = runOnUiThread { log.text = "$s\n${log.text}".take(4000) }

    private fun lookup(name: String) {
        val t0 = System.nanoTime()
        val r = runCatching { InetAddress.getAllByName(name) }
        say("${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms  $name  -> ${if (r.isSuccess) "resolved" else "no answer"}")
    }

    private fun rnd(n: Int) = (1..n).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

    private fun start(mode: String) {
        if (running) return
        running = true
        thread {
            when (mode) {
                "beacon" -> { var i = 0; while (running && i < 12) { lookup("${rnd(8)}.cdn-telemetry-sync.xyz"); i++; Thread.sleep(10_000) } }
                "dga" -> repeat(20) { if (running) { lookup("${rnd(12 + Random.nextInt(6))}.${listOf("com", "net", "xyz", "info", "top")[Random.nextInt(5)]}"); Thread.sleep(400) } }
                "exfil" -> repeat(18) { if (running) { lookup("${rnd(40)}.${rnd(20)}.data-sync-cache.top"); Thread.sleep(700) } }
            }
            running = false
            say("done: $mode")
        }
    }
}
