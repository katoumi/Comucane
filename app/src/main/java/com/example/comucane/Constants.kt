package com.example.comucane

import java.util.Locale

object Constants {
    const val AUDIO_PERMISSION_REQUEST_CODE = 501
    const val LOCATION_PERMISSION_REQUEST_CODE = 502
    const val BLUETOOTH_PERMISSION_REQUEST_CODE = 503

    val LOCALE_TAGALOG = Locale.forLanguageTag("fil-PH")
    val LOCALE_ENGLISH = Locale.US

    val UNIFIED_VOICE_GRAMMAR = listOf(
        "hey cane", "hey kane", "hey cain", "hey kain", "hey came", "hey can",
        "okay cane", "ok cane", "hello cane", "hi cane", "comucane", "comu cane",
        "navigation", "start navigation", "navigate", "guide", "nabigasyon", "start guide",
        "device", "connect device", "scan", "scan device", "aparato", "disconnect",
        "logs", "obstacle logs", "records", "talaan", "latest", "latest log", "clear", "clear logs",
        "settings", "preferences", "mga setting", "save", "save settings",
        "emergency", "sos", "help", "tulong", "call now", "cancel",
        "status", "read", "read screen", "kalagayan", "basahin",
        "english", "tagalog", "filipino", "voice on", "voice off", "vibration on", "vibration off",
        "stop", "repeat", "gps", "location", "back", "go back", "home", "main menu", "return", "exit",
        // Conversational fillers and helper words
        "please", "could", "you", "can", "to", "open", "go", "show", "check", "me", "the", "start", "i", "want", "take"
    )
}
