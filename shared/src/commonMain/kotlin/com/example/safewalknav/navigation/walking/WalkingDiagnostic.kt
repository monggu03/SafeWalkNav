package com.example.safewalknav.navigation.walking

/**
 * Compares the user's current heading with the target bearing and diagnoses
 * whether the walking direction is leaning left, right, or straight.
 */
class WalkingDiagnostic {

    private val leanThreshold = NavigationConstants.LEAN_THRESHOLD
    private val maxHistory = NavigationConstants.MAX_DIAGNOSTIC_HISTORY

    private val azimuthHistory = mutableListOf<Float>()

    fun analyzeLeanStatus(currentAzimuth: Float, targetBearing: Float): LeanStatus {
        updateHistory(currentAzimuth)

        val averageAzimuth = azimuthHistory.takeLast(10).average().toFloat()
        val angleDiff = calculateAngleDiff(averageAzimuth, targetBearing)

        return when {
            angleDiff > leanThreshold -> LeanStatus.LEFT_LEAN
            angleDiff < -leanThreshold -> LeanStatus.RIGHT_LEAN
            else -> LeanStatus.STRAIGHT
        }
    }

    private fun calculateAngleDiff(current: Float, target: Float): Float {
        var diff = target - current
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return diff
    }

    private fun updateHistory(azimuth: Float) {
        azimuthHistory.add(azimuth)
        if (azimuthHistory.size > maxHistory) {
            azimuthHistory.removeAt(0)
        }
    }
}

enum class LeanStatus {
    STRAIGHT,
    LEFT_LEAN,
    RIGHT_LEAN
}
