// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.entity.shield.ui.theme.Sh

/**
 * Shield's glass material, written from scratch (no library):
 *  1. [backdropSource] records whatever is drawn behind the glass (the page) into a GraphicsLayer.
 *  2. A glass surface redraws that layer, offset to its own position, through a RenderEffect chain:
 *     Gaussian blur, then (Android 13+) an AGSL lens that bends the light near the rim, with a touch
 *     of chromatic dispersion, the way a thick glass edge does.
 *  3. A tint, a rim highlight (bright top-left, dim bottom-right) and the content go on top, unblurred.
 * Android 12 gets blur without the lens; Android 10-11 get a frosted (more opaque) tint.
 * Cost: one extra replay of the page's display list, clipped to the glass, on the GPU.
 */
/**
 * How much glass this device gets. Chosen once per launch: full lens + blur on Android 13+, blur only on
 * Android 12, and a frosted tint (no backdrop capture at all, so zero extra GPU work) on Android 10-11,
 * low-RAM phones and in Battery Saver.
 */
enum class GlassQuality { High, Blur, Frosted }

fun glassQuality(ctx: android.content.Context): GlassQuality {
    if (Build.VERSION.SDK_INT < 31) return GlassQuality.Frosted
    val am = ctx.getSystemService(android.app.ActivityManager::class.java)
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    if (am.isLowRamDevice || am.memoryClass < 160 || pm.isPowerSaveMode) return GlassQuality.Frosted
    return if (Build.VERSION.SDK_INT >= 33) GlassQuality.High else GlassQuality.Blur
}

@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Everything drawn by this node (and its children) becomes available to glass surfaces above it. */
fun Modifier.backdropSource(b: Backdrop, enabled: Boolean = true): Modifier = if (!enabled) this else this
    .onGloballyPositioned { b.origin = it.positionInRoot() }
    .drawWithContent {
        b.layer.record { this@drawWithContent.drawContent() }
        drawLayer(b.layer)
    }

private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float rim;
uniform float strength;

float sdBox(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord - hs;
    float d = sdBox(p, hs, radius);
    float t = clamp(1.0 + d / rim, 0.0, 1.0);
    float e = 1.0;
    float2 g = float2(sdBox(p + float2(e, 0.0), hs, radius) - sdBox(p - float2(e, 0.0), hs, radius),
                      sdBox(p + float2(0.0, e), hs, radius) - sdBox(p - float2(0.0, e), hs, radius));
    float2 n = g / max(length(g), 1e-4);
    float k = t * t * t * strength;
    float2 s = clamp(coord - n * k, float2(0.5), size - 0.5);
    half4 base = content.eval(s);
    half r = content.eval(clamp(s - n * k * 0.10, float2(0.5), size - 0.5)).r;
    half b = content.eval(clamp(s + n * k * 0.10, float2(0.5), size - 0.5)).b;
    return half4(r, base.g, b, base.a);
}
"""

private fun glassEffect(w: Float, h: Float, radius: Float, blur: Float, rim: Float, strength: Float, lens: Boolean): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < 31 || w <= 0f || h <= 0f) return null
    val blurFx = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
    if (Build.VERSION.SDK_INT < 33 || !lens) return blurFx.asComposeRenderEffect()
    val sh = RuntimeShader(LENS).apply {
        setFloatUniform("size", w, h)
        setFloatUniform("radius", radius)
        setFloatUniform("rim", rim)
        setFloatUniform("strength", strength)
    }
    return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"), blurFx).asComposeRenderEffect()
}

/**
 * A piece of glass showing [backdrop] behind it. [radius] is the corner radius (a capsule when it is
 * half the height). Content is drawn sharp on top.
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    blur: Dp = 22.dp,
    lens: Dp = 14.dp,
    tint: Color = Sh.c.glassTint,
    quality: GlassQuality = GlassQuality.High,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Sh.c
    var pos by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(radius)
    if (quality == GlassQuality.Frosted) {
        Box(modifier) {
            Box(Modifier.matchParentSize().clip(shape).background(if (c.dark) Color(0xF01A1C20) else Color(0xF5FFFFFF)))
            Box(Modifier.matchParentSize().border(1.dp, Brush.linearGradient(listOf(c.glassRimHi, c.glassRimLo, c.glassRimLo, c.glassRimHi.copy(alpha = c.glassRimHi.alpha * 0.4f))), shape))
            content()
        }
        return
    }
    Box(modifier.onGloballyPositioned { pos = it.positionInRoot() }) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    clip = true
                    this.shape = shape
                    renderEffect = glassEffect(size.width, size.height, radius.toPx(), blur.toPx(), lens.toPx() * 2.2f, lens.toPx(), quality == GlassQuality.High)
                }
                .drawBehind {
                    translate(backdrop.origin.x - pos.x, backdrop.origin.y - pos.y) { drawLayer(backdrop.layer) }
                    drawRect(tint)
                },
        )
        Box(
            Modifier
                .matchParentSize()
                .border(1.dp, Brush.linearGradient(listOf(c.glassRimHi, c.glassRimLo, c.glassRimLo, c.glassRimHi.copy(alpha = c.glassRimHi.alpha * 0.4f))), shape),
        )
        content()
    }
}
