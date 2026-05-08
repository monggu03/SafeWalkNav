package com.example.safewalknav.navigation

import kotlin.math.abs

/**
 * [기존 WalkingDiagnostic.kt 고도화 이식]
 * 사용자의 현재 방위각과 목표 방향을 비교하여 보행 쏠림을 진단합니다.
 */
class WalkingDiagnostic {

    // 분석에 필요한 수치들을 Constants에서 가져옴[cite: 1]
    private val leanThreshold = NavigationConstants.LEAN_THRESHOLD
    private val maxHistory = NavigationConstants.MAX_DIAGNOSTIC_HISTORY

    // 보행 이력을 저장하여 노이즈를 방지 (KMP 공통 리스트)[cite: 1]
    private val azimuthHistory = mutableListOf<Float>()

    /**
     * 현재 상태를 분석하여 쏠림 여부를 반환합니다.[cite: 1]
     */
    fun analyzeLeanStatus(currentAzimuth: Float, targetBearing: Float): LeanStatus {
        // 1. 이력 업데이트 및 데이터 정제[cite: 1]
        updateHistory(currentAzimuth)

        // 2. 목표 방향과의 차이 계산[cite: 1]
        val angleDiff = calculateAngleDiff(currentAzimuth, targetBearing)

        // 3. 임계값(15도) 기준으로 상태 판정[cite: 1]
        return when {
            angleDiff > leanThreshold -> LeanStatus.LEFT_LEAN
            angleDiff < -leanThreshold -> LeanStatus.RIGHT_LEAN
            else -> LeanStatus.STRAIGHT
        }
    }

    /**
     * 두 각도의 최단 거리 차이를 계산 (-180 ~ 180)[cite: 1]
     */
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

/**
 * 보행 쏠림 상태를 나타내는 열거형 클래스[cite: 1]
 */
enum class LeanStatus {
    STRAIGHT,    // 정상 보행
    LEFT_LEAN,   // 왼쪽으로 치우침
    RIGHT_LEAN   // 오른쪽으로 치우침
}