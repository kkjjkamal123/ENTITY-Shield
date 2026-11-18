// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<String?>(null)
    private val openAlert = mutableStateOf<Long?>(null)
    private val tabReq = mutableStateOf<Int?>(null)

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) ShieldVpnService.start(this)
    }

    /** Turn Network Guard on (asking for the one-time VPN consent if needed) or off. */
    fun setNetworkGuard(on: Boolean) {
        if (!on) { ShieldVpnService.stop(this); return }
        val consent = ShieldVpnService.consentIntent(this)
        if (consent != null) vpnConsent.launch(consent) else ShieldVpnService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Pipeline.ensureChannel(this)
        Pipeline.load(this)
        Speaker.init(this)
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.POST_NOTIFICATIONS), 1)
        thread { Detectors.get(this); DeviceInfo.get(this) } // warm the detectors so the first SMS doesn't pay the load
        handle(intent)
        setContent { ShieldTheme { App(shared, openAlert, tabReq) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> shared.value = i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> shared.value = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }
        i?.getLongExtra("alert", 0L)?.takeIf { it != 0L }?.let { openAlert.value = it }
        i?.getIntExtra("tab", -1)?.takeIf { it >= 0 }?.let { tabReq.value = it }
    }
}

private val TABS = listOf("Home" to "◉", "Alerts" to "⚠", "Network" to "⌁", "Check" to "⌕", "Lab" to "⚗")

@Composable
private fun App(shared: MutableState<String?>, openAlert: MutableState<Long?>, tabReq: MutableState<Int?>) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var lang by remember { mutableStateOf(Prefs.lang(ctx)) }
    val selected = openAlert.value?.let { id -> Pipeline.alerts.firstOrNull { it.id == id } }
    LaunchedEffect(shared.value) { if (shared.value != null) tab = 3 }
    LaunchedEffect(tabReq.value) { tabReq.value?.let { tab = it; tabReq.value = null } }
    val open: (Alert) -> Unit = { openAlert.value = it.id }

    Box(Modifier.fillMaxSize().background(Sh.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(lang, back = if (selected != null) ({ openAlert.value = null }) else null) {
                lang = if (lang == Advice.Lang.EN) Advice.Lang.HI else Advice.Lang.EN
                Prefs.setLang(ctx, lang)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(targetState = selected?.id ?: -(tab + 1).toLong(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { key ->
                    when {
                        key >= 0 && selected != null -> AlertDetail(selected, lang)
                        tab == 0 -> HomeScreen(lang, onOpen = open, go = { tab = it })
                        tab == 1 -> AlertsScreen(onOpen = open)
                        tab == 2 -> NetworkScreen(onOpen = open)
                        tab == 3 -> CheckScreen(shared, onOpen = open)
                        else -> LabScreen(onOpen = open)
                    }
                }
            }
            if (selected == null) BottomNav(tab) { tab = it }
        }
    }
}

@Composable
private fun TopBar(lang: Advice.Lang, back: (() -> Unit)?, onLang: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Sh.Glass).clickable { back() }, contentAlignment = Alignment.Center) {
                Text("←", color = Sh.Text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ENTITY", color = Sh.Text, fontWeight = Sh.Black, fontSize = 20.sp, letterSpacing = 2.sp)
                Spacer(Modifier.width(6.dp))
                Text("SHIELD", fontWeight = Sh.Black, fontSize = 20.sp, letterSpacing = 2.sp,
                    style = androidx.compose.ui.text.TextStyle(brush = Sh.accentH))
            }
            Text("On-device AI · Offline · Arm", color = Sh.Text3, fontSize = 11.sp, letterSpacing = 0.6.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, Sh.Stroke, RoundedCornerShape(50)).clickable { onLang() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(if (lang == Advice.Lang.EN) "EN  ·  हिं" else "हिं  ·  EN", color = Sh.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    val alerts = Pipeline.alerts.count { it.v.band == Band.SCAM }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp)).background(Color(0xE60B1124)).border(1.dp, Sh.Stroke, RoundedCornerShape(22.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TABS.forEachIndexed { i, (label, glyph) ->
            val sel = i == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (sel) Brush.linearGradient(listOf(Sh.Cyan.copy(alpha = 0.25f), Sh.Violet.copy(alpha = 0.25f))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onTab(i) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Text(glyph, color = if (sel) Sh.Cyan else Sh.Text2, fontSize = 18.sp)
                    if (i == 1 && alerts > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-2).dp).size(8.dp).background(Sh.Red, RoundedCornerShape(4.dp)))
                }
                Text(label, color = if (sel) Sh.Text else Sh.Text3, fontSize = 11.sp, fontWeight = if (sel) Sh.Bold else FontWeight.Normal)
            }
        }
    }
}
