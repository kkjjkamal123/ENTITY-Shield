// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui.theme

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The app's own line icons: 24 pt grid, 1.7 pt round strokes. Drawn for Shield, no emoji anywhere. */
object Ico {
    private fun line(vararg d: String, w: Float = 1.7f) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            d.forEach {
                addPath(PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = w, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    private const val SHIELD = "M12 3 4.75 5.9v5.6c0 4.55 3.05 8.2 7.25 9.5 4.2-1.3 7.25-4.95 7.25-9.5V5.9z"
    private const val CIRCLE = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"

    val Home by lazy { line("M3.75 10.4 12 3.75l8.25 6.65v8.35a1.5 1.5 0 0 1-1.5 1.5H15v-5.75a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v5.75H5.25a1.5 1.5 0 0 1-1.5-1.5z") }
    val Bell by lazy { line("M6.25 9.5a5.75 5.75 0 0 1 11.5 0c0 5.6 2.25 7.25 2.25 7.25H4s2.25-1.65 2.25-7.25", "M10 20.25a2.3 2.3 0 0 0 4 0") }
    val Globe by lazy { line(CIRCLE, "M3.5 12h17", "M12 3c2.4 2.5 3.7 5.6 3.7 9s-1.3 6.5-3.7 9c-2.4-2.5-3.7-5.6-3.7-9S9.6 5.5 12 3z") }
    val Scan by lazy { line("M4 8.5V6.5A2.5 2.5 0 0 1 6.5 4h2", "M15.5 4h2A2.5 2.5 0 0 1 20 6.5v2", "M20 15.5v2a2.5 2.5 0 0 1-2.5 2.5h-2", "M8.5 20h-2A2.5 2.5 0 0 1 4 17.5v-2", "M7.5 12h9") }
    val Grid by lazy {
        line("M4.5 6A1.5 1.5 0 0 1 6 4.5h3A1.5 1.5 0 0 1 10.5 6v3A1.5 1.5 0 0 1 9 10.5H6A1.5 1.5 0 0 1 4.5 9z",
            "M13.5 6A1.5 1.5 0 0 1 15 4.5h3A1.5 1.5 0 0 1 19.5 6v3a1.5 1.5 0 0 1-1.5 1.5h-3A1.5 1.5 0 0 1 13.5 9z",
            "M4.5 15A1.5 1.5 0 0 1 6 13.5h3a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 9 19.5H6A1.5 1.5 0 0 1 4.5 18z",
            "M13.5 15a1.5 1.5 0 0 1 1.5-1.5h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-3a1.5 1.5 0 0 1-1.5-1.5z")
    }
    val Shield by lazy { line(SHIELD) }
    val ShieldCheck by lazy { line(SHIELD, "m8.75 12.1 2.3 2.3 4.2-4.5") }
    val ShieldAlert by lazy { line(SHIELD, "M12 8.25v4.25", "M12 15.9v.1") }
    val Message by lazy { line("M4 6.75A2.75 2.75 0 0 1 6.75 4h10.5A2.75 2.75 0 0 1 20 6.75v6.5A2.75 2.75 0 0 1 17.25 16H9l-5 4z") }
    val Link by lazy { line("M10 14a4.25 4.25 0 0 0 6 0l2.75-2.75a4.25 4.25 0 0 0-6-6L11.5 6.5", "M14 10a4.25 4.25 0 0 0-6 0l-2.75 2.75a4.25 4.25 0 0 0 6 6l1.25-1.25") }
    val Wifi by lazy { line("M2.75 9.25a13.5 13.5 0 0 1 18.5 0", "M5.75 12.6a9 9 0 0 1 12.5 0", "M8.85 15.9a4.6 4.6 0 0 1 6.3 0", "M12 19.25v.1") }
    val Radar by lazy { line(CIRCLE, "M12 7.5a4.5 4.5 0 1 0 4.5 4.5", "M12 12l5.5-5.5") }
    val Chip by lazy {
        line("M8.5 6h7A2.5 2.5 0 0 1 18 8.5v7a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 6 15.5v-7A2.5 2.5 0 0 1 8.5 6z",
            "M10 3v3", "M14 3v3", "M10 18v3", "M14 18v3", "M3 10h3", "M3 14h3", "M18 10h3", "M18 14h3", "M10 10h4v4h-4z")
    }
    val Alert by lazy { line("M10.3 4.3 2.9 17.3A2 2 0 0 0 4.6 20.3h14.8a2 2 0 0 0 1.7-3L13.7 4.3a2 2 0 0 0-3.4 0z", "M12 9.5v4", "M12 16.75v.1") }
    val Check by lazy { line("m5 12.5 4.5 4.5L19 7.5", w = 2f) }
    val Close by lazy { line("M6.5 6.5l11 11", "M17.5 6.5l-11 11", w = 1.9f) }
    val Back by lazy { line("M19 12H5.5", "M11 6.5 5.5 12l5.5 5.5", w = 1.9f) }
    val Chevron by lazy { line("m9.5 6 6 6-6 6", w = 1.9f) }
    val ArrowUpRight by lazy { line("M7 17 17 7", "M8.5 7H17v8.5") }
    val Phone by lazy { line("M5.2 4h3.1l1.6 4.2-2.1 1.4a11.5 11.5 0 0 0 6.6 6.6l1.4-2.1 4.2 1.6v3.1a1.6 1.6 0 0 1-1.7 1.6A16.3 16.3 0 0 1 3.6 5.7 1.6 1.6 0 0 1 5.2 4z") }
    val Flag by lazy { line("M5.5 21V4", "M5.5 4.5h11.25l-2.25 4.25 2.25 4.25H5.5") }
    val Copy by lazy { line("M10 8.5h8A1.5 1.5 0 0 1 19.5 10v8a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 8.5 18v-8A1.5 1.5 0 0 1 10 8.5z", "M15.5 8.5V6A1.5 1.5 0 0 0 14 4.5H6A1.5 1.5 0 0 0 4.5 6v8A1.5 1.5 0 0 0 6 15.5h2.5") }
    val Speaker by lazy { line("M4.5 9.5h3l4.5-4v13l-4.5-4h-3z", "M15.5 9.25a3.8 3.8 0 0 1 0 5.5", "M18.25 6.75a7.4 7.4 0 0 1 0 10.5") }
    val Stop by lazy { line("M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z") }
    val Ban by lazy { line(CIRCLE, "M5.7 5.7l12.6 12.6") }
    val Trash by lazy { line("M4.5 7h15", "M9.5 7V4.75h5V7", "M6.5 7l.9 12.25a1.5 1.5 0 0 0 1.5 1.25h6.2a1.5 1.5 0 0 0 1.5-1.25L17.5 7") }
    val Play by lazy { line("M8 5.75v12.5a.75.75 0 0 0 1.15.63l9.6-6.25a.75.75 0 0 0 0-1.26l-9.6-6.25A.75.75 0 0 0 8 5.75z") }
    val Inbox by lazy { line("M4 13.5 6.4 5.5h11.2l2.4 8V18a1.5 1.5 0 0 1-1.5 1.5H5.5A1.5 1.5 0 0 1 4 18z", "M4 13.5h4.5l1.5 2h4l1.5-2H20") }
    val Gauge by lazy { line("M4.6 17.5a8.5 8.5 0 1 1 14.8 0", "M12 13.25l3.75-4.25", "M12 13.25v.1") }
    val Info by lazy { line(CIRCLE, "M12 11v5.25", "M12 7.75v.1") }
    val Lock by lazy { line("M6.5 10.75h11A1.5 1.5 0 0 1 19 12.25v6.25A1.5 1.5 0 0 1 17.5 20h-11A1.5 1.5 0 0 1 5 18.5v-6.25a1.5 1.5 0 0 1 1.5-1.5z", "M8.5 10.75V8a3.5 3.5 0 0 1 7 0v2.75") }
    val Box by lazy { line("M12 3.25 19.75 7.6v8.8L12 20.75 4.25 16.4V7.6z", "M4.5 7.75 12 12l7.5-4.25", "M12 12v8.5") }
    val Eye by lazy { line("M2.75 12S6.25 5.75 12 5.75 21.25 12 21.25 12 17.75 18.25 12 18.25 2.75 12 2.75 12z", "M12 9.25a2.75 2.75 0 1 0 0 5.5a2.75 2.75 0 1 0 0-5.5z") }
    val Sliders by lazy { line("M4 7.5h9", "M18.5 7.5H20", "M4 16.5h3", "M12.5 16.5H20", "M15.75 5.25a2.25 2.25 0 1 0 0 4.5a2.25 2.25 0 1 0 0-4.5z", "M9.75 14.25a2.25 2.25 0 1 0 0 4.5a2.25 2.25 0 1 0 0-4.5z") }
    val Download by lazy { line("M12 4v11", "m7.25 10.5 4.75 4.75 4.75-4.75", "M5 20h14") }
    val Route by lazy { line("M6 17.25a2 2 0 1 0 0 .01", "M18 6.75a2 2 0 1 0 0 .01", "M8.25 17.25h7.5a3.25 3.25 0 0 0 0-6.5h-7.5a3.25 3.25 0 0 1 0-6.5h7.5") }
    val Key by lazy { line("M14.5 4a5.5 5.5 0 1 1-4.9 8l-5.6 5.6V20h2.5v-2h2v-2h2l1-1A5.5 5.5 0 0 1 14.5 4z", "M15.75 8.25v.1") }
    val Refresh by lazy { line("M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v4h-4") }
    val Sparkline by lazy { line("M3.5 16.5l4.5-5 3.5 3 4.5-6.5 4.5 4") }
    val Language by lazy { line("M4 5.5h8", "M8 4v1.5c0 3.9-1.7 6.6-4 8", "M6 9.5c1 1.8 2.6 3.2 4.5 4", "M12.5 20l3.75-9 3.75 9", "M13.75 17h5") }
}

/** Icon at a size, tinted. */
@Composable
fun Glyph(v: ImageVector, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier, description: String? = null) =
    Icon(v, description, modifier.size(size), tint = tint)
