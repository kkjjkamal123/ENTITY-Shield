// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One palette, tuned for a dark "security console" look. */
object Sh {
    val Bg0 = Color(0xFF04060C)
    val Bg1 = Color(0xFF0A0F1E)
    val Bg2 = Color(0xFF111833)
    val Glass = Color(0x14FFFFFF)
    val GlassHi = Color(0x22FFFFFF)
    val Stroke = Color(0x26FFFFFF)
    val Text = Color(0xFFE8ECF6)
    val Text2 = Color(0xFF9AA6C0)
    val Text3 = Color(0xFF5F6B85)
    val Cyan = Color(0xFF22D3EE)
    val Violet = Color(0xFFA78BFA)
    val Pink = Color(0xFFF472B6)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFFF4D6D)
    val Blue = Color(0xFF60A5FA)

    val Mono = FontFamily.Monospace
    val Bold = FontWeight.Bold
    val Black = FontWeight.Black

    val accent = Brush.linearGradient(listOf(Cyan, Violet, Pink))
    val accentH = Brush.horizontalGradient(listOf(Cyan, Violet))
    val bg = Brush.verticalGradient(listOf(Bg1, Bg0, Bg0))

    fun band(b: Band) = when (b) { Band.SCAM -> Red; Band.UNCERTAIN -> Amber; Band.SAFE -> Green }
    fun bandBrush(b: Band) = when (b) {
        Band.SCAM -> Brush.linearGradient(listOf(Color(0xFFFF4D6D), Color(0xFFB4235A)))
        Band.UNCERTAIN -> Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFD9822B)))
        Band.SAFE -> Brush.linearGradient(listOf(Color(0xFF34D399), Color(0xFF0E9F6E)))
    }
}

@Composable
fun ShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sh.Cyan, secondary = Sh.Violet, tertiary = Sh.Pink,
            background = Sh.Bg0, surface = Sh.Bg1, surfaceVariant = Sh.Bg2,
            onPrimary = Sh.Bg0, onBackground = Sh.Text, onSurface = Sh.Text, onSurfaceVariant = Sh.Text2,
            error = Sh.Red, outline = Sh.Text3,
        ),
        content = content,
    )
}

/** Frosted card used everywhere. */
@Composable
fun Glass(modifier: Modifier = Modifier, radius: Dp = 20.dp, pad: Dp = 16.dp, glow: Color? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .background(if (glow != null) Brush.linearGradient(listOf(glow.copy(alpha = 0.16f), Sh.Glass)) else Brush.linearGradient(listOf(Sh.GlassHi, Sh.Glass)), shape)
            .border(1.dp, if (glow != null) glow.copy(alpha = 0.45f) else Sh.Stroke, shape)
            .padding(pad),
        content = content,
    )
}
