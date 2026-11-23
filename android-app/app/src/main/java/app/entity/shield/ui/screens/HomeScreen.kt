// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.entity.shield.MainActivity
import app.entity.shield.bench.DeviceInfo
import app.entity.shield.core.Advice
import app.entity.shield.core.Band
import app.entity.shield.core.Pipeline
import app.entity.shield.llm.Explainer
import app.entity.shield.net.NetGuard
import app.entity.shield.ui.LocalLang
import app.entity.shield.ui.LocalAdaptive
import app.entity.shield.ui.LocalNav
import app.entity.shield.ui.LocalTabActive
import app.entity.shield.ui.Route
import app.entity.shield.ui.TAB_ALERTS
import app.entity.shield.ui.TAB_SCAN
import app.entity.shield.ui.TabPage
import app.entity.shield.ui.components.*
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Glyph
import app.entity.shield.ui.theme.Ico
import app.entity.shield.ui.theme.Sh
import app.entity.shield.ui.tr
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

@Composable
fun HomeScreen(@Suppress("UNUSED_PARAMETER") shared: MutableState<String?>) {
    val ctx = LocalContext.current
    val c = Sh.c
    val nav = LocalNav.current
    val lang = LocalLang.current
    val alerts = Pipeline.alerts
    val recent by remember { derivedStateOf { alerts.firstOrNull { it.v.band == Band.SCAM && System.currentTimeMillis() - it.at < 30 * 60_000 } } }
    val smsOn = ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val netOn = NetGuard.vpnOn
    val guards = listOf(smsOn, netOn, true).count { it }
    val checked by remember { derivedStateOf { alerts.count { it.source != "net" } } }
    val scams by remember { derivedStateOf { alerts.count { it.v.band == Band.SCAM } } }
    val stopped = scams + NetGuard.blocked
    // typical check time: the median, so one cold start or GC pause does not stand for every message
    val avgMs = remember(alerts.size) { alerts.filter { it.source != "net" }.take(50).map { it.v.millis }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] } }

    TabPage {
        // header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = Dim.s)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(app.entity.shield.R.drawable.ic_entity_mark), contentDescription = "ENTITY", modifier = Modifier.height(26.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("ENTITY Shield", style = Sh.t.headline, color = c.text)
                Text(tr("On-device · Offline · Arm", "इसी फ़ोन पर · ऑफ़लाइन · Arm"), style = Sh.t.caption, color = c.text3)
            }
            LangSwitch()
        }

        // status hero
        Card(pad = Dim.xl, tone = if (recent != null) Tone.Danger else null) {
            val status: @Composable () -> Unit = {
                Text(tr("STATUS", "स्थिति"), style = Sh.t.overline, color = c.text3)
                Text(if (recent != null) tr("Threat found", "ख़तरा मिला") else tr("Protected", "सुरक्षित"),
                    style = Sh.t.title1, color = c.text, modifier = Modifier.padding(top = 2.dp))
                Text(tr("$guards of 3 guards on", "3 में से $guards गार्ड चालू"), style = Sh.t.callout, color = c.text2)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Glyph(Ico.Lock, c.text3, 14.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(tr("Nothing leaves this phone", "कुछ भी फ़ोन से बाहर नहीं जाता"), style = Sh.t.caption, color = c.text3)
                }
            }
            if (LocalAdaptive.current.compact) {   // small phones: rings above the text
                GuardRings(smsOn, netOn, true, recent != null, 100.dp)
                Spacer(Modifier.height(Dim.l))
                status()
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                GuardRings(smsOn, netOn, true, recent != null, 116.dp)
                Spacer(Modifier.width(Dim.xl))
                Column(Modifier.weight(1f)) { status() }
            }
            if (recent != null) {
                Spacer(Modifier.height(Dim.l))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.danger.copy(alpha = 0.16f))
                        .pressable { recent?.let { nav.open(Route.AlertR(it.id)) } }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(Ico.Alert, c.dangerText, 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(Advice.headline(recent!!.v, lang), style = Sh.t.callout, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(tr("View", "देखें"), style = Sh.t.caption, color = c.dangerText)
                }
            }
        }

        // numbers
        Card {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Stat("$checked", tr("checked", "जांचे"), Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(36.dp).background(c.line))
                Spacer(Modifier.width(Dim.l))
                Stat("$stopped", tr("stopped", "रोके"), Modifier.weight(1f), if (stopped > 0) Tone.Danger else Tone.Neutral)
                Box(Modifier.width(1.dp).height(36.dp).background(c.line))
                Spacer(Modifier.width(Dim.l))
                Stat("${NetGuard.queries}", tr("lookups seen", "लुकअप देखे"), Modifier.weight(1f))
            }
        }

        // guards
        SectionHeader(tr("Guards", "गार्ड"))
        Card(pad = Dim.l) {
            ListRow(
                tr("SMS Guard", "SMS गार्ड"),
                if (smsOn) tr("Every SMS checked here", "हर SMS यहीं जांचा जाता है") + (avgMs?.let { " · ${"%.1f".format(it)} ms" } ?: "")
                else tr("Allow SMS access to turn on", "चालू करने के लिए SMS अनुमति दें"),
                Ico.Message, if (smsOn) Tone.Accent else Tone.Neutral,
                trailing = { Tag(if (smsOn) tr("On", "चालू") else tr("Off", "बंद"), if (smsOn) Tone.Accent else Tone.Neutral) },
            )
            Divider()
            ListRow(
                tr("Network Guard", "नेटवर्क गार्ड"),
                if (netOn) tr("${NetGuard.apps} apps watched · ${NetGuard.blocked} stopped", "${NetGuard.apps} ऐप पर नज़र · ${NetGuard.blocked} रोके")
                else tr("Checks every app's lookups, stops known scam sites", "हर ऐप के लुकअप जांचता है, धोखे की साइटें रोकता है"),
                Ico.Globe, if (netOn) Tone.Accent else Tone.Neutral,
                trailing = { Toggle(netOn) { (ctx as MainActivity).setNetworkGuard(it) } },
            )
            Divider()
            ListRow(
                tr("Link Guard", "लिंक गार्ड"),
                tr("Open links with Shield: checked in a sandbox first", "लिंक Shield से खोलें: पहले सैंडबॉक्स में जांच"),
                Ico.Link, Tone.Accent,
                trailing = { Tag(tr("Ready", "तैयार"), Tone.Accent) },
            )
        }

        // quick actions
        SectionHeader(tr("Check something", "कुछ जांचें"))
        val compact = LocalAdaptive.current.compact
        if (compact) {
            QuickTile(Ico.Scan, tr("Text or link", "मैसेज/लिंक"), tr("Paste and check", "चिपकाएं, जांचें"), Modifier.fillMaxWidth()) { nav.tab(TAB_SCAN) }
            QuickTile(Ico.Inbox, tr("My inbox", "मेरा इनबॉक्स"), tr("Scan all SMS", "सारे SMS जांचें"), Modifier.fillMaxWidth()) { nav.open(Route.Inbox) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(Dim.m)) {
            QuickTile(Ico.Scan, tr("Text or link", "मैसेज/लिंक"), tr("Paste and check", "चिपकाएं, जांचें"), Modifier.weight(1f)) { nav.tab(TAB_SCAN) }
            QuickTile(Ico.Inbox, tr("My inbox", "मेरा इनबॉक्स"), tr("Scan all SMS", "सारे SMS जांचें"), Modifier.weight(1f)) { nav.open(Route.Inbox) }
        }
        QuickTile(Ico.Play, tr("Try a demo scam", "डेमो स्कैम आज़माएं"), tr("Same path as a real SMS. Works offline.", "असली SMS जैसा रास्ता। ऑफ़लाइन चलता है।"), Modifier.fillMaxWidth()) { nav.open(Route.Demo) }

        // latest threats
        val threats by remember { derivedStateOf { alerts.asSequence().filter { it.v.band != Band.SAFE }.take(3).toList() } }
        SectionHeader(tr("Latest", "हाल के"), if (threats.isNotEmpty()) tr("See all", "सभी") else null) { nav.tab(TAB_ALERTS) }
        Card(pad = Dim.l) {
            if (threats.isEmpty()) ListRow(tr("All clear", "सब ठीक"), tr("No threats yet. Try the demo above.", "अभी कोई ख़तरा नहीं। ऊपर डेमो आज़माएं।"), Ico.ShieldCheck, Tone.Accent)
            else threats.forEachIndexed { i, a -> if (i > 0) Divider(); AlertRow(a) { nav.open(Route.AlertR(a.id)) } }
        }

        // the phone itself
        SectionHeader(tr("On this phone", "इस फ़ोन पर"))
        DeviceCard()
    }
}

@Composable
private fun QuickTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier, pad = Dim.l, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, Tone.Neutral, 38.dp)
            Spacer(Modifier.width(Dim.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = Sh.t.headline, color = Sh.c.text, maxLines = 2)
                Text(sub, style = Sh.t.caption, color = Sh.c.text2, maxLines = 2)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceCard() {
    val ctx = LocalContext.current
    val c = Sh.c
    var info by remember { mutableStateOf<DeviceInfo.Info?>(null) }
    var ramFree by remember { mutableDoubleStateOf(0.0) }
    val active = LocalTabActive.current
    LaunchedEffect(Unit) { info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DeviceInfo.get(ctx) } }
    LaunchedEffect(active) {   // poll memory only while Home is on screen, never on the main thread
        while (active) { ramFree = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DeviceInfo.refreshRam(ctx) }; delay(4000) }
    }
    val ex = remember { Explainer.get(ctx) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Ico.Chip, Tone.Accent, 40.dp)
            Spacer(Modifier.width(Dim.m))
            Column(Modifier.weight(1f)) {
                Text(ex.modelLabel(), style = Sh.t.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("ExecuTorch 1.5.1 · " + (info?.backends?.joinToString(" · ")?.ifEmpty { null } ?: "XNNPACK"), style = Sh.t.caption, color = c.text2, maxLines = 1)
            }
            val nav = LocalNav.current
            if (ex.available()) Tag(if (ex.loaded) tr("Loaded", "लोड") else tr("Ready", "तैयार"), Tone.Accent)
            else Chip(tr("Install", "इंस्टॉल")) { nav.tab(app.entity.shield.ui.TAB_MORE) }
        }
        info?.let { i ->
            Spacer(Modifier.height(Dim.l))
            Text(i.soc, style = Sh.t.callout.copy(fontWeight = Sh.t.headline.fontWeight), color = c.text)
            Text(i.cores, style = Sh.t.caption, color = c.text2)
            Spacer(Modifier.height(Dim.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                i.features.forEach { (f, has) -> Tag(f, if (has) Tone.Accent else Tone.Neutral) }
            }
            Spacer(Modifier.height(Dim.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Memory free", "ख़ाली मेमोरी"), style = Sh.t.caption, color = c.text2, modifier = Modifier.weight(1f))
                Text("${"%.1f".format(ramFree)} / ${"%.1f".format(i.ramTotalGb)} GB", style = Sh.t.monoSmall, color = c.text)
            }
            Spacer(Modifier.height(6.dp))
            Meter((1 - ramFree / i.ramTotalGb).toFloat(), c.text2, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Dim.m))
        Text(tr("Detectors decide in milliseconds. Llama only explains and can never change a verdict.",
            "डिटेक्टर मिलीसेकंड में तय करते हैं। Llama सिर्फ़ समझाता है, फ़ैसला कभी नहीं बदलता।"), style = Sh.t.caption, color = c.text3)
    }
}
