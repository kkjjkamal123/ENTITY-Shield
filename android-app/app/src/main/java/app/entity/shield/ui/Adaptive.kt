// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One layout for every Android screen. Width classes follow Android's window size classes:
 *  compact  < 360 dp   small phones: tighter gutters, stacked hero and tiles
 *  medium   360-599 dp most phones
 *  expanded >= 600 dp  tablets, foldables open, landscape: content column capped at 640 dp and
 *                      centred (no stretched lines), wider gutters
 * Text follows the user's font size everywhere except the compact chrome (nav labels), which is capped
 * at 1.15x so a 2x font setting cannot push the tab bar out of shape.
 */
@Immutable
data class Adaptive(val widthDp: Int, val gutter: Dp, val maxContent: Dp, val compact: Boolean, val expanded: Boolean) {
    /** Side padding for full-width scrolling lists, so the content column is centred on wide screens. */
    val listSide: Dp get() = maxOf(gutter, ((widthDp.dp - maxContent) / 2) + gutter / 2)
}

val LocalAdaptive = staticCompositionLocalOf { Adaptive(400, 20.dp, 640.dp, compact = false, expanded = false) }

@Composable
@ReadOnlyComposable
fun computeAdaptive(): Adaptive {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w < 360 -> Adaptive(w, 14.dp, 640.dp, compact = true, expanded = false)
        w < 600 -> Adaptive(w, 20.dp, 640.dp, compact = false, expanded = false)
        else -> Adaptive(w, 28.dp, 640.dp, compact = false, expanded = true)
    }
}

/** Keeps content clear of a camera cut-out on the left or right (landscape). */
fun Modifier.sideCutoutPadding(): Modifier = this.composed { windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)) }

/** Caps the font scale for compact UI chrome (tab bar) while leaving body text fully scalable. */
@Composable
fun CappedFontScale(max: Float = 1.15f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(max)), content = content)
}
