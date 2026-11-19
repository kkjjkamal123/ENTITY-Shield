package app.entity.shield.sms

import app.entity.shield.core.Pipeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Telephony

/** Every incoming SMS goes through the detectors. A multi-part SMS arrives as several PDUs: join them per sender. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pending = goAsync()
        Thread {
            try {
                parts.groupBy { it.displayOriginatingAddress ?: "" }.forEach { (sender, msgs) ->
                    Pipeline.onSms(ctx, sender, msgs.joinToString("") { it.displayMessageBody ?: "" }, "sms", t0)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
