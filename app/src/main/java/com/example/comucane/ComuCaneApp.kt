package com.example.comucane

import android.app.Application
import org.osmdroid.config.Configuration

class ComuCaneApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize osmdroid map configuration
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue =
            "ComuCaneAssistiveNavigation/1.0"

        // Clear any stale logs from previous session
        LogRepository.initialize(this)
    }
}