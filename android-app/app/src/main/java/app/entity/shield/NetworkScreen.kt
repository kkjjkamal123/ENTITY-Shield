// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NetworkScreen(onOpen: (Alert) -> Unit) {
    val ctx = LocalContext.current
    val hi = Prefs.lang(ctx) == Advice.Lang.HI
    var view by remember { mutableIntStateOf(0) }
    val on = NetGuard.vpnOn
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Glass(Modifier.fillMaxWidth(), radius = 26.dp, glow = if (on) Sh.Cyan else null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    ShieldOrb(if (on) Band.SAFE else Band.UNCERTAIN, 150.dp, sweeping = on)
                    Text(if (on) (if (hi) "नेटवर्क गार्ड चालू है" else "Network Guard is watching") else (if (hi) "नेटवर्क गार्ड बंद है" else "Network Guard is off"),
                        color = Sh.Text, fontWeight = Sh.Black, fontSize = 20.sp)
                    Text(
                        if (on) "DNS → ${NetGuard.upstream.ifEmpty { "network resolver" }} · ${if (hi) "फ़ोन IP" else "phone IP"} ${NetGuard.phoneIp}"
                        else (if (hi) "चालू करें: हर ऐप के लुकअप इसी फ़ोन पर जांचे जाएंगे" else "Turn on to check every app's lookups on this phone"),
                        color = Sh.Text2, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Sh.Red.copy(alpha = 0.18f) else Sh.Cyan.copy(alpha = 0.22f))
                        .border(1.dp, if (on) Sh.Red else Sh.Cyan, RoundedCornerShape(50))
                        .clickable { (ctx as MainActivity).setNetworkGuard(!on) }.padding(horizontal = 22.dp, vertical = 10.dp)) {
                        Text(if (on) (if (hi) "बंद करें" else "Turn off") else (if (hi) "नेटवर्क गार्ड चालू करें" else "Turn on Network Guard"), color = Sh.Text, fontWeight = Sh.Bold)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${NetGuard.queries}", if (hi) "लुकअप" else "lookups", Sh.Cyan, Modifier.weight(1f))
                StatTile("${NetGuard.blocked}", if (hi) "ब्लॉक" else "blocked", Sh.Red, Modifier.weight(1f))
                StatTile("${NetGuard.apps}", if (hi) "ऐप" else "apps", Sh.Violet, Modifier.weight(1f))
                StatTile("${NetGuard.tripHits}", if (hi) "ट्रिपवायर" else "tripwire", Sh.Amber, Modifier.weight(1f))
            }
        }
        item {
            Glass(Modifier.fillMaxWidth(), radius = 16.dp, pad = 12.dp) {
                Column {
                    Text(if (hi) "LAN ट्रिपवायर" else "LAN tripwire", color = Sh.Text, fontWeight = Sh.Bold, fontSize = 14.sp)
                    Text(
                        if (NetGuard.listening.isEmpty()) (if (hi) "नेटवर्क गार्ड चालू होने पर पोर्ट सुनता है" else "Listens on decoy ports while Network Guard is on")
                        else "${if (hi) "पोर्ट" else "Ports"} ${NetGuard.listening.joinToString(", ")} · ${if (hi) "कोई भी कनेक्शन = कोई आपके फ़ोन को टटोल रहा है" else "any connection = someone on this Wi-Fi is probing your phone"}",
                        color = Sh.Text2, fontSize = 12.sp,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(if (hi) "लाइव लुकअप" else "Live lookups", if (hi) "ऐप" else "Apps", if (hi) "ट्रिपवायर" else "Tripwire", if (hi) "अलर्ट" else "Alerts").forEachIndexed { i, n ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (view == i) Sh.Cyan.copy(alpha = 0.22f) else Sh.Glass)
                        .border(1.dp, if (view == i) Sh.Cyan else Sh.Stroke, RoundedCornerShape(50)).clickable { view = i }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(n, color = if (view == i) Sh.Cyan else Sh.Text2, fontSize = 12.sp, fontWeight = Sh.Bold)
                    }
                }
            }
        }
        when (view) {
            0 -> items(NetGuard.dnsEvents.take(150), key = { it.at.toString() + it.host + it.uid }) { e -> DnsRow(e, fmt) }
            1 -> {
                val byApp = NetGuard.dnsEvents.groupBy { it.uid }.map { (_, evs) -> evs.first() to (evs.size to evs.count { it.action != "ALLOW" }) }
                    .sortedByDescending { it.second.first }
                items(byApp, key = { it.first.uid }) { (e, c) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sh.Glass).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(e.pkg)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.app, color = Sh.Text, fontWeight = Sh.Bold, fontSize = 13.sp, maxLines = 1)
                            Text(e.pkg, color = Sh.Text3, fontSize = 10.sp, maxLines = 1)
                        }
                        Text("${c.first}", color = Sh.Text, fontFamily = Sh.Mono, fontWeight = Sh.Bold)
                        if (c.second > 0) { Spacer(Modifier.width(8.dp)); Pill("${c.second} ⚠", Sh.Red, filled = true) }
                    }
                }
            }
            2 -> items(NetGuard.tripEvents.take(100), key = { it.at.toString() + it.ip + it.port }) { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sh.Glass).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("📡", Sh.Amber, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${t.ip} → :${t.port}", color = Sh.Text, fontFamily = Sh.Mono, fontWeight = Sh.Bold, fontSize = 13.sp)
                        if (t.banner.isNotEmpty()) Text(t.banner, color = Sh.Text3, fontSize = 10.sp, maxLines = 1, fontFamily = Sh.Mono)
                    }
                    Text(fmt.format(Date(t.at)), color = Sh.Text3, fontSize = 10.sp)
                }
            }
            else -> items(Pipeline.alerts.filter { it.v.kind == "net" }, key = { it.id }) { a -> AlertRow(a, Prefs.lang(ctx)) { onOpen(a) } }
        }
        item {
            Text(if (hi) "जांच: लैपटॉप से (उसी Wi-Fi पर) scripts/lan_scan_demo.py ${NetGuard.phoneIp} चलाएं।" else "Test it: from a laptop on the same Wi-Fi run scripts/lan_scan_demo.py ${NetGuard.phoneIp.ifEmpty { "<phone-ip>" }}",
                color = Sh.Text3, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DnsRow(e: NetGuard.DnsEvent, fmt: SimpleDateFormat) {
    val c = when (e.action) { "BLOCK" -> Sh.Red; "FLAG" -> Sh.Amber; else -> Sh.Text3 }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (e.action == "ALLOW") Sh.Glass else c.copy(alpha = 0.12f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AppIcon(e.pkg)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.host, color = Sh.Text, fontFamily = Sh.Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.app} · ${fmt.format(Date(e.at))} · ${"%.0f".format(e.ms)} ms" + (if (e.rcode == 3) " · NXDOMAIN" else ""), color = Sh.Text3, fontSize = 10.sp, maxLines = 1)
        }
        Pill(e.action, c, filled = e.action != "ALLOW")
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) { NetGuard.icon(ctx, pkg) }
    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
    else Box(Modifier.size(30.dp).background(Sh.Glass, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("⌁", color = Sh.Text2) }
}
