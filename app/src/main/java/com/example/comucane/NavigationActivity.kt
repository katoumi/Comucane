package com.example.comucane

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import com.example.comucane.databinding.ActivityNavigationBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.util.MapTileIndex
import java.util.Locale
import kotlin.random.Random

class NavigationActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityNavigationBinding
    private lateinit var tts: TextToSpeech
    private lateinit var wakeWordManager: VoskWakeWordManager
    private lateinit var voiceManager: GlobalVoiceCommandManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var bluetoothManager: BluetoothManager

    private val handler = Handler(Looper.getMainLooper())

    private var latestLocation: Location? = null
    private var userMarker: Marker? = null
    private var hasCenteredMap = false

    private var isRunning = true
    private var isStoppingNavigation = false
    private var isLeavingScreen = false
    private var hasInitializedVoiceSystem = false
    private var hasSpokenIntro = false

    private var useTagalog = false
    private var voiceAlertEnabled = true
    private var vibrationAlertEnabled = true

    private var lastAlert = "Path ahead is safe. You may continue walking."
    private var currentStatus = "CLEAR"
    private var lastStatus = "CLEAR"
    private var lastBatteryLevel = -1
    private var lastSpokenDistance = -1
    private var lastSpokenTimeMs = 0L
    private var lastGlobalAlertTimeMs = 0L

    private var hapticPulseHandler = Handler(Looper.getMainLooper())
    private var hapticPulseRunnable: Runnable? = null
    private var lastPulseMs = 0L

    private val distanceBuffer = mutableListOf<Int>()
    private val bufferSize = 5

    private val detectionLoop = object : Runnable {
        override fun run() {
            if (isRunning && !isStoppingNavigation && !isFinishing && !isDestroyed) {
                // If bluetooth is not connected, use random data for testing
                if (!bluetoothManager.isConnected) {
                    processDistance(Random.nextInt(20, 180))
                }
                handler.postDelayed(this, 3000L)
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            if (isStoppingNavigation || isFinishing || isDestroyed) return

            val location = locationResult.lastLocation ?: return
            latestLocation = location
            showGpsActive()
            updateMapLocation(location)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        // Set a custom User-Agent to comply with OSM tile server policies and prevent "Access Blocked" errors.
        Configuration.getInstance().userAgentValue = "ComuCaneApp/1.0 (Android Navigation Tool)"

        binding = ActivityNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        loadPreferences()
        setupButtons()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)

        bluetoothManager = BluetoothManager.getInstance(this)
        setupBluetoothDataListener()

        startLocationTracking()
        handler.postDelayed(detectionLoop, 1000L)
    }

    private fun setupBluetoothDataListener() {
        bluetoothManager.onBatteryReceived = { battery ->
            handleBatteryUpdate(battery)
        }

        bluetoothManager.onDataReceived = { data ->
            try {
                Log.i("NavigationActivity", "Received from Hardware: $data")
                
                // Extract number from "DIST:100" OR handle raw "100"
                val distanceString = if (data.contains(":")) {
                    data.split(":")[1].trim()
                } else {
                    data.trim()
                }
                
                val distanceValue = distanceString.toIntOrNull()
                if (distanceValue != null) {
                    // Apply moving average filter
                    distanceBuffer.add(distanceValue)
                    if (distanceBuffer.size > bufferSize) {
                        distanceBuffer.removeAt(0)
                    }
                    
                    val averagedDistance = distanceBuffer.average().toInt()
                    processDistance(averagedDistance)
                }
            } catch (e: Exception) {
                Log.e("NavigationActivity", "Error parsing hardware data: ${e.message}")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        if (isStoppingNavigation || isFinishing || isDestroyed) return

        runOnUiThread {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH

            setupVoiceSystem()

            if (!hasSpokenIntro) {
                hasSpokenIntro = true

                speak(
                    if (useTagalog) {
                        "Navigation started. Sabihin ang Hey Cane para magbigay ng command."
                    } else {
                        "Navigation started. Say Hey Cane to give a command."
                    },
                    force = true
                )

                startWakeWordAfterDelay(3000L)
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
            onCommand = { command -> handleNavigationVoiceCommand(command) },
            onReadScreen = { readNavigationScreen() },
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
        if (isLeavingScreen || isStoppingNavigation || isFinishing || isDestroyed) return

        if (::voiceManager.isInitialized) {
            voiceManager.playAcknowledgment("Yes?")
        }
    }

    private fun returnToWakeWordMode() {
        if (isLeavingScreen || isStoppingNavigation || isFinishing || isDestroyed) return

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.notifyCommandModeFinished()
        }
    }

    private fun startWakeWordAfterDelay(delayMs: Long = 1000L) {
        if (!::wakeWordManager.isInitialized) return

        handler.postDelayed({
            if (
                !isLeavingScreen &&
                !isStoppingNavigation &&
                !isFinishing &&
                !isDestroyed &&
                ::wakeWordManager.isInitialized
            ) {
                wakeWordManager.start()
            }
        }, delayMs)
    }

    private fun setupMap() {
        val googleMaps = object : OnlineTileSourceBase(
            "GoogleMaps",
            1,
            20,
            256,
            ".png",
            arrayOf(
                "https://mt0.google.com/vt/lyrs=m&x=",
                "https://mt1.google.com/vt/lyrs=m&x=",
                "https://mt2.google.com/vt/lyrs=m&x=",
                "https://mt3.google.com/vt/lyrs=m&x="
            ),
            "© Google"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return "$baseUrl${MapTileIndex.getX(pMapTileIndex)}&y=${MapTileIndex.getY(pMapTileIndex)}&z=${MapTileIndex.getZoom(pMapTileIndex)}"
            }
        }

        binding.osmMapView.setTileSource(googleMaps)
        binding.osmMapView.setMultiTouchControls(false)
        binding.osmMapView.minZoomLevel = 4.0
        binding.osmMapView.maxZoomLevel = 20.0
        binding.osmMapView.controller.setZoom(17.0)

        val defaultPoint = GeoPoint(14.5995, 120.9842)
        binding.osmMapView.controller.setCenter(defaultPoint)
        binding.osmMapView.invalidate()
    }

    private fun setupButtons() {
        binding.btnRepeatAlert.setOnClickListener {
            if (isStoppingNavigation) return@setOnClickListener

            binding.voiceBottomBar.tvVoiceHint.text = "Repeating latest alert."
            speak(lastAlert, force = true)
            returnToWakeWordAfterSpeech(2200L)
        }

        binding.voiceBottomBar.btnHoldSpeak.setOnClickListener {
            if (isStoppingNavigation) return@setOnClickListener

            binding.voiceBottomBar.tvVoiceHint.text = "Repeating latest alert."
            speak(lastAlert, force = true)
            returnToWakeWordAfterSpeech(2200L)
        }

        binding.voiceBottomBar.btnHoldSpeak.setOnLongClickListener {
            if (isStoppingNavigation) return@setOnLongClickListener true

            onHeyCaneDetected()
            true
        }

        binding.btnStopNavigation.setOnClickListener {
            stopNavigation()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)

        useTagalog = prefs.getBoolean(SettingsActivity.KEY_USE_TAGALOG, false)
        voiceAlertEnabled = prefs.getBoolean(SettingsActivity.KEY_VOICE_ALERT, true)
        vibrationAlertEnabled = prefs.getBoolean(SettingsActivity.KEY_VIBRATION_ALERT, true)

        if (::tts.isInitialized) {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
        }

        if (::binding.isInitialized) {
            // binding.chipVoice.alpha = if (voiceAlertEnabled) 1f else 0.3f
            // binding.chipVibration.alpha = if (vibrationAlertEnabled) 1f else 0.3f
        }
    }

    private fun handleNavigationVoiceCommand(command: String) {
        if (isStoppingNavigation) return

        val cmd = normalize(command)
        if (cmd.isBlank()) return

        when {
            containsAny(cmd, listOf("stop", "top", "stop navigation", "stop navigating", "end", "cancel", "exit", "hinto", "tigilan", "tumigil")) -> {
                binding.voiceBottomBar.tvVoiceHint.text = "Stopping navigation."
                stopNavigation()
            }

            containsAny(cmd, listOf("repeat", "repeat alert", "again", "say again", "ulit")) -> {
                binding.voiceBottomBar.tvVoiceHint.text = "Repeating latest alert."
                speak(lastAlert, force = true)
                returnToWakeWordAfterSpeech(2200L)
            }

            containsAny(cmd, listOf("status", "ahead", "what is ahead", "whats ahead", "distance", "current status", "kalagayan")) -> {
                binding.voiceBottomBar.tvVoiceHint.text = "Current status: $currentStatus"
                speak(lastAlert, force = true)
                returnToWakeWordAfterSpeech(2600L)
            }

            containsAny(cmd, listOf("gps", "location", "where am i", "current location")) -> {
                val location = latestLocation

                if (location != null) {
                    speak(
                        "GPS active. Latitude ${"%.5f".format(location.latitude)}, longitude ${"%.5f".format(location.longitude)}.",
                        force = true
                    )
                } else {
                    speak(
                        "GPS is still searching. Move near a window or enable precise location.",
                        force = true
                    )
                }

                returnToWakeWordAfterSpeech(3000L)
            }

            containsAny(cmd, listOf("read", "read screen", "read this screen", "basahin")) -> {
                readNavigationScreen()
            }

            containsAny(cmd, listOf("english", "switch to english", "change to english")) -> {
                useTagalog = false
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit(commit = false) {
                        putBoolean(SettingsActivity.KEY_USE_TAGALOG, false)
                    }

            tts.language = Constants.LOCALE_ENGLISH
            loadPreferences()
                binding.voiceBottomBar.tvVoiceHint.text = "Language switched to English."
                returnToWakeWordAfterSpeech(1200L)
            }

            containsAny(cmd, listOf("tagalog", "filipino", "switch to tagalog", "change to tagalog")) -> {
                useTagalog = true
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit(commit = false) {
                        putBoolean(SettingsActivity.KEY_USE_TAGALOG, true)
                    }

            tts.language = Constants.LOCALE_TAGALOG
            loadPreferences()
                binding.voiceBottomBar.tvVoiceHint.text = "Language changed to Tagalog."
                returnToWakeWordAfterSpeech(1200L)
            }

            containsAny(cmd, listOf("back", "go back", "home", "main menu")) -> {
                stopNavigation()
            }

            containsAny(cmd, listOf("navigation", "start navigation", "navigate")) -> {
                speak(
                    if (useTagalog) "Nakaandar na ang navigation." else "Navigation is already running.",
                    force = true
                )
                returnToWakeWordAfterSpeech(2200L)
            }

            else -> {
                speak(
                    if (useTagalog) {
                        "Hindi ko naintindihan. Sabihin ang status, repeat, GPS, o stop."
                    } else {
                        "I did not understand. Say status, repeat, GPS, or stop."
                    },
                    force = true
                )

                returnToWakeWordAfterSpeech(3000L)
            }
        }
    }

    private fun readNavigationScreen() {
        if (isStoppingNavigation) return

        val gpsText = latestLocation?.let {
            "GPS active."
        } ?: "GPS searching."

        val message = if (useTagalog) {
            "Navigation screen. Kasalukuyang status: $currentStatus. $gpsText $lastAlert"
        } else {
            "Navigation screen. Current status: $currentStatus. $gpsText $lastAlert"
        }

        speak(message, force = true)
        returnToWakeWordAfterSpeech(3800L)
    }

    private fun returnToWakeWordAfterSpeech(delayMs: Long) {
        handler.postDelayed({
            returnToWakeWordMode()
        }, delayMs)
    }

    private fun processDistance(distance: Int) {
        if (isStoppingNavigation || isFinishing || isDestroyed) return

        updateHapticProximity(distance)

        // Hysteresis Implementation (10cm buffers)
        val currentZone = currentStatus
        
        when {
            // STOP ZONE: Enter at 45, exit at 55
            distance <= 45 || (currentZone == "STOP" && distance < 55) -> {
                showDanger(distance)
            }
            // CAUTION ZONE: Enter at 90, exit at 100
            distance <= 90 || (currentZone == "CAUTION" && distance < 100) -> {
                showWarning(distance)
            }
            // SAFE ZONE
            else -> {
                showSafe()
            }
        }
    }

    private fun updateHapticProximity(distance: Int) {
        if (isStoppingNavigation || !vibrationAlertEnabled) {
            stopHapticPulse()
            return
        }

        when {
            distance <= 45 -> {
                // Continuous hard vibration
                startHapticPulse(0) 
            }
            distance <= 60 -> {
                // Fast pulse
                startHapticPulse(300)
            }
            distance <= 90 -> {
                // Slow pulse
                startHapticPulse(800)
            }
            else -> {
                stopHapticPulse()
            }
        }
    }

    private fun startHapticPulse(intervalMs: Int) {
        if (hapticPulseRunnable != null && intervalMs == 0 && lastPulseMs == 0L) return // Already continuous
        
        stopHapticPulse()

        if (intervalMs == 0) {
            // Continuous
            vibrate(5000L) // Long duration, will be stopped by next update or stopHapticPulse
            lastPulseMs = 0L
            return
        }

        hapticPulseRunnable = object : Runnable {
            override fun run() {
                if (isStoppingNavigation || !vibrationAlertEnabled) return
                vibrate(120L) // Short distinct pulse
                hapticPulseHandler.postDelayed(this, intervalMs.toLong())
            }
        }
        hapticPulseHandler.post(hapticPulseRunnable!!)
        lastPulseMs = intervalMs.toLong()
    }

    private fun stopHapticPulse() {
        hapticPulseRunnable?.let { hapticPulseHandler.removeCallbacks(it) }
        hapticPulseRunnable = null
        lastPulseMs = -1L
        
        // Cancel any ongoing vibration
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.cancel()
    }

    private fun showSafe() {
        if (isStoppingNavigation || isFinishing || isDestroyed) return

        currentStatus = "CLEAR"

        binding.tvNavStatus.text = getString(R.string.clear)
        binding.tvNavStatus.setTextColor(getColor(R.color.brand_status_green))
        binding.tvDistance.text = getString(R.string.path_safe)

        lastAlert = if (useTagalog) {
            "Ligtas ang daanan. Maaari kang magpatuloy."
        } else {
            "Path ahead is safe. You may continue walking."
        }

        binding.voiceBottomBar.tvVoiceCommands.text =
            if (useTagalog) {
                "Sabihin: Hey Cane, then status, repeat, GPS, o stop."
            } else {
                "Say: Hey Cane, then status, repeat, GPS, or stop."
            }

        binding.voiceBottomBar.tvVoiceHint.text =
            if (useTagalog) "Waiting for: Hey Cane" else "Waiting for: Hey Cane"

        lastStatus = "CLEAR"
    }

    private fun showWarning(distance: Int) {
        if (isStoppingNavigation || isFinishing || isDestroyed) return

        currentStatus = "CAUTION"

        binding.tvNavStatus.text = "Caution"
        binding.tvNavStatus.setTextColor(getColor(R.color.comu_warning))
        binding.tvDistance.text = getString(R.string.distance_cm, distance)
        binding.voiceBottomBar.tvVoiceCommands.text = "Something is nearby. Slow down."
        binding.voiceBottomBar.tvVoiceHint.text = "Caution detected."

        lastAlert = if (useTagalog) {
            "May bagay na malapit. $distance sentimetro ang layo. Mag-ingat."
        } else {
            "Object nearby. $distance centimeters ahead. Slow down."
        }

        recordObstacleLog("CAUTION", distance)

        if (shouldSpeakAlert("CAUTION", distance)) {
            speak(lastAlert)
            lastSpokenDistance = distance
            lastSpokenTimeMs = System.currentTimeMillis()
        }

        lastStatus = "CAUTION"
    }

    private fun showDanger(distance: Int) {
        if (isStoppingNavigation || isFinishing || isDestroyed) return

        currentStatus = "STOP"

        binding.tvNavStatus.text = "Stop"
        binding.tvNavStatus.setTextColor(getColor(R.color.comu_danger))
        binding.tvDistance.text = getString(R.string.distance_cm, distance)
        binding.voiceBottomBar.tvVoiceCommands.text = "Obstacle very close. Stop or change direction."
        binding.voiceBottomBar.tvVoiceHint.text = "Immediate attention needed."

        lastAlert = if (useTagalog) {
            "May harang sa unahan. $distance sentimetro ang layo. Huminto o magpalit ng direksyon."
        } else {
            "Obstacle very close. $distance centimeters ahead. Stop or change direction."
        }

        recordObstacleLog("STOP", distance)

        if (shouldSpeakAlert("STOP", distance)) {
            speak(lastAlert)
            lastSpokenDistance = distance
            lastSpokenTimeMs = System.currentTimeMillis()
        }

        lastStatus = "STOP"
    }

    private fun shouldSpeakAlert(status: String, distance: Int): Boolean {
        val now = System.currentTimeMillis()
        
        // 1. STOP alerts always bypass the 12s cooldown but have a small 2s global safety gap
        if (status == "STOP") {
            if (lastStatus != "STOP") return true // New emergency
            return (now - lastSpokenTimeMs) > 3000L // Don't scream "STOP" faster than every 3 seconds
        }

        // 2. Status changed (e.g. CLEAR -> CAUTION)
        if (lastStatus != status) {
            // Global cooldown: don't allow any non-STOP alert more than once every 5 seconds
            if (now - lastGlobalAlertTimeMs < 5000L) return false
            lastGlobalAlertTimeMs = now
            return true
        }

        val timeSinceLastAlert = now - lastSpokenTimeMs
        
        // 3. Cooldown for same status: 12 seconds
        if (timeSinceLastAlert < 12000L) {
            // 4. Exception: Speak if distance changed significantly (more than 30cm)
            val distanceDelta = Math.abs(distance - lastSpokenDistance)
            if (distanceDelta < 30) {
                return false
            }
        }
        
        lastGlobalAlertTimeMs = now
        return true
    }

    private fun recordObstacleLog(status: String, distance: Int) {
        if (isStoppingNavigation || isFinishing || isDestroyed) return

        val location = latestLocation

        LogRepository.addLog(
            ObstacleLog(
                status = status,
                distanceCm = distance,
                latitude = location?.latitude,
                longitude = location?.longitude
            )
        )

        if (location != null) {
            addObstacleMarkerToMap(status, distance, location)
        }
    }

    private fun addObstacleMarkerToMap(status: String, distance: Int, location: Location) {
        if (isStoppingNavigation || isFinishing || isDestroyed) return

        val obstaclePoint = GeoPoint(location.latitude, location.longitude)

        val marker = Marker(binding.osmMapView).apply {
            position = obstaclePoint
            title = "$status obstacle"
            subDescription = "$distance cm ahead"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }

        binding.osmMapView.overlays.add(marker)
        binding.osmMapView.invalidate()
    }

    private fun stopNavigation() {
        if (isStoppingNavigation) return

        isStoppingNavigation = true
        isLeavingScreen = true
        isRunning = false

        GlobalVoiceCommandManager.blockGlobalListeningFor(5000L)

        handler.removeCallbacksAndMessages(null)
        stopLocationTracking()

        try {
            if (::wakeWordManager.isInitialized) {
                wakeWordManager.destroy()
            }
        } catch (_: Exception) {
        }

        try {
            if (::voiceManager.isInitialized) {
                voiceManager.destroy()
            }
        } catch (_: Exception) {
        }

        try {
            if (::tts.isInitialized) {
                tts.stop()
            }
        } catch (_: Exception) {
        }

        try {
            binding.voiceBottomBar.tvVoiceHint.text = "Navigation stopped."
            binding.voiceBottomBar.tvVoiceCommands.text = "Returning to previous screen."
        } catch (_: Exception) {
        }

        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }, 350L)
    }

    private fun startLocationTracking() {
        showGpsSearching()

        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L
        )
            .setMinUpdateIntervalMillis(1500L)
            .setMaxUpdateDelayMillis(5000L)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).addOnSuccessListener { location ->
                if (isStoppingNavigation || isFinishing || isDestroyed) return@addOnSuccessListener

                if (location != null) {
                    latestLocation = location
                    showGpsActive()
                    updateMapLocation(location)
                } else {
                    requestLastKnownLocation()
                }
            }.addOnFailureListener {
                requestLastKnownLocation()
            }

            handler.postDelayed({
                if (!isStoppingNavigation && !isFinishing && !isDestroyed && latestLocation == null) {
                    binding.voiceBottomBar.tvVoiceHint.text =
                        "GPS is still searching. Enable precise location or move near a window."
                    showGpsSearching()
                }
            }, 8000L)

        } catch (_: SecurityException) {
            showGpsOff()
            binding.voiceBottomBar.tvVoiceHint.text =
                "GPS permission denied. Enable location permission for ComuCane."
        }
    }

    private fun requestLastKnownLocation() {
        if (!hasLocationPermission()) {
            showGpsOff()
            return
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastKnown ->
                if (isStoppingNavigation || isFinishing || isDestroyed) return@addOnSuccessListener

                if (lastKnown != null) {
                    latestLocation = lastKnown
                    showGpsActive()
                    updateMapLocation(lastKnown)
                } else {
                    showGpsSearching()
                    binding.voiceBottomBar.tvVoiceHint.text =
                        "GPS searching. Turn on precise location or test outdoors."
                }
            }.addOnFailureListener {
                showGpsOff()
                binding.voiceBottomBar.tvVoiceHint.text =
                    "GPS failed. Check location permission and device location settings."
            }
        } catch (_: SecurityException) {
            showGpsOff()
        }
    }

    private fun stopLocationTracking() {
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    private fun showGpsSearching() {
        if (!::binding.isInitialized) return

        binding.tvGpsStatus.text = "GPS SEARCHING"
        binding.tvGpsStatus.setTextColor(getColor(R.color.comu_warning))
    }

    private fun showGpsActive() {
        if (!::binding.isInitialized) return

        binding.tvGpsStatus.text = "GPS ACTIVE"
        binding.tvGpsStatus.setTextColor(getColor(R.color.comu_primary))
    }

    private fun showGpsOff() {
        if (!::binding.isInitialized) return

        binding.tvGpsStatus.text = "GPS OFF"
        binding.tvGpsStatus.setTextColor(getColor(R.color.comu_danger))
    }

    private fun handleBatteryUpdate(level: Int) {
        if (isStoppingNavigation || isFinishing || isDestroyed) return
        
        binding.tvBattery.text = getString(R.string.battery_status, level)
        
        // Critical alerts
        if (level <= 10 && lastBatteryLevel > 10) {
            speak(if (useTagalog) "Panganib. Masyadong mababa ang baterya ng cane." else "Critical. Cane battery is extremely low. Please charge immediately.", force = true)
        } else if (level <= 20 && lastBatteryLevel > 20) {
            speak(if (useTagalog) "Mababa ang baterya ng cane." else "Cane battery is low. Please charge soon.", force = true)
        }
        
        lastBatteryLevel = level
    }

    private fun updateMapLocation(location: Location) {
        if (isStoppingNavigation || isFinishing || isDestroyed) return

        val point = GeoPoint(location.latitude, location.longitude)

        if (userMarker == null) {
            userMarker = Marker(binding.osmMapView).apply {
                position = point
                title = "You are here"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.osmMapView.overlays.add(userMarker)
        } else {
            userMarker?.position = point
        }

        if (!hasCenteredMap) {
            binding.osmMapView.controller.setZoom(17.5)
            binding.osmMapView.controller.setCenter(point)
            hasCenteredMap = true
        } else {
            binding.osmMapView.controller.animateTo(point)
        }

        binding.osmMapView.invalidate()
    }

    private fun speak(message: String, force: Boolean = false) {
        if (isStoppingNavigation) return
        if (!voiceAlertEnabled && !force) return
        if (!::tts.isInitialized) return

        try {
            tts.stop()
            tts.language = if (useTagalog) Locale.forLanguageTag("fil-PH") else Locale.US
            tts.setSpeechRate(1.05f)
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "NAVIGATION_TTS")
        } catch (_: Exception) {
        }
    }

    private fun vibrate(duration: Long) {
        if (isStoppingNavigation) return
        if (!vibrationAlertEnabled) return

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    duration,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
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
        return words.any { word -> command.contains(word) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (isStoppingNavigation || isFinishing || isDestroyed) return

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                val granted = grantResults.isNotEmpty() &&
                        grantResults.any { it == PackageManager.PERMISSION_GRANTED }

                if (granted) {
                    binding.voiceBottomBar.tvVoiceHint.text = "GPS permission granted. Starting GPS."
                    startLocationTracking()
                } else {
                    showGpsOff()
                    binding.voiceBottomBar.tvVoiceHint.text =
                        "GPS permission denied. Logs will save without location."
                }
            }

            AUDIO_PERMISSION_REQUEST_CODE -> {
                val granted = grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    if (::wakeWordManager.isInitialized) {
                        wakeWordManager.resume()
                    }
                } else {
                    binding.voiceBottomBar.tvVoiceHint.text =
                        "Microphone permission is required for voice commands."
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (isStoppingNavigation || isFinishing || isDestroyed) return

        isLeavingScreen = false
        binding.osmMapView.onResume()
        loadPreferences()

        if (::tts.isInitialized) {
            tts.language = if (useTagalog) Constants.LOCALE_TAGALOG else Constants.LOCALE_ENGLISH
        }

        if (::wakeWordManager.isInitialized) {
            wakeWordManager.resume()
        }
    }

    override fun onPause() {
        stopHapticPulse()
        if (::wakeWordManager.isInitialized) {
            wakeWordManager.pause()
        }

        if (::voiceManager.isInitialized) {
            voiceManager.pause()
        }

        if (::binding.isInitialized) {
            binding.osmMapView.onPause()
        }

        super.onPause()
    }

    override fun onDestroy() {
        isStoppingNavigation = true
        isLeavingScreen = true
        isRunning = false

        handler.removeCallbacksAndMessages(null)
        stopLocationTracking()

        bluetoothManager.onDataReceived = null

        try {
            if (::wakeWordManager.isInitialized) {
                wakeWordManager.destroy()
            }
        } catch (_: Exception) {
        }

        try {
            if (::voiceManager.isInitialized) {
                voiceManager.destroy()
            }
        } catch (_: Exception) {
        }

        try {
            if (::tts.isInitialized) {
                tts.stop()
                tts.shutdown()
            }
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    companion object {
        private const val AUDIO_PERMISSION_REQUEST_CODE = Constants.AUDIO_PERMISSION_REQUEST_CODE
        private const val LOCATION_PERMISSION_REQUEST_CODE = Constants.LOCATION_PERMISSION_REQUEST_CODE
    }
}