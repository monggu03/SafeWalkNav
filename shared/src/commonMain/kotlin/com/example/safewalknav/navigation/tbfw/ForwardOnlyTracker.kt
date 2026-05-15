package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.Waypoint
import com.example.safewalknav.navigation.distanceBetween
import kotlin.math.abs

/**
 * Forward-Only Waypoint Tracker.
 *
 * 두 가지 원칙을 결합:
 *   1. Forward-Only: 한 번 통과한 waypoint는 다시 목표로 잡지 않는다.
 *      → currentIndex는 단조 증가 (절대 감소하지 않음)
 *   2. Hysteresis: 통과 판정 후 거리가 다시 멀어져도 통과 상태 유지
 *      → GPS 흔들림으로 통과/미통과가 깜빡거리는 것 방지
 *
 * 통과 조건 (모두 만족해야 함):
 *   1. 거리 < 통과 거리 (Trust Level에 따라 8m 또는 12m)
 *   2. Trust Score > 통과 기준 점수 (기본 60)
 *   3. heading 차이 절댓값 < 허용 범위 (기본 45도)
 *
 * Trust Score가 낮으면 통과 처리 보류 — GPS 튐으로 인한 잘못된 통과를 막는다.
 *
 * @param waypoints 따라갈 waypoint 리스트 (TMap에서 받은 경로)
 */
class ForwardOnlyTracker(
    private val waypoints: List<Waypoint>
) {
    /** 현재 목표 waypoint의 인덱스. 절대 감소하지 않는다. */
    var currentIndex: Int = 0
        private set

    /** 모든 waypoint를 통과했는지 여부 */
    val isFinished: Boolean
        get() = currentIndex >= waypoints.size

    /** 현재 목표 waypoint. 모두 통과했으면 null. */
    val currentTarget: Waypoint?
        get() = if (isFinished) null else waypoints[currentIndex]

    /**
     * 현재 위치에서 다음 waypoint까지 거리 계산.
     * BearingMath의 distanceBetween을 재사용.
     */
    fun distanceToTarget(currentLat: Double, currentLon: Double): Float {
        val target = currentTarget ?: return Float.MAX_VALUE
        return distanceBetween(currentLat, currentLon, target.lat, target.lon)
    }

    /**
     * waypoint 통과 여부 판정 및 인덱스 갱신.
     *
     * @param distance 현재 위치에서 목표 waypoint까지 거리 (m)
     * @param trustScore 현재 GPS 신뢰도 점수
     * @param trustLevel 신뢰도 카테고리
     * @param headingDiff 현재 heading - 목표 bearing (-180 ~ +180)
     * @param config threshold 설정
     * @return 이번 호출에서 waypoint를 통과 처리했으면 true
     */
    fun update(
        distance: Float,
        trustScore: Int,
        trustLevel: TrustLevel,
        headingDiff: Float,
        config: NavigatorConfig
    ): Boolean {
        // 이미 끝났으면 더 할 일 없음
        if (isFinished) return false

        // CRITICAL — Trust Score가 너무 낮으면 통과 처리 보류
        // GPS 튐일 가능성이 높아서 잘못된 통과 판정을 막는다 (Forward-Only 동결)
        if (trustLevel == TrustLevel.CRITICAL) return false

        // Trust Level에 따라 통과 거리 차등 적용
        // HIGH: 8m, MEDIUM: 12m, LOW: 통과 보류
        val passDistance = when (trustLevel) {
            TrustLevel.HIGH -> config.passDistanceHigh
            TrustLevel.MEDIUM -> config.passDistanceMedium
            TrustLevel.LOW -> return false       // LOW에서는 통과 처리 안 함
            TrustLevel.CRITICAL -> return false  // 위에서 이미 처리됐지만 명시
        }

        // 통과 조건 3가지 모두 검증
        val nearEnough = distance < passDistance
        val trustEnough = trustScore > config.trustScoreForPass
        val directionOk = abs(headingDiff) < config.headingPassTolerance

        return if (nearEnough && trustEnough && directionOk) {
            // Forward-Only: 인덱스만 증가, 절대 감소 없음
            currentIndex += 1
            true
        } else {
            false
        }
    }

    /**
     * 디버그/테스트용 — 현재 상태 스냅샷.
     */
    fun debugInfo(): String {
        val total = waypoints.size
        return "ForwardOnlyTracker[$currentIndex/$total, finished=$isFinished]"
    }
}
