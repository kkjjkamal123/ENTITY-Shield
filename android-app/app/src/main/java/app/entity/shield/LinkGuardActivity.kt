package app.entity.shield

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.entity.shield.core.Band
import app.entity.shield.core.Detectors
import app.entity.shield.core.Featurizer
import app.entity.shield.core.Pipeline
import app.entity.shield.core.Prefs
import app.entity.shield.sandbox.LinkInspector
import app.entity.shield.ui.LocalLang
import app.entity.shield.ui.components.SecondaryButton
import app.entity.shield.ui.screens.SandboxSteps
import app.entity.shield.ui.theme.Sh
import app.entity.shield.ui.theme.ShieldTheme
import app.entity.shield.ui.tr
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.concurrent.thread

/**
 * Link Guard. When a link is opened with ENTITY Shield (from SMS, WhatsApp, anywhere):
 *  - an official or top-10k site goes straight on to the real browser (address check only, no visit);
 *  - anything else is opened in the sandbox first (LinkInspector), with the steps shown in a sheet over
 *    the app the user came from. Safe pages continue to the browser; risky ones open the alert with the
 *    sandbox report, the explanation and an "Open anyway" choice.
 * The user was about to open the link anyway, so the sandbox visit tells the site nothing new.
 */
class LinkGuardActivity : ComponentActivity() {
    private val steps = mutableStateOf<List<LinkInspector.Step>>(emptyList())
    @Volatile private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val link = uri.toString()
        val visit = getSharedPreferences("shield", 0).getBoolean("sandbox_visit", true)
        val host = Featurizer.urlHost(link)
        setContent {
            ShieldTheme { CompositionLocalProvider(LocalLang provides Prefs.lang(this)) { Sheet(link) } }
        }
        thread(name = "link-guard") {
            val trusted = Featurizer.allowlisted(host, Detectors.get(this).allow)
            val a = if (trusted || !visit) Pipeline.onUrl(this, link, "link", t0)
            else LinkInspector.inspect(this, link, "link") { s -> runOnUiThread { steps.value = s } }
            if (cancelled) return@thread
            runOnUiThread {
                if (a.v.band == Band.SAFE) openInBrowser(this, uri)
                else startActivity(Intent(this, MainActivity::class.java).putExtra("alert", a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Sheet(link: String) {
        val c = Sh.c
        Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.bg)
                    .navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.surface3))
                Text(tr("Checking this link first", "पहले यह लिंक जांच रहे हैं"), style = Sh.t.title2, color = c.text)
                Text(link, style = Sh.t.mono, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (steps.value.isNotEmpty()) SandboxSteps(steps.value)
                SecondaryButton(tr("Cancel", "रद्द करें"), Modifier.fillMaxWidth()) { cancelled = true; finish() }
            }
        }
    }

    companion object {
        /** Open [uri] in a real browser, never in Shield itself (it may be the default handler). */
        fun openInBrowser(ctx: Context, uri: Uri) {
            val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pm = ctx.packageManager
            val handlers = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_ALL)
                .map { it.activityInfo }.filter { it.packageName != ctx.packageName }
            val preferred = pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.takeIf { it.packageName != ctx.packageName && it.packageName != "android" }
            val target = preferred ?: handlers.firstOrNull { it.packageName.contains("chrome") } ?: handlers.firstOrNull()
            if (target != null) view.component = ComponentName(target.packageName, target.name)
            runCatching { ctx.startActivity(view) }
        }
    }
}
