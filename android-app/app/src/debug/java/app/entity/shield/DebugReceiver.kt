package app.entity.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Debug builds only (src/debug). Lets scripts/e2e_phone.py drive the real pipeline over adb:
 *
 *   adb shell am broadcast -n app.entity.shield/.DebugReceiver -a app.entity.shield.DEBUG \
 *       --es cmd sms --es from +919876543210 --es body "Your SBI KYC..."
 *
 * cmd: sms (from, body) | url (link) | explain (lang EN|HI, optional id) | ask (q, lang) | selftest
 * Every result is logged under the tag "ShieldE2E" as one line: key=value pairs, easy to grep.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        thread(name = "e2e-$cmd") {
            try {
                when (cmd) {
                    "sms" -> {
                        val a = Pipeline.onSms(app, intent.getStringExtra("from") ?: "", intent.getStringExtra("body") ?: "", "demo")
                        log("sms id=${a.id} band=${a.v.band} score=${"%.4f".format(a.v.score)} family=${a.v.family} detectMs=${"%.3f".format(a.v.millis)} warnMs=${"%.2f".format(a.warnMs)} evidence=${a.v.evidence.joinToString(",")}")
                    }
                    "url" -> {
                        val a = Pipeline.onUrl(app, intent.getStringExtra("link") ?: "", "check")
                        log("url id=${a.id} host=${a.v.worstHost} band=${a.v.band} score=${"%.4f".format(a.v.score)} evidence=${a.v.evidence.joinToString(",")}")
                    }
                    "explain", "ask" -> {
                        Pipeline.load(app)
                        val id = intent.getLongExtra("id", 0L)
                        val a = (if (id != 0L) Pipeline.alerts.firstOrNull { it.id == id } else null)
                            ?: Pipeline.alerts.firstOrNull { it.v.band != Band.SAFE }
                            ?: return@thread log("$cmd error=no_flagged_alert")
                        val lang = if (intent.getStringExtra("lang") == "HI") Advice.Lang.HI else Advice.Lang.EN
                        val prompt = if (cmd == "explain") Advice.explainPrompt(a.v, a.text, lang)
                            else Advice.followUpPrompt(a.v, a.text, intent.getStringExtra("q") ?: "I already clicked the link. What now?", lang)
                        val sb = StringBuilder()
                        val done = java.util.concurrent.CountDownLatch(1)
                        val warm = cmd == "explain" && intent.getStringExtra("warm") == "1"
                        val prefix = if (warm) Advice.explainPrefix(lang) else null
                        if (warm) { Explainer.get(app).prewarm(prefix!!); Thread.sleep(300) }
                        Explainer.get(app).run(prompt, intent.getIntExtra("max", 80), object : Explainer.Listener {
                            override fun onToken(piece: String) { sb.append(piece) }
                            override fun onDone(stats: Explainer.Stats) {
                                log("$cmd id=${a.id} lang=$lang warm=${stats.prewarmed} loadMs=${stats.loadMs} ttftMs=${stats.ttftMs} totalMs=${stats.totalMs} tokens=${stats.tokens} stats=${stats.raw.replace(" ", "")}")
                                log("$cmd text=${sb.toString().replace("\n", " ").trim()}")
                                done.countDown()
                            }
                            override fun onError(msg: String) { log("$cmd error=$msg"); done.countDown() }
                        }, prefix)
                        done.await(120, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    "selftest" -> log("selftest " + runSelfTest(app).replace("\n", " | "))
                    "net" -> {
                        val on = intent.getStringExtra("state") != "off"
                        if (on) ShieldVpnService.start(app) else ShieldVpnService.stop(app)
                        Thread.sleep(1500)
                        log("net requested=$on vpnOn=${NetGuard.vpnOn} listening=${NetGuard.listening.joinToString(",")} ip=${NetGuard.phoneIp} upstream=${NetGuard.upstream}")
                    }
                    "netstat" -> log("netstat vpnOn=${NetGuard.vpnOn} queries=${NetGuard.queries} blocked=${NetGuard.blocked} flagged=${NetGuard.flagged} apps=${NetGuard.apps} trip=${NetGuard.tripHits} last=" +
                        NetGuard.dnsEvents.take(8).joinToString(";") { "${it.app}:${it.host}:${it.action}:${it.rcode}" })
                    "bench" -> { Bench.run(app, intent.getIntExtra("runs", 3)); while (!Bench.running) Thread.sleep(50); while (Bench.running) Thread.sleep(500); log("bench done csv=${Bench.lastCsv}") }
                    "alerts" -> log("alerts " + Pipeline.alerts.take(intent.getIntExtra("n", 5)).joinToString(" || ") { "${it.source}:${it.v.band}:${it.v.family}:${it.text.take(90)}" })
                    else -> log("unknown cmd=$cmd")
                }
            } catch (t: Throwable) {
                log("$cmd exception=${t.javaClass.simpleName}:${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun log(s: String) { Log.i("ShieldE2E", s) }
}
