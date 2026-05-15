package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.Waypoint

/**
 * 경로 구간의 분류.
 *
 * 회전(Turn) 과 곡선(Curve) 의 구분:
 *   - Turn  : 단일 waypoint 구간에서 각도가 급변 (peak 기준)
 *   - Curve : 여러 waypoint에 걸쳐 같은 방향으로 누적 변화
 *
 * 강도 구분:
 *   - SLIGHT_* : 10~30°
 *   - 기본형   : 30~70°
 *   - SHARP_*  : 70°+
 */
enum class PathSegmentType {
    STRAIGHT,
    SLIGHT_CURVE,
    CURVE,
    SLIGHT_TURN,
    TURN,
    SHARP_TURN,
}

/** 회전/곡선의 진행 방향. */
enum class TurnDirection { LEFT, RIGHT, NONE }

/**
 * 경로 위 한 구간에 대한 사전 분석 결과.
 *
 * RouteAnnotator가 TMap waypoint 리스트를 받아 만들어내며,
 * TrustBasedNavigator는 진행 중 위치가 anouncement 거리에 들어오면
 * 해당 announceMessage를 음성 안내로 발화한다.
 *
 * @param startWaypointIndex 분석 구간이 시작되는 waypoint 인덱스 (annotation ID 로도 사용)
 * @param endWaypointIndex 분석 구간이 끝나는 waypoint 인덱스
 * @param type 구간 분류 (STRAIGHT, SLIGHT_*, TURN, CURVE, SHARP_TURN)
 * @param direction LEFT/RIGHT/NONE
 * @param totalAngle 누적 각도 변화 (부호 있음, -180~+180)
 * @param peakAngle 단일 구간 최대 각도 변화 (부호 있음)
 * @param distanceFromStartM 경로 시작점부터 annotation 시작까지의 누적 거리 (m)
 * @param announceMessage 발화할 안내 문장 (MessageBuilder.buildAnnotationAnnounce 결과)
 */
data class PathAnnotation(
    val startWaypointIndex: Int,
    val endWaypointIndex: Int,
    val type: PathSegmentType,
    val direction: TurnDirection,
    val totalAngle: Double,
    val peakAngle: Double,
    val distanceFromStartM: Double,
    val announceMessage: String,
) {
    companion object {
        /** Swift interop 용 — Swift 는 default 인자를 인식 못 하므로 팩토리 제공. */
        fun defaults(): PathAnnotation = PathAnnotation(
            startWaypointIndex = 0,
            endWaypointIndex = 0,
            type = PathSegmentType.STRAIGHT,
            direction = TurnDirection.NONE,
            totalAngle = 0.0,
            peakAngle = 0.0,
            distanceFromStartM = 0.0,
            announceMessage = "",
        )
    }
}

/**
 * 경로 + 사전 분석된 annotation 묶음.
 *
 * waypoints 는 TMap 응답의 원본 그대로,
 * annotations 는 RouteAnnotator 가 같은 waypoints 에 대해 만들어낸 결과.
 */
data class AnnotatedRoute(
    val waypoints: List<Waypoint>,
    val annotations: List<PathAnnotation>,
) {
    companion object {
        fun defaults(): AnnotatedRoute = AnnotatedRoute(
            waypoints = emptyList(),
            annotations = emptyList(),
        )
    }
}
