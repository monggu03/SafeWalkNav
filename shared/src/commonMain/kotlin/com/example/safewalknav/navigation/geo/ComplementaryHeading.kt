package com.example.safewalknav.navigation.geo

/**
 * GPS bearing + 자이로 각속도 상보 필터(Complementary Filter, heading 전용).
 *
 * 원리:
 *   - 자이로: 각속도(deg/s)를 dt 로 적분 → "총 몇 도 꺾였는지" 를 ms 단위로 빠르게 추정.
 *     단, 오차가 시간에 따라 누적(드리프트)됨.
 *   - GPS bearing: 이동 궤적 기반 → 느리지만(500ms) 오차 누적 없는 절대 기준값.
 *     단, 정지/저속에서는 노이즈 그 자체.
 *
 *   heading = α × (prev_heading + gyro_delta) + (1-α) × gps_bearing
 *
 *   α(=predictWeight)는 GPS accuracy/속도에 따라 동적:
 *     - 정지(speed < stationarySpeed): α ≈ 0.99 → GPS 거의 무시(드리프트만 천천히 쌓임)
 *     - GPS 정확(accuracy 5m):  α ≈ 0.90 → GPS 10% 반영
 *     - GPS 부정확(accuracy 30m): α ≈ 0.98 → GPS 2%만 반영
 *
 * 각도(0~360)의 평균은 350°/10° 경계 문제가 있으므로, 보정은 [angleDiff] 로 부호 있는
 * 최단 오차를 구해 predicted 에 (1-α) 비율로 더하는 방식으로 원형 안전하게 수행한다.
 * (이는 위 가중평균 식과 수학적으로 동일하다.)
 *
 * KalmanHeading 과의 관계:
 *   - KalmanHeading 은 GPS bearing 단일 신호의 노이즈 제거(자력계/자이로 미사용).
 *   - 본 클래스는 자이로를 1차 신호로 두고 GPS 로 드리프트를 잡는 융합기.
 *   - NavigationManager 는 KalmanHeading 으로 1차 평활화한 bearing 을 [correct] 의 절대 기준으로 넘긴다.
 *
 * 자력계를 쓰지 않으므로 금속/전자기기 자기 간섭의 영향을 받지 않는다.
 *
 * 부호 규약:
 *   - yawRateDegPerSec / cumulativeTurnDeg 양수 = 시계 방향(오른쪽으로 꺾임, bearing 증가).
 *   - 플랫폼(Android/iOS)이 월드 up 축 회전을 이 규약에 맞춰 변환해 넘긴다.
 *
 * 스레드: [predict] 는 센서 콜백(고빈도), [correct] 는 GPS tick 에서 호출된다. 내부 상태는
 * Float/Boolean 단순 필드라 JVM/Native 에서 찢긴 읽기 위험이 없고, 한 tick 어긋나도 다음
 * GPS 보정으로 자기수렴하므로 별도 락 없이 동작한다(의도된 benign race).
 *
 * KMM commonMain — Android/iOS 공통.
 *
 * @param stationarySpeed 정지 판정 속도(m/s). 미만이면 GPS 보정 가중을 최소화(α≈0.99).
 */
class ComplementaryHeading(
    private val stationarySpeed: Float = 0.5f,
) {
    private var headingDeg: Float = 0f
    private var initialized = false

    // 곡선 통과 판정용 — 마지막 resetCumulativeTurn 이후의 부호 있는 누적 회전(deg).
    private var cumulativeTurn: Float = 0f

    /** 현재 융합 heading(0~360). GPS 절대 기준이 한 번도 안 들어왔으면 -1. */
    val current: Float get() = if (initialized) headingDeg else -1f

    /** GPS 절대 기준으로 한 번이라도 보정(correct)되어 heading 이 의미를 갖는지. */
    val isInitialized: Boolean get() = initialized

    /** resetCumulativeTurn 이후 자이로로 적분한 부호 있는 누적 회전(deg, 시계 방향 +). */
    val cumulativeTurnDeg: Float get() = cumulativeTurn

    /**
     * 자이로 적분(Predict). 센서 tick 마다 호출.
     *
     * @param yawRateDegPerSec 월드 up 축 기준 각속도(시계 방향 +), deg/s.
     * @param dtSec 직전 자이로 샘플과의 시간차(초). 0 이하/과도값은 호출부에서 클램프 권장.
     * @return 현재 융합 heading(미초기화면 -1).
     */
    fun predict(yawRateDegPerSec: Float, dtSec: Float): Float {
        if (dtSec <= 0f) return current
        val delta = yawRateDegPerSec * dtSec
        // 절대 기준이 아직 없어도 곡선 누적(상대값)은 유효하므로 항상 적분.
        cumulativeTurn += delta
        if (initialized) {
            headingDeg = normalize(headingDeg + delta)
        }
        return current
    }

    /**
     * GPS bearing 으로 절대 보정(Correct). GPS tick 마다 호출. 드리프트 리셋 역할.
     *
     * @param gpsBearing GPS(또는 Kalman 평활화) bearing 0~360.
     * @param speed 이동 속도(m/s).
     * @param accuracy GPS 수평 정확도(m).
     * @return 보정된 융합 heading.
     */
    fun correct(gpsBearing: Float, speed: Float, accuracy: Float): Float {
        val gps = normalize(gpsBearing)
        if (!initialized) {
            headingDeg = gps
            initialized = true
            return headingDeg
        }
        val alpha = predictWeight(speed, accuracy)
        val error = angleDiff(gps, headingDeg)          // -180..180, gps - heading
        headingDeg = normalize(headingDeg + (1f - alpha) * error)
        return headingDeg
    }

    /** 곡선 구간 진입 시 호출 — 이후 자이로 누적 회전을 0 부터 다시 센다. */
    fun resetCumulativeTurn() {
        cumulativeTurn = 0f
    }

    /** 전체 상태 리셋 — 새 경로 시작/내비 종료 시. */
    fun reset() {
        headingDeg = 0f
        initialized = false
        cumulativeTurn = 0f
    }

    /**
     * Predict 가중 α 결정.
     *   정지: GPS_TRUST_STATIONARY(≈0.99)
     *   이동: accuracy 를 [GOOD_ACCURACY_M, POOR_ACCURACY_M] 구간에서
     *         [α_GOOD, α_POOR] 로 선형 보간(정확할수록 GPS 더 반영 → α 작음).
     */
    private fun predictWeight(speed: Float, accuracy: Float): Float {
        if (speed < stationarySpeed) return ALPHA_STATIONARY
        val a = accuracy.coerceIn(GOOD_ACCURACY_M, POOR_ACCURACY_M)
        val t = (a - GOOD_ACCURACY_M) / (POOR_ACCURACY_M - GOOD_ACCURACY_M)
        return ALPHA_GOOD + t * (ALPHA_POOR - ALPHA_GOOD)
    }

    private companion object {
        const val ALPHA_STATIONARY = 0.99f
        const val ALPHA_GOOD = 0.90f          // accuracy 5m → GPS 10% 반영
        const val ALPHA_POOR = 0.98f          // accuracy 30m → GPS 2% 반영
        const val GOOD_ACCURACY_M = 5f
        const val POOR_ACCURACY_M = 30f
    }
}

// angleDiff() 는 같은 패키지(geo)의 BearingMath.kt 에 정의되어 있어 import 없이 사용.

/** 0~360 정규화. */
private fun normalize(deg: Float): Float = ((deg % 360f) + 360f) % 360f
