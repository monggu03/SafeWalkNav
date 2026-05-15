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
 * @param gpsHighAccuracy 이 값(m) 이하이면 Trust HIGH 후보. 기본 10m.
 * @param gpsMediumAccuracy 이 값(m) 이하이면 Trust MEDIUM 후보. 기본 25m.
 *
 * @param trustScoreForPass waypoint 통과 처리에 필요한 최소 Trust Score. 기본 60.
 * @param trustScoreForLowWarning 이 값 미만이면 "위치 정확도 낮음" 경고. 기본 40.
 *
 * @param passDistanceHigh Trust HIGH일 때 waypoint 통과 거리 (m). 기본 8m.
 * @param passDistanceMedium Trust MEDIUM일 때 waypoint 통과 거리 (m). 기본 12m.
 *
 * @param headingPassTolerance waypoint 통과 시 허용되는 heading 차이 (도). 기본 45도.
 *
 * @param scoreGpsHigh GPS accuracy HIGH일 때 부여 점수. 기본 40.
 * @param scoreGpsMedium GPS accuracy MEDIUM일 때 부여 점수. 기본 25.
 * @param scoreGpsLow GPS accuracy 50m 이하일 때 부여 점수. 기본 10.
 *
 * @param scoreHeadingClose heading 차이 15도 이내일 때 점수. 기본 30.
 * @param scoreHeadingMid heading 차이 35도 이내일 때 점수. 기본 20.
 * @param scoreHeadingFar heading 차이 60도 이내일 때 점수. 기본 10.
 *
 * @param scoreSpeedNormal 보행 속도 정상(0.3~2.0 m/s)일 때 점수. 기본 20.
 * @param scoreSpeedAbnormal 보행 속도 비정상일 때 점수. 기본 5.
 */
data class NavigatorConfig(
    // GPS 신뢰도 구간
    val gpsHighAccuracy: Double = 10.0,
    val gpsMediumAccuracy: Double = 25.0,

    // Trust Score 통과 기준
    val trustScoreForPass: Int = 60,
    val trustScoreForLowWarning: Int = 40,

    // Waypoint 통과 거리 (신뢰도별 차등)
    val passDistanceHigh: Double = 8.0,
    val passDistanceMedium: Double = 12.0,

    // Heading 허용 범위
    val headingPassTolerance: Double = 45.0,

    // Trust Score 가중치
    val scoreGpsHigh: Int = 40,
    val scoreGpsMedium: Int = 25,
    val scoreGpsLow: Int = 10,

    val scoreHeadingClose: Int = 30,
    val scoreHeadingMid: Int = 20,
    val scoreHeadingFar: Int = 10,

    val scoreSpeedNormal: Int = 20,
    val scoreSpeedAbnormal: Int = 5,

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
