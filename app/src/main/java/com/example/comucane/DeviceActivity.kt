package com.example.comucane

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.comucane.databinding.ActivityDeviceBinding
import java.util.Locale

class DeviceActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityDeviceBinding
    private lateinit var tts: TextToSpeech
    private lateinit var wakeWordManager: VoskWakeWordManager
    private lateinit var voiceManager: GlobalVoiceCommandManager
    private lateinit var bluetoothManager: BluetoothManager

    private val handler = Handler(Looper.getMainLooper())

    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private var isConnected = false
    private var isScanning = false
    private var useTagalog = false
    private var voiceAlertEnabled = true
    private var ttsReady = false
    private var hasInitializedVoiceSystem = false
    private var hasSpokenIntro = false
    private var isLeavingScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPreferences()
        setupButtons()

        binding.voiceBottomBar.tvVoiceHint.text = "Initializing Voice AI..."
        binding.voiceBottomBar.tvVoiceCommands.text = "Please wait."

        bluetoothManager = BluetoothManager.getInstance(this)
        setupBluetoothObservers()

        requestPermissions()
        
        tts = TextToSpeech(this, this)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, 
                toRequest.toTypedArray(), 
                Constants.BLUETOOTH_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun setupBluetoothObservers() {
        bluetoothManager.onConnectionStatusChanged = { connected ->
            Log.d("DeviceActivity", "onConnectionStatusChanged: $connected")
            isConnected = connected
            updateUiState()

            if (connected) {
                bluetoothManager.stopLeScan()
                isScanning = false
            } else if (!isLeavingScreen) {
                if (!bluetoothManager.isConnecting) {
                    Log.w("DeviceActivity", "Connection failed and isConnecting=false.")
                }
            }
        }

        bluetoothManager.onConnectingStatusChanged = { connecting ->
            Log.d("DeviceActivity", "onConnectingStatusChanged: $connecting")
            updateUiState()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return

        runOnUiThread {
            ttsReady = true
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH

            setupVoiceSystem()
            updateUiState()

            if (!hasSpokenIntro) {
                hasSpokenIntro = true

                val connectionMsg = if (isConnected) {
                    if (useTagalog) "Nakakonekta na sa aparato." else "Connected to device."
                } else {
                    if (useTagalog) "Hindi nakakonekta." else "Disconnected."
                }

                speak(
                    if (useTagalog) {
                        "Device screen. $connectionMsg Sabihin ang Hey Cane para magbigay ng command."
                    } else {
                        "Device screen. $connectionMsg Say Hey Cane to give a command."
                    },
                    force = true
                )

                startWakeWordAfterDelay(2600L)
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
            onCommand = { command -> handleDeviceCommand(command) },
            onReadScreen = { readScreen() },
            onAppSpeaking = { speaking -> wakeWordManager.setIsAppSpeaking(speaking) },
            onManualTrigger = { wakeWordManager.forceOpenCommandWindow() },
            onPreferenceChanged = { loadPreferences() },
            onFinishedListening = { returnToWakeWordMode() },
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

        handler.postDelayed(
            {
                if (!isLeavingScreen && !isFinishing && !isDestroyed && ::wakeWordManager.isInitialized) {
                    wakeWordManager.start()
                }
            },
            delayMs,
        )
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        useTagalog = prefs.getBoolean(SettingsActivity.KEY_USE_TAGALOG, false)
        voiceAlertEnabled = prefs.getBoolean(SettingsActivity.KEY_VOICE_ALERT, true)

        if (::tts.isInitialized) {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
        }
    }

    private fun setupButtons() {
        binding.btnPageVoice.setOnClickListener {
            readScreen()
        }

        binding.btnConnectDevice.setOnClickListener {
            handleConnectButtonClick()
        }

        binding.btnDisconnect.setOnClickListener {
            disconnectDevice()
        }
    }

    private fun handleConnectButtonClick() {
        if (isConnected) {
            disconnectDevice()
            return
        }

        if (!bluetoothManager.isBluetoothEnabled()) {
            return
        }

        isScanning = !isScanning

        if (isScanning) {
            startScan()
        } else {
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceCommand(command: String) {
        val cmd = normalize(command)

        when {
            containsAny(cmd, listOf("scan", "start scanning", "search", "mag scan", "hanapin")) -> {
                if (isConnected) {
                    speak(if (useTagalog) "Nakakonekta na ang aparato." else "Device is already connected.", force = true)
                    return
                }

                if (!bluetoothManager.isBluetoothEnabled()) {
                    speak(
                        if (useTagalog) "Naka-off ang Bluetooth. Pakibuksan muna ito." else "Bluetooth is turned off. Please enable it to scan for devices.",
                        force = true
                    )
                } else if (!isScanning) {
                    speak(if (useTagalog) "Sinisimulan ang pag-scan." else "Starting scan.", force = true)
                    startScan()
                }
            }

            containsAny(cmd, listOf("connect", "link", "kumonekta", "idugtong", "isali")) -> {
                if (isConnected) {
                    speak(if (useTagalog) "Nakakonekta na ang aparato." else "Device is already connected.", force = true)
                } else if (discoveredDevices.isNotEmpty()) {
                    val target = discoveredDevices.first()
                    speak(
                        if (useTagalog) "Kinokonekta sa ${target.name ?: "aparato"}." else "Connecting to ${target.name ?: "device"}.",
                        force = true
                    )
                    stopScan()
                    bluetoothManager.connectToDevice(target.address)
                } else {
                    speak(
                        if (useTagalog) "Walang makitang aparato. Sabihin ang scan para maghanap." else "No devices found. Say scan to search.",
                        force = true
                    )
                }
            }
            
            containsAny(cmd, listOf("status", "read", "read screen", "connected", "connection", "kalagayan", "basahin")) -> {
                readScreen()
            }

            containsAny(cmd, listOf("disconnect", "dis connect", "tanggalin", "idiskonekta", "stop")) -> {
                disconnectDevice()
            }

            else -> {
                speak(
                    if (useTagalog) {
                        "Hindi ko naintindihan. Sabihin ang status o disconnect."
                    } else {
                        "I did not understand. Say status or disconnect."
                    },
                    force = true
                )

                returnToWakeWordAfterSpeech(2600L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!bluetoothManager.isBluetoothEnabled()) {
            return
        }

        if (!bluetoothManager.hasBluetoothPermission()) {
            requestPermissions()
            return
        }

        isScanning = true
        binding.llDeviceList.removeAllViews()
        discoveredDevices.clear()
        updateUiState()

        Log.i("DeviceActivity", "BLE: Starting active scan for AT-09/BT05...")

        val foundAddresses = mutableSetOf<String>()

        bluetoothManager.startLeScan { device ->
            val address = device.address
            if (address !in foundAddresses) {
                foundAddresses.add(address)
                val name = device.name ?: "Unknown Device"
                Log.d("DeviceActivity", "BLE Discovered: $name [$address]")

                if (name.uppercase().contains("AT-09") || 
                    name.uppercase().contains("BT05") || 
                    name.uppercase().contains("MLT-BT05") || 
                    name.uppercase().contains("COMUCANE")) {
                    
                    runOnUiThread {
                        if (discoveredDevices.none { it.address == device.address }) {
                            discoveredDevices.add(device)
                        }
                        addDeviceToResultList(device)
                    }
                }
            }
        }

        // Auto-stop scan after 30 seconds
        handler.postDelayed({
            if (isScanning && !isConnected) {
                stopScan()
                Log.w("DeviceActivity", "BLE Scan timed out.")
            }
        }, 30000)
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceToResultList(device: android.bluetooth.BluetoothDevice) {
        val deviceCard = layoutInflater.inflate(android.R.layout.simple_list_item_2, binding.llDeviceList, false)
        val text1 = deviceCard.findViewById<TextView>(android.R.id.text1)
        val text2 = deviceCard.findViewById<TextView>(android.R.id.text2)

        text1.text = device.name ?: "Unknown"
        text1.setTextColor(getColor(R.color.brand_text_on_card))
        text1.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
        
        text2.text = device.address
        text2.setTextColor(getColor(R.color.brand_text_on_card))
        text2.alpha = 0.7f

        deviceCard.setPadding(32, 32, 32, 32)
        deviceCard.setBackgroundResource(R.drawable.bg_rehaul_button)
        deviceCard.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_surface_card))
        
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 16)
        deviceCard.layoutParams = params

        deviceCard.setOnClickListener {
            Log.i("DeviceActivity", "User selected device: ${device.name}. Connecting...")
            stopScan()
            bluetoothManager.connectToDevice(device.address)
        }

        binding.llDeviceList.addView(deviceCard)
    }

    private fun stopScan() {
        isScanning = false
        bluetoothManager.stopLeScan()
        updateUiState()
    }

    private fun disconnectDevice() {
        bluetoothManager.disconnect()
        updateUiState()
    }

    private fun updateUiState() {
        if (isConnected) {
            binding.tvDeviceStatus.text = getString(R.string.connected)
            binding.tvDeviceStatus.setTextColor(getColor(R.color.status_connected))

            binding.tvScanningStatus.text = getString(R.string.device_active)

            binding.tvDevice1Status.text = getString(R.string.connected)
            binding.tvDevice1Status.setTextColor(getColor(R.color.status_connected))

            binding.btnConnectDevice.text = if (useTagalog) getString(R.string.disconnect_tl) else getString(R.string.disconnect)
            binding.btnConnectDevice.isEnabled = true
            
            // Fix redundancy: Hide the extra disconnect button when connected, 
            // since the main action button now serves as 'Disconnect'
            binding.btnDisconnect.visibility = View.GONE 
        } else if (bluetoothManager.isConnecting) {
            binding.tvDeviceStatus.text = if (useTagalog) "KUMONEKTA..." else "CONNECTING..."
            binding.tvDeviceStatus.setTextColor(getColor(R.color.comu_primary))

            binding.tvScanningStatus.text = if (useTagalog) "Sinusubukang kumonekta..." else "Attempting to connect..."

            binding.btnConnectDevice.text = if (useTagalog) "NAGHIHINTAY..." else "PLEASE WAIT..."
            binding.btnConnectDevice.isEnabled = false
            binding.btnDisconnect.visibility = View.VISIBLE
        } else {
            binding.tvDeviceStatus.text = getString(R.string.disconnected)
            binding.tvDeviceStatus.setTextColor(getColor(R.color.status_disconnected))

            binding.tvScanningStatus.text = if (isScanning) {
                if (useTagalog) getString(R.string.scanning_status_tl) else getString(R.string.scanning_status)
            } else {
                if (useTagalog) getString(R.string.ready_to_scan_tl) else getString(R.string.ready_to_scan)
            }

            binding.tvDevice1Status.text = getString(R.string.device_ready)
            binding.tvDevice1Status.setTextColor(getColor(R.color.comu_primary))

            binding.btnConnectDevice.text = if (isScanning) {
                if (useTagalog) getString(R.string.stop_scanning_tl) else getString(R.string.stop_scanning)
            } else {
                if (useTagalog) getString(R.string.scan_for_devices_tl) else getString(R.string.scan_for_devices)
            }
            binding.btnConnectDevice.isEnabled = true
            binding.btnDisconnect.visibility = View.GONE
        }
    }

    private fun readScreen() {
        val connectionStatus = if (isConnected) {
            if (useTagalog) "Nakakonekta." else "Connected."
        } else {
            if (useTagalog) "Hindi nakakonekta." else "Disconnected."
        }

        speak(
            if (useTagalog) {
                "Device screen. $connectionStatus Mga utos: status o disconnect."
            } else {
                "Device screen. $connectionStatus Commands: status or disconnect."
            },
            force = true
        )

        returnToWakeWordAfterSpeech(3000L)
    }

    private fun speak(message: String, force: Boolean = false) {
        if (!ttsReady) return
        if (!voiceAlertEnabled && !force) return

        try {
            tts.stop()
            tts.language = if (useTagalog) Locale.forLanguageTag("fil-PH") else Locale.US
            tts.setSpeechRate(1.08f)
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "DEVICE_TTS")
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

        // Sync connection state from manager
        isConnected = bluetoothManager.isConnected
        updateUiState()

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
