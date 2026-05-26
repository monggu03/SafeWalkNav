package com.example.safewalknav.navigation.walking

import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.tmap.Waypoint

enum class CrosswalkZoneState {
    NONE,
    APPROACHING,
    PASSED,
    NEARBY
}

data class CrosswalkZoneInfo(
    val state: CrosswalkZoneState,
    val crosswalkIndex: Int? = null,
    val distanceMeters: Float? = null
) {
    val isInZone: Boolean
        get() = state != CrosswalkZoneState.NONE
}

fun isCrosswalkWaypoint(wp: Waypoint): Boolean {
    return wp.pointType == "CROSSWALK" || wp.turnType in 211..217
}

fun isOnCrosswalkSegment(
    currentLat: Double,
    currentLon: Double,
    waypoints: List<Waypoint>,
    currentWaypointIndex: Int
): Boolean {
    return findCrosswalkZoneInfo(
        currentLat = currentLat,
        currentLon = currentLon,
        waypoints = waypoints,
        currentWaypointIndex = currentWaypointIndex
    ).isInZone
}

fun findCrosswalkZoneInfo(
    currentLat: Double,
    currentLon: Double,
    waypoints: List<Waypoint>,
    currentWaypointIndex: Int
): CrosswalkZoneInfo {
    if (waypoints.isEmpty()) return CrosswalkZoneInfo(CrosswalkZoneState.NONE)

    val lookAheadEnd = minOf(currentWaypointIndex + LOOK_AHEAD, waypoints.size)
    for (i in currentWaypointIndex until lookAheadEnd) {
        val wp = waypoints[i]
        if (isCrosswalkWaypoint(wp)) {
            val dist = distanceBetween(currentLat, currentLon, wp.lat, wp.lon)
            if (dist <= APPROACHING_RADIUS_M) {
                return CrosswalkZoneInfo(
                    state = CrosswalkZoneState.APPROACHING,
                    crosswalkIndex = i,
                    distanceMeters = dist
                )
            }
            break
        }
    }

    val backStart = maxOf(0, currentWaypointIndex - LOOK_BACK)
    for (i in (currentWaypointIndex - 1) downTo backStart) {
        val wp = waypoints[i]
        if (isCrosswalkWaypoint(wp)) {
            val dist = distanceBetween(currentLat, currentLon, wp.lat, wp.lon)
            if (dist <= PASSED_RADIUS_M) {
                return CrosswalkZoneInfo(
                    state = if (i == currentWaypointIndex - 1) {
                        CrosswalkZoneState.PASSED
                    } else {
                        CrosswalkZoneState.NEARBY
                    },
                    crosswalkIndex = i,
                    distanceMeters = dist
                )
            }
            break
        }
    }

    var nearestIndex: Int? = null
    var nearestDistance = Float.MAX_VALUE
    waypoints.forEachIndexed { index, waypoint ->
        if (isCrosswalkWaypoint(waypoint)) {
            val distance = distanceBetween(currentLat, currentLon, waypoint.lat, waypoint.lon)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = index
            }
        }
    }

    if (nearestIndex != null && nearestDistance <= NEARBY_RADIUS_M) {
        return CrosswalkZoneInfo(
            state = CrosswalkZoneState.NEARBY,
            crosswalkIndex = nearestIndex,
            distanceMeters = nearestDistance
        )
    }

    return CrosswalkZoneInfo(
        state = CrosswalkZoneState.NONE,
        crosswalkIndex = nearestIndex,
        distanceMeters = nearestDistance.takeIf { it != Float.MAX_VALUE }
    )
}

private const val APPROACHING_RADIUS_M = 50f
private const val PASSED_RADIUS_M = 30f
private const val NEARBY_RADIUS_M = 50f
private const val LOOK_AHEAD = 5
private const val LOOK_BACK = 3
