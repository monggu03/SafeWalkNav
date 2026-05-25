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
 *   1. selectAnnouncementCandidate 의 gap 윈도우 (0 ~ triggerDist)
 *   2. type 별 triggerDist 차등 (sharp/turn/curve)
 *   3. announcedIds 중복 발화 차단
 *   4. 빈 announceMessage 인 annotation 스킵 (STRAIGHT 등)
 *   5. announceDistanceFor 기본값
 *   6. computeCumulativeDistances 누적 거리 계산
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
            announcedIds = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `gap 이 trigger 윈도우 안이면 그 annotation 반환`() {
        // CURVE → triggerDist = 15m. distanceFromStart 100m, user 95m → gap=5m → 안내 대상.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 95.0,
            announcedIds = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(3, result.startWaypointIndex)
    }

    @Test
    fun `gap 이 trigger 보다 크면 아직 안내 안 함`() {
        // CURVE → 15m. distance 100m, user 50m → gap=50m → 윈도우 밖.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 50.0,
            announcedIds = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `gap 이 음수면 - annotation 을 지나쳤으면 스킵`() {
        // user 가 annotation 너머로 진행한 상태 — 더 이상 안내 안 함.
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 120.0,
            announcedIds = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `SHARP_TURN 은 더 먼 거리에서도 트리거 - 25m 윈도우`() {
        // SHARP_TURN → 25m. distance 100m, user 80m → gap=20m → 윈도우 안.
        val a = ann(startIdx = 5, distanceFromStartM = 100.0, type = PathSegmentType.SHARP_TURN)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 80.0,
            announcedIds = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(5, result.startWaypointIndex)
    }

    @Test
    fun `CURVE 는 20m gap 에서 아직 트리거 안 됨 - 15m 윈도우 밖`() {
        // 같은 100m/80m 조건이라도 CURVE 면 15m 윈도우 → gap 20m → 아직.
        val a = ann(startIdx = 5, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 80.0,
            announcedIds = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `이미 발화된 id 는 후보에서 제외`() {
        val a = ann(startIdx = 3, distanceFromStartM = 100.0, type = PathSegmentType.CURVE)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a),
            userCumulativeDistance = 95.0,
            announcedIds = setOf(3),
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
            announcedIds = emptySet(),
            config = config,
        )
        assertNull(result)
    }

    @Test
    fun `여러 annotation 중 윈도우 안인 첫 번째를 반환`() {
        // 두 annotation 모두 윈도우 안 — firstOrNull 이므로 리스트 순서대로 첫 번째.
        val a1 = ann(startIdx = 3, distanceFromStartM = 50.0)
        val a2 = ann(startIdx = 7, distanceFromStartM = 55.0)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a1, a2),
            userCumulativeDistance = 45.0,
            announcedIds = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(3, result.startWaypointIndex)
    }

    @Test
    fun `첫 번째가 이미 발화됐으면 두 번째 후보 선택`() {
        val a1 = ann(startIdx = 3, distanceFromStartM = 50.0)
        val a2 = ann(startIdx = 7, distanceFromStartM = 55.0)
        val result = selectAnnouncementCandidate(
            annotations = listOf(a1, a2),
            userCumulativeDistance = 45.0,
            announcedIds = setOf(3),
            config = config,
        )
        assertNotNull(result)
        assertEquals(7, result.startWaypointIndex)
    }

    // ───────── announceDistanceFor ─────────

    @Test
    fun `announceDistanceFor 기본값 확인`() {
        assertEquals(25.0, announceDistanceFor(PathSegmentType.SHARP_TURN, config))
        assertEquals(20.0, announceDistanceFor(PathSegmentType.TURN, config))
        assertEquals(20.0, announceDistanceFor(PathSegmentType.SLIGHT_TURN, config))
        assertEquals(15.0, announceDistanceFor(PathSegmentType.CURVE, config))
        assertEquals(15.0, announceDistanceFor(PathSegmentType.SLIGHT_CURVE, config))
        assertEquals(15.0, announceDistanceFor(PathSegmentType.INTERNAL_CURVE, config))
        // STRAIGHT 도 안전 기본값 — 의미 없지만 fallback 으로 curve 거리.
        assertEquals(15.0, announceDistanceFor(PathSegmentType.STRAIGHT, config))
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
