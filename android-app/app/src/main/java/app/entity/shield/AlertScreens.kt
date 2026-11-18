// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun kindGlyph(a: Alert) = when (a.v.kind) { "url" -> "🔗"; "net" -> if (a.v.family == Family.NET_SCAN || a.v.family == Family.NET_BRUTE) "📡" else "⌁"; else -> "✉" }

@Composable
fun AlertRow(a: Alert, lang: Advice.Lang, onClick: () -> Unit) {
    val c = Sh.band(a.v.band)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Sh.Glass).border(1.dp, Sh.Stroke, RoundedCornerShape(18.dp))
            .clickable { onClick() }.height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(c))
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(kindGlyph(a), c, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Advice.headline(a.v, lang), color = Sh.Text, fontWeight = Sh.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.text, color = Sh.Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                Text(
                    "${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(a.at))} · ${a.source} · ${a.sender.ifEmpty { a.v.worstHost ?: "" }}" +
                        (if (a.v.kind != "net") " · ${"%.1f".format(a.v.millis)} ms" else ""),
                    color = Sh.Text3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("${(a.v.score * 100).toInt()}%", color = c, fontFamily = Sh.Mono, fontWeight = Sh.Black, fontSize = 14.sp)
        }
    }
}

@Composable
fun AlertsScreen(onOpen: (Alert) -> Unit) {
    val ctx = LocalContext.current
    val lang = Prefs.lang(ctx)
    var filter by remember { mutableIntStateOf(0) }
    val names = listOf("All", "Scams", "Suspicious", "Network", "Safe")
    val list = Pipeline.alerts.filter {
        when (filter) {
            1 -> it.v.band == Band.SCAM && it.v.kind != "net"
            2 -> it.v.band == Band.UNCERTAIN
            3 -> it.v.kind == "net"
            4 -> it.v.band == Band.SAFE
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEachIndexed { i, n ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (filter == i) Sh.Cyan.copy(alpha = 0.22f) else Sh.Glass)
                        .border(1.dp, if (filter == i) Sh.Cyan else Sh.Stroke, RoundedCornerShape(50)).clickable { filter = i }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(n, color = if (filter == i) Sh.Cyan else Sh.Text2, fontSize = 12.sp, fontWeight = Sh.Bold)
                    }
                }
            }
        }
        if (list.isEmpty()) item {
            Glass(Modifier.fillMaxWidth()) {
                Text("Nothing here yet. Every SMS, shared link and network event lands here, checked on this phone.", color = Sh.Text2, fontSize = 13.sp)
            }
        }
        items(list, key = { it.id }) { a -> AlertRow(a, lang) { onOpen(a) } }
        if (Pipeline.alerts.isNotEmpty()) item {
            Text("Clear history", color = Sh.Text3, fontSize = 12.sp, modifier = Modifier.clickable { Pipeline.clear(ctx) }.padding(8.dp))
        }
    }
}

private val TRIGGERS = Regex("(?i)\\b(block(ed)?|suspend(ed)?|urgent(ly)?|immediately|today|kyc|otp|pin|cvv|arrest(ed)?|police|cbi|customs|prize|won|winner|lottery|refund|reward|disconnect(ed)?|expire[ds]?|verify|update|turant|abhi|jaldi)\\b|तुरंत|ब्लॉक|गिरफ्तार|गिरफ्तारी|पुलिस|आधार|इनाम|लॉटरी")
private val PHONE = Regex("\\+?\\d[\\d -]{8,}\\d")

private fun highlight(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    Featurizer.URL_RE.findAll(text).forEach { addStyle(SpanStyle(color = Sh.Red, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline), it.range.first, it.range.last + 1) }
    PHONE.findAll(text).forEach { addStyle(SpanStyle(color = Sh.Amber, fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1) }
    TRIGGERS.findAll(text).forEach { addStyle(SpanStyle(color = Sh.Amber, fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AlertDetail(a: Alert, lang: Advice.Lang) {
    val ctx = LocalContext.current
    val ex = remember { Explainer.get(ctx) }
    val hi = lang == Advice.Lang.HI
    var why by remember(a.id, lang) { mutableStateOf("") }
    var whyStats by remember(a.id, lang) { mutableStateOf("") }
    var answer by remember(a.id, lang) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var started by remember(a.id, lang) { mutableStateOf(false) }

    fun ask(prompt: String, maxNew: Int, prefix: String?, out: (String) -> Unit, statsOut: (String) -> Unit) {
        busy = true
        var acc = ""
        ex.run(prompt, maxNew, object : Explainer.Listener {
            override fun onToken(piece: String) { acc += piece; val s = acc; (ctx as ComponentActivity).runOnUiThread { out(s.trim()) } }
            override fun onDone(stats: Explainer.Stats) = (ctx as ComponentActivity).runOnUiThread {
                busy = false
                statsOut("first token ${"%.2f".format(stats.ttftMs / 1000.0)} s · ${stats.tokens} tokens · " +
                    "prefill ${"%.0f".format(stats.prefillTps ?: 0.0)} tok/s · decode ${"%.1f".format(stats.decodeTps ?: 0.0)} tok/s" +
                    (if (stats.prewarmed) " · pre-warmed" else "") + (if (stats.loadMs > 0) " · load ${"%.1f".format(stats.loadMs / 1000.0)} s" else ""))
            }
            override fun onError(msg: String) = (ctx as ComponentActivity).runOnUiThread { busy = false; out(msg) }
        }, prefix)
    }

    LaunchedEffect(a.id, lang) {
        if (a.v.band != Band.SAFE && ex.available() && !started) {
            started = true
            ask(Advice.explainPrompt(a.v, a.text, lang), 80, Advice.explainPrefix(lang), { why = it }, { whyStats = it })
        }
    }
    DisposableEffect(a.id) { onDispose { Speaker.stop() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. verdict banner (from the detector, instantly)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Sh.bandBrush(a.v.band)).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Advice.headline(a.v, lang), color = Color.White, fontWeight = Sh.Black, fontSize = 21.sp, lineHeight = 25.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${a.v.kind.uppercase()} · ${if (a.v.kind == "net") "Network Guard" else "ExecuTorch detector"} · " +
                            (if (a.v.kind != "net") "${"%.1f".format(a.v.millis)} ms on device" else a.v.evidence.firstOrNull { it.startsWith("interval_") || it.startsWith("ports_") || it.startsWith("subdomains_") || it.startsWith("nxdomain_") }?.replace('_', ' ') ?: ""),
                        color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                    )
                    if (a.warnMs > 0) Text("warned in ${"%.0f".format(a.warnMs)} ms", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontFamily = Sh.Mono)
                }
                ScoreRing(a.v.score, Color.White)
            }
        }

        // 2. the message, with the risky parts lit up
        Glass(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (a.sender.isNotEmpty()) Text((if (a.v.kind == "net") (if (hi) "स्रोत: " else "Source: ") else (if (hi) "भेजने वाला: " else "From: ")) + a.sender, color = Sh.Text2, fontSize = 12.sp, fontWeight = Sh.Bold)
                Text(highlight(a.text), color = Sh.Text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }

        // 3. evidence chips
        val signs = Advice.signs(a.v)
        if (signs.isNotEmpty()) {
            SectionTitle(if (hi) "संकेत" else "Warning signs")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                signs.forEach { Pill(it, Sh.band(a.v.band), filled = true) }
            }
        }
        if (a.v.band == Band.SAFE) {
            Text(if (hi) "यह सुरक्षित लगता है। फिर भी कभी OTP या PIN न बताएं।" else "This looks safe. Still, never share an OTP or PIN.", color = Sh.Text2, fontSize = 13.sp)
            return@Column
        }

        // 4. why: Llama, streamed
        Glass(Modifier.fillMaxWidth(), glow = Sh.Violet) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🧠", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (hi) "क्यों" else "Why", color = Sh.Text, fontWeight = Sh.Black, fontSize = 16.sp)
                        Text("Llama 3.2 1B · ExecuTorch · ${if (hi) "इसी फ़ोन पर" else "on this phone"}", color = Sh.Text3, fontSize = 11.sp)
                    }
                    if (busy) Thinking(Sh.Violet)
                }
                when {
                    !ex.available() -> Text(if (hi) "Llama मॉडल इंस्टॉल नहीं है। पहचान और नीचे के कदम फिर भी काम करते हैं।" else "Llama model not installed on this phone. Detection and the steps below still work.", color = Sh.Text2, fontSize = 13.sp)
                    why.isEmpty() -> Text(if (hi) "सोच रहा है…" else "Thinking…", color = Sh.Text2, fontSize = 14.sp)
                    else -> Text(why + if (busy) " ▍" else "", color = Sh.Text, fontSize = 15.sp, lineHeight = 21.sp)
                }
                if (whyStats.isNotEmpty()) Text(whyStats, color = Sh.Text3, fontSize = 10.sp, fontFamily = Sh.Mono)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(if (hi) "🔊 सुनें" else "🔊 Read aloud", Sh.Violet) {
                        val ok = Speaker.say(listOf(Advice.headline(a.v, lang), why, Advice.steps(a.v.family, lang).joinToString(". ")).joinToString(". "), lang)
                        if (!ok) Toast.makeText(ctx, if (hi) "इस फ़ोन पर हिंदी आवाज़ नहीं है" else "No voice for this language on this phone", Toast.LENGTH_SHORT).show()
                    }
                    if (busy) SmallButton(if (hi) "⏹ रोकें" else "⏹ Stop", Sh.Text2) { ex.stop() }
                }
            }
        }

        // 5. what to do: fixed, checked text
        SectionTitle(if (hi) "क्या करें" else "What to do now")
        Glass(Modifier.fillMaxWidth(), glow = Sh.Green) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Advice.steps(a.v.family, lang).forEachIndexed { i, s ->
                    Row {
                        Box(Modifier.size(24.dp).background(Sh.Green.copy(alpha = 0.2f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Sh.Green, fontWeight = Sh.Black, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(s, color = Sh.Text, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
                Text(if (hi) "ये कदम पहले से जांचे गए हैं, AI ने नहीं लिखे।" else "These steps are pre-checked, not written by the AI.", color = Sh.Text3, fontSize = 10.sp)
            }
        }

        // 6. actions that really act
        val pkg = a.v.evidence.firstOrNull { it.startsWith("pkg:") }?.removePrefix("pkg:")?.takeIf { it.isNotEmpty() && it != "android" }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.sender.isNotEmpty() && a.v.kind != "net") ActionButton(if (hi) "⛔ ब्लॉक करें" else "⛔ Block sender", Sh.Red) { block(ctx, a.sender) }
            if (pkg != null) ActionButton(if (hi) "🗑 ऐप हटाएं" else "🗑 Remove app", Sh.Red) {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            }
            if (a.v.family == Family.NET_SCAN || a.v.family == Family.NET_BRUTE) ActionButton(if (hi) "📶 Wi-Fi छोड़ें" else "📶 Leave this Wi-Fi", Sh.Amber) {
                ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI))
            }
            ActionButton("📞 1930", Sh.Cyan) { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))) }
            ActionButton(if (hi) "📝 शिकायत" else "📝 Report", Sh.Cyan) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cybercrime.gov.in"))) }
            ActionButton(if (hi) "📋 रिपोर्ट कॉपी" else "📋 Copy report", Sh.Text2) { copy(ctx, reportText(a)) }
            if (a.source == "link") ActionButton(if (hi) "फिर भी खोलें" else "Open anyway", Sh.Text3) { LinkGuardActivity.openInBrowser(ctx, Uri.parse(a.text)) }
        }

        // 7. follow-up questions
        if (ex.available() && a.v.kind != "net") {
            SectionTitle(if (hi) "पूछें" else "Ask Shield")
            val qs = if (hi) listOf("मैंने लिंक खोल दिया, अब क्या करूं?", "मैंने OTP बता दिया", "मेरे पैसे कट गए")
            else listOf("I already clicked the link. What now?", "I shared the OTP", "I already paid money")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                qs.forEach { q -> SmallButton(q, Sh.Cyan, enabled = !busy) { answer = "…"; ask(Advice.followUpPrompt(a.v, a.text, q, lang), 100, null, { answer = it }, {}) } }
            }
            if (answer.isNotEmpty()) Glass(Modifier.fillMaxWidth()) { Text(answer, color = Sh.Text, fontSize = 14.sp, lineHeight = 20.sp) }
        }
        Text(if (hi) "समझाने वाला हिस्सा AI ने लिखा है और ग़लत हो सकता है। फ़ैसला और कदम AI ने नहीं लिखे।" else "The explanation is written by an AI model and can be imperfect. The verdict and the steps are not generated.",
            color = Sh.Text3, fontSize = 10.sp)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun SmallButton(text: String, c: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = if (enabled) 0.16f else 0.06f)).border(1.dp, c.copy(alpha = if (enabled) 0.5f else 0.2f), RoundedCornerShape(50))
        .clickable(enabled = enabled) { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(text, color = if (enabled) c else Sh.Text3, fontSize = 12.sp, fontWeight = Sh.Bold)
    }
}

@Composable
fun ActionButton(text: String, c: Color, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.18f)).border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
        .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text, color = Sh.Text, fontSize = 13.sp, fontWeight = Sh.Bold)
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
