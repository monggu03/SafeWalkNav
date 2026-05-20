package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.tmap.Waypoint

/**
 * 안내 문장 생성기.
 *
 * 우선순위 기반 메시지 빌더:
 *   1순위 — 종료/도착 메시지
 *   2순위 — GPS 점프 안내 (JUMPED)
 *   3순위 — waypoint 통과 알림
 *   4순위 — 일반 거리 안내
 *
 * GPS 점프 안내 정책은 JumpGuidancePolicy 에서 결정한다.
 * 본 빌더는 점프 레벨이 JUMPED 일 때 어떤 문구를 쓸지만 담당.
 *
 * 모든 함수는 순수 함수 — 외부 상태 의존 없음.
 */
object MessageBuilder {

    /**
     * 메인 진입점 — 상황에 따라 적절한 메시지 생성.
     *
     * @param jumpLevel 현재 GPS 점프 레벨
     * @param distance 현재 waypoint까지 거리 (m)
     * @param didPassWaypoint 이번 update에서 waypoint 통과 처리됐는지
     * @param isFinished 모든 waypoint 통과 완료 여부
     * @param currentTarget 현재 목표 waypoint (description 사용)
     * @return TTS로 읽을 문장
     */
    fun build(
        jumpLevel: GpsJumpLevel,
        distance: Float,
        didPassWaypoint: Boolean,
        isFinished: Boolean,
        currentTarget: Waypoint?
    ): String {
        // 1순위: 종료
        if (isFinished) return MSG_ARRIVED_DESTINATION

        // 2순위: GPS 점프 — 거리 안내가 신뢰 어려우므로 별도 안내
        if (jumpLevel == GpsJumpLevel.JUMPED) {
            return MSG_GPS_DEGRADED
        }

        // 3순위: 방금 waypoint 통과
        if (didPassWaypoint) {
            return buildPassMessage(currentTarget)
        }

        // 4순위: 일반 거리 안내
        return buildDistanceMessage(distance, currentTarget)
    }

    // ─── 개별 메시지 빌더 ───

    /**
     * waypoint 통과 메시지.
     * waypoint description이 있으면 함께 안내.
     */
    internal fun buildPassMessage(target: Waypoint?): String {
        // 다음 목표 정보가 없으면 기본 메시지
        if (target == null) return MSG_PASSED_GENERIC

        // pointType에 따라 메시지 변형
        return when (target.pointType) {
            "CROSSWALK" -> "중간 지점을 통과했습니다. 다음 횡단보도로 안내합니다."
            "DESTINATION" -> "거의 도착했습니다."
            else -> MSG_PASSED_GENERIC
        }
    }

    /**
     * 거리 기반 일반 안내 메시지.
     * 거리에 따라 표현을 다르게 한다.
     */
    internal fun buildDistanceMessage(distance: Float, target: Waypoint?): String {
        val distanceInt = distance.toInt()

        // 매우 가까움 (3m 이내) — 곧 도착
        if (distance < 3f) {
            return when (target?.pointType) {
                "DESTINATION" -> MSG_ARRIVED_DESTINATION
                "CROSSWALK" -> "횡단보도 앞입니다. 신호를 확인해주세요."
                else -> "곧 다음 지점입니다."
            }
        }

        // 가까움 (10m 이내)
        if (distance < 10f) {
            return when (target?.pointType) {
                "CROSSWALK" -> "${distanceInt}미터 앞에 횡단보도가 있습니다."
                "DESTINATION" -> "목적지까지 ${distanceInt}미터 남았습니다."
                else -> "${distanceInt}미터 앞으로 이동하세요."
            }
        }

        // 일반 거리
        return "${distanceInt}미터 앞으로 이동하세요."
    }

    // ─── 보행 쏠림 보정: annotation / 초기 방향 ───

    /**
     * RouteAnnotator 가 만든 PathAnnotation 한 건에 대한 음성 안내 문장.
     * STRAIGHT 또는 direction == NONE 이면 빈 문자열을 돌려준다 (안내 안 함).
     */
    fun buildAnnotationAnnounce(annotation: PathAnnotation): String {
        val dir = when (annotation.direction) {
            TurnDirection.LEFT -> "왼쪽"
            TurnDirection.RIGHT -> "오른쪽"
            TurnDirection.NONE -> return ""
        }
        return when (annotation.type) {
            PathSegmentType.SLIGHT_CURVE ->
                "앞쪽 길이 ${dir}으로 완만하게 휘어집니다. 인도 방향을 따라 이동하세요."
            PathSegmentType.CURVE ->
                "앞쪽 길이 ${dir}으로 휘어집니다. 인도 방향을 따라 이동하세요."
            PathSegmentType.SLIGHT_TURN ->
                "잠시 후 ${dir}으로 살짝 꺾어집니다."
            PathSegmentType.TURN ->
                "잠시 후 ${dir}으로 꺾어집니다."
            PathSegmentType.SHARP_TURN ->
                "잠시 후 ${dir}으로 크게 꺾어집니다."
            PathSegmentType.INTERNAL_CURVE ->
                "앞쪽 도로가 ${dir}으로 휘어집니다. 인도를 따라가세요."
            PathSegmentType.STRAIGHT -> ""
        }
    }

    /**
     * 초기 방향 안내 — 사용자가 출발 전, 어느 방향으로 돌아야 하는지 알려준다.
     *
     * @param diffDeg 목표 bearing - 현재 heading (-180 ~ +180). 양수면 오른쪽으로 돌아야 함.
     * @param tolerance 이 값 이내이면 "정면" 으로 판정.
     */
    fun buildInitialHeadingMessage(diffDeg: Double, tolerance: Double): String {
        val absDiff = kotlin.math.abs(diffDeg)
        val direction = if (diffDeg > 0) "오른쪽" else "왼쪽"
        return when {
            absDiff < tolerance -> "정면입니다. 직진하세요."
            absDiff < 45.0 -> "${direction}으로 약 ${absDiff.toInt()}도 돌아주세요."
            absDiff < 135.0 -> "${direction}으로 약 90도 돌아주세요."
            else -> "뒤로 돌아주세요."
        }
    }

    /** 자세 안내 — 평평하지 않으면 발화. */
    fun buildFlatPosePromptMessage(): String =
        "스마트폰을 손바닥 위에 평평하게 들어주세요."

    // ─── 메시지 상수 ───

    const val MSG_ARRIVED_DESTINATION = "목적지에 도착했습니다."
    const val MSG_PASSED_GENERIC = "중간 지점을 통과했습니다. 다음 지점으로 안내합니다."

    /**
     * GPS 점프 상태 안내. 발표/인터뷰에서 합의된 문구로 변경 금지.
     * "멈추세요" 같은 표현은 GPS 회복이 보행 중 일어남을 고려해 의도적으로 배제했다.
     */
    const val MSG_GPS_DEGRADED = "위치 안내가 잠시 어렵습니다. 평소처럼 보행하세요"
}
