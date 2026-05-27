package com.example.safewalknav.navigation.walking

import com.example.safewalknav.navigation.tmap.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrosswalkGuardTest {

    private val baseLat = 37.5666
    private val baseLon = 126.9784
    private val mPerLat = 1.0 / 110_540.0

    @Test
    fun detectsNearbyCrosswalkWhenWaypointIndexHasMovedPastIt() {
        val waypoints = listOf(
            waypoint(0.0, "TURN", 11),
            waypoint(40.0, "CROSSWALK", 211),
            waypoint(90.0, "TURN", 12),
            waypoint(140.0, "TURN", 13)
        )

        val info = findCrosswalkZoneInfo(
            currentLat = baseLat + 42.0 * mPerLat,
            currentLon = baseLon,
            waypoints = waypoints,
            currentWaypointIndex = 3
        )

        assertTrue(info.isInZone)
        assertEquals(CrosswalkZoneState.NEARBY, info.state)
        assertEquals(1, info.crosswalkIndex)
    }

    @Test
    fun reportsNearestCrosswalkDistanceEvenOutsideZone() {
        val waypoints = listOf(
            waypoint(0.0, "TURN", 11),
            waypoint(100.0, "CROSSWALK", 211)
        )

        val info = findCrosswalkZoneInfo(
            currentLat = baseLat,
            currentLon = baseLon,
            waypoints = waypoints,
            currentWaypointIndex = 0
        )

        assertEquals(CrosswalkZoneState.NONE, info.state)
        assertEquals(1, info.crosswalkIndex)
        assertTrue((info.distanceMeters ?: 0f) > 50f)
    }

    private fun waypoint(offsetMeters: Double, pointType: String, turnType: Int): Waypoint {
        return Waypoint(
            lat = baseLat + offsetMeters * mPerLat,
            lon = baseLon,
            turnType = turnType,
            description = pointType,
            distance = offsetMeters.toInt(),
            roadType = 0,
            pointType = pointType
        )
    }
}
