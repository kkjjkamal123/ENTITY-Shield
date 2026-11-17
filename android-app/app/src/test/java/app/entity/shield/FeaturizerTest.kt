package app.entity.shield

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The Kotlin featurizer must produce the same vectors as training/featurize.py (golden.json). */
class FeaturizerTest {
    private fun golden(kind: String): JSONArray {
        // Gradle runs unit tests from the module dir: android-app/app
        val f = File("../../training/$kind/golden.json")
        return JSONArray(f.readText())
    }

    private fun check(kind: String) {
        val g = golden(kind)
        for (i in 0 until g.length()) {
            val row = g.getJSONObject(i)
            val text = row.getString("text")
            val sender = row.getString("sender")
            val input = if (kind == "url") Featurizer.urlHost(text) else text
            val h = Featurizer.hashed(input)
            val want = row.getJSONObject("hashed")
            assertEquals("$kind #$i slots: $text", want.keySet().map { it.toInt() }.toSortedSet(), h.keys.toSortedSet())
            for (k in want.keySet()) assertEquals("$kind #$i slot $k", want.getDouble(k), h[k.toInt()]!!, 1e-6)
            val sig = if (kind == "url") Featurizer.urlSignals(input) else Featurizer.smsSignals(text, sender)
            val wantSig = row.getJSONArray("signals")
            assertEquals("$kind #$i signal count", wantSig.length(), sig.size)
            for (j in 0 until sig.size) assertEquals("$kind #$i signal $j: $text", wantSig.getDouble(j), sig[j].toDouble(), 0.0)
        }
    }

    @Test fun smsMatchesPython() = check("sms")
    @Test fun urlMatchesPython() = check("url")

    @Test fun basics() {
        assertEquals(0x811C9DC5L, Featurizer.fnv1a32(ByteArray(0)))
        assertEquals(0xE40C292CL, Featurizer.fnv1a32("a".toByteArray()))
        assertEquals(" pay rs 0000 now ", Featurizer.normalize("Pay Rs 4999 NOW"))
        assertEquals("sbi-kyc.top", Featurizer.urlHost("HTTPS://user@Sbi-KYC.top:8080/a?b"))
    }
}
