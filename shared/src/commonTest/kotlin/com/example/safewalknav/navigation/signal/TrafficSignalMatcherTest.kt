package com.example.safewalknav.navigation.signal

import kotlin.test.Test
import kotlin.test.assertEquals

class TrafficSignalMatcherTest {
    private val baseLat = 37.5666
    private val baseLon = 126.9784
    private val mPerLat = 1.0 / 110_540.0

    @Test
    fun prioritizesSignalNearCrosswalkOverSignalNearUser() {
        val signalNearUser = signal("near-user", 20.0)
        val signalNearCrosswalk = signal("near-crosswalk", 39.0)

        val selected = TrafficSignalMatcher.findBestSignalForCrosswalk(
            currentLat = baseLat,
            currentLon = baseLon,
            crosswalkLat = latOffset(40.0),
            crosswalkLon = baseLon,
            routeBearing = 0f,
            signals = listOf(signalNearUser, signalNearCrosswalk),
        )

        assertEquals("near-crosswalk", selected?.itstId)
    }

    @Test
    fun excludesSignalThatPointsAwayFromRouteBearing() {
        val behindUser = signal("behind-user", -5.0)
        val aheadAtCrosswalk = signal("ahead-crosswalk", 42.0)

        val selected = TrafficSignalMatcher.findBestSignalForCrosswalk(
            currentLat = baseLat,
            currentLon = baseLon,
            crosswalkLat = latOffset(40.0),
            crosswalkLon = baseLon,
            routeBearing = 0f,
            signals = listOf(behindUser, aheadAtCrosswalk),
        )

        assertEquals("ahead-crosswalk", selected?.itstId)
    }

    @Test
    fun fallsBackToNearbySignalWhenNoSignalIsRouteAligned() {
        val sideSignal = TrafficSignalLocation(
            itstId = "side-signal",
            lat = latOffset(40.0),
            lon = lonOffset(20.0),
        )

        val selected = TrafficSignalMatcher.findBestSignalForCrosswalk(
            currentLat = baseLat,
            currentLon = baseLon,
            crosswalkLat = latOffset(40.0),
            crosswalkLon = baseLon,
            routeBearing = 180f,
            signals = listOf(sideSignal),
        )

        assertEquals("side-signal", selected?.itstId)
    }

    private fun signal(id: String, northOffsetMeters: Double): TrafficSignalLocation {
        return TrafficSignalLocation(
            itstId = id,
            lat = latOffset(northOffsetMeters),
            lon = baseLon,
        )
    }

    private fun latOffset(northOffsetMeters: Double): Double {
        return baseLat + northOffsetMeters * mPerLat
    }

    private fun lonOffset(eastOffsetMeters: Double): Double {
        val metersPerLon = 88_800.0
        return baseLon + eastOffsetMeters / metersPerLon
    }
}
