// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The hero: a shield inside pulsing rings and a radar sweep. Colour follows the state. */
@Composable
fun ShieldOrb(state: Band, size: Dp = 210.dp, sweeping: Boolean = true) {
    val t = rememberInfiniteTransition(label = "orb")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "sweep")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pulse")
    val breathe by t.animateFloat(0.92f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val c = Sh.band(state)
    Canvas(Modifier.size(size)) {
        val r = min(this.size.width, this.size.height) / 2f
        val ctr = Offset(this.size.width / 2f, this.size.height / 2f)
        // expanding pulse rings
        for (k in 0..2) {
            val p = (pulse + k / 3f) % 1f
            drawCircle(c.copy(alpha = (1f - p) * 0.35f), radius = r * (0.45f + 0.55f * p), center = ctr, style = Stroke(2.dp.toPx()))
        }
        // static rings
        drawCircle(Sh.Stroke, radius = r * 0.98f, center = ctr, style = Stroke(1.dp.toPx()))
        drawCircle(Sh.Stroke, radius = r * 0.72f, center = ctr, style = Stroke(1.dp.toPx()))
        // radar sweep
        if (sweeping) rotate(sweep, ctr) {
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, c.copy(alpha = 0.0f), c.copy(alpha = 0.45f)), center = ctr),
                startAngle = -90f, sweepAngle = 90f, useCenter = true,
                topLeft = Offset(ctr.x - r * 0.98f, ctr.y - r * 0.98f), size = Size(r * 1.96f, r * 1.96f),
            )
            drawLine(c.copy(alpha = 0.9f), ctr, Offset(ctr.x, ctr.y - r * 0.98f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        // blips
        for (i in 0..4) {
            val a = Math.toRadians((i * 77 + 30).toDouble())
            val rr = r * (0.3f + 0.13f * i)
            drawCircle(c.copy(alpha = 0.55f), radius = 2.5.dp.toPx(), center = Offset(ctr.x + (rr * cos(a)).toFloat(), ctr.y + (rr * sin(a)).toFloat()))
        }
        // shield body
        val s = r * 0.46f * breathe
        val shield = Path().apply {
            moveTo(ctr.x, ctr.y - s)
            cubicTo(ctr.x + s * 0.55f, ctr.y - s * 0.78f, ctr.x + s * 0.82f, ctr.y - s * 0.72f, ctr.x + s * 0.86f, ctr.y - s * 0.70f)
            cubicTo(ctr.x + s * 0.86f, ctr.y + s * 0.15f, ctr.x + s * 0.55f, ctr.y + s * 0.72f, ctr.x, ctr.y + s)
            cubicTo(ctr.x - s * 0.55f, ctr.y + s * 0.72f, ctr.x - s * 0.86f, ctr.y + s * 0.15f, ctr.x - s * 0.86f, ctr.y - s * 0.70f)
            cubicTo(ctr.x - s * 0.82f, ctr.y - s * 0.72f, ctr.x - s * 0.55f, ctr.y - s * 0.78f, ctr.x, ctr.y - s)
            close()
        }
        drawPath(shield, Brush.linearGradient(listOf(c.copy(alpha = 0.95f), Sh.Violet.copy(alpha = 0.85f)), start = Offset(ctr.x - s, ctr.y - s), end = Offset(ctr.x + s, ctr.y + s)))
        drawPath(shield, Color.White.copy(alpha = 0.25f), style = Stroke(1.5.dp.toPx()))
        // glyph: check when safe, "!" otherwise
        if (state == Band.SAFE) {
            val p = Path().apply {
                moveTo(ctr.x - s * 0.36f, ctr.y + s * 0.02f)
                lineTo(ctr.x - s * 0.08f, ctr.y + s * 0.30f)
                lineTo(ctr.x + s * 0.40f, ctr.y - s * 0.26f)
            }
            drawPath(p, Color.White, style = Stroke(s * 0.14f, cap = StrokeCap.Round))
        } else {
            drawLine(Color.White, Offset(ctr.x, ctr.y - s * 0.40f), Offset(ctr.x, ctr.y + s * 0.12f), strokeWidth = s * 0.15f, cap = StrokeCap.Round)
            drawCircle(Color.White, radius = s * 0.085f, center = Offset(ctr.x, ctr.y + s * 0.38f))
        }
    }
}

/** Ring that fills to [value] (0..1), animated, with a percentage in the middle. */
@Composable
fun ScoreRing(value: Float, color: Color, size: Dp = 74.dp, label: String = "risk") {
    val v by animateFloatAsState(value.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "ring")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 7.dp.toPx()
            drawArc(Color.White.copy(alpha = 0.18f), -90f, 360f, false, topLeft = Offset(st / 2, st / 2), size = Size(this.size.width - st, this.size.height - st), style = Stroke(st))
            drawArc(Color.White, -90f, 360f * v, false, topLeft = Offset(st / 2, st / 2), size = Size(this.size.width - st, this.size.height - st), style = Stroke(st, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(v * 100).toInt()}%", color = Color.White, fontWeight = Sh.Black, fontSize = 18.sp, fontFamily = Sh.Mono)
            Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
        }
    }
}

@Composable
fun StatTile(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Glass(modifier, radius = 18.dp, pad = 14.dp) {
        Column {
            Text(value, color = Sh.Text, fontSize = 24.sp, fontWeight = Sh.Black, fontFamily = Sh.Mono, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Box(Modifier.width(22.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(6.dp))
            Text(label, color = Sh.Text2, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2)
        }
    }
}

@Composable
fun Pill(text: String, color: Color = Sh.Cyan, filled: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = Sh.Text2, fontSize = 12.sp, fontWeight = Sh.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = Sh.Cyan, fontSize = 12.sp, fontWeight = Sh.Bold, modifier = Modifier.clickable { onAction() })
    }
}

/** Round glyph badge (emoji/text inside a tinted circle). */
@Composable
fun Glyph(g: String, color: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(color.copy(alpha = 0.16f), CircleShape).border(1.dp, color.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
        Text(g, fontSize = (size.value * 0.45f).sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun GuardRow(glyph: String, title: String, subtitle: String, on: Boolean, enabled: Boolean = true, onToggle: ((Boolean) -> Unit)?) {
    Glass(Modifier.fillMaxWidth(), radius = 18.dp, pad = 14.dp, glow = if (on) Sh.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, if (on) Sh.Green else Sh.Text3)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Sh.Text, fontWeight = Sh.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Pill(if (on) "ON" else "OFF", if (on) Sh.Green else Sh.Text3, filled = on)
                }
                Text(subtitle, color = Sh.Text2, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (onToggle != null) Switch(
                checked = on, enabled = enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Sh.Bg0, checkedTrackColor = Sh.Green, uncheckedTrackColor = Sh.Bg2, uncheckedBorderColor = Sh.Stroke),
            )
        }
    }
}

/** Small animated equalizer used while Llama is generating. */
@Composable
fun Thinking(color: Color = Sh.Cyan) {
    val t = rememberInfiniteTransition(label = "think")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(width = 34.dp, height = 16.dp)) {
        for (i in 0..3) {
            val h = (0.3f + 0.7f * kotlin.math.abs(sin((a + i * 0.22f) * Math.PI)).toFloat()) * size.height
            drawRoundRect(color, topLeft = Offset(i * size.width / 4f + 2f, size.height - h), size = Size(size.width / 4f - 4f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f))
        }
    }
}

/** Minimal horizontal bar for benchmark tables. */
@Composable
fun MeterBar(frac: Float, color: Color, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(frac.coerceIn(0f, 1f), tween(700), label = "bar")
    Box(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(Sh.Glass)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(v).background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.6f))), RoundedCornerShape(4.dp)))
    }
}
