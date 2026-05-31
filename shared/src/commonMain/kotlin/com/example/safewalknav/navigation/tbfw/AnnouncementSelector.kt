package com.example.safewalknav.navigation.tbfw

/**
 * 회전 안내 단계.
 *
 *  - APPROACH: 회전 예고 — `gap > imminentDistanceM && gap <= triggerDist`. 기존 "곧 …" 문구.
 *  - IMMINENT: 회전 직전 — `gap in 0.0 .. imminentDistanceM`. 신규 "지금 …" 선점 발화.
 *
 * 두 윈도우는 겹치지 않으며 경계(imminentDistanceM)는 IMMINENT 쪽 포함.
 * 음수 gap(이미 지난 회전)은 어느 단계도 아님.
 */
enum class AnnouncementStage { APPROACH, IMMINENT }

/**
 * selectAnnouncementCandidate 의 반환 타입.
 * stage / gapM 은 호출자(NavigationManager) 의 게이트 분기·로깅에 사용.
 */
data class AnnouncementPick(
    val annotation: PathAnnotation,
    val stage: AnnouncementStage,
    val gapM: Double,
)

/**
 * RouteAnnotator 가 만든 PathAnnotation 리스트 중에서, 사용자 누적 진행 거리 기준으로
 * 다음에 발화할 후보 하나를 단계(APPROACH / IMMINENT) 와 함께 골라낸다.
 *
 * 선택 규칙:
 *   - announceMessage 가 공백/빈 문자열인 annotation 제외 (STRAIGHT 등)
 *   - INTERNAL_CURVE 의 인덱스 범위 안에 들어가는 TURN 계열은 제외 (2026-05-26)
 *   - `gap = ann.distanceFromStartM - userCumulativeDistance` 를 단계로 매핑:
 *       * gap < 0                                              → 발화 금지 (안전: 지난 회전 X)
 *       * gap in 0.0 .. imminentDistanceM                      → IMMINENT
 *       * gap > imminentDistanceM && gap <= triggerDist        → APPROACH
 *       * else                                                 → 발화 금지
 *   - dedup 키는 `(startWaypointIndex, stage)` — 같은 회전이라도 APPROACH 1회 + IMMINENT 1회 가능.
 *   - 위 조건을 만족하는 첫 번째 annotation 을 [AnnouncementPick] 으로 감싸 반환.
 *   - 없으면 null.
 *
 * `triggerDist` 는 annotation type 에 따라 [announceDistanceFor] 로 결정한다.
 *
 * 2026-05-26 — INTERNAL_CURVE 범위 안 TURN 계열 차단 추가.
 *   사유: scanInternalCurve 가 검출한 곡선 구간이 Stage A 의 SHARP_TURN 과 동일 위치에
 *   동시 존재할 때, 두 안내가 연달아 발화돼 사용자에게 모순적인 동작 지시가 가는 문제.
 *   데이터 레이어에는 둘 다 보존하되, 발화 레이어에서 INTERNAL_CURVE 를 우선시한다.
 *
 * 2026-05-31 — 단계(APPROACH / IMMINENT) 분기 + (idx, stage) dedup.
 *   사유: 회전 하나당 예고(20/25/30m) + 직전(5m) 두 단계로 안내하기 위함.
 *   dedup 키를 (idx, stage) 로 바꾸지 않으면 예고가 idx 를 선점해 직전이 영영 안 나옴.
 */
internal fun selectAnnouncementCandidate(
    annotations: List<PathAnnotation>,
    userCumulativeDistance: Double,
    announcedKeys: Set<Pair<Int, AnnouncementStage>>,
    config: NavigatorConfig,
): AnnouncementPick? {
    if (annotations.isEmpty()) return null

    // INTERNAL_CURVE 의 인덱스 범위를 모아둔다 — TURN 계열 차단용.
    val internalCurveRanges: List<IntRange> = annotations
        .filter { it.type == PathSegmentType.INTERNAL_CURVE }
        .map { it.startWaypointIndex..it.endWaypointIndex }

    return annotations.firstNotNullOfOrNull { ann ->
        if (ann.announceMessage.isBlank()) return@firstNotNullOfOrNull null

        // TURN 계열이 어떤 INTERNAL_CURVE 의 범위 안에 들면 발화 차단.
        if (ann.isTurnFamily() && internalCurveRanges.any { ann.startWaypointIndex in it }) {
            return@firstNotNullOfOrNull null
        }

        val triggerDist = announceDistanceFor(ann.type, config)
        val gap = ann.distanceFromStartM - userCumulativeDistance
        val stage = when {
            gap < 0.0 -> null                                  // 지난 회전 발화 금지
            gap <= config.imminentDistanceM -> AnnouncementStage.IMMINENT
            gap <= triggerDist -> AnnouncementStage.APPROACH
            else -> null
        } ?: return@firstNotNullOfOrNull null

        if (Pair(ann.startWaypointIndex, stage) in announcedKeys) {
            return@firstNotNullOfOrNull null
        }

        AnnouncementPick(annotation = ann, stage = stage, gapM = gap)
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
 * annotation type 별 사전 안내 trigger 거리 (m). 예고(APPROACH) 윈도우의 바깥 경계.
 *
 *   SHARP_TURN                    → announceDistanceSharpM   (기본 30m)
 *   TURN / SLIGHT_TURN            → announceDistanceTurnM    (기본 25m)
 *   그 외 (CURVE / INTERNAL_CURVE / SLIGHT_CURVE / STRAIGHT) → announceDistanceCurveM (기본 20m)
 *
 * STRAIGHT 의 trigger 값은 의미가 없지만 (selectAnnouncementCandidate 가 빈 메시지로 거르므로) 안전 기본값을 돌려준다.
 */
internal fun announceDistanceFor(type: PathSegmentType, config: NavigatorConfig): Double = when (type) {
    PathSegmentType.SHARP_TURN -> config.announceDistanceSharpM
    PathSegmentType.TURN, PathSegmentType.SLIGHT_TURN -> config.announceDistanceTurnM
    else -> config.announceDistanceCurveM
}
