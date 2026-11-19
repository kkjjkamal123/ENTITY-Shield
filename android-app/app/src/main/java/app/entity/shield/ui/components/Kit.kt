// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Glyph
import app.entity.shield.ui.theme.Palette
import app.entity.shield.ui.theme.Sh
import kotlin.math.sin

enum class Tone { Neutral, Accent, Warn, Danger, Info }

fun Palette.fill(t: Tone) = when (t) { Tone.Neutral -> text2; Tone.Accent -> accent; Tone.Warn -> warn; Tone.Danger -> danger; Tone.Info -> info }
fun Palette.ink(t: Tone) = when (t) { Tone.Neutral -> text2; Tone.Accent -> accentText; Tone.Warn -> warnText; Tone.Danger -> dangerText; Tone.Info -> infoText }

/** Scale down slightly while pressed: every tappable surface uses it, so the app feels physical. */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "press")
    this.graphicsLayer { scaleX = s; scaleY = s }.clickable(src, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun Card(modifier: Modifier = Modifier, pad: Dp = Dim.l, tone: Tone? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Sh.c
    val shape = RoundedCornerShape(Dim.rCard)
    val bg = if (tone == null) c.surface else lerpOver(c.surface, c.fill(tone), if (c.dark) 0.10f else 0.08f)
    val line = if (tone == null) c.line else c.fill(tone).copy(alpha = 0.28f)
    Column(
        modifier.clip(shape).background(bg).border(1.dp, line, shape)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier).padding(pad),
        content = content,
    )
}

fun lerpOver(base: Color, over: Color, a: Float) = Color(
    red = base.red + (over.red - base.red) * a, green = base.green + (over.green - base.green) * a,
    blue = base.blue + (over.blue - base.blue) * a, alpha = 1f,
)

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Dim.s, bottom = Dim.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Sh.t.display, color = Sh.c.text)
            if (subtitle != null) Text(subtitle, style = Sh.t.callout, color = Sh.c.text2, modifier = Modifier.padding(top = 2.dp))
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = Dim.m, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = Sh.t.overline, color = Sh.c.text3, modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = Sh.t.caption, color = Sh.c.accentText, modifier = Modifier.pressable(onClick = onAction).padding(4.dp))
    }
}

@Composable
fun IconTile(icon: ImageVector, tone: Tone, size: Dp = 40.dp) {
    val c = Sh.c
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(c.fill(tone).copy(alpha = if (c.dark) 0.14f else 0.12f)), contentAlignment = Alignment.Center) {
        Glyph(icon, c.ink(tone), size * 0.52f)
    }
}

@Composable
fun Tag(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val c = Sh.c
    Box(modifier.clip(RoundedCornerShape(Dim.rChip)).background(c.fill(tone).copy(alpha = if (c.dark) 0.14f else 0.13f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, style = Sh.t.caption, color = c.ink(tone), maxLines = 1)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val c = Sh.c
    val bg by animateColorAsState(if (enabled) c.accent else c.surface3, label = "btn")
    Row(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(bg).pressable(enabled && !busy, onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) TypingDots(c.onAccent) else {
            if (icon != null) { Glyph(icon, if (enabled) c.onAccent else c.text3, 20.dp); Spacer(Modifier.width(8.dp)) }
            Text(text, style = Sh.t.headline, color = if (enabled) c.onAccent else c.text3)
        }
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: Tone = Tone.Neutral, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Sh.c
    val ink = if (tone == Tone.Neutral) c.text else c.ink(tone)
    Row(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .pressable(enabled, onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Glyph(icon, if (enabled) ink else c.text3, 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Sh.t.callout.copy(fontWeight = Sh.t.headline.fontWeight), color = if (enabled) ink else c.text3, maxLines = 1)
    }
}

/** Small rounded action used in rows of choices (follow-up questions, filters). */
@Composable
fun Chip(text: String, selected: Boolean = false, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Sh.c
    val bg by animateColorAsState(if (selected) c.text else c.surface2, label = "chip")
    val fg = if (selected) c.bg else if (enabled) c.text else c.text3
    Row(
        Modifier.clip(RoundedCornerShape(Dim.rChip)).background(bg).border(1.dp, if (selected) Color.Transparent else c.line, RoundedCornerShape(Dim.rChip))
            .pressable(enabled, onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Glyph(icon, fg, 16.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Sh.t.caption.copy(fontSize = Sh.t.callout.fontSize * 0.93f), color = fg, maxLines = 1)
    }
}

/** Segmented control with a sliding thumb. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = Sh.c
    val pos by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f), label = "seg")
    Box(
        modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(3.dp)
            .drawBehind {
                val slot = size.width / options.size
                val travel = sin(Math.PI.toFloat() * (pos - kotlin.math.floor(pos)))
                val ww = slot * (1f + 0.25f * travel)
                val left = (slot * (pos + 0.5f) - ww / 2f).coerceIn(0f, size.width - ww)
                drawRoundRect(if (c.dark) c.surface3 else Color.White, Offset(left, 0f), Size(ww, size.height), CornerRadius(9.dp.toPx()))
            },
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { i, o ->
                Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 34.dp).clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(o, style = Sh.t.caption, color = if (i == selected) c.text else c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One row in a grouped list. */
@Composable
fun ListRow(
    title: String, subtitle: String? = null, icon: ImageVector? = null, tone: Tone = Tone.Neutral,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconTile(icon, tone, 38.dp); Spacer(Modifier.width(Dim.m)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Sh.t.headline, color = Sh.c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Sh.t.callout.copy(fontSize = Sh.t.caption.fontSize * 1.08f, lineHeight = Sh.t.caption.lineHeight * 1.1f),
                color = Sh.c.text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(Dim.s)); trailing() }
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Sh.c.line))

/** Switch drawn in the app's style (Material's has a different look and a thumb icon). */
@Composable
fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Sh.c
    val x by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "tog")
    val track by animateColorAsState(if (checked) c.accent else c.surface3, label = "track")
    Box(
        Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track)
            .clickable(remember { MutableInteractionSource() }, null, enabled) { onChange(!checked) }.padding(3.dp),
    ) {
        Box(Modifier.offset(x = 20.dp * x).size(24.dp).clip(CircleShape).background(if (checked) c.onAccent else if (c.dark) c.text2 else Color.White))
    }
}

/** A big number: shrinks to fit its column (large font settings, small screens, 6-digit counts). */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    Column(modifier) {
        BasicText(value, style = Sh.t.number.copy(color = if (tone == Tone.Neutral) Sh.c.text else Sh.c.ink(tone)), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = Sh.t.number.fontSize))
        Text(label, style = Sh.t.caption, color = Sh.c.text2, maxLines = 2)
    }
}

/** Ring that fills to [value] (0..1) with the number in the middle. */
@Composable
fun ScoreRing(value: Float, color: Color, size: Dp = 76.dp, label: String) {
    val c = Sh.c
    val v by animateFloatAsState(value.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "ring")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 6.dp.toPx()
            val tl = Offset(st / 2, st / 2)
            val sz = Size(this.size.width - st, this.size.height - st)
            drawArc(c.line.copy(alpha = 0.14f), -90f, 360f, false, tl, sz, style = Stroke(st))
            drawArc(color, -90f, 360f * v, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("${(v * 100).toInt()}", style = Sh.t.number.copy(fontSize = Sh.t.title2.fontSize, color = c.text), maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = Sh.t.title2.fontSize))
            Text(label, style = Sh.t.overline.copy(fontSize = Sh.t.overline.fontSize * 0.85f), color = c.text2)
        }
    }
}

@Composable
fun Meter(frac: Float, color: Color, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(frac.coerceIn(0f, 1f), tween(700), label = "meter")
    Box(modifier.height(6.dp).clip(CircleShape).background(Sh.c.surface3)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(v).clip(CircleShape).background(color))
    }
}

/** Three dots while the on-device model is working. */
@Composable
fun TypingDots(color: Color = Sh.c.text2) {
    val t = rememberInfiniteTransition(label = "dots")
    val a = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {   // read in the layer block: animates without recomposing
            Box(Modifier.size(6.dp).graphicsLayer {
                val k = ((sin((a.value - i * 0.18f) * 2 * Math.PI) + 1) / 2).toFloat()
                alpha = 0.3f + 0.7f * k; translationY = -3.dp.toPx() * k
            }.background(color, CircleShape))
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile(icon, Tone.Neutral, 56.dp)
        Spacer(Modifier.height(Dim.m))
        Text(title, style = Sh.t.headline, color = Sh.c.text)
        Text(body, style = Sh.t.callout, color = Sh.c.text2, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Small status dot with a soft halo, pulsing when [live]. */
@Composable
fun StatusDot(color: Color, live: Boolean = false) {
    if (!live) { Canvas(Modifier.size(14.dp)) { drawCircle(color, radius = size.minDimension * 0.22f) }; return }
    val t = rememberInfiniteTransition(label = "dot")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    Canvas(Modifier.size(14.dp)) {   // the pulse is read while drawing: it never recomposes
        val v = p.value
        drawCircle(color.copy(alpha = (1f - v) * 0.35f), radius = size.minDimension / 2f * (0.45f + 0.55f * v))
        drawCircle(color, radius = size.minDimension * 0.22f)
    }
}
