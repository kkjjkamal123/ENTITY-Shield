// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

@Composable
fun CheckScreen(shared: MutableState<String?>, onOpen: (Alert) -> Unit) {
    val ctx = LocalContext.current
    val hi = Prefs.lang(ctx) == Advice.Lang.HI
    var text by remember { mutableStateOf(shared.value ?: "") }
    var sender by remember { mutableStateOf("") }
    LaunchedEffect(shared.value) { shared.value?.let { text = it; shared.value = null } }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sh.Cyan, unfocusedBorderColor = Sh.Stroke, focusedLabelColor = Sh.Cyan, cursorColor = Sh.Cyan)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (hi) "कुछ भी जांचें" else "Check anything", color = Sh.Text, fontWeight = Sh.Black, fontSize = 24.sp)
        Text(if (hi) "मैसेज या लिंक चिपकाएं। जांच इसी फ़ोन पर होती है, इंटरनेट के बिना।" else "Paste a message or a link. It is checked on this phone, with no internet.", color = Sh.Text2, fontSize = 13.sp)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 150.dp), label = { Text(if (hi) "मैसेज या लिंक" else "Message or link") }, colors = fieldColors)
        OutlinedTextField(sender, { sender = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors,
            label = { Text(if (hi) "भेजने वाला (वैकल्पिक: VM-HDFCBK या नंबर)" else "Sender (optional: VM-HDFCBK or a number)") })
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Sh.accent).clickable(enabled = text.isNotBlank()) {
            val t = text.trim()
            thread {
                val a = if (!t.contains(' ') && Featurizer.URL_RE.containsMatchIn(t)) Pipeline.onUrl(ctx, t, "check") else Pipeline.onSms(ctx, sender, t, "check")
                (ctx as ComponentActivity).runOnUiThread { onOpen(a) }
            }
        }.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(if (hi) "जांचें" else "Check now", color = Sh.Bg0, fontWeight = Sh.Black, fontSize = 16.sp)
        }
        Glass(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (hi) "और तरीके" else "Other ways in", color = Sh.Text, fontWeight = Sh.Bold)
                Text(if (hi) "• किसी भी ऐप से \"Share\" → ENTITY Shield" else "• Share from any app → ENTITY Shield", color = Sh.Text2, fontSize = 13.sp)
                Text(if (hi) "• टेक्स्ट चुनें → \"Check with Shield\"" else "• Select text anywhere → \"Check with Shield\"", color = Sh.Text2, fontSize = 13.sp)
                Text(if (hi) "• लिंक खोलते समय \"Check link (Shield)\" चुनें" else "• Opening a link → choose \"Check link (Shield)\"", color = Sh.Text2, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LabScreen(onOpen: (Alert) -> Unit) {
    var section by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Demo", "Inbox audit", "Bench", "About").forEachIndexed { i, n ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (section == i) Sh.Violet.copy(alpha = 0.25f) else Sh.Glass)
                    .border(1.dp, if (section == i) Sh.Violet else Sh.Stroke, RoundedCornerShape(50)).clickable { section = i }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(n, color = if (section == i) Sh.Text else Sh.Text2, fontSize = 12.sp, fontWeight = Sh.Bold)
                }
            }
        }
        when (section) {
            0 -> DemoSection(onOpen)
            1 -> InboxSection()
            2 -> BenchSection()
            else -> AboutSection()
        }
    }
}

@Composable
private fun DemoSection(onOpen: (Alert) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Each sample takes the same path as a real incoming SMS: detectors → notification → alert → Llama. Works in airplane mode.", color = Sh.Text2, fontSize = 13.sp) }
        items(SAMPLES) { s ->
            val safe = "(safe)" in s.title
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sh.Glass).border(1.dp, Sh.Stroke, RoundedCornerShape(16.dp)).clickable {
                thread { val a = Pipeline.onSms(ctx, s.sender, s.text, "demo"); (ctx as ComponentActivity).runOnUiThread { onOpen(a) } }
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (safe) "✓" else "✉", if (safe) Sh.Green else Sh.Pink, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = Sh.Text, fontWeight = Sh.Bold, fontSize = 14.sp)
                    Text(s.text, color = Sh.Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("▶", color = Sh.Cyan, fontSize = 16.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("🔗 Phishing link", Sh.Red) { thread { val a = Pipeline.onUrl(ctx, "https://hdfc-netbanking-verify.top/login", "demo"); (ctx as ComponentActivity).runOnUiThread { onOpen(a) } } }
                ActionButton("🔗 Real link", Sh.Green) { thread { val a = Pipeline.onUrl(ctx, "https://www.onlinesbi.sbi/", "demo"); (ctx as ComponentActivity).runOnUiThread { onOpen(a) } } }
            }
        }
    }
}

@Composable
private fun InboxSection() {
    val ctx = LocalContext.current
    val granted = ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    val s = InboxAudit.summary()
    var exported by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Glass(Modifier.fillMaxWidth(), glow = Sh.Violet) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Real-inbox audit", color = Sh.Text, fontWeight = Sh.Black, fontSize = 18.sp)
                    Text("Runs the detectors over this phone's own SMS inbox. You mark what it flagged (and a random sample of what it let through). Messages never leave the phone; only the counts can be exported.",
                        color = Sh.Text2, fontSize = 12.sp)
                    if (!granted) Text("Grant the SMS permission first (it is asked when the app opens).", color = Sh.Amber, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(if (InboxAudit.running) "Scanning…" else "Scan inbox", Sh.Violet) { if (granted) InboxAudit.scan(ctx) }
                        if (InboxAudit.scanned > 0) ActionButton("Export counts", Sh.Cyan) { exported = InboxAudit.export(ctx).absolutePath }
                    }
                    if (InboxAudit.scanned > 0) {
                        Text("Scanned ${s.scanned} · flagged ${s.flagged} · ${"%.2f".format(InboxAudit.msPerMsg)} ms per message", color = Sh.Text, fontFamily = Sh.Mono, fontSize = 12.sp)
                        Text("Labelled flagged: ${s.flaggedLabelled}/${s.flagged} · confirmed ${s.confirmed} · false alarms ${s.falseAlarms}" +
                            (s.precision?.let { " · precision ${"%.2f".format(it)}" } ?: "") + (s.fpr?.let { " · FPR ${"%.2f".format(it * 100)}%" } ?: ""),
                            color = Sh.Text2, fontFamily = Sh.Mono, fontSize = 11.sp)
                        Text("Unflagged sample labelled: ${s.sampleLabelled}/${InboxAudit.sample.size} · missed scams ${s.missed}", color = Sh.Text2, fontFamily = Sh.Mono, fontSize = 11.sp)
                    }
                    if (exported.isNotEmpty()) Text("Saved: $exported", color = Sh.Text3, fontSize = 10.sp)
                }
            }
        }
        if (InboxAudit.flagged.isNotEmpty()) item { SectionTitle("Flagged: is it really a scam?") }
        items(InboxAudit.flagged, key = { "f" + it.id }) { AuditRow(it) }
        if (InboxAudit.sample.isNotEmpty()) item { SectionTitle("Let through: was any of these a scam?") }
        items(InboxAudit.sample, key = { "s" + it.id }) { AuditRow(it) }
    }
}

@Composable
private fun AuditRow(it: InboxAudit.Item) {
    val l = InboxAudit.labels[it.id]
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sh.Glass).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(it.sender, color = Sh.Text, fontWeight = Sh.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text("${(it.v.score * 100).toInt()}%", color = Sh.band(it.v.band), fontFamily = Sh.Mono, fontWeight = Sh.Bold, fontSize = 12.sp)
        }
        Text(it.text, color = Sh.Text2, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallButton("Scam", Sh.Red, enabled = true) { InboxAudit.labels[it.id] = true }
            SmallButton("Not a scam", Sh.Green, enabled = true) { InboxAudit.labels[it.id] = false }
            if (l != null) Pill(if (l) "marked scam" else "marked safe", if (l) Sh.Red else Sh.Green)
        }
    }
}

@Composable
private fun BenchSection() {
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Glass(Modifier.fillMaxWidth(), glow = Sh.Cyan) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Benchmark of record", color = Sh.Text, fontWeight = Sh.Black, fontSize = 18.sp)
                    Text("Detector parity and latency, time to first warning, and Llama cold vs pre-warmed (TTFT, prefill, decode, energy when unplugged). Raw CSV is saved for the report.",
                        color = Sh.Text2, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(if (Bench.running) "Running…" else "Run benchmark", Sh.Cyan) { Bench.run(ctx) }
                        ActionButton("Self-test only", Sh.Text2) { thread { val r = runSelfTest(ctx); (ctx as ComponentActivity).runOnUiThread { Bench.log.add(r.trim()) } } }
                    }
                    if (Power.plugged(ctx)) Text("Charging: energy numbers are skipped. Unplug for a power run.", color = Sh.Amber, fontSize = 11.sp)
                }
            }
        }
        items(Bench.log.toList()) { line -> Text(line, color = Sh.Text, fontSize = 12.sp, fontFamily = Sh.Mono, lineHeight = 16.sp) }
        if (Bench.rows.any { it.test == "llama" }) item {
            val ttft = Bench.rows.filter { it.metric == "ttft" }.groupBy { it.arm }.mapValues { (_, r) -> r.map { it.value }.sorted().let { it[it.size / 2] } }
            val maxV = ttft.values.maxOrNull() ?: 1.0
            Glass(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Median time to first token", color = Sh.Text, fontWeight = Sh.Bold)
                    ttft.forEach { (arm, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(arm, color = Sh.Text2, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                            MeterBar((v / maxV).toFloat(), if (arm == "cold") Sh.Amber else Sh.Green, Modifier.weight(1f))
                            Text("  ${"%.2f".format(v / 1000)} s", color = Sh.Text, fontFamily = Sh.Mono, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val ctx = LocalContext.current
    val info = remember { DeviceInfo.get(ctx) }
    val ex = Explainer.get(ctx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Glass(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ENTITY Shield 0.3", color = Sh.Text, fontWeight = Sh.Black, fontSize = 18.sp)
                Text("Bharat AI-SoC Challenge 2026–27 · Problem Statement 3 · Team ENTITY", color = Sh.Text2, fontSize = 12.sp)
                Text("${info.model} · ${info.soc}", color = Sh.Text2, fontSize = 12.sp)
                Text("Runtime: ExecuTorch 1.5.1 (${info.backends.joinToString()})", color = Sh.Text2, fontSize = 12.sp)
                Text("Llama: ${ex.files()?.first?.absolutePath ?: "not installed"}", color = Sh.Text2, fontSize = 12.sp)
            }
        }
        Glass(Modifier.fillMaxWidth(), glow = Sh.Green) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Privacy", color = Sh.Text, fontWeight = Sh.Bold)
                Text("• Every model runs on this phone: two ExecuTorch detectors and Llama 3.2 1B.", color = Sh.Text2, fontSize = 12.sp)
                Text("• No account, no analytics, no cloud calls. Messages never leave the phone.", color = Sh.Text2, fontSize = 12.sp)
                Text("• The INTERNET permission is used only by Network Guard, to forward this phone's own DNS lookups to the network's resolver.", color = Sh.Text2, fontSize = 12.sp)
                Text("• The inbox audit exports counts only, never message text.", color = Sh.Text2, fontSize = 12.sp)
            }
        }
        Glass(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Built with Llama", color = Sh.Text, fontWeight = Sh.Bold)
                Text("Llama 3.2 is licensed under the Llama 3.2 Community License, Copyright © Meta Platforms, Inc. All Rights Reserved.", color = Sh.Text2, fontSize = 12.sp)
                Text("ExecuTorch (BSD-3-Clause) · XNNPACK · KleidiAI · UCI SMS Spam Collection (CC BY 4.0) · PhishTank · OpenPhish · Tranco", color = Sh.Text3, fontSize = 11.sp)
            }
        }
    }
}
