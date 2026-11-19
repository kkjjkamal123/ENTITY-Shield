package app.entity.shield.util

import app.entity.shield.core.Advice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Read-aloud for people who struggle with small text. Uses the phone's own (offline) TTS voices. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { ready = it == TextToSpeech.SUCCESS }
    }

    /** false if no voice is available for that language on this phone. */
    fun say(text: String, lang: Advice.Lang): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val loc = if (lang == Advice.Lang.HI) Locale("hi", "IN") else Locale("en", "IN")
        val ok = t.setLanguage(loc)
        if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (lang == Advice.Lang.HI) return false
            t.setLanguage(Locale.ENGLISH)
        }
        t.setSpeechRate(0.92f)
        t.speak(text.replace(Regex("[⚠️✅]"), ""), TextToSpeech.QUEUE_FLUSH, null, "shield")
        return true
    }

    fun stop() { tts?.stop() }
}
