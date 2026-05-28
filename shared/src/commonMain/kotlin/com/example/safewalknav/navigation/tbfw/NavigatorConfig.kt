package com.example.safewalknav.navigation.tbfw

/**
 * RouteAnnotator + 가상 waypoint 기반 곡선 안내 시스템의 튜닝 가능한 threshold 묶음.
 *
 * 모든 값에 default가 있어서 NavigatorConfig() 만 호출해도 동작한다.
 *
 * 2026-05-23 — Trust Score / GPS Jump / ForwardOnlyTracker 관련 필드 일괄 제거.
 *   사유: 1차 설계의 신뢰도 점수화는 magnetic heading 노이즈로 실측 부정확.
 *   RouteAnnotator 의 사전 안내 + 가상 waypoint 기반 곡선 안내로 대체.
 *
 * 2026-05-26 — 곡선 진행 중 방향 리마인더 추가.
 *   사유: 사전 안내(15~25m 전 1회)만으로는 긴 곡선 중간에 사용자가 방향을 잊을 수 있고,
 *   GPS sideways pass 로 가상 waypoint 통과 판정이 누락될 수 있어 정보 우선으로 발화.
 */
data class NavigatorConfig(
    // ─── 보행 쏠림 보정 (Path Annotation) ───
    // RouteAnnotator 가 waypoint 시퀀스를 곡선/회전으로 분류할 때 쓰는 임계값.
    val minSegmentDistanceM: Double = 3.0,
    val noiseAngleThresholdDeg: Double = 10.0,
    val turnPeakThresholdDeg: Double = 30.0,
    val curveCumulativeThresholdDeg: Double = 30.0,
    val slightThresholdDeg: Double = 30.0,
    val sharpThresholdDeg: Double = 70.0,
    val curveSignConsistencyRatio: Double = 0.75,

    // 안내 시점 — 곡선/회전 시작 직전에 짧게 알린다.
    val announceDistanceCurveM: Double = 5.0,
    val announceDistanceTurnM: Double = 5.0,
    val announceDistanceSharpM: Double = 5.0,

    // ─── 초기 방향 안내 ───
    val initialHeadingToleranceDeg: Double = 15.0,
    // gravity.z 는 화면이 하늘을 향한 평면 자세에서 -1.0 근처가 된다.
    val flatPoseGravityZTolerance: Double = 0.2,
    val flatPoseGravityXYTolerance: Double = 0.3,

    // ─── 가상 waypoint 기반 곡선 안내 (NEW 2026-05-23) ───
    // RouteAnnotator 가 검출한 곡선 구간에 5m 간격 가상 점을 삽입,
    // 통과 시점에 사용자 이탈 정도로 비프/음성을 전환한다.
    val virtualWaypointSpacingM: Double = 5.0,
    val curveDeviationLowM: Double = 1.0,
    val curveDeviationHighM: Double = 3.0,
    val curveDeviationCriticalM: Double = 5.0,

    // ─── 곡선 방향 리마인더 (NEW 2026-05-26) ───
    // 곡선 진행 중 사용자가 방향을 잊지 않도록 주기적으로 "오른쪽/왼쪽으로 휘어집니다" 발화.
    // GPS sideways pass 로 가상 waypoint 통과가 누락될 가능성을 고려해 이탈 상태 무관하게 발화한다.
    //
    // curveReminderEveryNVirtuals = 3 → 가상 waypoint 3개(≈15m)마다 리마인더 후보.
    // curveReminderCooldownMs = 8000 → 같은 멘트가 8초 이내 반복되지 않도록 시간 쿨다운 안전장치.
    val curveReminderEveryNVirtuals: Int = 3,
    val curveReminderCooldownMs: Long = 8_000L,
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
