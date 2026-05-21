package com.example.safewalknav.navigation.tbfw

/**
 * TBFW (Trust-Based Forward Waypoint) 알고리즘의 튜닝 가능한 threshold 묶음.
 *
 * 왜 별도 클래스인가?
 *  - 기존 NavigationConstants는 프로젝트 전체에서 쓰는 상수 (GPS_ACCURACY_LIMIT 등)
 *  - NavigatorConfig는 TBFW 전용. 실측 데이터로 조정될 값들.
 *
 * 모든 값에 default가 있어서 NavigatorConfig() 만 호출해도 동작한다.
 * 실측 후 특정 값만 바꿀 때:
 *   NavigatorConfig(passDistanceHigh = 6.0)
 *
 * @param passDistanceHigh GPS 점프 NORMAL일 때 waypoint 통과 거리 (m). 기본 8m.
 * @param passDistanceMedium GPS 점프 SUSPECT일 때 waypoint 통과 거리 (m). 기본 12m.
 * @param headingPassTolerance waypoint 통과 시 허용되는 heading 차이 (도). 기본 45도.
 *
 * @param jumpSpeedSuspect 함의 속도 이상이면 SUSPECT 판정 (m/s). 기본 5.0.
 * @param jumpSpeedCritical 함의 속도 이상이면 단발성도 JUMPED (m/s). 기본 10.0.
 * @param suspectStreakForJump SUSPECT 연속 N회 이상이면 JUMPED 승격. 기본 2회.
 *
 * @param jumpSilentWindowMs JUMPED 진입 직후 침묵 구간 (ms). 기본 3000ms.
 * @param jumpAnnounceMaxOnceMs JUMPED 안내 가능 한계 시각 (ms, 참고용). 기본 10000ms.
 * @param jumpRecoveryAnnounceCooldownMs 점프 안내 간 최소 쿨다운 (ms). 기본 5000ms.
 */
data class NavigatorConfig(
    // ─── DEPRECATED (구 Trust Score 기반, 본 코드에서 더 이상 사용 안 함) ───
    // 향후 어떤 모듈도 참조하지 않음이 확인되면 v2에서 삭제 예정.
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val gpsHighAccuracy: Double = 10.0,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val gpsMediumAccuracy: Double = 25.0,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val trustScoreForPass: Int = 60,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val trustScoreForLowWarning: Int = 40,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val scoreGpsHigh: Int = 40,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val scoreGpsMedium: Int = 25,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val scoreGpsLow: Int = 10,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val scoreHeadingClose: Int = 30,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val scoreHeadingMid: Int = 20,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val scoreHeadingFar: Int = 10,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val scoreSpeedNormal: Int = 20,
    @Deprecated("Trust Score 폐기됨. GPS 점프 감지로 대체. 참조 모듈 확인 후 제거 예정.")
    val scoreSpeedAbnormal: Int = 5,

    // ─── Waypoint 통과 거리 (점프 레벨별 차등) ───
    val passDistanceHigh: Double = 8.0,
    val passDistanceMedium: Double = 12.0,

    // ─── Heading 허용 범위 ───
    val headingPassTolerance: Double = 45.0,

    // ─── GPS 점프 감지 (NEW) ───
    val jumpSpeedSuspect: Double = 5.0,     // m/s. 이 이상이면 SUSPECT.
    val jumpSpeedCritical: Double = 10.0,   // m/s. 이 이상이면 단발성도 JUMPED.
    val suspectStreakForJump: Int = 2,      // SUSPECT 연속 N회 이상이면 JUMPED 승격.

    // ─── GPS 점프 시 안내 정책 (NEW) ───
    val jumpSilentWindowMs: Long = 3_000,                  // 0~3초: 안내 없음
    val jumpAnnounceMaxOnceMs: Long = 10_000,              // 3~10초: 1회만 안내
    val jumpRecoveryAnnounceCooldownMs: Long = 5_000,      // 복귀 후 다음 튐 안내까지 최소 간격

    // ─── 보행 쏠림 보정 (Path Annotation) ───
    // 경로 분석용 — RouteAnnotator가 waypoint 시퀀스를 곡선/회전으로 분류할 때 쓰는 임계값.
    // 값은 모두 실 사용자 테스트로 추후 튜닝 대상이라 상수로 분리해 둔다.
    val minSegmentDistanceM: Double = 3.0,
    val noiseAngleThresholdDeg: Double = 10.0,
    val turnPeakThresholdDeg: Double = 30.0,
    val curveCumulativeThresholdDeg: Double = 30.0,
    val slightThresholdDeg: Double = 30.0,
    val sharpThresholdDeg: Double = 70.0,
    val curveSignConsistencyRatio: Double = 0.75,

    // 안내 시점 — 곡선/회전 시작 지점에서 얼마 전부터 미리 알릴지.
    val announceDistanceCurveM: Double = 15.0,
    val announceDistanceTurnM: Double = 20.0,
    val announceDistanceSharpM: Double = 25.0,

    // ─── 초기 방향 안내 ───
    val initialHeadingToleranceDeg: Double = 15.0,
    // gravity.z 는 화면이 하늘을 향한 평면 자세에서 -1.0 근처가 된다.
    val flatPoseGravityZTolerance: Double = 0.2,
    val flatPoseGravityXYTolerance: Double = 0.3,
) {
    companion object {
        /**
         * Swift interop용 기본 설정 팩토리.
         * Kotlin에서는 NavigatorConfig() 한 줄이면 되지만,
         * Swift는 default 인자를 인식 못 하므로 이 함수를 통해 만든다.
         */
        fun defaults(): NavigatorConfig = NavigatorConfig()
    }
}
