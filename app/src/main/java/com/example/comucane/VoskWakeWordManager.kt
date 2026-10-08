package com.example.comucane

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

class VoskWakeWordManager(
    private val activity: AppCompatActivity,
    private val onWakeWordDetected: () -> Unit,
    private val onCommandDetected: (String) -> Unit = {}
) {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    private var isDestroyed = false
    private var isPreparing = false
    private var isModelReady = false
    private var isListening = false
    private var shouldKeepListening = false
    private var isAppSpeaking = false
    
    private var isInCommandWindow = false
    private var lastWakeDetectedMs = 0L
    private var lastStartAttemptMs = 0L
    private var lastStopMs = 0L

    private var hintView: TextView? = null
    private var commandsView: TextView? = null

    private val handler = Handler(Looper.getMainLooper())

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed || activity.isFinishing || activity.isDestroyed) return

            if (shouldKeepListening && !isListening) {
                start(delayMs = 600L)
            }

            handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
        }
    }

    private val closeCommandWindowRunnable = Runnable {
        if (isInCommandWindow) {
            isInCommandWindow = false
            showWaitingStatus()
        }
    }

    fun attachStatusBar(hintView: TextView, commandsView: TextView) {
        this.hintView = hintView
        this.commandsView = commandsView
        showWaitingStatus()
    }

    /**
     * Call this when the app is using TTS to avoid hearing itself.
     */
    fun setIsAppSpeaking(speaking: Boolean) {
        this.isAppSpeaking = speaking
        if (speaking) {
            Log.i("VoskManager", "App is speaking. Ignoring incoming audio.")
        }
    }

    fun prepare() {
        if (isDestroyed || activity.isFinishing || activity.isDestroyed) return
        if (isPreparing) return

        if (!hasMicPermission()) {
            showStatus("Microphone needed", "Allow microphone permission.")
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), Constants.AUDIO_PERMISSION_REQUEST_CODE)
            return
        }

        if (isModelReady && model != null) {
            showWaitingStatus()
            return
        }

        isPreparing = true
        showStatus("Loading Voice AI", "Preparing local model...")

        StorageService.unpack(activity, MODEL_ASSET_FOLDER, MODEL_TARGET_FOLDER, { loadedModel ->
            if (isDestroyed || activity.isFinishing || activity.isDestroyed) {
                try { loadedModel.close() } catch (_: Exception) {}
                return@unpack
            }

            model = loadedModel
            isModelReady = true
            isPreparing = false

            activity.runOnUiThread {
                showWaitingStatus()
                if (shouldKeepListening) start(delayMs = 700L)
            }
        }, { exception ->
            isPreparing = false
            isModelReady = false
            activity.runOnUiThread {
                showStatus("Model Error", exception.message ?: "Could not load model.")
            }
        })
    }

    fun start(delayMs: Long = 0L) {
        if (delayMs > 0L) {
            handler.postDelayed({ start() }, delayMs)
            return
        }

        if (isDestroyed || activity.isFinishing || activity.isDestroyed) return
        shouldKeepListening = true

        if (!hasMicPermission() || !isModelReady || model == null) {
            prepare()
            return
        }

        if (isListening) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastStartAttemptMs < MIN_START_GAP_MS || now - lastStopMs < MIC_RELEASE_GAP_MS) {
            handler.postDelayed({ start() }, 500L)
            return
        }

        lastStartAttemptMs = now

        try {
            stopInternal(clearKeepListening = false)

            val grammar = Constants.UNIFIED_VOICE_GRAMMAR.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
            val recognizer = Recognizer(model, SAMPLE_RATE, grammar)

            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(createListener())

            isListening = true
            showWaitingStatus()
            startHealthCheck()
            Log.i("VoskManager", "Continuous listening started with unified grammar.")

        } catch (e: Exception) {
            isListening = false
            restartWakeListening(RESTART_AFTER_ERROR_MS)
        }
    }

    fun stop() {
        shouldKeepListening = false
        stopInternal(true)
    }

    fun pause() {
        shouldKeepListening = false
        stopInternal(true)
    }

    fun resume() {
        if (isDestroyed || activity.isFinishing || activity.isDestroyed) return
        shouldKeepListening = true
        if (!isModelReady || model == null) prepare()
        start(delayMs = 900L)
    }

    fun notifyCommandModeFinished() {
        isInCommandWindow = false
        showWaitingStatus()
    }

    /**
     * Manually open the command listening window (e.g. from a button hold).
     */
    fun forceOpenCommandWindow() {
        if (isDestroyed) return
        isInCommandWindow = true
        showStatus("Manual Trigger", "Say your command.")
        
        handler.removeCallbacks(closeCommandWindowRunnable)
        handler.postDelayed(closeCommandWindowRunnable, COMMAND_WINDOW_DURATION_MS)
    }

    fun destroy() {
        isDestroyed = true
        shouldKeepListening = false
        handler.removeCallbacksAndMessages(null)
        stopInternal(true)
        try { model?.close() } catch (_: Exception) {}
        model = null
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                if (!isListening) return
                val text = extractText(hypothesis)
                if (text.isBlank() || text == "unk") return

                handleSpeechDetected(text, isPartial = true)
            }

            override fun onResult(hypothesis: String?) {
                if (!isListening) return
                val text = extractText(hypothesis)
                if (text.isBlank() || text == "unk") return

                handleSpeechDetected(text, isPartial = false)
            }

            override fun onFinalResult(hypothesis: String?) {
                isListening = false
                if (shouldKeepListening) restartWakeListening(RESTART_AFTER_FINAL_MS)
            }

            override fun onError(exception: Exception?) {
                isListening = false
                if (shouldKeepListening) restartWakeListening(RESTART_AFTER_ERROR_MS)
            }

            override fun onTimeout() {
                isListening = false
                if (shouldKeepListening) restartWakeListening(RESTART_AFTER_TIMEOUT_MS)
            }
        }
    }

    private fun handleSpeechDetected(text: String, isPartial: Boolean) {
        val now = SystemClock.elapsedRealtime()

        if (isWakeWord(text)) {
            if (now - lastWakeDetectedMs > WAKE_DEBOUNCE_MS) {
                lastWakeDetectedMs = now
                isInCommandWindow = true
                
                showStatus("Hey Cane detected", "Listening for command...")
                onWakeWordDetected()
                
                handler.removeCallbacks(closeCommandWindowRunnable)
                handler.postDelayed(closeCommandWindowRunnable, COMMAND_WINDOW_DURATION_MS)
            }
            return
        }

        if (isInCommandWindow) {
            val command = removeWakePhrase(text)
            if (command.isNotBlank()) {
                Log.i("VoskManager", "Command detected: $command")
                
                // If it's a stable result (non-partial), execute it
                if (!isPartial) {
                    isInCommandWindow = false
                    handler.removeCallbacks(closeCommandWindowRunnable)
                    onCommandDetected(command)
                    // Reset to waiting status immediately after a full command
                    showWaitingStatus()
                } else {
                    // Visual feedback for partial command
                    showStatus("Hearing...", command)
                }
            }
        }
    }

    private fun restartWakeListening(delayMs: Long) {
        if (isDestroyed || !shouldKeepListening) return
        stopInternal(false)
        handler.postDelayed({ if (shouldKeepListening) start() }, delayMs)
    }

    private fun stopInternal(clearKeepListening: Boolean) {
        if (clearKeepListening) shouldKeepListening = false
        isListening = false
        lastStopMs = SystemClock.elapsedRealtime()
        try { speechService?.stop() } catch (_: Exception) {}
        try { speechService?.shutdown() } catch (_: Exception) {}
        speechService = null
    }

    private fun startHealthCheck() {
        handler.removeCallbacks(healthCheckRunnable)
        handler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun extractText(jsonText: String?): String {
        if (jsonText.isNullOrBlank()) return ""
        return try {
            val json = JSONObject(jsonText)
            val partial = json.optString("partial", "")
            val text = json.optString("text", "")
            val result = partial.ifBlank { text }.lowercase(Locale.getDefault()).replace("[^a-z ]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
            
            // Explicitly filter out the "unk" string produced by Vosk unknown token
            if (result == "unk") "" else result
        } catch (_: Exception) { "" }
    }

    private fun isWakeWord(text: String): Boolean {
        val wakePhrases = listOf("hey cane", "hey kane", "hey cain", "hey kain", "hey came", "hey can", "okay cane", "ok cane", "hello cane", "hi cane", "comucane", "comu cane")
        return wakePhrases.any { text.contains(it) }
    }

    private fun removeWakePhrase(text: String): String {
        val wakePhrases = listOf("hey cane", "hey kane", "hey cain", "hey kain", "hey came", "hey can", "okay cane", "ok cane", "hello cane", "hi cane", "comucane", "comu cane")
        var result = text
        for (wake in wakePhrases) result = result.replace(wake, "").trim()
        return result
    }

    private fun showWaitingStatus() {
        showStatus("Waiting for: Hey Cane", "Say Hey Cane to start.")
    }

    private fun showStatus(title: String, subtitle: String) {
        if (isDestroyed) return
        activity.runOnUiThread {
            hintView?.text = title
            commandsView?.text = subtitle
        }
    }

    private fun hasMicPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val MODEL_ASSET_FOLDER = "vosk-model-small-en-us-0.15"
        private const val MODEL_TARGET_FOLDER = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000.0f
        private const val WAKE_DEBOUNCE_MS = 3000L
        private const val COMMAND_WINDOW_DURATION_MS = 8000L
        private const val MIC_RELEASE_GAP_MS = 500L
        private const val MIN_START_GAP_MS = 500L
        private const val RESTART_AFTER_FINAL_MS = 400L
        private const val RESTART_AFTER_TIMEOUT_MS = 400L
        private const val RESTART_AFTER_ERROR_MS = 1000L
        private const val HEALTH_CHECK_INTERVAL_MS = 4000L
    }
}
