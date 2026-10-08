package com.example.comucane

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.example.comucane.databinding.ActivityEmergencyBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.Locale

class EmergencyActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityEmergencyBinding
    private lateinit var tts: TextToSpeech
    private lateinit var wakeWordManager: VoskWakeWordManager
    private lateinit var voiceManager: GlobalVoiceCommandManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var bluetoothManager: BluetoothManager

    private val handler = Handler(Looper.getMainLooper())

    private var useTagalog = false
    private var contactName = ""
    private var contactPhone = ""
    private var countdownTimer: CountDownTimer? = null
    private var currentLocation: Location? = null
    private var voiceAlertEnabled = true

    private var ttsReady = false
    private var hasInitializedVoiceSystem = false
    private var isLeavingScreen = false
    private var hasStartedCountdown = false
    private var hasSpokenIntro = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEmergencyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPreferences()
        setupInitialUi()
        setupButtons()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        
        bluetoothManager = BluetoothManager.getInstance(this)
        setupBluetoothStatusListener()

        requestEmergencyPermissions()
        getLastKnownLocation()
    }

    private fun requestEmergencyPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.SEND_SMS)
        permissions.add(Manifest.permission.CALL_PHONE)

        val toRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1001)
        }
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
                    if (contactPhone.isNotEmpty()) {
                        if (useTagalog) {
                            "Emergency screen. Sabihin ang Hey Cane para magbigay ng command."
                        } else {
                            "Emergency screen. Say Hey Cane to give a command."
                        }
                    } else {
                        if (useTagalog) {
                            "Walang emergency contact. Pumunta sa settings para magdagdag."
                        } else {
                            "No emergency contact. Go to settings to add one."
                        }
                    },
                    force = true
                )

                startWakeWordAfterDelay(3300L)
            } else {
                startWakeWordAfterDelay(1000L)
            }
        }
    }

    private fun setupBluetoothStatusListener() {
        bluetoothManager.onConnectionStatusChanged = { 
            updateLocationAndStatusText()
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
            onCommand = { command -> handleEmergencyCommand(command) },
            onReadScreen = { readScreen() },
            onAppSpeaking = { speaking -> wakeWordManager.setIsAppSpeaking(speaking) },
            onManualTrigger = { wakeWordManager.forceOpenCommandWindow() },
            onPreferenceChanged = {
                loadPreferences()
                setupInitialUi()
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

    private fun setupInitialUi() {
        binding.tvContactName.text = contactName.ifEmpty {
            if (useTagalog) "Walang nakatakdang contact" else "No contact set"
        }

        binding.tvContactPhone.text = contactPhone.ifEmpty { "—" }

        binding.tvCountdown.text = if (contactPhone.isNotEmpty()) {
            "Ready to call."
        } else {
            if (useTagalog) "Walang emergency contact." else "No emergency contact."
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)

        useTagalog = prefs.getBoolean(SettingsActivity.KEY_USE_TAGALOG, false)
        contactName = prefs.getString(SettingsActivity.KEY_EMERGENCY_NAME, "") ?: ""
        contactPhone = prefs.getString(SettingsActivity.KEY_EMERGENCY_PHONE, "") ?: ""
        voiceAlertEnabled = prefs.getBoolean(SettingsActivity.KEY_VOICE_ALERT, true)

        if (::tts.isInitialized) {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
        }

        if (::binding.isInitialized) {
            binding.tvContactName.text = contactName.ifEmpty {
                if (useTagalog) "Walang nakatakdang contact" else "No contact set"
            }

            binding.tvContactPhone.text = contactPhone.ifEmpty { "—" }
        }
    }

    private fun setupButtons() {
        binding.voiceBottomBar.btnHoldSpeak.setOnClickListener {
            readScreen()
        }

        binding.btnCallNow.setOnClickListener {
            callContact()
        }

        binding.btnCancelEmergency.setOnClickListener {
            cancelEmergency()
        }
    }

    private fun startCountdown() {
        if (hasStartedCountdown) return

        if (contactPhone.isEmpty()) {
            speak(
                if (useTagalog) {
                    "Walang emergency contact. Pumunta sa settings para magdagdag."
                } else {
                    "No emergency contact. Go to settings to add one."
                },
                force = true
            )

            returnToWakeWordAfterSpeech(3000L)
            return
        }

        hasStartedCountdown = true
        countdownTimer?.cancel()

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.pause()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.pause()
        }

        countdownTimer = object : CountDownTimer(5000L, 1000L) {
            override fun onTick(millisLeft: Long) {
                val seconds = (millisLeft / 1000L) + 1L

                binding.tvCountdown.text =
                    if (useTagalog) {
                        getString(R.string.calling_in_tl, seconds.toInt())
                    } else {
                        getString(R.string.calling_in, seconds.toInt())
                    }
            }

            override fun onFinish() {
                binding.tvCountdown.text =
                    if (useTagalog) getString(R.string.calling_now_tl) else getString(R.string.calling_now)

                callContact()
            }
        }.start()
    }

    private fun callContact() {
        countdownTimer?.cancel()
        countdownTimer = null

        if (contactPhone.isEmpty()) {
            speak(
                if (useTagalog) {
                    "Walang emergency contact. Pumunta sa settings para magdagdag."
                } else {
                    "No emergency contact. Go to settings to add one."
                },
                force = true
            )

            returnToWakeWordAfterSpeech(3000L)
            return
        }

        sendEmergencySms()

        isLeavingScreen = true

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.pause()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.pause()
        }

        speak(
            if (useTagalog) {
                "Tumatawag kay $contactName."
            } else {
                "Calling $contactName."
            },
            force = true
        )

        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed

            val phoneUri = "tel:$contactPhone".toUri()

            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startActivity(Intent(Intent.ACTION_CALL, phoneUri))
            } else {
                startActivity(Intent(Intent.ACTION_DIAL, phoneUri))
            }
        }, 900L)
    }

    private fun sendEmergencySms() {
        if (contactPhone.isEmpty()) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val locationUrl = currentLocation?.let {
                "https://maps.google.com/?q=${it.latitude},${it.longitude}"
            } ?: "Location unavailable"

            val message = if (useTagalog) {
                "COMUCANE SOS! Kailangan ni $contactName ng tulong. Huling lokasyon: $locationUrl"
            } else {
                "COMUCANE SOS! $contactName needs help. Last location: $locationUrl"
            }

            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(contactPhone, null, message, null, null)
            android.util.Log.i("EmergencyActivity", "SOS SMS sent to $contactPhone")
        } catch (e: Exception) {
            android.util.Log.e("EmergencyActivity", "Failed to send SOS SMS", e)
        }
    }

    private fun cancelEmergency() {
        isLeavingScreen = true

        countdownTimer?.cancel()
        countdownTimer = null

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.pause()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.pause()
        }

        GlobalVoiceCommandManager.blockGlobalListeningFor(2500L)

        speak(
            if (useTagalog) "Kanselado." else "Cancelled.",
            force = true
        )

        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }, 700L)
    }

    private fun handleEmergencyCommand(command: String) {
        val cmd = normalize(command)

        when {
            containsAny(
                cmd,
                listOf(
                    "cancel",
                    "stop",
                    "go back",
                    "back",
                    "exit",
                    "kansela",
                    "kanselahin",
                    "itigil",
                    "bumalik"
                )
            ) -> {
                cancelEmergency()
            }

            containsAny(
                cmd,
                listOf(
                    "call",
                    "call now",
                    "emergency call",
                    "dial",
                    "phone",
                    "tumawag",
                    "tawag"
                )
            ) -> {
                callContact()
            }

            containsAny(
                cmd,
                listOf(
                    "countdown",
                    "start countdown",
                    "timer"
                )
            ) -> {
                startCountdown()
            }

            containsAny(
                cmd,
                listOf(
                    "read",
                    "read screen",
                    "status",
                    "contact",
                    "basahin",
                    "kalagayan"
                )
            ) -> {
                readScreen()
            }

            containsAny(
                cmd,
                listOf(
                    "settings",
                    "setting",
                    "preferences"
                )
            ) -> {
                openSettings()
            }

            else -> {
                speak(
                    if (useTagalog) {
                        "Hindi ko naintindihan. Sabihin ang call now, cancel, o status."
                    } else {
                        "I did not understand. Say call now, cancel, or status."
                    },
                    force = true
                )

                returnToWakeWordAfterSpeech(3000L)
            }
        }
    }

    private fun openSettings() {
        isLeavingScreen = true

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.pause()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.pause()
        }

        GlobalVoiceCommandManager.blockGlobalListeningFor(2500L)
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun updateLocationAndStatusText() {
        if (!::bluetoothManager.isInitialized) return
        
        val bluetoothStatus = if (bluetoothManager.isConnected) "CANE: ON" else "CANE: OFF"
        
        val locText = currentLocation?.let { 
            "GPS: ${"%.4f".format(it.latitude)}, ${"%.4f".format(it.longitude)}" 
        } ?: (if (useTagalog) "GPS: SEARCHING" else "GPS: SEARCHING")

        binding.tvLocationInfo.text = getString(R.string.bluetooth_gps_status, bluetoothStatus, locText)
        
        // Visual feedback: full opacity when connected, dimmed when searching/disconnected
        binding.tvLocationInfo.alpha = if (bluetoothManager.isConnected) 1.0f else 0.7f
    }

    private fun readScreen() {
        updateLocationAndStatusText()
        val bluetoothText = if (bluetoothManager.isConnected) {
            if (useTagalog) "Nakakonekta ang cane." else "Cane is connected."
        } else {
            if (useTagalog) "Hindi nakakonekta ang cane." else "Cane is disconnected."
        }

        val locationText = currentLocation?.let { location ->
            "GPS: ${"%.4f".format(location.latitude)}, ${"%.4f".format(location.longitude)}"
        } ?: if (useTagalog) "Walang GPS." else "No GPS."

        val contactText = contactName.ifEmpty { if (useTagalog) "Wala" else "None" }

        speak(
            if (useTagalog) {
                "Emergency screen. $bluetoothText Contact: $contactText. $locationText. Sabihin ang call now o cancel."
            } else {
                "Emergency screen. $bluetoothText Contact: $contactText. $locationText. Say call now or cancel."
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
            tts.language = if (useTagalog) Locale.forLanguageTag("fil-PH") else Locale.US
            tts.setSpeechRate(1.08f)
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "EMERGENCY_TTS")
        } catch (_: Exception) {
        }
    }

    private fun returnToWakeWordAfterSpeech(delayMs: Long) {
        handler.postDelayed({
            returnToWakeWordMode()
        }, delayMs)
    }

    private fun getLastKnownLocation() {
        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateLocationAndStatusText()
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (isFinishing || isDestroyed) return@addOnSuccessListener
            if (location != null) {
                currentLocation = location
            }
            updateLocationAndStatusText()
        }
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
        setupInitialUi()
        getLastKnownLocation()
        updateLocationAndStatusText()

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

        countdownTimer?.cancel()
        countdownTimer = null

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