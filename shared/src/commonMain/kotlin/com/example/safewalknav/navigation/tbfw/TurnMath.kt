package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.geo.bearing
import com.example.safewalknav.navigation.tmap.LatLng
import kotlin.math.abs

data class TurnComputation(
    val direction: TurnDirection,
    val incomingBearing: Double,
    val outgoingBearing: Double,
    val delta: Double,
)

fun normalizedAngle(angle: Double): Double {
    var result = ((angle + 540.0) % 360.0) - 180.0
    if (result == -180.0) {
        result = 180.0
    }
    return result
}

fun turnDirection(
    prev: LatLng,
    current: LatLng,
    next: LatLng,
    straightThreshold: Double = 25.0,
    uturnThreshold: Double = 150.0,
): TurnDirection {
    return computeTurn(prev, current, next, straightThreshold, uturnThreshold).direction
}

fun computeTurn(
    prev: LatLng,
    current: LatLng,
    next: LatLng,
    straightThreshold: Double = 25.0,
    uturnThreshold: Double = 150.0,
    log: Boolean = true,
): TurnComputation {
    val incomingBearing = bearing(prev.lat, prev.lon, current.lat, current.lon).toDouble()
    val outgoingBearing = bearing(current.lat, current.lon, next.lat, next.lon).toDouble()
    val delta = normalizedAngle(outgoingBearing - incomingBearing)

    if (log) {
        println("[TURN-DEBUG] prev=${prev.lat},${prev.lon}")
        println("[TURN-DEBUG] current=${current.lat},${current.lon}")
        println("[TURN-DEBUG] next=${next.lat},${next.lon}")
        println("[TURN-DEBUG] incomingBearing=$incomingBearing")
        println("[TURN-DEBUG] outgoingBearing=$outgoingBearing")
        println("[TURN-DEBUG] delta=$delta")
    }

    val direction = when {
        abs(delta) < straightThreshold -> TurnDirection.STRAIGHT
        abs(delta) >= uturnThreshold -> TurnDirection.UTURN
        delta > 0.0 -> TurnDirection.RIGHT
        else -> TurnDirection.LEFT
    }

    if (log) {
        println("[TURN-DEBUG] result=${direction.name}")
    }

    return TurnComputation(
        direction = direction,
        incomingBearing = incomingBearing,
        outgoingBearing = outgoingBearing,
        delta = delta,
    )
}

const val shouldInvertTurnDirectionForTest: Boolean = false

fun maybeInvert(direction: TurnDirection): TurnDirection {
    if (!shouldInvertTurnDirectionForTest) return direction
    return when (direction) {
        TurnDirection.LEFT -> TurnDirection.RIGHT
        TurnDirection.RIGHT -> TurnDirection.LEFT
        else -> direction
    }
}
