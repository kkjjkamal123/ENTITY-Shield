// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.entity.shield.ui.theme.Glyph
import app.entity.shield.ui.theme.Sh
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

data class NavItem(val label: String, val icon: ImageVector, val badge: Int = 0)

/**
 * Floating glass tab bar. The selection is a liquid lens on its own spring: it glides across any
 * number of tabs in one motion and stretches in proportion to its real speed, then settles. Dragging a
 * finger along the bar slides the lens with it (with a tick at every tab) and selects where it is let go.
 * Everything that moves is read in the draw phase only, so an animating bar never recomposes.
 */
@Composable
fun GlassNavBar(
    backdrop: Backdrop,
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    glass: GlassQuality = GlassQuality.High,
) {
    val c = Sh.c
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(selected.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { if (!dragging) pos.animateTo(selected.toFloat(), spring(dampingRatio = 0.72f, stiffness = 340f)) }
    val pillFill = if (c.dark) Color(0x1FFFFFFF) else Color(0x14000000)
    val pillRimHi = if (c.dark) Color(0x52FFFFFF) else Color(0xE6FFFFFF)
    val pillRimLo = if (c.dark) Color(0x0FFFFFFF) else Color(0x1A000000)
    val n = items.size

    GlassSurface(backdrop, modifier.fillMaxWidth().height(68.dp), radius = 34.dp, quality = glass) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(5.dp)
                .pointerInput(n) {
                    var last = -1
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; last = (o.x / (size.width / n)).toInt(); scope.launch { pos.snapTo((o.x / (size.width / n) - 0.5f).coerceIn(0f, n - 1f)) } },
                        onDragEnd = { dragging = false; val t = pos.value.roundToInt().coerceIn(0, n - 1); scope.launch { pos.animateTo(t.toFloat(), spring(dampingRatio = 0.72f, stiffness = 340f)) }; onSelect(t) },
                        onDragCancel = { dragging = false; scope.launch { pos.animateTo(selected.toFloat()) } },
                    ) { change, _ ->
                        val p = (change.position.x / (size.width / n) - 0.5f).coerceIn(0f, n - 1f)
                        scope.launch { pos.snapTo(p) }
                        val idx = p.roundToInt()
                        if (idx != last) { last = idx; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                }
                .drawBehind {
                    val slot = size.width / n
                    val p = pos.value
                    val speed = if (dragging) 0f else (abs(pos.velocity) / 14f).coerceIn(0f, 1f)   // tabs per second -> 0..1
                    val inset = 3.dp.toPx()
                    val w = slot * (1f + 0.55f * speed) - 2 * inset
                    val h = size.height * (1f - 0.12f * speed)
                    val cx = slot * (p + 0.5f)
                    val left = (cx - w / 2f).coerceIn(inset, size.width - w - inset)
                    val top = (size.height - h) / 2f
                    val r = CornerRadius(h / 2f, h / 2f)
                    drawRoundRect(pillFill, Offset(left, top), Size(w, h), r)
                    drawRoundRect(
                        Brush.linearGradient(listOf(pillRimHi, pillRimLo, pillRimLo, pillRimHi.copy(alpha = pillRimHi.alpha * 0.5f)),
                            start = Offset(left, top), end = Offset(left + w, top + h)),
                        Offset(left, top), Size(w, h), r, style = Stroke(1.dp.toPx()),
                    )
                },
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, it ->
                    NavCell(it, active = { 1f - abs(pos.value - i).coerceAtMost(1f) }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (i != selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(i)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavCell(item: NavItem, active: () -> Float, modifier: Modifier, onClick: () -> Unit) {
    val c = Sh.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    val on = if (c.dark) c.accent else c.text
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // two copies, cross-faded on the GPU: no recomposition while the lens moves
        Box {
            Glyph(item.icon, c.text2, 22.dp, Modifier.graphicsLayer { alpha = 1f - active() })
            Glyph(item.icon, on, 22.dp, Modifier.graphicsLayer { alpha = active() })
            if (item.badge > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(8.dp).background(c.danger, CircleShape))
        }
        Spacer(Modifier.height(3.dp))
        Box {
            val st = Sh.t.caption.copy(fontSize = Sh.t.caption.fontSize * 0.92f)
            Text(item.label, style = st, color = c.text2, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.graphicsLayer { alpha = 1f - active() })
            Text(item.label, style = st, color = on, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.graphicsLayer { alpha = active() })
        }
    }
}
