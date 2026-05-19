package com.example.safewalknav.navigation

import kotlin.math.*

/**
 * 보행자 물리 제약 기반 GPS 점 유효성 검사.
 *
 * 보행자는 1초 사이에 최대 약 2.5m만 이동 가능하다는 도메인 가정을 활용해
 * 비현실적으로 빠른 GPS 점(노이즈)을 걸러낸다.
 *
 * Accuracy 필터와 함께 직렬로 사용:
 *   원본 GPS → Accuracy 필터 → WalkingMotionFilter → NavigationManager
 *
 * Accuracy 필터는 "점이 얼마나 부정확한가" (점의 신뢰도)를,
 * 이 필터는 "점이 물리적으로 타당한가" (점의 도달 가능성)를 검사한다.
 */
class WalkingMotionFilter {

    companion object {
        /** 보행자 최대 순간 속도 (m/s). 2.5 ≈ 조깅 시작 직전. */
        private const val MAX_WALKING_SPEED = 2.5

        /** 지구 반지름 (m), Haversine 계산용. */
        private const val EARTH_RADIUS_M = 6_371_000.0
    }

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimestamp: Long = 0L

    /**
     * 새 GPS 점이 보행자 물리 제약을 만족하는지 검사.
     *
     * @param lat 측정된 위도
     * @param lon 측정된 경도
     * @param timestamp 측정 시각 (ms)
     * @return true면 유효 (NavigationManager로 통과), false면 노이즈 (무시)
     */
    fun isPlausible(lat: Double, lon: Double, timestamp: Long): Boolean {
        // [Point 3] 첫 호출 가드 — 비교 대상 없음 → 통과 + 상태 저장
        val prevLat = lastLat
        val prevLon = lastLon
        if (prevLat == null || prevLon == null) {
            lastLat = lat
            lastLon = lon
            lastTimestamp = timestamp
            return true
        }

        // [Point 4] 시간 간격 가드 — 0 또는 음수면 비교 불가
        val dtSec = (timestamp - lastTimestamp) / 1000.0
        if (dtSec <= 0) {
            // 상태는 갱신 안 함 — 다음 정상 점을 이전 점과 비교
            return true
        }

        // [Point 1] GPS speed 필드 대신 두 점 거리/시간으로 직접 계산
        val distance = haversineDistance(prevLat, prevLon, lat, lon)
        val instantSpeed = distance / dtSec

        // [Point 2] 보행자 최대 속도 초과 → 노이즈
        if (instantSpeed > MAX_WALKING_SPEED) {
            println("[MotionFilter] 비현실적 속도 ${instantSpeed.format(2)}m/s — 무시")
            // 상태 갱신 안 함 — 다음 점이 이 점이 아닌 이전 정상 점과 비교됨
            return false
        }

        // 통과 → 상태 갱신
        lastLat = lat
        lastLon = lon
        lastTimestamp = timestamp
        return true
    }

    /** 새 안내 시작 시 호출 — 이전 점 초기화. */
    fun reset() {
        lastLat = null
        lastLon = null
        lastTimestamp = 0L
    }

    /**
     * 두 위/경도 사이의 거리를 미터로 계산 (Haversine).
     * 보행자 스케일(수십 m)에서 정확함.
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2).pow(2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
    private fun Double.format(digits: Int): String =
        ((this * 10.0.pow(digits)).roundToLong() / 10.0.pow(digits)).toString()
}