package com.example.safewalknav.navigation.tbfw

import kotlin.math.abs

/**
 * GPS 신뢰도 점수(Trust Score)를 계산하는 순수 함수 모음.
 *
 * 입력 3가지를 종합해서 0~100점 사이의 점수를 만든다:
 *   1. GPS accuracy   (horizontalAccuracy, m 단위) — 가장 큰 비중
 *   2. heading 차이    (현재 방향과 목표 방향의 차이, 도)
 *   3. 보행 속도       (m/s)
 *
 * 점수 자체는 단일 update 시점의 스냅샷이다.
 * 시간에 따른 변화 추적은 호출하는 쪽(TrustBasedNavigator)이 담당한다.
 *
 * 모든 함수는 순수 함수 — 외부 상태 의존 없음. 테스트하기 쉽다.
 */
object TrustScoreCalculator {

    /**
     * 종합 Trust Score 계산.
     *
     * @param gpsAccuracy GPS 수평 정확도 (m). horizontalAccuracy 값.
     * @param headingDiff 현재 heading - 목표 bearing (-180 ~ +180 도).
     * @param speed 보행 속도 (m/s).
     * @param config threshold/가중치 설정.
     * @return 0 ~ 100 사이의 점수.
     */
    fun calculate(
        gpsAccuracy: Float,
        headingDiff: Float,
        speed: Float,
        config: NavigatorConfig
    ): Int {
        val gpsScore = scoreGpsAccuracy(gpsAccuracy, config)
        val headingScore = scoreHeading(headingDiff, config)
        val speedScore = scoreSpeed(speed, config)

        return gpsScore + headingScore + speedScore
    }

    /**
     * Trust Score를 카테고리로 변환.
     *
     * 80+ HIGH       — GPS 매우 정확, 표준 안내
     * 60~79 MEDIUM   — GPS 보통, 통과 거리 완화
     * 40~59 LOW      — GPS 부정확, 거리 안내 대신 방향 유지
     * <40 CRITICAL   — 매우 부정확, waypoint 통과 보류
     */
    fun classify(score: Int): TrustLevel = when {
        score >= 80 -> TrustLevel.HIGH
        score >= 60 -> TrustLevel.MEDIUM
        score >= 40 -> TrustLevel.LOW
        else -> TrustLevel.CRITICAL
    }

    // ─── 개별 점수 함수 (private이지만 테스트 위해 internal) ───

    /**
     * GPS accuracy 점수.
     * 보통 도심에서는 5~25m 사이. 25m 넘으면 신뢰하기 어렵다.
     */
    internal fun scoreGpsAccuracy(accuracy: Float, config: NavigatorConfig): Int = when {
        accuracy <= config.gpsHighAccuracy -> config.scoreGpsHigh           // 10m 이하: 40점
        accuracy <= config.gpsMediumAccuracy -> config.scoreGpsMedium       // 25m 이하: 25점
        accuracy <= 50.0 -> config.scoreGpsLow                              // 50m 이하: 10점
        else -> 0                                                            // 50m 초과: 0점
    }

    /**
     * heading 일치도 점수.
     * 사용자가 목표 방향과 가까이 보고 있을수록 위치가 신뢰할 만하다는 가정.
     * 절댓값으로 처리 (좌/우 무관).
     */
    internal fun scoreHeading(headingDiff: Float, config: NavigatorConfig): Int {
        val absDiff = abs(headingDiff)
        return when {
            absDiff <= 15f -> config.scoreHeadingClose   // 15도 이내: 30점
            absDiff <= 35f -> config.scoreHeadingMid     // 35도 이내: 20점
            absDiff <= 60f -> config.scoreHeadingFar     // 60도 이내: 10점
            else -> 0                                    // 60도 초과: 0점
        }
    }

    /**
     * 보행 속도 점수.
     * 0.3~2.0 m/s가 정상 보행 범위. 너무 느리거나 빠르면 GPS 노이즈일 가능성.
     * - 0.3 미만: 정지 상태 또는 GPS 흔들림
     * - 2.0 초과: 뛰는 중이거나 차량 이동 (보행 안내 부적합)
     */
    internal fun scoreSpeed(speed: Float, config: NavigatorConfig): Int =
        if (speed in 0.3f..2.0f) config.scoreSpeedNormal       // 정상: 20점
        else config.scoreSpeedAbnormal                          // 그 외: 5점
}
