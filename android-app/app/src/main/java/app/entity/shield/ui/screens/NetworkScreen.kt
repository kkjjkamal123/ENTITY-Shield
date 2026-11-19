// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.entity.shield.core.Pipeline
import app.entity.shield.net.NetGuard
import app.entity.shield.ui.LocalAdaptive
import app.entity.shield.ui.LocalNav
import app.entity.shield.ui.sideCutoutPadding
import app.entity.shield.ui.LocalTabActive
import app.entity.shield.ui.Route
import app.entity.shield.ui.components.*
import app.entity.shield.ui.navClearance
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Glyph
import app.entity.shield.ui.theme.Ico
import app.entity.shield.ui.theme.Sh
import app.entity.shield.ui.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NetworkScreen() {
    val ctx = LocalContext.current
    val c = Sh.c
    val nav = LocalNav.current
    var view by remember { mutableIntStateOf(0) }
    val on = NetGuard.vpnOn
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var blocking by remember { mutableStateOf(NetGuard.blocking) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().sideCutoutPadding(),
        contentPadding = PaddingValues(start = LocalAdaptive.current.listSide, end = LocalAdaptive.current.listSide, top = Dim.s, bottom = navClearance()),
        verticalArrangement = Arrangement.spacedBy(Dim.s),
    ) {
        item { ScreenTitle(tr("Network", "नेटवर्क"), tr("Every app's lookups, checked on this phone", "हर ऐप के लुकअप, इसी फ़ोन पर जांचे")) }
        item {
            Card(pad = Dim.xl, tone = if (on) Tone.Accent else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (on) c.accent else c.text3, live = on && LocalTabActive.current)
                    Spacer(Modifier.width(8.dp))
                    Text(if (on) tr("Watching", "नज़र रखे हुए") else tr("Off", "बंद"), style = Sh.t.overline, color = if (on) c.accentText else c.text3, modifier = Modifier.weight(1f))
                    Toggle(on) { (ctx as MainActivity).setNetworkGuard(it) }
                }
                Text(tr("Network Guard", "नेटवर्क गार्ड"), style = Sh.t.title1, color = c.text, modifier = Modifier.padding(top = Dim.m))
                Text(
                    if (on) tr("Local DNS filter · resolver ${NetGuard.upstream.ifEmpty { "auto" }} · phone ${NetGuard.phoneIp}", "लोकल DNS फ़िल्टर · ${NetGuard.upstream.ifEmpty { "auto" }} · फ़ोन ${NetGuard.phoneIp}")
                    else tr("A local VPN that only sees DNS lookups. Nothing is sent anywhere else.", "एक लोकल VPN जो सिर्फ़ DNS लुकअप देखता है। कुछ और कहीं नहीं भेजा जाता।"),
                    style = Sh.t.callout, color = c.text2, modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(Dim.l))
                Row {
                    Stat("${NetGuard.queries}", tr("lookups", "लुकअप"), Modifier.weight(1f))
                    Stat("${NetGuard.blocked}", tr("stopped", "रोके"), Modifier.weight(1f), if (NetGuard.blocked > 0) Tone.Danger else Tone.Neutral)
                    Stat("${NetGuard.flagged}", tr("flagged", "चिह्नित"), Modifier.weight(1f), if (NetGuard.flagged > 0) Tone.Warn else Tone.Neutral)
                    Stat("${NetGuard.apps}", tr("apps", "ऐप"), Modifier.weight(1f))
                }
            }
        }
        item {
            Card(pad = Dim.l) {
                ListRow(tr("Stop scam sites", "धोखे की साइटें रोकें"),
                    tr("Only reported phishing sites and near-certain fakes. Popular sites and apps' own servers are never stopped.",
                        "सिर्फ़ रिपोर्ट की गई और लगभग पक्की नकली साइटें। लोकप्रिय साइटें और ऐप के अपने सर्वर कभी नहीं रुकते।"),
                    Ico.ShieldCheck, Tone.Accent, trailing = { Toggle(blocking) { blocking = it; NetGuard.setBlocking(ctx, it) } })
                Divider()
                ListRow(tr("Allowed sites", "चालू की गई साइटें"), tr("Sites you told Shield to trust", "जिन साइटों पर आपने भरोसा किया"), Ico.Check, Tone.Neutral,
                    trailing = { Glyph(Ico.Chevron, c.text3, 18.dp) }) { nav.open(Route.Allowed) }
                Divider()
                ListRow(tr("LAN tripwire", "LAN ट्रिपवायर"),
                    if (NetGuard.listening.isEmpty()) tr("Decoy ports, on while Network Guard is on", "नकली पोर्ट, नेटवर्क गार्ड के साथ चालू")
                    else tr("Ports ${NetGuard.listening.joinToString(", ")} · ${NetGuard.tripHits} touches", "पोर्ट ${NetGuard.listening.joinToString(", ")} · ${NetGuard.tripHits} बार छुए"),
                    Ico.Radar, if (NetGuard.listening.isNotEmpty()) Tone.Info else Tone.Neutral)
            }
        }
        item { Spacer(Modifier.height(Dim.xs)); Segmented(listOf(tr("Live", "लाइव"), tr("Apps", "ऐप"), tr("Tripwire", "ट्रिपवायर"), tr("Alerts", "अलर्ट")), view) { view = it } }
        when (view) {
            0 -> {
                val evs = NetGuard.dnsEvents.take(150)
                if (evs.isEmpty()) item { EmptyState(Ico.Globe, tr("No lookups yet", "अभी कोई लुकअप नहीं"), tr("Turn on Network Guard and use your phone as usual.", "नेटवर्क गार्ड चालू करें और फ़ोन हमेशा की तरह चलाएं।")) }
                items(evs, key = { it.at.toString() + it.host + it.uid + it.qtype }) { e -> DnsRow(e, fmt) }
            }
            1 -> {
                val byApp = NetGuard.dnsEvents.groupBy { it.uid }.map { (_, evs) -> evs.first() to (evs.size to evs.count { it.action != "ALLOW" }) }.sortedByDescending { it.second.first }
                items(byApp, key = { it.first.uid }) { (e, n) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(e.pkg)
                        Spacer(Modifier.width(Dim.m))
                        Column(Modifier.weight(1f)) {
                            Text(e.app, style = Sh.t.headline, color = c.text, maxLines = 1)
                            Text(e.pkg, style = Sh.t.monoSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (n.second > 0) { Tag("${n.second}", Tone.Warn); Spacer(Modifier.width(8.dp)) }
                        Text("${n.first}", style = Sh.t.mono, color = c.text)
                    }
                }
            }
            2 -> {
                if (NetGuard.tripEvents.isEmpty()) item { EmptyState(Ico.Radar, tr("No one has knocked", "किसी ने दस्तक नहीं दी"), tr("Any touch on a decoy port means a device on this Wi-Fi is probing your phone. Try it: run scripts/lan_scan_demo.py ${NetGuard.phoneIp.ifEmpty { "<phone-ip>" }} from a laptop on this Wi-Fi.",
                    "नकली पोर्ट छूने का मतलब: इस Wi-Fi पर कोई आपके फ़ोन को टटोल रहा है। आज़माएं: इसी Wi-Fi पर लैपटॉप से scripts/lan_scan_demo.py ${NetGuard.phoneIp.ifEmpty { "<phone-ip>" }} चलाएं।")) }
                items(NetGuard.tripEvents.take(100), key = { it.at.toString() + it.ip + it.port }) { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Ico.Radar, Tone.Warn, 34.dp)
                        Spacer(Modifier.width(Dim.m))
                        Column(Modifier.weight(1f)) {
                            Text("${t.ip} → :${t.port}", style = Sh.t.mono, color = c.text)
                            if (t.banner.isNotEmpty()) Text(t.banner, style = Sh.t.monoSmall, color = c.text3, maxLines = 1)
                        }
                        Text(fmt.format(Date(t.at)), style = Sh.t.monoSmall, color = c.text3)
                    }
                }
            }
            else -> {
                val net = Pipeline.alerts.filter { it.v.kind == "net" }
                if (net.isEmpty()) item { EmptyState(Ico.ShieldCheck, tr("No network alerts", "कोई नेटवर्क अलर्ट नहीं"), tr("Malware-like behaviour and Wi-Fi probes show up here.", "मैलवेयर जैसा व्यवहार और Wi-Fi पर टटोलना यहां दिखेगा।")) }
                else item { Card(pad = Dim.l) { net.forEachIndexed { i, a -> if (i > 0) Divider(); AlertRow(a) { nav.open(Route.AlertR(a.id)) } } } }
            }
        }
    }
}

@Composable
private fun DnsRow(e: NetGuard.DnsEvent, fmt: SimpleDateFormat) {
    val c = Sh.c
    val tone = when (e.action) { "BLOCK" -> Tone.Danger; "FLAG" -> Tone.Warn; else -> null }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (tone == null) c.surface else lerpOver(c.surface, c.fill(tone), 0.10f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(e.pkg, 32.dp)
        Spacer(Modifier.width(Dim.m))
        Column(Modifier.weight(1f)) {
            Text(e.host, style = Sh.t.mono, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.app} · ${fmt.format(Date(e.at))} · ${"%.0f".format(e.ms)} ms" + (if (e.rcode == 3) " · NXDOMAIN" else ""),
                style = Sh.t.caption, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (tone != null) Tag(if (e.action == "BLOCK") tr("Stopped", "रोका") else tr("Flagged", "चिह्नित"), tone)
    }
}
