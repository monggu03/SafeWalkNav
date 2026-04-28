package com.example.safewalknav.navigation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Circular Kalman Filter (heading 전용)
 *
 * 각도 데이터(0~360)를 sin/cos 두 직교 성분으로 분해하여 각각 1D Kalman을 적용한 뒤
 * atan2로 합성한다. 단순 EMA로는 350°/10° 같은 경계에서 평균이 180°로 잘못 나오는
 * 문제를 sin/cos 공간으로 회피.
 *
 * Process noise:
 *   보행자 진행 방향이 한 스텝 사이에 얼마나 바뀔 수 있다고 보는지의 분산 추정.
 *
 * Measurement noise (동적):
 *   - 정지(speed < stationarySpeed): STATIONARY_NOISE — GPS bearing은 잡음 그 자체이므로 사실상 무시
 *   - 그 외: max(MEAS_NOISE_FLOOR, accuracy * MEAS_NOISE_GAIN) — GPS 정확도 나쁘면 측정을 덜 믿음
 *
 * Kalman gain K = predicted_uncertainty / (predicted_uncertainty + measurement_noise)
 *   K → 1: 측정값 적극 수용. K → 0: 이전 추정값 유지.
 *
 * tools/heading_analysis.py 의 circular_kalman_filter() 와 동일 파라미터.
 *
 * KMM commonMain — Android/iOS 공통.
 *
 * @param stationarySpeed 정지 판정 속도(m/s). 이 미만이면 measurement noise를 STATIONARY_NOISE로 설정.
 */
class KalmanHeading(
    private val stationarySpeed: Float = 0.5f
) {
    private var sinEstimate = 0.0
    private var cosEstimate = 1.0
    private var uncertainty = INITIAL_UNCERTAINTY
    private var smoothedHeading = 0f
    private var initialized = false
    private var lastGain = 0.0

    /** 현재 필터링된 heading (0~360). 미초기화 상태면 -1 반환. */
    val current: Float get() = if (initialized) smoothedHeading else -1f

    /** 최근 Kalman gain (0.0 ~ 1.0). CSV 로그/시각화용. */
    val gain: Double get() = lastGain

    /** 한 번이라도 update()가 호출되어 추정값이 만들어졌는지. */
    val isInitialized: Boolean get() = initialized

    /**
     * 새 측정값으로 추정 갱신.
     *
     * @param rawHeading GPS bearing 또는 센서 heading (0~360도)
     * @param speed 현재 이동 속도 (m/s)
     * @param accuracy GPS 수평 정확도 (m). 작을수록 정확.
     * @return 필터링된 heading (0~360도)
     */
    fun update(rawHeading: Float, speed: Float, accuracy: Float): Float {
        // 초기 1회: 측정값을 그대로 추정값으로 사용
        if (!initialized) {
            val rad = rawHeading.toDouble() * PI / 180.0
            sinEstimate = sin(rad)
            cosEstimate = cos(rad)
            uncertainty = INITIAL_UNCERTAINTY
            smoothedHeading = ((rawHeading % 360f) + 360f) % 360f
            lastGain = 1.0
            initialized = true
            return smoothedHeading
        }

        // ----- Predict 단계 -----
        // 보행자 운동 모델 없이 "방향이 그대로 유지된다"고 보고 process noise만큼 불확실성만 키움.
        val predictedSin = sinEstimate
        val predictedCos = cosEstimate
        val predictedUncertainty = uncertainty + PROCESS_NOISE

        // ----- Measurement noise 결정 -----
        val measurementNoise: Double = if (speed < stationarySpeed) {
            // 정지 상태: GPS bearing은 사실상 노이즈. 거의 0에 가까운 gain → 추정값 유지.
            STATIONARY_NOISE
        } else {
            max(MEAS_NOISE_FLOOR, accuracy.toDouble() * MEAS_NOISE_GAIN)
        }

        // ----- Kalman gain -----
        val k = predictedUncertainty / (predictedUncertainty + measurementNoise)
        lastGain = k

        // ----- Update (sin/cos 각각 1D Kalman) -----
        val rad = rawHeading.toDouble() * PI / 180.0
        val measSin = sin(rad)
        val measCos = cos(rad)

        sinEstimate = predictedSin + k * (measSin - predictedSin)
        cosEstimate = predictedCos + k * (measCos - predictedCos)
        uncertainty = (1.0 - k) * predictedUncertainty

        val resultRad = atan2(sinEstimate, cosEstimate)
        smoothedHeading = ((resultRad * 180.0 / PI + 360.0) % 360.0).toFloat()
        return smoothedHeading
    }

    /** 추정 상태 완전 리셋 — 새 경로 시작 / 내비 종료 시 호출. */
    fun reset() {
        sinEstimate = 0.0
        cosEstimate = 1.0
        uncertainty = INITIAL_UNCERTAINTY
        smoothedHeading = 0f
        initialized = false
        lastGain = 0.0
    }

    private companion object {
        const val INITIAL_UNCERTAINTY = 10.0
        const val PROCESS_NOISE = 0.5
        const val MEAS_NOISE_GAIN = 3.0
        const val MEAS_NOISE_FLOOR = 5.0
        const val STATIONARY_NOISE = 999.0
    }
}
