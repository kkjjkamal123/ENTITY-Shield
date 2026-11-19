// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.entity.shield.core.Advice
import app.entity.shield.core.Alert
import app.entity.shield.core.Band
import app.entity.shield.core.Family
import app.entity.shield.net.NetGuard
import app.entity.shield.ui.LocalLang
import app.entity.shield.ui.LocalAdaptive
import app.entity.shield.ui.LocalNav
import app.entity.shield.ui.sideCutoutPadding
import app.entity.shield.ui.components.IconTile
import app.entity.shield.ui.components.Tone
import app.entity.shield.ui.components.pressable
import app.entity.shield.ui.glass.GlassSurface
import app.entity.shield.ui.glass.backdropSource
import app.entity.shield.ui.glass.rememberBackdrop
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Glyph
import app.entity.shield.ui.theme.Ico
import app.entity.shield.ui.theme.Sh
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun toneOf(b: Band) = when (b) { Band.SCAM -> Tone.Danger; Band.UNCERTAIN -> Tone.Warn; Band.SAFE -> Tone.Accent }

fun kindIcon(a: Alert): ImageVector = when (a.v.kind) {
    "url" -> Ico.Link
    "net" -> if (a.v.family == Family.NET_SCAN || a.v.family == Family.NET_BRUTE) Ico.Radar else Ico.Globe
    else -> Ico.Message
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())

fun whenText(at: Long): String {
    val d = System.currentTimeMillis() - at
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000} min"
        d < 86_400_000 -> timeFmt.format(Date(at))
        else -> dayFmt.format(Date(at))
    }
}

@Composable
fun AlertRow(a: Alert, onClick: () -> Unit) {
    val c = Sh.c
    val lang = LocalLang.current
    Row(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        IconTile(kindIcon(a), toneOf(a.v.band), 40.dp)
        Spacer(Modifier.width(Dim.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Advice.headline(a.v, lang), style = Sh.t.headline, color = c.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Dim.s))
                Text(whenText(a.at), style = Sh.t.monoSmall, color = c.text3)
            }
            Text(a.text, style = Sh.t.callout, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            val src = a.sender.ifEmpty { a.v.worstHost ?: "" }
            if (src.isNotEmpty()) Text(src, style = Sh.t.monoSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Language switch: EN | हिं. */
@Composable
fun LangSwitch() {
    val c = Sh.c
    val hi = LocalLang.current == Advice.Lang.HI
    val nav = LocalNav.current
    Row(
        Modifier.clip(RoundedCornerShape(Dim.rChip)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(Dim.rChip))
            .pressable(onClick = nav.toggleLang).padding(3.dp),
    ) {
        listOf("EN" to !hi, "हिं" to hi).forEach { (t, on) ->
            Box(Modifier.clip(RoundedCornerShape(Dim.rChip)).background(if (on) c.text else c.surface2).padding(horizontal = 11.dp, vertical = 5.dp)) {
                Text(t, style = Sh.t.caption, color = if (on) c.bg else c.text2)
            }
        }
    }
}

/**
 * Three guard rings (SMS, network, links) around the shield: a ring is closed when that guard is on.
 * They draw themselves in once when the screen opens.
 */
@Composable
fun GuardRings(sms: Boolean, net: Boolean, link: Boolean, threat: Boolean, size: Dp = 128.dp) {
    val c = Sh.c
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) { prog.animateTo(1.24f, tween(1300, easing = FastOutSlowInEasing)) } // 1 + stagger, so every ring completes
    val on = listOf(sms, net, link)
    val ring = if (threat) c.danger else c.accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 7.dp.toPx()
            val gap = 4.dp.toPx()
            for (i in 0..2) {
                val inset = st / 2 + i * (st + gap)
                val tl = Offset(inset, inset)
                val sz = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
                drawArc(c.text.copy(alpha = 0.07f), 0f, 360f, false, tl, sz, style = Stroke(st))
                val sweep = (if (on[i]) 360f else 0f) * (prog.value - i * 0.12f).coerceIn(0f, 1f)
                if (sweep > 0f) drawArc(ring.copy(alpha = 1f - i * 0.22f), -90f, sweep, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
            }
        }
        Glyph(if (threat) Ico.ShieldAlert else Ico.ShieldCheck, if (threat) c.dangerText else c.accentText, size * 0.3f)
    }
}

/** App icons are decoded off the main thread (PackageManager can take 10+ ms each) and cached. */
@Composable
fun AppIcon(pkg: String, size: Dp = 34.dp) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pkg) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NetGuard.icon(ctx, pkg)?.asImageBitmap() }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)))
    else IconTile(Ico.Globe, Tone.Neutral, size)
}

/**
 * Scaffold for screens opened on top of the tabs: their own glass back button floats over the content,
 * which scrolls underneath it.
 */
@Composable
fun DetailScaffold(title: String, actions: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Sh.c
    val nav = LocalNav.current
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        val a = LocalAdaptive.current
        Column(
            Modifier.fillMaxSize().backdropSource(backdrop).background(c.bg).verticalScroll(rememberScrollState())
                .statusBarsPadding().sideCutoutPadding().padding(top = 64.dp, bottom = 40.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = a.maxContent).fillMaxWidth().padding(horizontal = a.gutter), verticalArrangement = Arrangement.spacedBy(Dim.m), content = content)
        }
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.bg.copy(alpha = 0.9f)))
        Row(Modifier.statusBarsPadding().sideCutoutPadding().padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassSurface(backdrop, Modifier.size(46.dp).pressable(onClick = nav.back), radius = 23.dp) {
                Glyph(Ico.Back, c.text, 22.dp, Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(10.dp))
            GlassSurface(backdrop, Modifier.height(46.dp).weight(1f, fill = false), radius = 23.dp) {
                Text(title, style = Sh.t.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp))
            }
            Spacer(Modifier.weight(1f))
            if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

/** Round glass button for the detail top bar. */
@Composable
fun BarIcon(icon: ImageVector, onClick: () -> Unit) {
    val c = Sh.c
    Box(Modifier.size(46.dp).clip(CircleShape).background(c.surface2).border(1.dp, c.line, CircleShape).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(icon, c.text, 20.dp)
    }
}
