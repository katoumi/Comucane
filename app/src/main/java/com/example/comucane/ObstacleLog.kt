package com.example.comucane

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ObstacleLog(
    val status: String,           // "CAUTION" or "STOP"
    val distanceCm: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("MMM d, yyyy  hh:mm:ss a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formattedLocation(): String {
        return if (latitude != null && longitude != null) {
            "GPS: %.6f, %.6f".format(latitude, longitude)
        } else {
            "GPS: No signal"
        }
    }
}
