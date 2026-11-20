package app.entity.shield.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The app's scam gate must give the same answers as sms_gate() in training/featurize.py (gate_golden.json). */
class ScamGateTest {
    @Test fun matchesPython() {
        val g = JSONArray(File("../../training/sms/gate_golden.json").readText())
        for (i in 0 until g.length()) {
            val row = g.getJSONObject(i)
            val text = row.getString("text")
            val sender = row.getString("sender")
            val sig = Featurizer.smsSignals(text, sender)
            val want = row.getJSONArray("signals")
            for (j in sig.indices) assertEquals("#$i signal $j: $text", want.getDouble(j), sig[j].toDouble(), 0.0)
            val hosts = Featurizer.riskyHosts(text)
            val wantHosts = row.getJSONArray("hosts").let { a -> List(a.length()) { a.getString(it) } }
            assertEquals("#$i hosts: $text", wantHosts, hosts)
            val scam = row.getJSONArray("scam_hosts").let { a -> List(a.length()) { a.getString(it) } }
            val r = ScamGate.apply(Band.valueOf(row.getString("band_in")), sig, scam, text, sender, hosts)
            assertEquals("#$i band (${row.getString("band_in")}, scam=$scam): $sender | $text", Band.valueOf(row.getString("band")), r.band)
            assertEquals("#$i promo: $text", row.getBoolean("promo"), r.promo)
        }
    }

    @Test fun businessSender() {
        for (s in listOf("JT-JioPay-S", "JM-KVBANK", "JIOPAY", "AX-iPaytm", "VM-HDFCBK-T")) assertEquals(s, true, Featurizer.isBusinessSender(s))
        for (s in listOf("+919876543210", "919876543210", "57575701", "", "t2.ru")) assertEquals(s, false, Featurizer.isBusinessSender(s))
    }
}
