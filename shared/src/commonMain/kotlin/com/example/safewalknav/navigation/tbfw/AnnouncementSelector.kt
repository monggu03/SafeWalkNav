package com.example.safewalknav.navigation.tbfw

/**
 * RouteAnnotator 가 만든 PathAnnotation 리스트 중에서, 사용자 누적 진행 거리 기준으로
 * 다음에 발화할 후보 하나를 골라낸다.
 *
 * 선택 규칙:
 *   - 이미 발화된 (`announcedIds` 포함) annotation 은 제외
 *   - announceMessage 가 공백/빈 문자열인 annotation 제외 (STRAIGHT 등)
 *   - `gap = ann.distanceFromStartM - userCumulativeDistance` 가
 *     `0.0 .. triggerDist` 구간에 들어오는 첫 번째 annotation 반환
 *   - 위 조건을 만족하는 게 없으면 null
 *
 * `triggerDist` 는 annotation type 에 따라 [announceDistanceFor] 로 결정한다.
 *
 * NavigationManager 에서 인라인되어 있던 로직을 단위 테스트가 가능하도록 분리한 것.
 * 호출자(NavigationManager) 는 반환값이 null 이 아니면 [PathAnnotation.startWaypointIndex] 를
 * announcedIds 에 추가하고 announceMessage 를 발화한다.
 */
internal fun selectAnnouncementCandidate(
    annotations: List<PathAnnotation>,
    userCumulativeDistance: Double,
    announcedIds: Set<Int>,
    config: NavigatorConfig,
): PathAnnotation? {
    if (annotations.isEmpty()) return null
    return annotations.firstOrNull { ann ->
        if (ann.startWaypointIndex in announcedIds) return@firstOrNull false
        if (ann.announceMessage.isBlank()) return@firstOrNull false
        val triggerDist = announceDistanceFor(ann.type, config)
        val gap = ann.distanceFromStartM - userCumulativeDistance
        gap in 0.0..triggerDist
    }
}

/**
 * annotation type 별 사전 안내 trigger 거리 (m).
 *
 *   SHARP_TURN                    → announceDistanceSharpM   (기본 25m)
 *   TURN / SLIGHT_TURN            → announceDistanceTurnM    (기본 20m)
 *   그 외 (CURVE / INTERNAL_CURVE / SLIGHT_CURVE / STRAIGHT) → announceDistanceCurveM (기본 15m)
 *
 * STRAIGHT 의 trigger 값은 의미가 없지만 (selectAnnouncementCandidate 가 빈 메시지로 거르므로) 안전 기본값을 돌려준다.
 */
internal fun announceDistanceFor(type: PathSegmentType, config: NavigatorConfig): Double = when (type) {
    PathSegmentType.SHARP_TURN -> config.announceDistanceSharpM
    PathSegmentType.TURN, PathSegmentType.SLIGHT_TURN -> config.announceDistanceTurnM
    else -> config.announceDistanceCurveM
}
