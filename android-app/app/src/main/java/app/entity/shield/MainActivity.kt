// ENTITY Shield UI. Proprietary: (c) 2026 Team ENTITY. All rights reserved. See LICENSE.
package app.entity.shield

import app.entity.shield.core.Advice
import app.entity.shield.core.Alert
import app.entity.shield.core.Band
import app.entity.shield.core.Detectors
import app.entity.shield.bench.DeviceInfo
import app.entity.shield.core.Pipeline
import app.entity.shield.core.Prefs
import app.entity.shield.net.ShieldVpnService
import app.entity.shield.util.Speaker
import app.entity.shield.llm.ModelImport

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import app.entity.shield.ui.ShieldApp
import app.entity.shield.ui.theme.ShieldTheme
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<String?>(null)
    private val openAlert = mutableStateOf<Long?>(null)
    private val tabReq = mutableStateOf<Int?>(null)

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) ShieldVpnService.start(this)
    }

    /** Import the Llama files (llama*.pte + tokenizer.model) picked in the system file picker. */
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ModelImport.start(this, uris)
    }

    fun importModel() = modelPicker.launch(arrayOf("*/*"))

    /** Turn Network Guard on (asking for the one-time VPN consent if needed) or off. */
    fun setNetworkGuard(on: Boolean) {
        if (!on) { ShieldVpnService.stop(this); return }
        val consent = ShieldVpnService.consentIntent(this)
        if (consent != null) vpnConsent.launch(consent) else ShieldVpnService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Debug and QA builds only (R.bool.e2e_hooks): scripts/e2e_phone.py can show the app above the lock screen (like an alarm app),
        // so tests run with the app in the foreground on a locked phone. Release builds never do this.
        if (resources.getBoolean(R.bool.e2e_hooks) && intent?.getBooleanExtra("e2e", false) == true) { setShowWhenLocked(true); setTurnScreenOn(true) }
        Pipeline.ensureChannel(this)
        Pipeline.load(this)
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.POST_NOTIFICATIONS), 1)
        // Off the main thread: detectors (so the first SMS doesn't pay the load) and the device probe.
        thread(name = "warm") { Detectors.get(this); DeviceInfo.get(this); app.entity.shield.llm.Explainer.get(this).available() }
        window.decorView.post { Speaker.init(this) } // text-to-speech binds a service: after the first frame
        handle(intent)
        setContent { ShieldTheme { ShieldApp(shared, openAlert, tabReq) } }
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

