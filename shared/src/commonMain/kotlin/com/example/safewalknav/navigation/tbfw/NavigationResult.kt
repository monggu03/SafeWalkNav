package com.example.safewalknav.navigation.tbfw

/**
 * TBFW Navigator의 출력 묶음.
 *
 * 단순히 안내 문장 하나만 리턴하지 않고, 내부 판단 결과를 함께 노출한다.
 * 이렇게 하면 호출하는 쪽(ViewModel 등)이 추가 동작을 결정할 수 있다.
 * 예: jumpLevel이 JUMPED면 진동 패턴을 다르게, didPassWaypoint=true면 비프음 등.
 *
 * @param message TTS로 읽어줄 안내 문장
 * @param didPassWaypoint 이번 update에서 waypoint를 통과 처리했는지 여부
 * @param currentWaypointIndex 현재 목표 waypoint의 인덱스 (디버그/UI용)
 * @param jumpLevel 현재 GPS 점프 레벨 (NORMAL/SUSPECT/JUMPED)
 * @param distanceToWaypoint 현재 위치에서 다음 waypoint까지 거리 (m)
 * @param headingDiff 현재 heading - 목표 bearing (-180 ~ +180 도)
 * @param isFinished 모든 waypoint를 통과해서 더 이상 안내할 게 없는 상태
 * @param guidanceAction GPS 점프 시 안내 행동 결정 (SILENT/ANNOUNCE_DEGRADED).
 *                       호출자는 ANNOUNCE_DEGRADED 시 TTS + 진동 짧게 1회.
 * @param annotationAnnouncement 이번 update 에서 새로 발화할 PathAnnotation 안내 (없으면 null).
 *                               말 그대로 "사전 안내" — message 와는 별개로 곡선/회전 사전 알림용.
 */
data class NavigationResult(
    val message: String,
    val didPassWaypoint: Boolean,
    val currentWaypointIndex: Int,
    val jumpLevel: GpsJumpLevel,
    val distanceToWaypoint: Float,
    val headingDiff: Float,
    val isFinished: Boolean,
    val guidanceAction: JumpGuidanceAction = JumpGuidanceAction.SILENT,
    val annotationAnnouncement: String? = null,
)
