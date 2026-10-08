package com.example.comucane

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Lightweight TTS wrapper used by Device, Logs, Settings, and Main activities
 * to read screen content aloud. NavigationActivity manages its own TTS instance.
 *
 * Usage:
 *   1. Create in onCreate: voiceHelper = ScreenVoiceHelper(this)
 *   2. Call speak() anywhere
 *   3. Call shutdown() in onDestroy
 */
class ScreenVoiceHelper(
    private val context: Context,
    private var useTagalog: Boolean = false
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var ready = false
    private var pendingMessage: String? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = if (useTagalog) Locale("fil", "PH") else Locale.US
            ready = true
            pendingMessage?.let { speak(it) }
            pendingMessage = null
        }
    }

    fun speak(message: String) {
        if (ready) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "SCREEN_VOICE")
        } else {
            pendingMessage = message
        }
    }

    fun setTagalog(tagalog: Boolean) {
        useTagalog = tagalog
        if (ready) tts.language = if (tagalog) Locale("fil", "PH") else Locale.US
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}