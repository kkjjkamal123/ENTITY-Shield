package app.entity.shield.util

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import app.entity.shield.net.ShieldVpnService

/**
 * Keeping Shield on when the app is closed. SMS checks need no running app: Android starts
 * [app.entity.shield.sms.SmsReceiver] for every message. What can stop protection:
 *  - battery optimisation and vendor app freezers (Transsion "Usf_Hiber", Xiaomi, Oppo, Vivo...)
 *    that freeze or kill a closed app, so the SMS broadcast or the VPN is never delivered
 *  - a reboot or an app update, after which the VPN is off until something starts it
 *  - "Force stop", which Android lets nothing undo until the user opens the app again
 * Shield restarts Network Guard itself after a reboot or update, and the More screen walks the user
 * through the three system switches it cannot flip itself (battery, auto-start, always-on VPN).
 */
object KeepAlive {
    private const val TAG = "ShieldKeep"
    private const val WANTED = "net_wanted"

    /** The user's choice for Network Guard, so it can be restored after a reboot or update. */
    fun setNetWanted(ctx: Context, on: Boolean) = ctx.getSharedPreferences("shield", 0).edit().putBoolean(WANTED, on).apply()
    fun netWanted(ctx: Context) = ctx.getSharedPreferences("shield", 0).getBoolean(WANTED, false)

    fun batteryExempt(ctx: Context) = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Asks Android to stop battery-optimising Shield (one system dialog). */
    @SuppressLint("BatteryLife") // a security app that must react to every SMS and DNS lookup
    fun requestBatteryExempt(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        if (!launch(ctx, direct)) launch(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** The system VPN screen, where the user can turn on "Always-on VPN" for Shield. */
    fun openVpnSettings(ctx: Context) {
        if (!launch(ctx, Intent(Settings.ACTION_VPN_SETTINGS))) launch(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    /** Vendor auto-start / background managers. Falls back to the app's own settings page. */
    private val AUTOSTART = listOf(
        ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),          // Infinix, Tecno, itel
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"), // Xiaomi, Redmi, Poco
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"), // Oppo, Realme
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"), // Vivo
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
    )

    fun hasAutostartScreen(ctx: Context) = AUTOSTART.any { resolves(ctx, Intent().setComponent(it)) }

    fun openAutostart(ctx: Context) {
        if (AUTOSTART.any { launch(ctx, Intent().setComponent(it)) }) return
        launch(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    /** Restarts Network Guard if the user had it on and VPN consent still stands. */
    fun restoreNet(ctx: Context, why: String) {
        if (!netWanted(ctx)) return
        if (VpnService.prepare(ctx) != null) { Log.i(TAG, "$why: Network Guard wanted but VPN consent is gone"); return }
        runCatching { ShieldVpnService.start(ctx) }
            .onSuccess { Log.i(TAG, "$why: Network Guard restarted") }
            .onFailure { Log.w(TAG, "$why: could not restart Network Guard: ${it.message}") }
    }

    private fun resolves(ctx: Context, i: Intent) = runCatching { i.resolveActivity(ctx.packageManager) != null }.getOrDefault(false)

    private fun launch(ctx: Context, i: Intent): Boolean = runCatching {
        if (!resolves(ctx, i)) return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}

/** After a reboot or an app update: bring Network Guard back, and load the detectors before the first SMS. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val why = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON" -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }
        val pending = goAsync()
        Thread {
            try {
                KeepAlive.restoreNet(ctx.applicationContext, why)
                runCatching { app.entity.shield.core.Detectors.get(ctx.applicationContext) }
            } finally { pending.finish() }
        }.start()
    }
}
