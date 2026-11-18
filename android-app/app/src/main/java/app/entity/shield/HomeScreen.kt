// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(lang: Advice.Lang, onOpen: (Alert) -> Unit, go: (Int) -> Unit) {
    val ctx = LocalContext.current
    val hi = lang == Advice.Lang.HI
    val alerts = Pipeline.alerts
    val recentScam = alerts.firstOrNull { it.v.band == Band.SCAM && System.currentTimeMillis() - it.at < 30 * 60_000 }
    val state = if (recentScam != null) Band.SCAM else Band.SAFE
    val smsOn = ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val netOn = NetGuard.vpnOn
    val guards = listOf(smsOn, netOn, true).count { it }
    val checked = alerts.count { it.source != "net" }
    val stopped = alerts.count { it.v.band == Band.SCAM } + NetGuard.blocked
    var info by remember { mutableStateOf<DeviceInfo.Info?>(null) }
    var ramFree by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) {
        thread { val i = DeviceInfo.get(ctx); info = i }
        while (true) { ramFree = DeviceInfo.refreshRam(ctx); delay(3000) }
    }
    val avgDetect = alerts.filter { it.source != "net" }.take(50).map { it.v.millis }.average().takeIf { !it.isNaN() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- hero
        Glass(Modifier.fillMaxWidth(), radius = 28.dp, pad = 18.dp, glow = Sh.band(state)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ShieldOrb(state, 200.dp)
                Text(
                    if (state == Band.SAFE) (if (hi) "आप सुरक्षित हैं" else "You're protected") else (if (hi) "एक ख़तरे पर ध्यान दें" else "A threat needs your attention"),
                    color = Sh.Text, fontSize = 24.sp, fontWeight = Sh.Black, textAlign = TextAlign.Center,
                )
                Text(
                    "$guards ${if (hi) "गार्ड चालू" else "guards active"}  ·  ${if (hi) "कोई डेटा फ़ोन से बाहर नहीं गया" else "0 bytes sent to any cloud"}",
                    color = Sh.Text2, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
                if (recentScam != null) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Sh.Red.copy(alpha = 0.18f)).clickable { onOpen(recentScam) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("${Advice.headline(recentScam.v, lang)}  →", color = Sh.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // ---- stats
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("$checked", if (hi) "मैसेज/लिंक जांचे" else "messages & links checked", Sh.Cyan, Modifier.weight(1f))
            StatTile("$stopped", if (hi) "ख़तरे रोके" else "threats stopped", Sh.Red, Modifier.weight(1f))
            StatTile("${NetGuard.queries}", if (hi) "नेटवर्क लुकअप जांचे" else "network lookups inspected", Sh.Violet, Modifier.weight(1f))
        }

        // ---- guards
        SectionTitle(if (hi) "सुरक्षा गार्ड" else "Guards")
        GuardRow("💬", if (hi) "SMS गार्ड" else "SMS Guard",
            if (smsOn) (if (hi) "हर SMS इसी फ़ोन पर जांचा जाता है" else "Every SMS is checked on this phone" + (avgDetect?.let { " · ~${"%.0f".format(it)} ms" } ?: ""))
            else (if (hi) "SMS अनुमति दें" else "Allow SMS permission to turn on"), smsOn, onToggle = null)
        GuardRow("🌐", if (hi) "नेटवर्क गार्ड" else "Network Guard",
            if (netOn) "${NetGuard.apps} ${if (hi) "ऐप देखे" else "apps watched"} · ${NetGuard.blocked} ${if (hi) "ब्लॉक" else "blocked"} · ${if (hi) "ट्रिपवायर" else "tripwire"} ${NetGuard.listening.joinToString(",")}"
            else (if (hi) "हर ऐप के DNS लुकअप जांचें, धोखे वाली साइटें ब्लॉक करें, Wi-Fi पर जासूसी पकड़ें" else "Checks every app's DNS lookups, blocks scam sites, catches Wi-Fi probes"),
            netOn, onToggle = { (ctx as MainActivity).setNetworkGuard(it) })
        GuardRow("🔗", if (hi) "लिंक गार्ड" else "Link Guard",
            if (hi) "लिंक खोलते समय \"ENTITY Shield\" चुनें: पहले जांच, फिर ब्राउज़र" else "Pick \"Check link (Shield)\" when opening a link: checked first, then your browser", true, onToggle = null)

        // ---- on-device AI
        SectionTitle(if (hi) "इसी फ़ोन पर AI" else "On-device AI")
        Glass(Modifier.fillMaxWidth(), radius = 20.dp, glow = Sh.Violet) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph("🧠", Sh.Violet)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Explainer.get(ctx).modelLabel(), color = Sh.Text, fontWeight = Sh.Bold, fontSize = 15.sp)
                        Text("ExecuTorch 1.5.1 · " + (info?.backends?.joinToString(" · ")?.ifEmpty { null } ?: "XNNPACK") + " · Arm CPU", color = Sh.Text2, fontSize = 12.sp)
                    }
                    Pill(if (Explainer.get(ctx).loaded) "LOADED" else if (Explainer.get(ctx).available()) "READY" else "MISSING",
                        if (Explainer.get(ctx).available()) Sh.Green else Sh.Amber, filled = true)
                }
                info?.let { i ->
                    Text(i.soc, color = Sh.Text, fontWeight = Sh.Bold, fontSize = 14.sp)
                    Text(i.cores, color = Sh.Text2, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        i.features.forEach { (f, has) -> Pill((if (has) "✓ " else "✗ ") + f, if (has) Sh.Cyan else Sh.Text3, filled = has) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RAM ${"%.1f".format(ramFree)} / ${"%.1f".format(i.ramTotalGb)} GB free", color = Sh.Text2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        MeterBar((1 - ramFree / i.ramTotalGb).toFloat(), Sh.Violet, Modifier.width(120.dp))
                    }
                }
                Text(if (hi) "डिटेक्टर तय करते हैं (मिलीसेकंड), Llama सिर्फ़ समझाता है: फ़ैसला कभी नहीं बदलता।" else "Detectors decide in milliseconds; Llama only explains, and can never change a verdict.",
                    color = Sh.Text3, fontSize = 11.sp)
            }
        }

        // ---- latest threats
        val threats = alerts.filter { it.v.band != Band.SAFE }.take(3)
        SectionTitle(if (hi) "हाल के ख़तरे" else "Latest threats", if (threats.isNotEmpty()) (if (hi) "सभी" else "See all") else null) { go(1) }
        if (threats.isEmpty()) Glass(Modifier.fillMaxWidth()) {
            Text(if (hi) "अभी कोई ख़तरा नहीं। Lab टैब में डेमो आज़माएं।" else "Nothing yet. Try a demo scam in the Lab tab.", color = Sh.Text2, fontSize = 13.sp)
        } else threats.forEach { AlertRow(it, lang) { onOpen(it) } }

        // ---- quick actions
        SectionTitle(if (hi) "तुरंत करें" else "Quick actions")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("⌕", if (hi) "मैसेज/लिंक जांचें" else "Check a message", Sh.Cyan, Modifier.weight(1f)) { go(3) }
            QuickAction("📥", if (hi) "इनबॉक्स स्कैन" else "Scan my inbox", Sh.Violet, Modifier.weight(1f)) { go(4) }
            QuickAction("▶", if (hi) "डेमो स्कैम" else "Try a demo", Sh.Pink, Modifier.weight(1f)) { go(4) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun QuickAction(g: String, label: String, c: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(c.copy(alpha = 0.22f), Sh.Glass))).clickable { onClick() }.padding(14.dp)) {
        Column {
            Text(g, fontSize = 20.sp, color = c)
            Spacer(Modifier.height(6.dp))
            Text(label, color = Sh.Text, fontSize = 12.sp, fontWeight = Sh.Bold, lineHeight = 14.sp)
        }
    }
}
