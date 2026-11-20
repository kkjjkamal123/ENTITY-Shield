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
 * Keeps Shield's process alive while the 1.1 GB model downloads, so switching apps or turning the
 * screen off does not kill it halfway. It does no work itself: ModelDownload's thread does the
 * download, this only holds a progress notification in the foreground.
 */
class ModelDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ModelDownload.busy && intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        val n = notification(this, ModelDownload.progress)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (_: Exception) {
            // Not allowed to go foreground (e.g. started from the background): the download still runs
            // while the app is open.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {   // Android 15 data-sync time limit
        ModelDownload.stop(); stopSelf()
    }

    companion object {
        private const val CHANNEL = "model_download"
        private const val ID = 4207
        private const val ACTION_START = "start"

        private fun notification(ctx: Context, frac: Float): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Llama download", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading Llama 3.2 1B")
                .setContentText(ModelDownload.status)
                .setProgress(1000, (frac * 1000).toInt(), frac <= 0f)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, ModelDownloadService::class.java).setAction(ACTION_START)) }
        }

        private var lastPct = -1
        fun update(ctx: Context, frac: Float) {
            val pct = (frac * 100).toInt()
            if (pct == lastPct) return
            lastPct = pct
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, notification(ctx, frac)) }
        }

        fun stop(ctx: Context) {
            lastPct = -1
            runCatching { ctx.stopService(Intent(ctx, ModelDownloadService::class.java)) }
        }
    }
}
