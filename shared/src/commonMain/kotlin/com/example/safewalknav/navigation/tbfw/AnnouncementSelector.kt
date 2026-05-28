package com.example.safewalknav.navigation.tbfw

/**
 * RouteAnnotator 가 만든 PathAnnotation 리스트 중에서, 사용자 누적 진행 거리 기준으로
 * 다음에 발화할 후보 하나를 골라낸다.
 *
 * 선택 규칙:
 *   - 이미 발화된 (`announcedIds` 포함) annotation 은 제외
 *   - announceMessage 가 공백/빈 문자열인 annotation 제외 (STRAIGHT 등)
 *   - INTERNAL_CURVE 의 인덱스 범위 안에 들어가는 TURN 계열은 제외 (NEW 2026-05-26)
 *   - `gap = ann.distanceFromStartM - userCumulativeDistance` 가
 *     `0.0 .. triggerDist` 구간에 들어오는 첫 번째 annotation 반환
 *   - 위 조건을 만족하는 게 없으면 null
 *
 * `triggerDist` 는 annotation type 에 따라 [announceDistanceFor] 로 결정한다.
 *
 * 2026-05-26 — INTERNAL_CURVE 범위 안 TURN 계열 차단 추가.
 *   사유: scanInternalCurve 가 검출한 곡선 구간이 Stage A 의 SHARP_TURN 과 동일 위치에
 *   동시 존재할 때, 두 안내가 연달아 발화돼 사용자에게 모순적인 동작 지시가 가는 문제.
 *   데이터 레이어에는 둘 다 보존하되, 발화 레이어에서 INTERNAL_CURVE 를 우선시한다.
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

    // INTERNAL_CURVE 의 인덱스 범위를 모아둔다 — TURN 계열 차단용.
    val internalCurveRanges: List<IntRange> = annotations
        .filter { it.type == PathSegmentType.INTERNAL_CURVE }
        .map { it.startWaypointIndex..it.endWaypointIndex }

    return annotations.firstOrNull { ann ->
        if (ann.startWaypointIndex in announcedIds) return@firstOrNull false
        if (ann.announceMessage.isBlank()) return@firstOrNull false

        // TURN 계열이 어떤 INTERNAL_CURVE 의 범위 안에 들면 발화 차단.
        // INTERNAL_CURVE 자체와 CURVE/SLIGHT_CURVE 는 영향 없음.
        if (ann.isTurnFamily() && internalCurveRanges.any { ann.startWaypointIndex in it }) {
            return@firstOrNull false
        }

        val triggerDist = announceDistanceFor(ann.type, config)
        val gap = ann.distanceFromStartM - userCumulativeDistance
        gap in 0.0..triggerDist
    }
}

/**
 * TURN 계열(SLIGHT_TURN / TURN / SHARP_TURN) 여부.
 *
 * INTERNAL_CURVE 범위 안 발화 차단 정책에서 사용. CURVE / SLIGHT_CURVE / INTERNAL_CURVE / STRAIGHT 는 false.
 */
private fun PathAnnotation.isTurnFamily(): Boolean = when (type) {
    PathSegmentType.SLIGHT_TURN,
    PathSegmentType.TURN,
    PathSegmentType.SHARP_TURN -> true
    else -> false
}

/**
 * annotation type 별 사전 안내 trigger 거리 (m).
 *
 *   SHARP_TURN                    → announceDistanceSharpM   (기본 5m)
 *   TURN / SLIGHT_TURN            → announceDistanceTurnM    (기본 5m)
 *   그 외 (CURVE / INTERNAL_CURVE / SLIGHT_CURVE / STRAIGHT) → announceDistanceCurveM (기본 5m)
 *
 * STRAIGHT 의 trigger 값은 의미가 없지만 (selectAnnouncementCandidate 가 빈 메시지로 거르므로) 안전 기본값을 돌려준다.
 */
internal fun announceDistanceFor(type: PathSegmentType, config: NavigatorConfig): Double = when (type) {
    PathSegmentType.SHARP_TURN -> config.announceDistanceSharpM
    PathSegmentType.TURN, PathSegmentType.SLIGHT_TURN -> config.announceDistanceTurnM
    else -> config.announceDistanceCurveM
}
