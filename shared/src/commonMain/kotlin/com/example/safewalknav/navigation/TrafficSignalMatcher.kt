package com.example.safewalknav.navigation

object TrafficSignalMatcher {
    fun findNearestSignal(
        currentLat: Double,
        currentLon: Double,
        signals: List<TrafficSignalLocation>,
        radiusMeters: Float = 30f
    ): TrafficSignalLocation? {
        return signals
            .map { signal ->
                signal to distanceBetween(
                    currentLat,
                    currentLon,
                    signal.lat,
                    signal.lon
                )
            }
            .filter { (_, distance) -> distance <= radiusMeters }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }
}