package app.entity.shield

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Link Guard. When a link is opened with ENTITY Shield (from SMS, WhatsApp, anywhere), it is checked on
 * the phone first. Safe links go straight on to the real browser; risky ones open the alert screen with
 * the verdict, the explanation and an "Open anyway" choice. No network request is made to check it.
 */
class LinkGuardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        val t0 = SystemClock.elapsedRealtimeNanos()
        thread {
            val a = Pipeline.onUrl(this, uri.toString(), "link", t0)
            runOnUiThread {
                if (a.v.band == Band.SAFE) openInBrowser(this, uri)
                else startActivity(Intent(this, MainActivity::class.java).putExtra("alert", a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
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
