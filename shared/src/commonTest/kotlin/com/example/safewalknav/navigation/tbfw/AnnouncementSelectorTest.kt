package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.geo.computeCumulativeDistances
import com.example.safewalknav.navigation.tmap.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NavigationManager 가 사용자 진행 거리에 따라 사전 안내 후보를 고르는 로직 단위 테스트.
 *
 * 검증 대상:
 *   1. selectAnnouncementCandidate 의 gap 윈도우 (0 ~ triggerDist) 와 단계 분기 (APPROACH / IMMINENT)
 *   2. type 별 triggerDist 차등 (sharp/turn/curve)
 *   3. announcedKeys (idx, stage) 중복 발화 차단 — stage 별로 dedup
 *   4. 빈 announceMessage 인 annotation 스킵 (STRAIGHT 등)
 *   5. announceDistanceFor 기본값
 *   6. computeCumulativeDistances 누적 거리 계산
 *   7. 음수 gap(지난 회전) 발화 금지 불변식
 */
class AnnouncementSelectorTest {

    private val config = NavigatorConfig()

    private fun ann(
        startIdx: Int,
        distanceFromStartM: Double,
        type: PathSegmentType = PathSegmentType.CURVE,
        message: String = "휘어집니다",
    ): PathAnnotation = PathAnnotation(
        startWaypointIndex = startIdx,
        endWaypointIndex = startIdx + 1,
        type = type,
        direction = TurnDirection.RIGHT,
        totalAngle = 30.0,
        peakAngle = 30.0,
        distanceFromStartM = distanceFromStartM,
        announceMessage = message,
    )

    // ───────── selectAnnouncementCandidate ─────────

    @Test
    fun `빈 리스트면 null`() {
        val result = selectAnnouncementCandidate(
            annotations = emptyList(),
            userCumulativeDistance = 0.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `APPROACH - gap 이 imminent 와 trigger 사이면 APPROACH 단계로 반환`() {
        // CURVE → triggerDist 20m, imminent 5m. distance 100m, user 90m → gap=10m → APPROACH.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 90.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(3, result.annotation.startWaypointIndex)
        assertEquals(AnnouncementStage.APPROACH, result.stage)
        assertEquals(10.0, result.gapM)
    }

    @Test
    fun `IMMINENT - gap 이 imminent 이하면 IMMINENT 단계로 반환`() {
        // CURVE → imminent 5m. distance 100m, user 96m → gap=4m → IMMINENT.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 96.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(AnnouncementStage.IMMINENT, result.stage)
        assertEquals(4.0, result.gapM)
    }

    @Test
    fun `경계 - gap이 정확히 imminentDistanceM이면 IMMINENT 쪽 포함`() {
        // gap = 5.0 → IMMINENT (경계 imminent 포함 규칙).
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 95.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(AnnouncementStage.IMMINENT, result.stage)
    }

    @Test
    fun `gap 이 trigger 보다 크면 아직 안내 안 함`() {
        // CURVE → 20m. distance 100m, user 50m → gap=50m → 윈도우 밖.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 50.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `gap 이 음수면 - 지난 회전은 절대 발화 안 함`() {
        // 안전 불변식 — IMMINENT 가 "지난 회전"으로 잘못 뽑히면 사용자가 반대 방향 안내를 받음.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 120.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `SHARP_TURN - 30m 윈도우 안 APPROACH 진입`() {
        // SHARP_TURN → 30m. distance 100m, user 80m → gap=20m → APPROACH.
        val a = ann(startIdx = 5, distanceFromStartM = 100.0, type = PathSegmentType.SHARP_TURN)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 80.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(AnnouncementStage.APPROACH, result.stage)
    }

    @Test
    fun `CURVE 는 25m gap 에서 아직 트리거 안 됨 - 20m 윈도우 밖`() {
        val a = ann(startIdx = 5, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 75.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `이미 APPROACH 발화된 idx stage 는 후보에서 제외 - 하지만 IMMINENT 는 통과`() {
        // 회전당 예고 1회 + 직전 1회를 위한 핵심 동작. 예고가 idx 를 선점하면 안 됨.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val approachAnnounced = setOf(Pair(3, AnnouncementStage.APPROACH))

        // 사용자가 직전 윈도우(95m, gap=5m) 도달 — APPROACH 는 announced 이지만 IMMINENT 는 미발화.
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 95.0,
            announcedKeys = approachAnnounced,
            config = config,
        )
        assertNotNull(result)
        assertEquals(AnnouncementStage.IMMINENT, result.stage)
    }

    @Test
    fun `APPROACH 단계에서 이미 발화된 idx APPROACH 는 차단`() {
        // 같은 단계 중복 안내 방지.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 85.0,   // gap = 15m → APPROACH 윈도우.
            announcedKeys = setOf(Pair(3, AnnouncementStage.APPROACH)),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `IMMINENT 발화 후 같은 idx IMMINENT 재진입은 차단`() {
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 97.0,
            announcedKeys = setOf(Pair(3, AnnouncementStage.IMMINENT)),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `빈 announceMessage 는 스킵 - STRAIGHT 등`() {
        val a = ann(
            startIdx = 3,
            distanceFromStartM = 100.0,
            type = PathSegmentType.STRAIGHT,
            message = "",
        )
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 95.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `여러 annotation 중 윈도우 안인 첫 번째를 반환`() {
        val a1 = ann(startIdx = 3, distanceFromStartM = 50.0)
        val a2 = ann(startIdx = 7, distanceFromStartM = 55.0)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a1, a2),
            userCumulativeDistance = 45.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(3, result.annotation.startWaypointIndex)
    }

    @Test
    fun `첫 번째가 같은 stage 로 이미 발화됐으면 두 번째 후보 선택`() {
        val a1 = ann(startIdx = 3, distanceFromStartM = 50.0)
        val a2 = ann(startIdx = 7, distanceFromStartM = 55.0)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a1, a2),
            userCumulativeDistance = 40.0,                          // gap1=10, gap2=15 → 둘 다 APPROACH 윈도우.
            announcedKeys = setOf(Pair(3, AnnouncementStage.APPROACH)),
            config = config,
        )
        assertNotNull(result)
        assertEquals(7, result.annotation.startWaypointIndex)
    }

    // ───────── 튜닝 가능한 imminentDistanceM ─────────

    @Test
    fun `imminentDistanceM 튜닝 - 7m 로 설정하면 7m 까지 IMMINENT`() {
        val custom = config.copy(imminentDistanceM = 7.0)
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 94.0,   // gap = 6m → 7m 윈도우 안 → IMMINENT.
            announcedKeys = emptySet(),
            config = custom,
        )
        assertNotNull(result)
        assertEquals(AnnouncementStage.IMMINENT, result.stage)
    }

    // ───────── announceDistanceFor ─────────

    @Test
    fun `announceDistanceFor 기본값 확인`() {
        // 2026-05-31 A-2 후 기본값: CURVE 20 / TURN 25 / SHARP_TURN 30. G0 정렬 위에 얹는 lookahead.
        assertEquals(30.0, announceDistanceFor(PathSegmentType.SHARP_TURN, config))
        assertEquals(25.0, announceDistanceFor(PathSegmentType.TURN, config))
        assertEquals(25.0, announceDistanceFor(PathSegmentType.SLIGHT_TURN, config))
        assertEquals(20.0, announceDistanceFor(PathSegmentType.CURVE, config))
        assertEquals(20.0, announceDistanceFor(PathSegmentType.SLIGHT_CURVE, config))
        assertEquals(20.0, announceDistanceFor(PathSegmentType.INTERNAL_CURVE, config))
        // STRAIGHT 도 안전 기본값 — 의미 없지만 fallback 으로 curve 거리(=20m).
        assertEquals(20.0, announceDistanceFor(PathSegmentType.STRAIGHT, config))
    }

    @Test
    fun `config 튜닝값이 그대로 반영됨`() {
        val custom = NavigatorConfig(
            announceDistanceCurveM = 10.0,
            announceDistanceTurnM = 12.0,
            announceDistanceSharpM = 30.0,
        )
        assertEquals(30.0, announceDistanceFor(PathSegmentType.SHARP_TURN, custom))
        assertEquals(12.0, announceDistanceFor(PathSegmentType.TURN, custom))
        assertEquals(10.0, announceDistanceFor(PathSegmentType.CURVE, custom))
    }

    @Test
    fun `imminentDistanceM 기본값 5m`() {
        assertEquals(5.0, config.imminentDistanceM)
    }

    // ───────── computeCumulativeDistances ─────────

    @Test
    fun `누적 거리 - 빈 리스트면 빈 결과`() {
        assertTrue(computeCumulativeDistances(emptyList()).isEmpty())
    }

    @Test
    fun `누적 거리 - 단일 waypoint 는 0 하나만`() {
        val wp = mkWaypoint(37.5666, 126.9784)
        val result = computeCumulativeDistances(listOf(wp))
        assertEquals(listOf(0.0), result)
    }

    @Test
    fun `누적 거리 - 직선 3개 waypoint 가 단조 증가`() {
        // 서울 시청 부근에서 동쪽으로 일정 간격
        val wps = listOf(
            mkWaypoint(37.5666, 126.9784),
            mkWaypoint(37.5666, 126.9785),
            mkWaypoint(37.5666, 126.9786),
        )
        val result = computeCumulativeDistances(wps)
        assertEquals(3, result.size)
        assertEquals(0.0, result[0])
        assertTrue(result[1] > 0.0, "두번째 누적 거리는 양수여야 함, got=${result[1]}")
        assertTrue(result[2] > result[1], "세번째가 두번째보다 커야 함, got=${result[2]}")
        // 같은 간격(경도 0.0001°)이면 두 구간 거리도 거의 같아야 함.
        val seg1 = result[1] - result[0]
        val seg2 = result[2] - result[1]
        val diff = kotlin.math.abs(seg1 - seg2)
        assertTrue(diff < 0.5, "두 구간 거리가 비슷해야 함 (≈ ${seg1}m vs ${seg2}m)")
    }

    // ───────── INTERNAL_CURVE 범위 안 TURN 계열 차단 (NEW 2026-05-26) ─────────

    @Test
    fun `INTERNAL_CURVE 범위 안 SHARP_TURN 은 발화 차단`() {
        // INTERNAL_CURVE [3..7] 안에 SHARP_TURN at 5 가 있는 케이스.
        val internalCurve = ann(
            startIdx = 3,
            distanceFromStartM = 50.0,
            type = PathSegmentType.INTERNAL_CURVE,
            message = "왼쪽으로 휘어집니다",
        ).copy(endWaypointIndex = 7)
        val sharpTurn = ann(
            startIdx = 5,
            distanceFromStartM = 60.0,
            type = PathSegmentType.SHARP_TURN,
            message = "급좌회전합니다",
        )
        // INTERNAL_CURVE trigger 20m, distance 50m, user 35m → gap 15m → APPROACH 윈도우 안.
        val result = selectAnnouncementCandidate(
            annotations = listOf(internalCurve, sharpTurn),
            userCumulativeDistance = 35.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(3, result.annotation.startWaypointIndex)
        assertEquals(PathSegmentType.INTERNAL_CURVE, result.annotation.type)
    }

    @Test
    fun `INTERNAL_CURVE 범위 밖 SHARP_TURN 은 정상 발화`() {
        val internalCurve = ann(
            startIdx = 3,
            distanceFromStartM = 50.0,
            type = PathSegmentType.INTERNAL_CURVE,
        ).copy(endWaypointIndex = 7)
        val sharpTurn = ann(
            startIdx = 10,
            distanceFromStartM = 200.0,
            type = PathSegmentType.SHARP_TURN,
        )
        // SHARP_TURN APPROACH 윈도우(30m): user 175m → gap 25m.
        val result = selectAnnouncementCandidate(
            annotations = listOf(internalCurve, sharpTurn),
            userCumulativeDistance = 175.0,
            announcedKeys = setOf(Pair(3, AnnouncementStage.APPROACH), Pair(3, AnnouncementStage.IMMINENT)),
            config = config,
        )
        assertNotNull(result)
        assertEquals(10, result.annotation.startWaypointIndex)
        assertEquals(PathSegmentType.SHARP_TURN, result.annotation.type)
    }

    @Test
    fun `INTERNAL_CURVE 범위 안 TURN 과 SLIGHT_TURN 모두 차단`() {
        val internalCurve = ann(
            startIdx = 0,
            distanceFromStartM = 100.0,
            type = PathSegmentType.INTERNAL_CURVE,
        ).copy(endWaypointIndex = 10)
        val turn = ann(
            startIdx = 3,
            distanceFromStartM = 99.0,
            type = PathSegmentType.TURN,
        )
        val slightTurn = ann(
            startIdx = 7,
            distanceFromStartM = 100.0,
            type = PathSegmentType.SLIGHT_TURN,
        )
        val result = selectAnnouncementCandidate(
            annotations = listOf(internalCurve, turn, slightTurn),
            userCumulativeDistance = 80.0,
            announcedKeys = setOf(Pair(0, AnnouncementStage.APPROACH), Pair(0, AnnouncementStage.IMMINENT)),
            config = config,
        )
        assertNull(result, "TURN/SLIGHT_TURN 둘 다 차단되어야 함")
    }

    @Test
    fun `INTERNAL_CURVE 범위 안 CURVE 는 차단 안 됨`() {
        val internalCurve = ann(
            startIdx = 0,
            distanceFromStartM = 100.0,
            type = PathSegmentType.INTERNAL_CURVE,
        ).copy(endWaypointIndex = 10)
        val nestedCurve = ann(
            startIdx = 3,
            distanceFromStartM = 100.0,
            type = PathSegmentType.CURVE,
        )
        val result = selectAnnouncementCandidate(
            annotations = listOf(internalCurve, nestedCurve),
            userCumulativeDistance = 85.0,
            announcedKeys = setOf(Pair(0, AnnouncementStage.APPROACH), Pair(0, AnnouncementStage.IMMINENT)),
            config = config,
        )
        assertNotNull(result)
        assertEquals(3, result.annotation.startWaypointIndex)
        assertEquals(PathSegmentType.CURVE, result.annotation.type)
    }

    @Test
    fun `INTERNAL_CURVE 가 없으면 기존 동작과 동일`() {
        val sharpTurn = ann(
            startIdx = 5,
            distanceFromStartM = 100.0,
            type = PathSegmentType.SHARP_TURN,
        )
        val result = selectAnnouncementCandidate(
            annotations = listOf(sharpTurn),
            userCumulativeDistance = 75.0,
            announcedKeys = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(5, result.annotation.startWaypointIndex)
    }

    private fun mkWaypoint(lat: Double, lon: Double): Waypoint = Waypoint(
        lat = lat,
        lon = lon,
        turnType = 0,
        description = "t",
        distance = 0,
        roadType = 0,
        pointType = "TURN",
    )
}
