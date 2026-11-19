// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.entity.shield.core.Advice
import app.entity.shield.core.Band
import app.entity.shield.core.Pipeline
import app.entity.shield.core.Prefs
import app.entity.shield.ui.glass.GlassNavBar
import app.entity.shield.ui.glass.GlassQuality
import app.entity.shield.ui.glass.glassQuality
import app.entity.shield.ui.glass.NavItem
import app.entity.shield.ui.glass.backdropSource
import app.entity.shield.ui.glass.rememberBackdrop
import app.entity.shield.ui.screens.AlertDetailScreen
import app.entity.shield.ui.screens.AlertsScreen
import app.entity.shield.ui.screens.HomeScreen
import app.entity.shield.ui.screens.MoreScreen
import app.entity.shield.ui.screens.NetworkScreen
import app.entity.shield.ui.screens.ScanScreen
import app.entity.shield.ui.screens.SubScreen
import app.entity.shield.ui.theme.Dim
import app.entity.shield.ui.theme.Ico
import app.entity.shield.ui.theme.Sh

/** Screens opened on top of the tabs. */
sealed interface Route {
    data class AlertR(val id: Long) : Route
    data object Demo : Route
    data object Inbox : Route
    data object Bench : Route
    data object About : Route
    data object Allowed : Route
}

val LocalLang = staticCompositionLocalOf { Advice.Lang.EN }

/** Pick the English or Hindi string for the current language. */
@Composable
@ReadOnlyComposable
fun tr(en: String, hi: String) = if (LocalLang.current == Advice.Lang.HI) hi else en

/** Navigation for every screen: switch tab, open a route, go back, toggle language. */
class Nav(val tab: (Int) -> Unit, val open: (Route) -> Unit, val back: () -> Unit, val toggleLang: () -> Unit)
val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

const val TAB_HOME = 0
const val TAB_ALERTS = 1
const val TAB_NETWORK = 2
const val TAB_SCAN = 3
const val TAB_MORE = 4

/** true while this tab is the one on screen: hidden tabs pause polling and endless animations. */
val LocalTabActive = staticCompositionLocalOf { true }

@Composable
fun ShieldApp(shared: MutableState<String?>, openAlert: MutableState<Long?>, tabReq: MutableState<Int?>) {
    val ctx = LocalContext.current
    val c = Sh.c
    val backdrop = rememberBackdrop()
    val quality = remember { glassQuality(ctx) }
    val stack = remember { mutableStateListOf<Route>() }
    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var lang by remember { mutableStateOf(Prefs.lang(ctx)) }
    var tabsHidden by remember { mutableStateOf(false) }

    val goTab: (Int) -> Unit = { i -> stack.clear(); tab = i }
    val nav = remember {
        Nav(goTab, { r -> stack.add(r) }, { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
            { lang = if (lang == Advice.Lang.EN) Advice.Lang.HI else Advice.Lang.EN; Prefs.setLang(ctx, lang) })
    }
    LaunchedEffect(openAlert.value) { openAlert.value?.let { stack.removeAll { r -> r is Route.AlertR }; stack.add(Route.AlertR(it)); openAlert.value = null } }
    LaunchedEffect(shared.value) { if (shared.value != null) { stack.clear(); tab = TAB_SCAN } }
    LaunchedEffect(tabReq.value) { tabReq.value?.let { stack.clear(); tab = it; tabReq.value = null } }
    // once a screen fully covers the tabs, stop drawing them (no overdraw under the detail screen)
    LaunchedEffect(stack.isEmpty()) { if (stack.isEmpty()) tabsHidden = false else { delay(380); tabsHidden = stack.isNotEmpty() } }
    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }
    BackHandler(enabled = stack.isEmpty() && tab != TAB_HOME) { goTab(TAB_HOME) }

    val threat by remember { derivedStateOf { Pipeline.alerts.any { it.v.band == Band.SCAM && System.currentTimeMillis() - it.at < 30 * 60_000 } } }
    val unseen by remember { derivedStateOf { Pipeline.alerts.count { it.v.band == Band.SCAM } } }

    CompositionLocalProvider(LocalLang provides lang, LocalNav provides nav, LocalAdaptive provides computeAdaptive()) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            // the page background is drawn inside the source, so the glass bends an opaque, blurred copy
            Box(Modifier.fillMaxSize().backdropSource(backdrop, quality != GlassQuality.Frosted).background(c.bg)) {
                if (!tabsHidden) {
                    Ambient(if (threat) c.danger else c.accent)
                    TabHost(tab) { page ->
                        when (page) {
                            TAB_HOME -> HomeScreen(shared)
                            TAB_ALERTS -> AlertsScreen()
                            TAB_NETWORK -> NetworkScreen()
                            TAB_SCAN -> ScanScreen(shared)
                            else -> MoreScreen()
                        }
                    }
                }
            }
            // status bar fade: content scrolls away under it instead of colliding with the clock
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Brush.verticalGradient(listOf(c.bg, c.bg.copy(alpha = 0.85f)))))
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(18.dp).background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.85f), Color.Transparent))))
            if (!tabsHidden) CappedFontScale { GlassNavBar(
                backdrop,
                listOf(
                    NavItem(tr("Home", "होम"), Ico.Home), NavItem(tr("Alerts", "अलर्ट"), Ico.Bell, unseen),
                    NavItem(tr("Network", "नेटवर्क"), Ico.Globe), NavItem(tr("Scan", "जांचें"), Ico.Scan), NavItem(tr("More", "और"), Ico.Grid),
                ),
                selected = tab,
                onSelect = goTab,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().sideCutoutPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 520.dp),
                glass = quality,
            ) }
            AnimatedContent(
                targetState = stack.lastOrNull(),
                transitionSpec = {
                    val forward = targetState != null && (initialState == null || stack.size > 1)
                    if (forward) (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(200))) togetherWith fadeOut(tween(150))
                    else fadeIn(tween(150)) togetherWith (slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(200)))
                },
                label = "route",
            ) { r ->
                when (r) {
                    null -> Spacer(Modifier.size(0.dp))
                    is Route.AlertR -> Pipeline.alerts.firstOrNull { it.id == r.id }?.let { AlertDetailScreen(it) } ?: LaunchedEffect(r) { nav.back() }
                    else -> SubScreen(r)
                }
            }
        }
    }
}

/**
 * Holds the five tabs. Each tab is composed once (the rest are warmed up a few frames apart after
 * launch) and then kept, so switching never builds a screen mid-animation. A switch animates only the
 * two tabs involved: a 240 ms cross-fade with a 5% slide, drawn with ModulateAlpha so no offscreen
 * buffer is allocated. Tabs that are not involved are not placed at all: no drawing, no touches.
 */
@Composable
private fun TabHost(selected: Int, content: @Composable (Int) -> Unit) {
    val visited = remember { mutableStateListOf(selected) }
    val prog = remember { Animatable(1f) }
    var from by remember { mutableIntStateOf(selected) }
    var to by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected !in visited) visited.add(selected)
        if (selected != to) {
            from = to; to = selected
            prog.snapTo(0f)
            prog.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(Unit) {
        delay(900)
        for (i in 0 until 5) if (i !in visited) { withFrameNanos { }; visited.add(i); delay(160) }
    }
    Box(Modifier.fillMaxSize()) {
        for (i in visited.toList().sorted()) key(i) {
            Box(Modifier.fillMaxSize().layout { m, cst ->
                val pl = m.measure(cst)
                layout(pl.width, pl.height) {
                    val p = prog.value
                    val inbound = i == to
                    if (inbound || (i == from && p < 1f)) pl.placeWithLayer(0, 0) {
                        val dir = if (to > from) 1f else -1f
                        val a = if (inbound) p else 1f - p
                        alpha = a
                        translationX = (if (inbound) 1f - p else -p) * dir * 0.05f * pl.width
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                }
            }) {
                CompositionLocalProvider(LocalTabActive provides (i == selected)) { content(i) }
            }
        }
    }
}

/** A soft wash of colour at the top that follows the protection state. It gives the glass something to bend. */
@Composable
private fun Ambient(color: Color) {
    val c = Sh.c
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(Brush.radialGradient(listOf(color.copy(alpha = if (c.dark) 0.13f else 0.22f), Color.Transparent),
            center = Offset(size.width * 0.5f, -size.width * 0.1f), radius = size.width * 1.05f))
    })
}

/** Standard tab page: scrolls under the status bar and the floating glass nav; centred column on wide screens. */
@Composable
fun TabPage(content: @Composable ColumnScope.() -> Unit) {
    val a = LocalAdaptive.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().sideCutoutPadding()
            .padding(top = Dim.s, bottom = navClearance()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = a.maxContent).fillMaxWidth().padding(horizontal = a.gutter), verticalArrangement = Arrangement.spacedBy(Dim.m), content = content)
    }
}

@Composable
fun navClearance(): Dp = 68.dp + 34.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
