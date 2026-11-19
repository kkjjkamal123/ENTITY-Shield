// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.entity.shield.LinkGuardActivity
import app.entity.shield.core.Advice
import app.entity.shield.core.Alert
import app.entity.shield.core.Band
import app.entity.shield.core.Family
import app.entity.shield.core.Featurizer
import app.entity.shield.llm.Explainer
import app.entity.shield.net.NetGuard
import app.entity.shield.sandbox.LinkInspector
import app.entity.shield.ui.LocalLang
import app.entity.shield.ui.LocalNav
import app.entity.shield.ui.Route
import kotlin.concurrent.thread
import app.entity.shield.ui.components.*
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Glyph
import app.entity.shield.ui.theme.Ico
import app.entity.shield.ui.theme.Palette
import app.entity.shield.ui.theme.Sh
import app.entity.shield.ui.tr
import app.entity.shield.util.Speaker
import java.util.Date

private val TRIGGERS = Regex("(?i)\\b(block(ed)?|suspend(ed)?|urgent(ly)?|immediately|today|kyc|otp|pin|cvv|arrest(ed)?|police|cbi|customs|prize|won|winner|lottery|refund|reward|disconnect(ed)?|expire[ds]?|verify|update|turant|abhi|jaldi)\\b|तुरंत|ब्लॉक|गिरफ्तार|गिरफ्तारी|पुलिस|आधार|इनाम|लॉटरी")
private val PHONE = Regex("\\+?\\d[\\d -]{8,}\\d")

private fun highlight(text: String, c: Palette): AnnotatedString = buildAnnotatedString {
    append(text)
    Featurizer.URL_RE.findAll(text).forEach { addStyle(SpanStyle(color = c.dangerText, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline), it.range.first, it.range.last + 1) }
    PHONE.findAll(text).forEach { addStyle(SpanStyle(color = c.warnText, fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1) }
    TRIGGERS.findAll(text).forEach { addStyle(SpanStyle(color = c.warnText, fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertDetailScreen(a: Alert) {
    val ctx = LocalContext.current
    val c = Sh.c
    val lang = LocalLang.current
    val hi = lang == Advice.Lang.HI
    val ex = remember { Explainer.get(ctx) }
    val tone = toneOf(a.v.band)
    var why by remember(a.id, lang) { mutableStateOf("") }
    var whyStats by remember(a.id, lang) { mutableStateOf("") }
    var answer by remember(a.id, lang) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val nav = LocalNav.current
    var inspecting by remember(a.id) { mutableStateOf<List<LinkInspector.Step>>(emptyList()) }
    val link = remember(a.id) { if (a.v.kind == "net") null else Featurizer.URL_RE.find(a.text)?.value }

    fun ask(prompt: String, maxNew: Int, prefix: String?, out: (String) -> Unit, statsOut: (String) -> Unit) {
        busy = true
        var acc = ""
        val act = ctx as ComponentActivity
        ex.run(prompt, maxNew, object : Explainer.Listener {
            override fun onToken(piece: String) { acc += piece; val s = acc; act.runOnUiThread { out(s.trim()) } }
            override fun onDone(stats: Explainer.Stats) = act.runOnUiThread {
                busy = false
                statsOut("${"%.2f".format(stats.ttftMs / 1000.0)} s to first word · ${stats.tokens} tokens · " +
                    "prefill ${"%.0f".format(stats.prefillTps ?: 0.0)} tok/s · decode ${"%.1f".format(stats.decodeTps ?: 0.0)} tok/s" +
                    (if (stats.prewarmed) " · pre-warmed" else "") + (if (stats.loadMs > 0) " · load ${"%.1f".format(stats.loadMs / 1000.0)} s" else ""))
            }
            override fun onError(msg: String) = act.runOnUiThread { busy = false; out(msg) }
        }, prefix)
    }

    LaunchedEffect(a.id, lang) {
        if (a.v.band != Band.SAFE && ex.available()) ask(Advice.explainPrompt(a.v, a.text, lang), 80, Advice.explainPrefix(lang), { why = it }, { whyStats = it })
    }
    DisposableEffect(a.id) { onDispose { Speaker.stop(); if (busy) ex.stop() } }

    DetailScaffold(tr("Alert", "अलर्ट"), actions = { LangSwitch() }) {
        // 1. verdict, instantly, from the detectors
        Card(pad = Dim.xl, tone = tone) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Tag(when (a.v.band) { Band.SCAM -> tr("Danger", "ख़तरा"); Band.UNCERTAIN -> tr("Be careful", "सावधान"); Band.SAFE -> tr("Safe", "सुरक्षित") }, tone)
                    Text(Advice.headline(a.v, lang), style = Sh.t.title1, color = c.text, modifier = Modifier.padding(top = Dim.s))
                    Text(
                        listOfNotNull(
                            when (a.v.kind) { "net" -> "Network Guard"; "url" -> tr("Link check", "लिंक जांच"); else -> tr("SMS check", "SMS जांच") },
                            if (a.v.kind != "net") "${"%.1f".format(a.v.millis)} ms" else null,
                            if (a.warnMs > 0) tr("warned in ${"%.0f".format(a.warnMs)} ms", "${"%.0f".format(a.warnMs)} ms में चेतावनी") else null,
                        ).joinToString(" · "),
                        style = Sh.t.caption, color = c.text2, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.width(Dim.m))
                ScoreRing(a.v.score, c.band(a.v.band), 74.dp, tr("RISK", "जोखिम"))
            }
        }

        // 2. the message with the risky parts lit up
        Card {
            if (a.sender.isNotEmpty()) {
                Text((if (a.v.kind == "net") tr("Source", "स्रोत") else tr("From", "भेजने वाला")).uppercase(), style = Sh.t.overline, color = c.text3)
                Text(a.sender, style = Sh.t.mono, color = c.text, modifier = Modifier.padding(top = 2.dp, bottom = Dim.m))
            }
            Text(highlight(a.text, c), style = Sh.t.body, color = c.text)
        }

        // 2b. what the sandbox saw (links that were visited), or the offer to visit
        if (a.extra.isNotEmpty()) SandboxReport(a.extra)
        else if (link != null) {
            if (inspecting.isNotEmpty()) SandboxSteps(inspecting)
            else Card(pad = Dim.l, onClick = {
                val act = ctx as ComponentActivity
                inspecting = listOf(LinkInspector.Step("address", LinkInspector.RUNNING))
                thread(name = "inspect") {
                    val r = LinkInspector.inspect(ctx, link, "check", a.v) { st -> act.runOnUiThread { inspecting = st } }
                    act.runOnUiThread { nav.open(Route.AlertR(r.id)) }
                }
            }) {
                ListRow(tr("Open the link in the sandbox", "लिंक सैंडबॉक्स में खोलें"),
                    tr("See where it really goes and what the page asks for, without risk", "देखें यह असल में कहां जाता है और पेज क्या मांगता है, बिना जोखिम"),
                    Ico.Box, Tone.Info, trailing = { Glyph(Ico.Chevron, c.text3, 18.dp) })
            }
        }

        // 3. warning signs
        val signs = Advice.signsFor(a.v, lang)
        if (signs.isNotEmpty()) {
            SectionHeader(tr("Warning signs", "संकेत"))
            Card(pad = Dim.l) {
                signs.forEachIndexed { i, s ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(c.band(a.v.band)))
                        Spacer(Modifier.width(10.dp))
                        Text(s.replaceFirstChar { it.uppercase() }, style = Sh.t.callout, color = c.text)
                    }
                }
            }
        }

        if (a.v.band == Band.SAFE) {
            Card(tone = Tone.Accent) {
                Text(tr("This looks safe. Still, never share an OTP or PIN.", "यह सुरक्षित लगता है। फिर भी कभी OTP या PIN न बताएं।"), style = Sh.t.callout, color = c.text)
            }
        } else {
            // 4. why: Llama on this phone, streamed
            SectionHeader(tr("Why", "क्यों"))
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Ico.Chip, Tone.Neutral, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Llama 3.2 1B", style = Sh.t.headline, color = c.text)
                        Text(tr("ExecuTorch · on this phone", "ExecuTorch · इसी फ़ोन पर"), style = Sh.t.caption, color = c.text3)
                    }
                    if (busy) TypingDots()
                }
                Spacer(Modifier.height(Dim.m))
                when {
                    !ex.available() -> Text(tr("The Llama model is not installed on this phone. Detection and the steps below still work.",
                        "Llama मॉडल इंस्टॉल नहीं है। पहचान और नीचे के कदम फिर भी काम करते हैं।"), style = Sh.t.callout, color = c.text2)
                    why.isEmpty() -> Text(tr("Reading the message…", "मैसेज पढ़ रहा है…"), style = Sh.t.body, color = c.text3)
                    else -> Text(why + if (busy) " ▍" else "", style = Sh.t.body, color = c.text)
                }
                if (whyStats.isNotEmpty()) Text(whyStats, style = Sh.t.monoSmall, color = c.text3, modifier = Modifier.padding(top = Dim.s))
                Spacer(Modifier.height(Dim.m))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(tr("Read aloud", "सुनें"), icon = Ico.Speaker) {
                        val ok = Speaker.say(listOf(Advice.headline(a.v, lang), why, Advice.steps(a.v.family, lang).joinToString(". ")).joinToString(". "), lang)
                        if (!ok) Toast.makeText(ctx, if (hi) "इस फ़ोन पर हिंदी आवाज़ नहीं है" else "No voice for this language on this phone", Toast.LENGTH_SHORT).show()
                    }
                    if (busy) Chip(tr("Stop", "रोकें"), icon = Ico.Stop) { ex.stop() }
                }
            }

            // 5. what to do: fixed, checked text
            SectionHeader(tr("What to do now", "अब क्या करें"))
            Card {
                Advice.steps(a.v.family, lang).forEachIndexed { i, s ->
                    if (i > 0) Spacer(Modifier.height(Dim.m))
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(c.text), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = Sh.t.caption, color = c.bg)
                        }
                        Spacer(Modifier.width(Dim.m))
                        Text(s, style = Sh.t.callout, color = c.text, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Text(tr("Pre-checked steps, not written by the AI.", "पहले से जांचे गए कदम, AI ने नहीं लिखे।"), style = Sh.t.caption, color = c.text3, modifier = Modifier.padding(top = Dim.l))
            }

            // 6. actions that really act
            val pkg = a.v.evidence.firstOrNull { it.startsWith("pkg:") }?.removePrefix("pkg:")?.takeIf { it.isNotEmpty() && it != "android" }
            val acts = buildList<Triple<String, ImageVector, () -> Unit>> {
                if (a.sender.isNotEmpty() && a.v.kind != "net") add(Triple(if (hi) "ब्लॉक करें" else "Block sender", Ico.Ban) { block(ctx, a.sender) })
                if (a.v.family == Family.NET_DOMAIN && a.v.worstHost != null) add(Triple(if (hi) "साइट चालू करें" else "Allow this site", Ico.Check) {
                    NetGuard.allowSite(ctx, a.v.worstHost)
                    Toast.makeText(ctx, (if (hi) "चालू: " else "Allowed: ") + Featurizer.registrable(a.v.worstHost), Toast.LENGTH_SHORT).show()
                })
                if (pkg != null) add(Triple(if (hi) "ऐप हटाएं" else "Remove app", Ico.Trash) { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) })
                if (a.v.family == Family.NET_SCAN || a.v.family == Family.NET_BRUTE) add(Triple(if (hi) "Wi-Fi छोड़ें" else "Leave this Wi-Fi", Ico.Wifi) { ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI)) })
                add(Triple(if (hi) "1930 पर कॉल" else "Call 1930", Ico.Phone) { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))) })
                add(Triple(if (hi) "शिकायत करें" else "Report online", Ico.Flag) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cybercrime.gov.in"))) })
                add(Triple(if (hi) "रिपोर्ट कॉपी" else "Copy report", Ico.Copy) { copy(ctx, reportText(a)) })
                if (a.source == "link") add(Triple(if (hi) "फिर भी खोलें" else "Open anyway", Ico.ArrowUpRight) { LinkGuardActivity.openInBrowser(ctx, Uri.parse(a.text)) })
            }
            SectionHeader(tr("Actions", "कार्रवाई"))
            acts.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dim.m)) {
                    row.forEach { (label, icon, f) -> ActionTile(label, icon, Modifier.weight(1f), f) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // 7. follow-up questions
            if (ex.available() && a.v.kind != "net") {
                SectionHeader(tr("Ask Shield", "Shield से पूछें"))
                val qs = if (hi) listOf("मैंने लिंक खोल दिया, अब क्या करूं?", "मैंने OTP बता दिया", "मेरे पैसे कट गए")
                else listOf("I already clicked the link. What now?", "I shared the OTP", "I already paid money")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    qs.forEach { q -> Chip(q, enabled = !busy) { answer = "…"; ask(Advice.followUpPrompt(a.v, a.text, q, lang), 100, null, { answer = it }, {}) } }
                }
                if (answer.isNotEmpty()) Card { Text(answer, style = Sh.t.body, color = c.text) }
            }
            Text(tr("The explanation is written by an on-device AI model and can be imperfect. The verdict and the steps are not generated.",
                "समझाने वाला हिस्सा AI ने लिखा है और ग़लत हो सकता है। फ़ैसला और कदम AI ने नहीं लिखे।"), style = Sh.t.caption, color = c.text3)
        }
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = Sh.c
    Row(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).pressable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, c.text, 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, style = Sh.t.callout.copy(fontWeight = Sh.t.headline.fontWeight), color = c.text, maxLines = 1)
    }
}

fun reportText(a: Alert) = "Suspected ${a.v.kind.uppercase()} fraud\nFrom: ${a.sender}\nReceived: ${Date(a.at)}\nMessage: ${a.text}\n" +
    (a.v.worstHost?.let { "Domain/host: $it\n" } ?: "") + "Flagged on-device by ENTITY Shield (score ${(a.v.score * 100).toInt()}%)."

fun copy(ctx: Context, s: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("report", s))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

/** Only the default dialer/SMS app may write the block list, so: copy the number and open the system block list. */
fun block(ctx: Context, sender: String) {
    copy(ctx, sender)
    Toast.makeText(ctx, "Number copied. Tap 'Add a number' and paste.", Toast.LENGTH_LONG).show()
    runCatching { ctx.startActivity(ctx.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent()) }
}
