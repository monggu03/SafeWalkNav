package com.example.safewalknav.navigation.signal

import com.example.safewalknav.navigation.geo.angleDiff
import com.example.safewalknav.navigation.geo.bearing
import com.example.safewalknav.navigation.signal.TrafficSignalLocation
import com.example.safewalknav.navigation.geo.distanceBetween
import kotlin.math.abs

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

    fun findBestSignalForCrosswalk(
        currentLat: Double,
        currentLon: Double,
        crosswalkLat: Double,
        crosswalkLon: Double,
        routeBearing: Float,
        signals: List<TrafficSignalLocation>,
        crosswalkRadiusMeters: Float = 50f,
        currentRadiusMeters: Float = 80f,
        maxBearingDiffDegrees: Float = 90f,
    ): TrafficSignalLocation? {
        return signals
            .mapNotNull { signal ->
                val crosswalkDistance = distanceBetween(
                    crosswalkLat,
                    crosswalkLon,
                    signal.lat,
                    signal.lon,
                )
                if (crosswalkDistance > crosswalkRadiusMeters) return@mapNotNull null

                val currentDistance = distanceBetween(
                    currentLat,
                    currentLon,
                    signal.lat,
                    signal.lon,
                )
                if (currentDistance > currentRadiusMeters) return@mapNotNull null

                val signalBearing = bearing(currentLat, currentLon, signal.lat, signal.lon)
                val bearingDiff = abs(angleDiff(signalBearing, routeBearing))

                SignalCandidate(
                    signal = signal,
                    crosswalkDistance = crosswalkDistance,
                    currentDistance = currentDistance,
                    bearingDiff = bearingDiff,
                    isRouteAligned = bearingDiff <= maxBearingDiffDegrees,
                )
            }
            .minWithOrNull(
                compareBy<SignalCandidate> { if (it.isRouteAligned) 0 else 1 }
                    .thenBy { it.crosswalkDistance }
                    .thenBy { it.bearingDiff }
                    .thenBy { it.currentDistance }
            )
            ?.signal
    }

    private data class SignalCandidate(
        val signal: TrafficSignalLocation,
        val crosswalkDistance: Float,
        val currentDistance: Float,
        val bearingDiff: Float,
        val isRouteAligned: Boolean,
    )
}
