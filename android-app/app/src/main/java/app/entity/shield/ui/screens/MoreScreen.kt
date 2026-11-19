// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.entity.shield.bench.Bench
import app.entity.shield.bench.DeviceInfo
import app.entity.shield.bench.Power
import app.entity.shield.core.Pipeline
import app.entity.shield.core.SAMPLES
import app.entity.shield.core.runSelfTest
import app.entity.shield.llm.Explainer
import app.entity.shield.llm.ModelDownload
import app.entity.shield.llm.ModelImport
import app.entity.shield.net.DnsPolicy
import app.entity.shield.net.NetGuard
import app.entity.shield.sms.InboxAudit
import app.entity.shield.ui.LocalNav
import app.entity.shield.ui.Route
import app.entity.shield.ui.TabPage
import app.entity.shield.ui.components.*
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Glyph
import app.entity.shield.ui.theme.Ico
import app.entity.shield.ui.theme.Sh
import app.entity.shield.ui.tr
import kotlin.concurrent.thread

@Composable
fun MoreScreen() {
    val ctx = LocalContext.current
    val c = Sh.c
    val nav = LocalNav.current
    var prewarm by remember { mutableStateOf(ctx.getSharedPreferences("shield", 0).getBoolean("prewarm", true)) }
    TabPage {
        ScreenTitle(tr("More", "और"))
        SectionHeader(tr("On-device AI", "इसी फ़ोन पर AI"))
        LlamaCard()
        SectionHeader(tr("Settings", "सेटिंग"))
        Card(pad = Dim.l) {
            ListRow(tr("Language", "भाषा"), tr("Alerts, steps and explanations", "अलर्ट, कदम और समझाना"), Ico.Language, trailing = { LangSwitch() })
            Divider()
            ListRow(tr("Fast explanations", "तेज़ समझाना"), tr("Load Llama as soon as a threat arrives (first word in about 1 s)", "ख़तरा आते ही Llama तैयार करें (पहला शब्द ~1 सेकंड में)"), Ico.Chip,
                trailing = { Toggle(prewarm) { prewarm = it; ctx.getSharedPreferences("shield", 0).edit().putBoolean("prewarm", it).apply() } })
            Divider()
            ListRow(tr("Allowed sites", "चालू की गई साइटें"), tr("${DnsPolicy.allowed().size} sites Network Guard always lets through", "${DnsPolicy.allowed().size} साइटें जिन्हें नेटवर्क गार्ड हमेशा जाने देता है"), Ico.Check,
                trailing = { Glyph(Ico.Chevron, c.text3, 18.dp) }) { nav.open(Route.Allowed) }
        }
        SectionHeader(tr("Lab", "लैब"))
        Card(pad = Dim.l) {
            ListRow(tr("Demo scams", "डेमो स्कैम"), tr("Real pipeline, sample messages, works offline", "असली पाइपलाइन, नमूना मैसेज, ऑफ़लाइन"), Ico.Play,
                trailing = { Glyph(Ico.Chevron, c.text3, 18.dp) }) { nav.open(Route.Demo) }
            Divider()
            ListRow(tr("Inbox audit", "इनबॉक्स ऑडिट"), tr("Check this phone's own SMS. Only counts can leave.", "इसी फ़ोन के SMS जांचें। सिर्फ़ गिनती बाहर जा सकती है।"), Ico.Inbox,
                trailing = { Glyph(Ico.Chevron, c.text3, 18.dp) }) { nav.open(Route.Inbox) }
            Divider()
            ListRow(tr("Benchmark", "बेंचमार्क"), tr("Detector speed, warning time, Llama on this chip", "डिटेक्टर की गति, चेतावनी समय, इस चिप पर Llama"), Ico.Gauge,
                trailing = { Glyph(Ico.Chevron, c.text3, 18.dp) }) { nav.open(Route.Bench) }
        }
        SectionHeader(tr("About", "जानकारी"))
        Card(pad = Dim.l) {
            ListRow(tr("Privacy and models", "प्राइवेसी और मॉडल"), tr("What runs where, licences, Built with Llama", "क्या कहां चलता है, लाइसेंस, Built with Llama"), Ico.Lock,
                trailing = { Glyph(Ico.Chevron, c.text3, 18.dp) }) { nav.open(Route.About) }
        }
        Text("ENTITY Shield 0.4 · Bharat AI-SoC Challenge 2026–27 · PS3", style = Sh.t.caption, color = c.text3, modifier = Modifier.padding(top = Dim.s))
    }
}

/** Screens opened from More. */
@Composable
fun SubScreen(r: Route) = when (r) {
    Route.Demo -> DemoScreen()
    Route.Inbox -> InboxScreen()
    Route.Bench -> BenchScreen()
    Route.About -> AboutScreen()
    Route.Allowed -> AllowedScreen()
    else -> Unit
}

@Composable
private fun DemoScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val c = Sh.c
    fun run(f: () -> app.entity.shield.core.Alert) = thread { val a = f(); (ctx as ComponentActivity).runOnUiThread { nav.open(Route.AlertR(a.id)) } }
    DetailScaffold(tr("Demo scams", "डेमो स्कैम")) {
        Text(tr("Each sample takes the same path as a real incoming SMS: detectors, notification, alert, Llama. Works in airplane mode.",
            "हर नमूना असली SMS जैसा रास्ता लेता है: डिटेक्टर, नोटिफ़िकेशन, अलर्ट, Llama। एयरप्लेन मोड में भी चलता है।"), style = Sh.t.callout, color = c.text2)
        Card(pad = Dim.l) {
            SAMPLES.forEachIndexed { i, s ->
                if (i > 0) Divider()
                val safe = "(safe)" in s.title
                ListRow(s.title, s.text, Ico.Message, if (safe) Tone.Accent else Tone.Danger, trailing = { Glyph(Ico.Play, c.text3, 18.dp) }) {
                    run { Pipeline.onSms(ctx, s.sender, s.text, "demo") }
                }
            }
        }
        SectionHeader(tr("Links", "लिंक"))
        Card(pad = Dim.l) {
            ListRow(tr("Phishing link", "फ़िशिंग लिंक"), "https://hdfc-netbanking-verify.top/login", Ico.Link, Tone.Danger, trailing = { Glyph(Ico.Play, c.text3, 18.dp) }) {
                run { Pipeline.onUrl(ctx, "https://hdfc-netbanking-verify.top/login", "demo") }
            }
            Divider()
            ListRow(tr("Real bank link", "असली बैंक लिंक"), "https://www.onlinesbi.sbi/", Ico.Link, Tone.Accent, trailing = { Glyph(Ico.Play, c.text3, 18.dp) }) {
                run { Pipeline.onUrl(ctx, "https://www.onlinesbi.sbi/", "demo") }
            }
        }
    }
}

@Composable
private fun InboxScreen() {
    val ctx = LocalContext.current
    val c = Sh.c
    val granted = ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    val s = InboxAudit.summary()
    var exported by remember { mutableStateOf("") }
    DetailScaffold(tr("Inbox audit", "इनबॉक्स ऑडिट")) {
        Card {
            Text(tr("Runs the detectors over this phone's own SMS inbox. You mark what it flagged, and a random sample of what it let through. Messages never leave the phone; only the counts can be exported.",
                "डिटेक्टर इसी फ़ोन के SMS पर चलते हैं। आप बताते हैं कि जो पकड़ा गया वो सही है या नहीं, और जो छोड़ा गया उसका एक नमूना। मैसेज कभी बाहर नहीं जाते; सिर्फ़ गिनती निर्यात हो सकती है।"),
                style = Sh.t.callout, color = c.text2)
            if (!granted) Text(tr("Allow SMS access first (asked when the app opens).", "पहले SMS अनुमति दें।"), style = Sh.t.caption, color = c.warnText, modifier = Modifier.padding(top = Dim.s))
            Spacer(Modifier.height(Dim.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.s)) {
                PrimaryButton(if (InboxAudit.running) tr("Scanning…", "स्कैन हो रहा है…") else tr("Scan inbox", "इनबॉक्स स्कैन"), Modifier.weight(1f), Ico.Inbox, enabled = granted, busy = InboxAudit.running) { InboxAudit.scan(ctx) }
                if (InboxAudit.scanned > 0) SecondaryButton(tr("Export counts", "गिनती निर्यात"), icon = Ico.Download) { exported = InboxAudit.export(ctx).absolutePath }
            }
            if (InboxAudit.scanned > 0) {
                Spacer(Modifier.height(Dim.l))
                Row {
                    Stat("${s.scanned}", tr("scanned", "स्कैन"), Modifier.weight(1f))
                    Stat("${s.flagged}", tr("flagged", "पकड़े"), Modifier.weight(1f), Tone.Warn)
                    Stat("${"%.2f".format(InboxAudit.msPerMsg)}", tr("ms / SMS", "ms / SMS"), Modifier.weight(1f))
                }
                Text(tr("Marked ${s.flaggedLabelled}/${s.flagged} · confirmed ${s.confirmed} · false alarms ${s.falseAlarms}", "चिह्नित ${s.flaggedLabelled}/${s.flagged} · सही ${s.confirmed} · ग़लत ${s.falseAlarms}") +
                    (s.precision?.let { " · precision ${"%.2f".format(it)}" } ?: "") + (s.fpr?.let { " · FPR ${"%.2f".format(it * 100)}%" } ?: ""),
                    style = Sh.t.monoSmall, color = c.text2, modifier = Modifier.padding(top = Dim.m))
                Text(tr("Let-through sample marked ${s.sampleLabelled}/${InboxAudit.sample.size} · missed scams ${s.missed}", "छोड़े गए नमूने ${s.sampleLabelled}/${InboxAudit.sample.size} · छूटे धोखे ${s.missed}"),
                    style = Sh.t.monoSmall, color = c.text2)
            }
            if (exported.isNotEmpty()) Text(exported, style = Sh.t.monoSmall, color = c.text3, modifier = Modifier.padding(top = Dim.s))
        }
        if (InboxAudit.flagged.isNotEmpty()) SectionHeader(tr("Flagged: really a scam?", "पकड़े गए: सच में धोखा?"))
        InboxAudit.flagged.forEach { AuditRow(it) }
        if (InboxAudit.sample.isNotEmpty()) SectionHeader(tr("Let through: was any a scam?", "छोड़े गए: कोई धोखा था?"))
        InboxAudit.sample.forEach { AuditRow(it) }
    }
}

@Composable
private fun AuditRow(it: InboxAudit.Item) {
    val c = Sh.c
    val l = InboxAudit.labels[it.id]
    Card(pad = Dim.l) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(it.sender, style = Sh.t.mono, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
            Tag("${(it.v.score * 100).toInt()}%", toneOf(it.v.band))
        }
        Text(it.text, style = Sh.t.callout, color = c.text2, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = Dim.m)) {
            Chip(tr("Scam", "धोखा"), selected = l == true) { InboxAudit.labels[it.id] = true }
            Chip(tr("Not a scam", "धोखा नहीं"), selected = l == false) { InboxAudit.labels[it.id] = false }
        }
    }
}

@Composable
private fun BenchScreen() {
    val ctx = LocalContext.current
    val c = Sh.c
    DetailScaffold(tr("Benchmark", "बेंचमार्क")) {
        Card {
            Text(tr("Benchmark of record", "रिकॉर्ड बेंचमार्क"), style = Sh.t.title2, color = c.text)
            Text(tr("Detector parity and latency, time to first warning, Llama cold vs pre-warmed (first word, prefill, decode, energy when unplugged). Raw CSV is saved for the report.",
                "डिटेक्टर की समानता और गति, पहली चेतावनी का समय, Llama ठंडा बनाम तैयार। रिपोर्ट के लिए CSV सेव होती है।"), style = Sh.t.callout, color = c.text2, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(Dim.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.s)) {
                PrimaryButton(if (Bench.running) tr("Running…", "चल रहा है…") else tr("Run benchmark", "बेंचमार्क चलाएं"), Modifier.weight(1f), Ico.Gauge, busy = Bench.running) { Bench.run(ctx) }
                SecondaryButton(tr("Self-test", "सेल्फ़-टेस्ट"), icon = Ico.Check) { thread { val r = runSelfTest(ctx); (ctx as ComponentActivity).runOnUiThread { Bench.log.add(r.trim()) } } }
            }
            if (Power.plugged(ctx)) Text(tr("Charging: energy numbers are skipped. Unplug for a power run.", "चार्ज हो रहा है: ऊर्जा के आंकड़े छोड़े गए।"), style = Sh.t.caption, color = c.warnText, modifier = Modifier.padding(top = Dim.s))
        }
        if (Bench.rows.any { it.test == "llama" }) {
            val ttft = Bench.rows.filter { it.metric == "ttft" }.groupBy { it.arm }.mapValues { (_, r) -> r.map { it.value }.sorted().let { it[it.size / 2] } }
            val maxV = ttft.values.maxOrNull() ?: 1.0
            Card {
                Text(tr("Llama: time to first word (median)", "Llama: पहले शब्द का समय (मध्य)"), style = Sh.t.headline, color = c.text)
                ttft.forEach { (arm, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Dim.m)) {
                        Text(arm, style = Sh.t.caption, color = c.text2, modifier = Modifier.width(88.dp))
                        Meter((v / maxV).toFloat(), if (arm == "cold") c.warn else c.accent, Modifier.weight(1f))
                        Text("  ${"%.2f".format(v / 1000)} s", style = Sh.t.mono, color = c.text)
                    }
                }
            }
        }
        if (Bench.log.isNotEmpty()) Card {
            Bench.log.toList().forEach { line -> Text(line, style = Sh.t.monoSmall, color = c.text, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
private fun AboutScreen() {
    val ctx = LocalContext.current
    val c = Sh.c
    val info = remember { DeviceInfo.get(ctx) }
    val ex = remember { Explainer.get(ctx) }
    DetailScaffold(tr("Privacy and models", "प्राइवेसी और मॉडल")) {
        Card {
            Text("ENTITY Shield 0.4", style = Sh.t.title2, color = c.text)
            Text("Bharat AI-SoC Challenge 2026–27 · Problem Statement 3 · Team ENTITY", style = Sh.t.caption, color = c.text2, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(Dim.m))
            Text("${info.model} · ${info.soc}", style = Sh.t.monoSmall, color = c.text2)
            Text("ExecuTorch 1.5.1 (${info.backends.joinToString()})", style = Sh.t.monoSmall, color = c.text2)
            Text("Llama: ${ex.files()?.first?.name ?: "not installed"}", style = Sh.t.monoSmall, color = c.text2)
        }
        SectionHeader(tr("Privacy", "प्राइवेसी"))
        Card(pad = Dim.l) {
            listOf(
                tr("Every model runs on this phone: two ExecuTorch detectors and Llama 3.2 1B.", "हर मॉडल इसी फ़ोन पर चलता है: दो ExecuTorch डिटेक्टर और Llama 3.2 1B।"),
                tr("No account, no analytics, no cloud. Messages never leave the phone.", "कोई खाता नहीं, कोई एनालिटिक्स नहीं, कोई क्लाउड नहीं। मैसेज कभी फ़ोन से बाहर नहीं जाते।"),
                tr("Network Guard forwards this phone's own DNS lookups to the network's resolver, nothing else.", "नेटवर्क गार्ड सिर्फ़ इसी फ़ोन के DNS लुकअप नेटवर्क के रिज़ॉल्वर को भेजता है।"),
                tr("The link sandbox visits a link only when you check it or open it with Shield, never on its own. It runs in an isolated process with no access to your files, and sends no cookies.",
                    "लिंक सैंडबॉक्स सिर्फ़ तब लिंक खोलता है जब आप जांचें या Shield से खोलें, ख़ुद से कभी नहीं। यह एक अलग बंद प्रोसेस में चलता है, आपकी फ़ाइलों तक पहुंच नहीं, कोई कुकी नहीं भेजता।"),
                tr("The inbox audit exports counts only, never message text.", "इनबॉक्स ऑडिट सिर्फ़ गिनती निर्यात करता है, मैसेज कभी नहीं।"),
            ).forEachIndexed { i, s ->
                if (i > 0) Divider()
                ListRow(s, icon = null)
            }
        }
        SectionHeader(tr("Credits", "श्रेय"))
        Card {
            Text("Built with Llama", style = Sh.t.headline, color = c.text)
            Text("Llama 3.2 is licensed under the Llama 3.2 Community License, Copyright © Meta Platforms, Inc. All Rights Reserved.", style = Sh.t.caption, color = c.text2, modifier = Modifier.padding(top = 4.dp))
            Text("ExecuTorch (BSD-3-Clause) · XNNPACK · KleidiAI · Geist fonts (OFL) · UCI SMS Spam Collection (CC BY 4.0) · PhishTank · OpenPhish · Tranco · Cisco Umbrella", style = Sh.t.caption, color = c.text3, modifier = Modifier.padding(top = Dim.s))
        }
    }
}

@Composable
private fun AllowedScreen() {
    val ctx = LocalContext.current
    val c = Sh.c
    var list by remember { mutableStateOf(DnsPolicy.allowed()) }
    DetailScaffold(tr("Allowed sites", "चालू की गई साइटें")) {
        Text(tr("Network Guard never stops or flags these. Remove one to have it checked again.", "नेटवर्क गार्ड इन्हें कभी नहीं रोकता। हटाने पर फिर से जांच होगी।"), style = Sh.t.callout, color = c.text2)
        if (list.isEmpty()) EmptyState(Ico.Check, tr("No sites yet", "अभी कोई साइट नहीं"), tr("Tap \"Allow this site\" on a network alert to add one.", "जोड़ने के लिए नेटवर्क अलर्ट पर \"साइट चालू करें\" दबाएं।"))
        else Card(pad = Dim.l) {
            list.forEachIndexed { i, d ->
                if (i > 0) Divider()
                ListRow(d, icon = Ico.Globe, trailing = { Chip(tr("Remove", "हटाएं"), icon = Ico.Close) { DnsPolicy.disallow(ctx, d); NetGuard.forgetVerdicts(); list = DnsPolicy.allowed() } })
            }
        }
    }
}

/** Llama status, and the two ways to install it: one-tap download, or import from files. */
@Composable
fun LlamaCard() {
    val ctx = LocalContext.current
    val c = Sh.c
    val ex = remember { Explainer.get(ctx) }
    val installed = ex.available() && !ModelDownload.busy
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Ico.Chip, if (installed) Tone.Accent else Tone.Warn, 40.dp)
            Spacer(Modifier.width(Dim.m))
            Column(Modifier.weight(1f)) {
                Text(if (installed) ex.modelLabel() else tr("Llama 3.2 1B is not installed", "Llama 3.2 1B इंस्टॉल नहीं है"), style = Sh.t.headline, color = c.text)
                Text(if (installed) tr("Explanations run on this phone", "समझाना इसी फ़ोन पर चलता है")
                    else tr("Detection already works. Llama adds the plain-language \"why\".", "पहचान पहले से चलती है। Llama आसान भाषा में \"क्यों\" बताता है।"),
                    style = Sh.t.caption, color = c.text2)
            }
        }
        if (ModelDownload.busy) {
            Spacer(Modifier.height(Dim.l))
            Meter(ModelDownload.progress, c.accent, Modifier.fillMaxWidth())
            Row(Modifier.padding(top = Dim.s), verticalAlignment = Alignment.CenterVertically) {
                Text(ModelDownload.status, style = Sh.t.monoSmall, color = c.text2, modifier = Modifier.weight(1f))
                Chip(tr("Pause", "रोकें"), icon = Ico.Stop) { ModelDownload.stop() }
            }
        } else if (!installed) {
            Spacer(Modifier.height(Dim.l))
            PrimaryButton(tr("Download Llama · ${ModelDownload.totalBytes shr 20} MB", "Llama डाउनलोड करें · ${ModelDownload.totalBytes shr 20} MB"),
                Modifier.fillMaxWidth(), Ico.Download, busy = ModelImport.busy) { ModelDownload.start(ctx) }
            Spacer(Modifier.height(Dim.s))
            SecondaryButton(tr("Import files instead", "फ़ाइलें चुनें"), Modifier.fillMaxWidth(), icon = Ico.Inbox, enabled = !ModelImport.busy) {
                (ctx as app.entity.shield.MainActivity).importModel()
            }
            val note = listOfNotNull(
                ModelDownload.status.ifEmpty { null },
                ModelImport.status.ifEmpty { null },
                if (ModelDownload.onMobileData(ctx)) tr("You are on mobile data: Wi-Fi is better for 1 GB.", "आप मोबाइल डेटा पर हैं: 1 GB के लिए Wi-Fi बेहतर है।") else null,
                tr("One download, checked with SHA-256. Nothing about you is sent.", "एक बार डाउनलोड, SHA-256 से जांचा गया। आपकी कोई जानकारी नहीं भेजी जाती।"),
            )
            note.forEach { Text(it, style = Sh.t.caption, color = c.text3, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
