package com.example.comucane

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.comucane.databinding.ActivityLogsBinding
import java.util.Locale

class LogsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityLogsBinding
    private lateinit var logsAdapter: LogsAdapter
    private lateinit var tts: TextToSpeech
    private lateinit var wakeWordManager: VoskWakeWordManager
    private lateinit var voiceManager: GlobalVoiceCommandManager

    private val handler = Handler(Looper.getMainLooper())

    private var useTagalog = false
    private var voiceAlertEnabled = true
    private var vibrationAlertEnabled = true
    private var ttsReady = false
    private var hasInitializedVoiceSystem = false
    private var hasSpokenIntro = false
    private var isLeavingScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPreferences()
        setupRecyclerView()
        setupButtons()
        refreshLogs()

        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return

        runOnUiThread {
            ttsReady = true
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH

            setupVoiceSystem()

            if (!hasSpokenIntro) {
                hasSpokenIntro = true

                speak(
                    if (useTagalog) {
                        "Logs screen. Sabihin ang Hey Cane para magbigay ng command."
                    } else {
                        "Logs screen. Say Hey Cane to give a command."
                    },
                    force = false
                )

                startWakeWordAfterDelay(2600L)
            } else {
                startWakeWordAfterDelay(1000L)
            }
        }
    }

    private fun setupRecyclerView() {
        logsAdapter = LogsAdapter(LogRepository.logs)
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logsAdapter
    }

    private fun setupVoiceSystem() {
        if (hasInitializedVoiceSystem) return
        hasInitializedVoiceSystem = true

        val voiceHint = binding.voiceBottomBar.tvVoiceHint
        val voiceCommands = binding.voiceBottomBar.tvVoiceCommands

        voiceManager = GlobalVoiceCommandManager(
            activity = this,
            tts = tts,
            useTagalog = useTagalog,
            onCommand = { command -> handleLogsCommand(command) },
            onReadScreen = { readLogsAloud(force = true) },
            onAppSpeaking = { speaking -> wakeWordManager.setIsAppSpeaking(speaking) },
            onManualTrigger = { wakeWordManager.forceOpenCommandWindow() },
            onPreferenceChanged = {
                loadPreferences()
                refreshLogs()
            },
            onFinishedListening = { returnToWakeWordMode() }
        )

        wakeWordManager = VoskWakeWordManager(
            activity = this,
            onWakeWordDetected = { onHeyCaneDetected() },
            onCommandDetected = { command -> voiceManager.onCommandDetected(command) }
        )

        wakeWordManager.attachStatusBar(voiceHint, voiceCommands)
        wakeWordManager.prepare()

        voiceManager.attachStatusBar(voiceHint, voiceCommands)
        voiceManager.attachToHoldButton(binding.voiceBottomBar.btnHoldSpeak)
    }

    private fun onHeyCaneDetected() {
        if (isLeavingScreen || isFinishing || isDestroyed) return

        if (::voiceManager.isInitialized) {
            voiceManager.playAcknowledgment("Yes?")
        }
    }

    private fun returnToWakeWordMode() {
        if (isLeavingScreen || isFinishing || isDestroyed) return

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.notifyCommandModeFinished()
        }
    }

    private fun startWakeWordAfterDelay(delayMs: Long = 1000L) {
        if (!::wakeWordManager.isInitialized) return

        handler.postDelayed({
            if (!isLeavingScreen && !isFinishing && !isDestroyed && ::wakeWordManager.isInitialized) {
                wakeWordManager.start()
            }
        }, delayMs)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)

        useTagalog = prefs.getBoolean(SettingsActivity.KEY_USE_TAGALOG, false)
        voiceAlertEnabled = prefs.getBoolean(SettingsActivity.KEY_VOICE_ALERT, true)
        vibrationAlertEnabled = prefs.getBoolean(SettingsActivity.KEY_VIBRATION_ALERT, true)

        if (::tts.isInitialized) {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
        }
    }

    private fun setupButtons() {
        binding.btnPageVoice.setOnClickListener {
            readLogsAloud(force = true)
        }
    }

    private fun handleLogsCommand(command: String) {
        val cmd = normalize(command)

        when {
            containsAny(cmd, listOf("clear", "clear logs", "delete logs", "delete all", "remove logs", "burahin", "tanggalin")) -> {
                clearLogs()
            }

            containsAny(cmd, listOf("read", "read logs", "read screen", "summary", "status", "how many", "ilang", "basahin", "kalagayan")) -> {
                readLogsAloud(force = true)
            }

            containsAny(cmd, listOf("latest", "latest log", "last log", "most recent", "recent", "pinakabago", "huling tala")) -> {
                readLatestLog(force = true)
            }

            containsAny(cmd, listOf("refresh", "reload", "update")) -> {
                refreshLogs()

                speak(
                    if (useTagalog) "Na-refresh ang logs." else "Logs refreshed.",
                    force = true
                )

                returnToWakeWordAfterSpeech(1800L)
            }

            else -> {
                speak(
                    if (useTagalog) {
                        "Hindi ko naintindihan. Sabihin ang read, latest, clear, o status."
                    } else {
                        "I did not understand. Say read, latest, clear, or status."
                    },
                    force = true
                )

                returnToWakeWordAfterSpeech(3000L)
            }
        }
    }

    private fun clearLogs() {
        LogRepository.clearLogs()
        refreshLogs()

        speak(
            if (useTagalog) "Nabura ang lahat ng logs." else "All logs cleared.",
            force = true
        )

        returnToWakeWordAfterSpeech(2000L)
    }

    private fun refreshLogs() {
        if (::logsAdapter.isInitialized) {
            logsAdapter.updateLogs(LogRepository.logs)
        }

        refreshCounts()
    }

    private fun refreshCounts() {
        val logs = LogRepository.logs
        val total = logs.size
        val stopCount = logs.count { it.status == "STOP" }
        val cautionCount = logs.count { it.status == "CAUTION" }

        binding.tvStopCount.text = stopCount.toString()
        binding.tvCautionCount.text = cautionCount.toString()
        binding.tvTotalCount.text = total.toString()
    }

    private fun readLogsAloud(force: Boolean = false) {
        val logs = LogRepository.logs
        val total = logs.size
        val stopCount = logs.count { it.status == "STOP" }
        val cautionCount = logs.count { it.status == "CAUTION" }

        val message = if (logs.isEmpty()) {
            if (useTagalog) {
                "Walang obstacle logs. Walang naitalang panganib."
            } else {
                "No obstacle logs. No hazards have been recorded."
            }
        } else {
            if (useTagalog) {
                "Logs screen. May $total na tala. Stop: $stopCount. Caution: $cautionCount. Sabihin ang latest para basahin ang pinakabagong tala, o clear para burahin."
            } else {
                "Logs screen. There are $total logs. Stop: $stopCount. Caution: $cautionCount. Say latest to read the most recent log, or clear to delete all logs."
            }
        }

        speak(message, force = force)
        returnToWakeWordAfterSpeech(4200L)
    }

    private fun readLatestLog(force: Boolean = false) {
        val latest = LogRepository.logs.firstOrNull()

        val message = if (latest == null) {
            if (useTagalog) {
                "Walang pinakabagong log."
            } else {
                "There is no latest log."
            }
        } else {
            val statusText = when (latest.status) {
                "STOP" -> "Stop obstacle"
                "CAUTION" -> "Caution obstacle"
                else -> latest.status
            }

            if (useTagalog) {
                "Pinakabagong log. $statusText. Distansya: ${latest.distanceCm} sentimetro. Oras: ${latest.formattedTime()}. Lokasyon: ${latest.formattedLocation()}."
            } else {
                "Latest log. $statusText. Distance: ${latest.distanceCm} centimeters. Time: ${latest.formattedTime()}. Location: ${latest.formattedLocation()}."
            }
        }

        speak(message, force = force)
        returnToWakeWordAfterSpeech(4600L)
    }

    private fun speak(message: String, force: Boolean = false) {
        if (!ttsReady) return
        if (!voiceAlertEnabled && !force) return

        try {
            tts.stop()
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
            tts.setSpeechRate(1.08f)
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "LOGS_TTS")
        } catch (_: Exception) {
        }
    }

    private fun returnToWakeWordAfterSpeech(delayMs: Long) {
        handler.postDelayed({
            returnToWakeWordMode()
        }, delayMs)
    }

    private fun normalize(input: String): String {
        return input
            .lowercase(Locale.getDefault())
            .replace("[^a-z ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun containsAny(command: String, words: List<String>): Boolean {
        return words.any { command.contains(it) }
    }

    override fun onResume() {
        super.onResume()

        isLeavingScreen = false
        loadPreferences()
        refreshLogs()

        if (::tts.isInitialized) {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
        }

        if (::wakeWordManager.isInitialized && ttsReady) {
            wakeWordManager.resume()
        }
    }

    override fun onPause() {
        if (::wakeWordManager.isInitialized) {
            wakeWordManager.pause()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.pause()
        }

        super.onPause()
    }

    override fun onDestroy() {
        isLeavingScreen = true
        handler.removeCallbacksAndMessages(null)

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.destroy()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.destroy()
        }

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        super.onDestroy()
    }
}