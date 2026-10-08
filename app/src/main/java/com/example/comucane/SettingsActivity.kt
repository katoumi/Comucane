package com.example.comucane

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.comucane.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivitySettingsBinding
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
    private var isApplyingVoiceChange = false
    private var isLeavingScreen = false

    companion object {
        const val PREFS_NAME = "ComuCanePrefs"
        const val KEY_USE_TAGALOG = "useTagalog"
        const val KEY_VOICE_ALERT = "voiceAlert"
        const val KEY_VIBRATION_ALERT = "vibrationAlert"
        const val KEY_EMERGENCY_NAME = "emergencyName"
        const val KEY_EMERGENCY_PHONE = "emergencyPhone"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPreferences()
        applyPreferencesToUi()
        setupButtons()

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
                        "Settings screen. Say Hey Cane to change settings."
                    } else {
                        "Settings screen. Say Hey Cane to change settings."
                    },
                    force = true
                )

                startWakeWordAfterDelay(2500L)
            } else {
                startWakeWordAfterDelay(1000L)
            }
        }
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
            onCommand = { command -> handleSettingsCommand(command) },
            onReadScreen = { readScreen() },
            onAppSpeaking = { speaking -> wakeWordManager.setIsAppSpeaking(speaking) },
            onManualTrigger = { wakeWordManager.forceOpenCommandWindow() },
            onPreferenceChanged = {
                loadPreferences()
                applyPreferencesToUi()
            },
            onFinishedListening = {
                returnToWakeWordMode()
            }
        )

        wakeWordManager = VoskWakeWordManager(
            activity = this,
            onWakeWordDetected = { onHeyCaneDetected() },
            onCommandDetected = { command -> voiceManager.onCommandDetected(command) }
        )

        wakeWordManager.attachStatusBar(
            hintView = voiceHint,
            commandsView = voiceCommands
        )

        wakeWordManager.prepare()

        voiceManager.attachStatusBar(
            hintView = voiceHint,
            commandsView = voiceCommands
        )

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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        useTagalog = prefs.getBoolean(KEY_USE_TAGALOG, false)
        voiceAlertEnabled = prefs.getBoolean(KEY_VOICE_ALERT, true)
        vibrationAlertEnabled = prefs.getBoolean(KEY_VIBRATION_ALERT, true)

        if (::tts.isInitialized) {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
        }
    }

    private fun applyPreferencesToUi() {
        if (!::binding.isInitialized) return

        isApplyingVoiceChange = true

        binding.rbTagalog.isChecked = useTagalog
        binding.rbEnglish.isChecked = !useTagalog
        binding.cbVoiceAlert.isChecked = voiceAlertEnabled
        binding.cbVibrationAlert.isChecked = vibrationAlertEnabled

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.etEmergencyName.setText(prefs.getString(KEY_EMERGENCY_NAME, ""))
        binding.etEmergencyPhone.setText(prefs.getString(KEY_EMERGENCY_PHONE, ""))

        isApplyingVoiceChange = false
    }

    private fun setupButtons() {
        binding.voiceBottomBar.btnHoldSpeak.setOnClickListener {
            readScreen()
        }

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            if (isApplyingVoiceChange) return@setOnCheckedChangeListener

            useTagalog = checkedId == R.id.rbTagalog
            saveSettings(silent = true)

            speak(
                if (useTagalog) "Napalitan sa Tagalog." else "Switched to English.",
                force = true
            )

            returnToWakeWordAfterSpeech(1800L)
        }

        binding.cbVoiceAlert.setOnCheckedChangeListener { _, isChecked ->
            if (isApplyingVoiceChange) return@setOnCheckedChangeListener

            voiceAlertEnabled = isChecked
            saveSettings(silent = true)

            updateVoiceBar(
                if (isChecked) "Voice alerts on" else "Voice alerts off",
                "Wake word is still active."
            )

            returnToWakeWordMode()
        }

        binding.cbVibrationAlert.setOnCheckedChangeListener { _, isChecked ->
            if (isApplyingVoiceChange) return@setOnCheckedChangeListener

            vibrationAlertEnabled = isChecked
            saveSettings(silent = true)

            updateVoiceBar(
                if (isChecked) "Vibration on" else "Vibration off",
                "Say Hey Cane again."
            )

            returnToWakeWordMode()
        }

        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    private fun handleSettingsCommand(command: String) {
        val cmd = normalize(command)

        val hasVoice = containsAny(cmd, listOf("voice", "boses"))
        val hasVibration = containsAny(cmd, listOf("vibration", "vibrate", "vibrasyon"))
        val isOff = containsAny(cmd, listOf("off", "disable", "patayin", "i off", "tigilan"))
        val isOn = containsAny(cmd, listOf("on", "enable", "buksan", "i on"))

        when {
            hasVoice && hasVibration && isOff -> {
                binding.cbVoiceAlert.isChecked = false
                binding.cbVibrationAlert.isChecked = false
                voiceAlertEnabled = false
                vibrationAlertEnabled = false
                saveSettings(silent = true)

                updateVoiceBar(
                    "Voice and vibration off",
                    "Wake word is still active. Say Hey Cane again."
                )

                returnToWakeWordMode()
            }

            hasVoice && hasVibration && isOn -> {
                binding.cbVoiceAlert.isChecked = true
                binding.cbVibrationAlert.isChecked = true
                voiceAlertEnabled = true
                vibrationAlertEnabled = true
                saveSettings(silent = true)

                updateVoiceBar(
                    "Voice and vibration on",
                    "Say Hey Cane again."
                )

                returnToWakeWordMode()
            }

            hasVoice && isOff -> {
                binding.cbVoiceAlert.isChecked = false
                voiceAlertEnabled = false
                saveSettings(silent = true)

                updateVoiceBar(
                    "Voice alerts off",
                    "Wake word is still active. Say Hey Cane again."
                )

                returnToWakeWordMode()
            }

            hasVoice && isOn -> {
                binding.cbVoiceAlert.isChecked = true
                voiceAlertEnabled = true
                saveSettings(silent = true)

                updateVoiceBar(
                    "Voice alerts on",
                    "Say Hey Cane again."
                )

                returnToWakeWordMode()
            }

            hasVibration && isOff -> {
                binding.cbVibrationAlert.isChecked = false
                vibrationAlertEnabled = false
                saveSettings(silent = true)

                updateVoiceBar(
                    "Vibration off",
                    "Say Hey Cane again."
                )

                returnToWakeWordMode()
            }

            hasVibration && isOn -> {
                binding.cbVibrationAlert.isChecked = true
                vibrationAlertEnabled = true
                saveSettings(silent = true)

                updateVoiceBar(
                    "Vibration on",
                    "Say Hey Cane again."
                )

                returnToWakeWordMode()
            }

            containsAny(cmd, listOf("english", "switch english", "switch to english", "change to english")) -> {
                binding.rbEnglish.isChecked = true
                useTagalog = false
                saveSettings(silent = true)

                updateVoiceBar(
                    "Language changed",
                    "English selected. Say Hey Cane again."
                )

                returnToWakeWordMode()
            }

            containsAny(cmd, listOf("tagalog", "filipino", "switch tagalog", "switch to tagalog", "change to tagalog")) -> {
                binding.rbTagalog.isChecked = true
                useTagalog = true
                saveSettings(silent = true)

                updateVoiceBar(
                    "Language changed",
                    "Tagalog selected. Say Hey Cane again."
                )

                returnToWakeWordMode()
            }

            containsAny(cmd, listOf("save", "save settings", "i save", "i save ang settings")) -> {
                saveSettings()
            }

            containsAny(cmd, listOf("status", "current settings", "read", "read screen", "basahin")) -> {
                readScreen()
            }

            else -> {
                speak(
                    if (useTagalog) {
                        "Hindi ko naintindihan. Sabihin ang voice on, vibration off, English, Tagalog, save, o status."
                    } else {
                        "I did not understand. Say voice on, vibration off, English, Tagalog, save, or status."
                    },
                    force = true
                )

                returnToWakeWordAfterSpeech(3200L)
            }
        }
    }

    private fun saveSettings(silent: Boolean = false) {
        val selectedTagalog = binding.rbTagalog.isChecked

        useTagalog = selectedTagalog
        voiceAlertEnabled = binding.cbVoiceAlert.isChecked
        vibrationAlertEnabled = binding.cbVibrationAlert.isChecked

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_TAGALOG, selectedTagalog)
            .putBoolean(KEY_VOICE_ALERT, voiceAlertEnabled)
            .putBoolean(KEY_VIBRATION_ALERT, vibrationAlertEnabled)
            .putString(KEY_EMERGENCY_NAME, binding.etEmergencyName.text.toString().trim())
            .putString(KEY_EMERGENCY_PHONE, binding.etEmergencyPhone.text.toString().trim())
            .apply()

        if (::tts.isInitialized) {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
        }

        if (!silent) {
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

            speak(
                if (useTagalog) "Na-save ang settings." else "Settings saved.",
                force = true
            )

            returnToWakeWordAfterSpeech(2200L)
        }
    }

    private fun readScreen() {
        val language = if (binding.rbTagalog.isChecked) "Tagalog" else "English"
        val voice = if (binding.cbVoiceAlert.isChecked) "on" else "off"
        val vibration = if (binding.cbVibrationAlert.isChecked) "on" else "off"

        val contact = binding.etEmergencyName.text
            .toString()
            .trim()
            .ifEmpty { if (useTagalog) "Wala" else "None" }

        speak(
            if (useTagalog) {
                "Settings screen. Wika: $language. Voice: $voice. Vibration: $vibration. Emergency contact: $contact."
            } else {
                "Settings screen. Language: $language. Voice: $voice. Vibration: $vibration. Emergency contact: $contact."
            },
            force = true
        )

        returnToWakeWordAfterSpeech(3800L)
    }

    private fun speak(message: String, force: Boolean = false) {
        if (!ttsReady) return
        if (!voiceAlertEnabled && !force) return

        try {
            tts.stop()
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
            tts.setSpeechRate(1.08f)

            tts.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SETTINGS_TTS"
            )
        } catch (_: Exception) {
        }
    }

    private fun returnToWakeWordAfterSpeech(delayMs: Long = 1800L) {
        if (!::wakeWordManager.isInitialized) return

        handler.postDelayed({
            if (!isLeavingScreen && !isFinishing && !isDestroyed && ::wakeWordManager.isInitialized) {
                wakeWordManager.notifyCommandModeFinished()
            }
        }, delayMs)
    }

    private fun updateVoiceBar(title: String, subtitle: String) {
        binding.voiceBottomBar.tvVoiceHint.text = title
        binding.voiceBottomBar.tvVoiceCommands.text = subtitle
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
        applyPreferencesToUi()

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