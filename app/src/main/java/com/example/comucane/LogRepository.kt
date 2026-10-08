package com.example.comucane

import android.content.Context
import kotlin.math.*

object LogRepository {

    private val _logs = mutableListOf<ObstacleLog>()

    // Read-only view for LogsActivity
    val logs: List<ObstacleLog> get() = _logs.toList()

    /**
     * Called once from ComuCaneApp.onCreate().
     * Clears any stale in-memory data from a previous session.
     * When you upgrade to Room DB, pass context here to build the database.
     */
    fun initialize(context: Context) {
        _logs.clear()
    }

    fun addLog(log: ObstacleLog) {
        _logs.add(0, log) // newest first
    }

    fun clearLogs() {
        _logs.clear()
    }

    /**
     * Returns true if any previously recorded obstacle is within [radiusMeters]
     * of the given coordinates. Used by NavigationActivity to warn the user
     * they are approaching a known hazard spot.
     */
    fun hasNearbyKnownObstacle(
        lat: Double,
        lon: Double,
        radiusMeters: Double = 10.0
    ): Boolean {
        return _logs.any { log ->
            if (log.latitude == null || log.longitude == null) return@any false
            haversineDistance(lat, lon, log.latitude, log.longitude) <= radiusMeters
        }
    }

    /**
     * Returns the closest known obstacle log to the given position, or null if none.
     */
    fun closestKnownObstacle(lat: Double, lon: Double): ObstacleLog? {
        return _logs
            .filter { it.latitude != null && it.longitude != null }
            .minByOrNull { haversineDistance(lat, lon, it.latitude!!, it.longitude!!) }
    }

    // Haversine formula — distance in metres between two GPS coordinates
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in metres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}