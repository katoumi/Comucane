package com.example.comucane

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.comucane.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var wakeWordManager: VoskWakeWordManager
    private lateinit var voiceManager: GlobalVoiceCommandManager
    private lateinit var bluetoothManager: BluetoothManager

    private val handler = Handler(Looper.getMainLooper())

    private var ttsReady = false
    private var useTagalog = false
    private var voiceAlertEnabled = true
    private var hasInitializedVoiceSystem = false
    private var hasSpokenIntro = false
    private var isLeavingScreen = false

    private var vibrationAlertEnabled = true
    private var bluetoothConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPreferences()
        setupButtons()

        bluetoothManager = BluetoothManager.getInstance(this)
        bluetoothManager.onBatteryReceived = { level: Int -> 
             // Just triggering UI update if needed
             updateFeatureIndicators() 
        }
        
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
                        "ComuCane. Sabihin ang Hey Cane para magsimula ng voice command."
                    } else {
                        "ComuCane. Say Hey Cane to start a voice command."
                    },
                    force = false,
                )

                startWakeWordAfterDelay(3000L)
            } else {
                startWakeWordAfterDelay(1200L)
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
            onCommand = { command -> handleHomeVoiceCommand(command) },
            onReadScreen = { readScreen() },
            onAppSpeaking = { speaking -> wakeWordManager.setIsAppSpeaking(speaking) },
            onManualTrigger = { wakeWordManager.forceOpenCommandWindow() },
            onPreferenceChanged = { loadPreferences() },
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
        
        // Instant feedback
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

    private fun startWakeWordAfterDelay(delayMs: Long = 1200L) {
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

    private fun updateFeatureIndicators() {
        val activeColor = getColor(R.color.brand_status_green)
        val inactiveColor = getColor(R.color.brand_primary)
        
        val bluetoothManager = BluetoothManager.getInstance(this)
        bluetoothConnected = bluetoothManager.isConnected
        
        val activeAlpha = 1.0f
        val inactiveAlpha = 0.5f

        binding.tvVoiceFeature.apply {
            alpha = if (voiceAlertEnabled) activeAlpha else inactiveAlpha
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (voiceAlertEnabled) activeColor else inactiveColor)
            // Visual cue for state
            text = if (voiceAlertEnabled) "Voice: ON" else "Voice: OFF"
        }
        
        binding.tvVibrationFeature.apply {
            alpha = if (vibrationAlertEnabled) activeAlpha else inactiveAlpha
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (vibrationAlertEnabled) activeColor else inactiveColor)
            text = if (vibrationAlertEnabled) "Vibration: ON" else "Vibration: OFF"
        }

        val gpsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        binding.tvGpsFeature.apply {
            alpha = if (gpsPermission) activeAlpha else inactiveAlpha
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (gpsPermission) activeColor else inactiveColor)
            text = if (gpsPermission) "GPS: ACTIVE" else "GPS: OFF"
        }
        
        // Bluetooth connection indicator
        bluetoothConnected = BluetoothManager.getInstance(this).isConnected
        if (bluetoothConnected) {
            binding.btnDeviceConnection.backgroundTintList = android.content.res.ColorStateList.valueOf(activeColor)
        } else {
            binding.btnDeviceConnection.backgroundTintList = null // Default
        }
    }

    private fun setupButtons() {
        binding.btnPageVoice.setOnClickListener { readScreen() }
        binding.btnStartNavigation.setOnClickListener { openNavigation() }
        binding.btnDeviceConnection.setOnClickListener { openDevice() }
        binding.btnLogs.setOnClickListener { openLogs() }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnEmergency.setOnClickListener { openEmergency() }
    }

    private fun handleHomeVoiceCommand(command: String) {
        val cmd = normalize(command)

        when {
            containsAny(cmd, listOf("navigation", "start navigation", "navigate", "start guide", "guide", "nabigasyon")) -> {
                openNavigation()
            }

            containsAny(cmd, listOf("device", "devices", "connect device", "connect", "scan", "aparato")) -> {
                openDevice()
            }

            containsAny(cmd, listOf("logs", "log", "obstacle logs", "records", "talaan")) -> {
                openLogs()
            }

            containsAny(cmd, listOf("settings", "setting", "preferences")) -> {
                openSettings()
            }

            containsAny(cmd, listOf("emergency", "sos", "help", "tulong")) -> {
                openEmergency()
            }

            containsAny(cmd, listOf("read", "read screen", "read this screen", "status", "basahin")) -> {
                readScreen()
            }

            else -> {
                speak(
                    if (useTagalog) {
                        "Hindi ko naintindihan. Sabihin ang navigation, device, logs, settings, o emergency."
                    } else {
                        "I did not understand. Say navigation, device, logs, settings, or emergency."
                    },
                    force = true
                )

                returnToWakeWordAfterSpeech(3200L)
            }
        }
    }

    private fun openNavigation() {
        prepareToLeaveScreen()

        speak(
            if (useTagalog) "Sinisimulan ang nabigasyon." else "Starting navigation.",
            force = false
        )

        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, NavigationActivity::class.java))
            }
        }, 300L)
    }

    private fun openDevice() {
        prepareToLeaveScreen()

        speak(
            if (useTagalog) "Binubuksan ang device screen." else "Opening device screen.",
            force = false
        )

        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, DeviceActivity::class.java))
            }
        }, 300L)
    }

    private fun openLogs() {
        prepareToLeaveScreen()

        speak(
            if (useTagalog) "Binubuksan ang logs." else "Opening logs.",
            force = false
        )

        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, LogsActivity::class.java))
            }
        }, 300L)
    }

    private fun openSettings() {
        prepareToLeaveScreen()

        speak(
            if (useTagalog) "Binubuksan ang settings." else "Opening settings.",
            force = false
        )

        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }, 300L)
    }

    private fun openEmergency() {
        prepareToLeaveScreen()

        speak(
            if (useTagalog) "Binubuksan ang emergency screen." else "Opening emergency screen.",
            force = true
        )

        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, EmergencyActivity::class.java))
            }
        }, 300L)
    }

    private fun prepareToLeaveScreen() {
        isLeavingScreen = true

        handler.removeCallbacksAndMessages(null)

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.stop()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.pause()
        }

        GlobalVoiceCommandManager.blockGlobalListeningFor(2200L)
    }

    private fun readScreen() {
        if (::wakeWordManager.isInitialized) {
            wakeWordManager.stop()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.pause()
        }

        speak(
            if (useTagalog) {
                getString(R.string.read_home_tl)
            } else {
                getString(R.string.read_home_en)
            },
            force = true
        )

        returnToWakeWordAfterSpeech(4200L)
    }

    private fun speak(message: String, force: Boolean = false) {
        if (!ttsReady) return
        if (!voiceAlertEnabled && !force) return

        try {
            tts.stop()
            tts.language = if (useTagalog) Locale.forLanguageTag("fil-PH") else Locale.US
            tts.setSpeechRate(1.05f)
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "MAIN_TTS")
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
        updateFeatureIndicators()

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
