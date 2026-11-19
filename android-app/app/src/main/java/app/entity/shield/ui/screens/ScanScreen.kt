// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.screens

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.entity.shield.core.Featurizer
import app.entity.shield.core.Pipeline
import app.entity.shield.sandbox.LinkInspector
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

private fun isLink(t: String) = !t.contains(' ') && !t.contains('\n') && Featurizer.URL_RE.containsMatchIn(t)

@Composable
fun ScanScreen(shared: MutableState<String?>) {
    val ctx = LocalContext.current
    val c = Sh.c
    val nav = LocalNav.current
    var text by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf<List<LinkInspector.Step>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var visit by remember { mutableStateOf(ctx.getSharedPreferences("shield", 0).getBoolean("sandbox_visit", true)) }
    LaunchedEffect(shared.value) { shared.value?.let { text = it; shared.value = null } }
    val link = isLink(text.trim())

    fun check() {
        val t = text.trim()
        if (t.isEmpty() || running) return
        val act = ctx as ComponentActivity
        running = true
        steps = emptyList()
        thread(name = "scan") {
            val a = if (link && visit) LinkInspector.inspect(ctx, t, "check") { s -> act.runOnUiThread { steps = s } }
            else if (link) Pipeline.onUrl(ctx, t, "check") else Pipeline.onSms(ctx, sender, t, "check")
            act.runOnUiThread { running = false; nav.open(Route.AlertR(a.id)) }
        }
    }

    TabPage {
        ScreenTitle(tr("Scan", "जांचें"), tr("Paste a message or a link", "मैसेज या लिंक चिपकाएं"))
        Card(pad = Dim.l) {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
                if (text.isEmpty()) Text(tr("e.g. \"Your SBI account will be blocked today, update KYC at…\" or a link", "जैसे \"आपका SBI खाता आज बंद होगा, KYC करें…\" या कोई लिंक"),
                    style = Sh.t.body, color = c.text3)
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = Sh.t.body.copy(color = c.text), cursorBrush = SolidColor(c.accentText))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Dim.s)) {
                Chip(tr("Paste", "चिपकाएं"), icon = Ico.Copy) {
                    ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { text = it }
                }
                Spacer(Modifier.width(8.dp))
                if (text.isNotEmpty()) Chip(tr("Clear", "मिटाएं"), icon = Ico.Close) { text = ""; steps = emptyList() }
                Spacer(Modifier.weight(1f))
                if (text.isNotBlank()) Tag(if (link) tr("Link", "लिंक") else tr("Message", "मैसेज"), Tone.Info)
            }
        }
        AnimatedVisibility(!link && text.isNotBlank(), enter = fadeIn() + expandVertically()) {
            Card(pad = Dim.l) {
                Text(tr("SENDER (OPTIONAL)", "भेजने वाला (वैकल्पिक)"), style = Sh.t.overline, color = c.text3)
                Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    if (sender.isEmpty()) Text("VM-HDFCBK · +91 98…", style = Sh.t.mono, color = c.text3)
                    BasicTextField(sender, { sender = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Sh.t.mono.copy(color = c.text), cursorBrush = SolidColor(c.accentText))
                }
            }
        }
        AnimatedVisibility(link, enter = fadeIn() + expandVertically()) {
            Card(pad = Dim.l) {
                ListRow(tr("Open it in the sandbox", "सैंडबॉक्स में खोलें"),
                    tr("Shield visits the link itself, in an isolated process with no access to your files, cookies or accounts, and reads the page without running it.",
                        "Shield ख़ुद लिंक खोलता है, एक अलग बंद प्रोसेस में, जिसकी आपकी फ़ाइलों, कुकीज़ या खातों तक पहुंच नहीं। पेज बिना चलाए पढ़ा जाता है।"),
                    Ico.Box, Tone.Info, trailing = { Toggle(visit) { visit = it; ctx.getSharedPreferences("shield", 0).edit().putBoolean("sandbox_visit", it).apply() } })
            }
        }
        PrimaryButton(if (link && visit) tr("Inspect link", "लिंक जांचें") else tr("Check now", "अभी जांचें"), Modifier.fillMaxWidth(),
            icon = if (link) Ico.Box else Ico.ShieldCheck, enabled = text.isNotBlank(), busy = running) { check() }

        if (steps.isNotEmpty()) SandboxSteps(steps)

        SectionHeader(tr("Other ways in", "और तरीके"))
        Card(pad = Dim.l) {
            ListRow(tr("Share to Shield", "Shield पर शेयर करें"), tr("From WhatsApp, Messages or any app: Share, then ENTITY Shield", "WhatsApp, Messages या किसी ऐप से: Share, फिर ENTITY Shield"), Ico.ArrowUpRight)
            Divider()
            ListRow(tr("Select text", "टेक्स्ट चुनें"), tr("Long-press any text, then \"Check with Shield\"", "कोई भी टेक्स्ट दबाकर रखें, फिर \"Check with Shield\""), Ico.Scan)
            Divider()
            ListRow(tr("Open links with Shield", "लिंक Shield से खोलें"), tr("Choose \"Check link (Shield)\" when a link opens: sandbox first, then your browser", "लिंक खुलते समय \"Check link (Shield)\" चुनें: पहले सैंडबॉक्स, फिर ब्राउज़र"), Ico.Link)
        }
    }
}

/** Live view of the sandbox pipeline: each stage ticks off with what it found. */
@Composable
fun SandboxSteps(steps: List<LinkInspector.Step>) {
    val c = Sh.c
    Card(pad = Dim.l) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Ico.Box, Tone.Info, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Sandbox", "सैंडबॉक्स"), style = Sh.t.headline, color = c.text)
                Text(tr("Isolated process · no files · no cookies · no scripts", "अलग प्रोसेस · फ़ाइलें नहीं · कुकीज़ नहीं · स्क्रिप्ट नहीं"), style = Sh.t.caption, color = c.text3)
            }
        }
        Spacer(Modifier.height(Dim.m))
        steps.forEach { s ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp).size(18.dp), contentAlignment = Alignment.Center) {
                    when (s.state) {
                        LinkInspector.RUNNING -> TypingDots(c.text3)
                        LinkInspector.OK -> Glyph(Ico.Check, c.accentText, 16.dp)
                        LinkInspector.WARN -> Glyph(Ico.Alert, c.warnText, 16.dp)
                        LinkInspector.BAD -> Glyph(Ico.Alert, c.dangerText, 16.dp)
                        else -> Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(c.text3))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stepTitle(s.key), style = Sh.t.callout, color = if (s.state == LinkInspector.PENDING) c.text3 else c.text)
                    if (s.detail.isNotEmpty()) Text(s.detail, style = Sh.t.monoSmall, color = c.text2, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
fun stepTitle(key: String) = when (key) {
    "address" -> tr("Address check (ExecuTorch URL model)", "पते की जांच (ExecuTorch URL मॉडल)")
    "visit" -> tr("Visit from the sandbox", "सैंडबॉक्स से खोलना")
    "read" -> tr("Read the page (no scripts run)", "पेज पढ़ना (कोई स्क्रिप्ट नहीं चली)")
    "traps" -> tr("Look for traps", "जाल ढूंढना")
    "text" -> tr("Read the words (ExecuTorch text model)", "शब्द पढ़ना (ExecuTorch टेक्स्ट मॉडल)")
    "verdict" -> tr("Verdict", "फ़ैसला")
    else -> key
}
