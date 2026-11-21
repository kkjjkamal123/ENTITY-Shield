package app.entity.shield.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.entity.shield.MainActivity

/**
 * Holds Shield in the foreground while Llama writes an explanation. Without it, leaving the app
 * mid-answer lets vendor app freezers stop the process: on the Infinix X6878 (Transsion "Usf_Hiber")
 * Shield was frozen 4 s into an answer and resumed 12.6 min later (decode 0.035 tok/s). Uses the
 * short-service type (Android 14+): no extra permission, and the system ends it after ~3 minutes,
 * far longer than an answer takes.
 */
class LlmRunService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() must always follow startForegroundService(), even if the answer already
        // ended: stopping first crashes the app on some Android versions. So go foreground, then stop
        // right away if nothing is running any more.
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(ID, notification(this))
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY   // not allowed from the background: the answer still runs while the app is open
        }
        synchronized(LOCK) { started = true; if (!wanted) { started = false; stopSelf() } }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) { synchronized(LOCK) { started = false }; stopSelf() }

    companion object {
        private const val CHANNEL = "llm_run"
        private const val ID = 4208

        private fun notification(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "On-device AI answer", NotificationManager.IMPORTANCE_MIN))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Writing the explanation on this phone")
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }

        private val LOCK = Any()
        @Volatile private var wanted = false    // an answer is running
        @Volatile private var started = false   // the service reached startForeground()

        fun start(ctx: Context) = synchronized(LOCK) {
            wanted = true
            if (!started) runCatching { ctx.startForegroundService(Intent(ctx, LlmRunService::class.java)) }
        }

        /** Stops it if it is up; if it is still starting, onStartCommand stops it once it is foreground. */
        fun stop(ctx: Context) = synchronized(LOCK) {
            wanted = false
            if (started) { started = false; runCatching { ctx.stopService(Intent(ctx, LlmRunService::class.java)) } }
        }
    }
}
