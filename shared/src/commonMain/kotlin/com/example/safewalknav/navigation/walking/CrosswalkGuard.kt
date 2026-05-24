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

    if (currentWaypointIndex < waypoints.size) {
        val next = waypoints[currentWaypointIndex]
        if (isCrosswalkWaypoint(next)) {
            val dist = distanceBetween(currentLat, currentLon, next.lat, next.lon)
            if (dist <= APPROACHING_RADIUS_M) {
                return CrosswalkZoneInfo(
                    state = CrosswalkZoneState.APPROACHING,
                    crosswalkIndex = currentWaypointIndex,
                    distanceMeters = dist
                )
            }
        }
    }

    val prevIdx = currentWaypointIndex - 1
    if (prevIdx in waypoints.indices) {
        val prev = waypoints[prevIdx]
        if (isCrosswalkWaypoint(prev)) {
            val dist = distanceBetween(currentLat, currentLon, prev.lat, prev.lon)
            if (dist <= PASSED_RADIUS_M) {
                return CrosswalkZoneInfo(
                    state = CrosswalkZoneState.PASSED,
                    crosswalkIndex = prevIdx,
                    distanceMeters = dist
                )
            }
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

    if (nearestIndex != null && nearestDistance <= APPROACHING_RADIUS_M) {
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
