package com.example.comucane

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Embedded On-Device Voice Command Manager.
 * Consumes text from Vosk continuous stream for zero-latency commands.
 */
class GlobalVoiceCommandManager(
    private val activity: AppCompatActivity,
    private val tts: TextToSpeech,
    useTagalog: Boolean,
    private val onCommand: (String) -> Unit,
    private val onReadScreen: () -> Unit,
    private val onAppSpeaking: (Boolean) -> Unit = {},
    private val onManualTrigger: (() -> Unit)? = null,
    private val onPreferenceChanged: (() -> Unit)? = null,
    private val onFinishedListening: (() -> Unit)? = null
) {

    private var currentUseTagalog = useTagalog
    private var isDestroyed = false
    private var hintView: TextView? = null
    private var commandsView: TextView? = null

    private val handler = Handler(Looper.getMainLooper())

    fun attachStatusBar(hintView: TextView, commandsView: TextView) {
        this.hintView = hintView
        this.commandsView = commandsView
    }

    fun attachToHoldButton(button: View) {
        button.setOnClickListener {
            onReadScreen()
        }
        
        button.setOnLongClickListener {
            onManualTrigger?.invoke()
            playAcknowledgment("Yes?")
            true
        }
    }

    /**
     * Called by Activity when Vosk detects a stable command string.
     */
    fun onCommandDetected(rawCommand: String) {
        if (isDestroyed) return
        
        val cleaned = normalizeCommand(rawCommand)
        if (cleaned.isBlank()) return

        Log.i("VoiceManager", "Processing Embedded Command: $cleaned")
        
        showStatus("Command heard", cleaned)
        
        val result = processCommand(cleaned)

        handler.postDelayed({
            if (!isDestroyed) {
                onFinishedListening?.invoke()
            }
        }, result.delayBeforeFinishMs)
    }

    /**
     * Feedback after "Hey Cane" is heard.
     */
    fun playAcknowledgment(message: String) {
        if (isDestroyed) return
        
        val utteranceId = "ACK_${System.currentTimeMillis()}"
        
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                // onAppSpeaking(true) // Unblocked for smoother flow
            }
            override fun onDone(id: String?) {
                // onAppSpeaking(false)
            }
            override fun onError(id: String?) {
                // onAppSpeaking(false)
            }
        })

        tts.language = if (currentUseTagalog) Locale.forLanguageTag("fil-PH") else Locale.US
        tts.setSpeechRate(1.15f) // Snappier response
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun processCommand(cmd: String): CommandResult {
        val command = normalizeCommand(cmd)

        return when {
            hasWholeCommand(command, listOf("english", "switch to english")) -> {
                setLanguage(false)
                showStatus("Language changed", "English selected.")
                CommandResult(delayBeforeFinishMs = 1000L)
            }

            hasWholeCommand(command, listOf("tagalog", "filipino", "switch to tagalog")) -> {
                setLanguage(true)
                showStatus("Language changed", "Tagalog selected.")
                CommandResult(delayBeforeFinishMs = 1000L)
            }

            hasWholeCommand(command, listOf("voice off", "disable voice", "patayin voice")) -> {
                setVoiceAlert(false)
                showStatus("Voice alerts off", "Wake word active.")
                CommandResult(delayBeforeFinishMs = 1300L)
            }

            hasWholeCommand(command, listOf("voice on", "enable voice", "buksan voice")) -> {
                setVoiceAlert(true)
                showStatus("Voice alerts on", "Ready for commands.")
                CommandResult(delayBeforeFinishMs = 1300L)
            }

            hasWholeCommand(command, listOf("navigation", "guide", "nabigasyon", "start navigation", "navigate", "start guide")) -> {
                if (activity !is NavigationActivity) {
                    blockGlobalListeningFor(2500L)
                    activity.startActivity(Intent(activity, NavigationActivity::class.java))
                    CommandResult(delayBeforeFinishMs = 0L)
                } else {
                    onCommand(command)
                    CommandResult(delayBeforeFinishMs = 700L)
                }
            }

            hasWholeCommand(command, listOf("connect", "device", "scan", "aparato", "start scanning", "search")) -> {
                if (activity is DeviceActivity) {
                    onCommand(command)
                    CommandResult(delayBeforeFinishMs = 700L)
                } else {
                    blockGlobalListeningFor(2200L)
                    activity.startActivity(Intent(activity, DeviceActivity::class.java))
                    CommandResult(delayBeforeFinishMs = 0L)
                }
            }

            hasWholeCommand(command, listOf("logs", "records", "talaan")) -> {
                if (activity is LogsActivity) {
                    onCommand(command)
                    CommandResult(delayBeforeFinishMs = 700L)
                } else {
                    blockGlobalListeningFor(2200L)
                    activity.startActivity(Intent(activity, LogsActivity::class.java))
                    CommandResult(delayBeforeFinishMs = 0L)
                }
            }

            hasWholeCommand(command, listOf("settings", "preferences", "mga setting")) -> {
                if (activity is SettingsActivity) {
                    onCommand(command)
                    CommandResult(delayBeforeFinishMs = 700L)
                } else {
                    blockGlobalListeningFor(2200L)
                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                    CommandResult(delayBeforeFinishMs = 0L)
                }
            }

            hasWholeCommand(command, listOf("emergency", "sos", "help", "tulong", "call help", "i need help")) -> {
                if (activity is EmergencyActivity) {
                    onCommand(command)
                    CommandResult(delayBeforeFinishMs = 700L)
                } else {
                    blockGlobalListeningFor(2200L)
                    activity.startActivity(Intent(activity, EmergencyActivity::class.java))
                    CommandResult(delayBeforeFinishMs = 0L)
                }
            }

            hasWholeCommand(command, listOf("read screen", "read", "basahin", "status", "kalagayan", "how are you", "kumusta", "what is happening", "is it safe")) -> {
                onReadScreen()
                CommandResult(delayBeforeFinishMs = 900L)
            }

            hasWholeCommand(command, listOf("go back", "back", "bumalik", "return")) -> {
                blockGlobalListeningFor(2000L)
                if (activity !is MainActivity) activity.finish()
                CommandResult(delayBeforeFinishMs = 0L)
            }

            hasWholeCommand(command, listOf("home", "dashboard", "tahanan")) -> {
                if (activity !is MainActivity) {
                    blockGlobalListeningFor(2000L)
                    activity.startActivity(Intent(activity, MainActivity::class.java))
                    CommandResult(delayBeforeFinishMs = 0L)
                } else {
                    onCommand(command)
                    CommandResult(delayBeforeFinishMs = 700L)
                }
            }

            hasWholeCommand(command, listOf("start")) -> {
                playAcknowledgment(if (currentUseTagalog) "Simulan ang alin? Navigation o pag-scan?" else "Start what? Navigation or scanning?")
                CommandResult(delayBeforeFinishMs = 2000L)
            }

            else -> {
                onCommand(command)
                CommandResult(delayBeforeFinishMs = 700L)
            }
        }
    }

    private fun normalizeCommand(input: String): String {
        val cleaned = input.lowercase(Locale.getDefault()).replace("[^a-z ]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
        val fillers = listOf("could you", "can you", "please", "kindly", "would you", "paki", "maaari bang", "pwede bang", "i", "want to", "take me to", "open", "go to", "launch")
        var result = cleaned
        for (filler in fillers) {
            val pattern = Regex("(?i)\\b$filler\\b")
            result = result.replace(pattern, "").trim()
        }
        return result
    }

    private fun hasWholeCommand(command: String, phrases: List<String>): Boolean {
        val normalizedCommand = normalizeCommand(command)
        return phrases.any { phrase ->
            val normalizedPhrase = phrase.lowercase(Locale.getDefault())
            if (normalizedPhrase.isBlank()) false else {
                val pattern = Regex("(^|\\s)${Regex.escape(normalizedPhrase)}($|\\s)")
                pattern.containsMatchIn(normalizedCommand)
            }
        }
    }

    private fun setVoiceAlert(enabled: Boolean) {
        activity.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(SettingsActivity.KEY_VOICE_ALERT, enabled).apply()
        onPreferenceChanged?.invoke()
    }

    private fun setLanguage(tagalog: Boolean) {
        currentUseTagalog = tagalog
        activity.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(SettingsActivity.KEY_USE_TAGALOG, tagalog).apply()
        onPreferenceChanged?.invoke()
    }

    fun pause() {}
    fun destroy() {
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        hintView = null
        commandsView = null
    }

    private fun showStatus(title: String, subtitle: String) {
        activity.runOnUiThread {
            hintView?.text = title
            commandsView?.text = subtitle
        }
    }

    private data class CommandResult(val delayBeforeFinishMs: Long = 0L)

    companion object {
        @Volatile private var globalVoiceBlockedUntil: Long = 0L
        fun blockGlobalListeningFor(durationMs: Long) {
            val until = android.os.SystemClock.elapsedRealtime() + durationMs
            if (until > globalVoiceBlockedUntil) globalVoiceBlockedUntil = until
        }
    }
}
