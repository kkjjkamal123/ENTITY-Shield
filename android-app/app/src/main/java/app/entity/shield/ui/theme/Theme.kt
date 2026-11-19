// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.entity.shield.R
import app.entity.shield.core.Band

/**
 * Design tokens. Graphite neutrals, one accent (signal lime) for "protected" and primary actions, and
 * colour otherwise only where it carries meaning: amber = check this, red = danger, blue = network info.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color, val bgDeep: Color,
    val surface: Color, val surface2: Color, val surface3: Color,
    val line: Color, val lineStrong: Color,
    val text: Color, val text2: Color, val text3: Color,
    val accent: Color, val onAccent: Color, val accentText: Color,
    val warn: Color, val warnText: Color,
    val danger: Color, val dangerText: Color,
    val info: Color, val infoText: Color,
    val glassTint: Color, val glassRimHi: Color, val glassRimLo: Color, val shadow: Color,
) {
    fun band(b: Band) = when (b) { Band.SCAM -> danger; Band.UNCERTAIN -> warn; Band.SAFE -> accent }
    fun bandText(b: Band) = when (b) { Band.SCAM -> dangerText; Band.UNCERTAIN -> warnText; Band.SAFE -> accentText }
}

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0B0C0E), bgDeep = Color(0xFF070809),
    surface = Color(0xFF141518), surface2 = Color(0xFF1B1D21), surface3 = Color(0xFF25282D),
    line = Color(0x14FFFFFF), lineStrong = Color(0x26FFFFFF),
    text = Color(0xFFF2F3F5), text2 = Color(0xFFA0A6AF), text3 = Color(0xFF6B717A),
    accent = Color(0xFFC8F04A), onAccent = Color(0xFF0B0C0E), accentText = Color(0xFFC8F04A),
    warn = Color(0xFFFFB547), warnText = Color(0xFFFFC56E),
    danger = Color(0xFFFF5B4A), dangerText = Color(0xFFFF7A6B),
    info = Color(0xFF7AB6FF), infoText = Color(0xFF94C4FF),
    glassTint = Color(0x5218191C), glassRimHi = Color(0x59FFFFFF), glassRimLo = Color(0x14FFFFFF), shadow = Color(0x66000000),
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF3F3EF), bgDeep = Color(0xFFEAEAE4),
    surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF8F8F5), surface3 = Color(0xFFEDEDE8),
    line = Color(0x14000000), lineStrong = Color(0x24000000),
    text = Color(0xFF101214), text2 = Color(0xFF575D65), text3 = Color(0xFF8B9199),
    accent = Color(0xFFC8F04A), onAccent = Color(0xFF101214), accentText = Color(0xFF4A6600),
    warn = Color(0xFFF5A524), warnText = Color(0xFF9A5B00),
    danger = Color(0xFFE5402F), dangerText = Color(0xFFC22F20),
    info = Color(0xFF2F7BE0), infoText = Color(0xFF1C5DB4),
    glassTint = Color(0x8CFFFFFF), glassRimHi = Color(0xCCFFFFFF), glassRimLo = Color(0x1A000000), shadow = Color(0x26000000),
)

@OptIn(ExperimentalTextApi::class)
private val Geist = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.geist, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)
@OptIn(ExperimentalTextApi::class)
private val GeistMono = FontFamily(
    listOf(400, 500, 600).map { w ->
        Font(R.font.geist_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

@Immutable
data class Type(
    val display: TextStyle, val title1: TextStyle, val title2: TextStyle, val headline: TextStyle,
    val body: TextStyle, val callout: TextStyle, val caption: TextStyle, val overline: TextStyle,
    val mono: TextStyle, val monoSmall: TextStyle, val number: TextStyle,
)

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
private fun s(size: Int, line: Int, w: Int, track: Double = 0.0, fam: FontFamily = Geist) =
    TextStyle(fontFamily = fam, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(w), letterSpacing = track.sp, lineHeightStyle = trim)

val ShieldType = Type(
    display = s(34, 40, 600, -0.8),
    title1 = s(24, 30, 600, -0.4),
    title2 = s(19, 24, 600, -0.2),
    headline = s(16, 22, 600, -0.1),
    body = s(15, 22, 400),
    callout = s(14, 20, 400),
    caption = s(12, 16, 500),
    overline = s(11, 14, 600, 0.9),
    mono = s(13, 18, 500, fam = GeistMono),
    monoSmall = s(11, 14, 500, fam = GeistMono),
    number = s(28, 32, 600, -0.8, GeistMono),
)

/** Spacing (4 pt grid) and corner radii. */
object Dim {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 20.dp; val xxl = 28.dp
    val gutter = 20.dp
    val rCard = 22.dp; val rInner = 14.dp; val rChip = 100.dp
}

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object Sh {
    val c: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current
    val t: Type get() = ShieldType
}

@Composable
fun ShieldTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2,
        error = p.danger, outline = p.lineStrong,
    ) else lightColorScheme(
        primary = p.accentText, onPrimary = Color.White, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2,
        error = p.danger, outline = p.lineStrong,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
