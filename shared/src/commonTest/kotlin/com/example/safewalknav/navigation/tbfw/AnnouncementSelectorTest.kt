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

    // ───────── INTERNAL_CURVE 범위 안 TURN 계열 차단 (NEW 2026-05-26) ─────────

    @Test
    fun `INTERNAL_CURVE 범위 안 SHARP_TURN 은 발화 차단`() {
        // INTERNAL_CURVE [3..7] 안에 SHARP_TURN at 5 가 있는 케이스.
        // 사용자가 안내 윈도우에 들어가도 SHARP_TURN 은 후보에서 제외돼야 함.
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
        // SHARP_TURN trigger 25m, distance 60m, user 40m → gap 20m → 윈도우 안.
        // 하지만 INTERNAL_CURVE 범위 안이므로 차단되어야 함.
        val result = selectAnnouncementCandidate(
            annotations = listOf(internalCurve, sharpTurn),
            userCumulativeDistance = 40.0,
            announcedIds = emptySet(),
            config = config,
        )
        // 결과: INTERNAL_CURVE (startIdx=3) 가 선택됨. SHARP_TURN(startIdx=5)은 차단.
        assertNotNull(result)
        assertEquals(3, result.startWaypointIndex)
        assertEquals(PathSegmentType.INTERNAL_CURVE, result.type)
    }

    @Test
    fun `INTERNAL_CURVE 범위 밖 SHARP_TURN 은 정상 발화`() {
        // INTERNAL_CURVE [3..7], SHARP_TURN at 10 — 범위 밖이므로 차단 안 됨.
        val internalCurve = ann(
            startIdx = 3,
            distanceFromStartM = 50.0,
            type = PathSegmentType.INTERNAL_CURVE,
        ).copy(endWaypointIndex = 7)
        val sharpTurn = ann(
            startIdx = 10,
            distanceFromStartM = 200.0,  // 멀리 떨어진 별개 회전
            type = PathSegmentType.SHARP_TURN,
        )
        // INTERNAL_CURVE 발화 직후 announcedIds 에 추가됐다고 가정.
        // 그 후 SHARP_TURN 윈도우에 도달했을 때 정상 발화돼야 함.
        val result = selectAnnouncementCandidate(
            annotations = listOf(internalCurve, sharpTurn),
            userCumulativeDistance = 180.0,  // SHARP_TURN 의 25m 윈도우 안
            announcedIds = setOf(3),         // INTERNAL_CURVE 는 이미 발화됨
            config = config,
        )
        assertNotNull(result)
        assertEquals(10, result.startWaypointIndex)
        assertEquals(PathSegmentType.SHARP_TURN, result.type)
    }

    @Test
    fun `INTERNAL_CURVE 범위 안 TURN 과 SLIGHT_TURN 모두 차단`() {
        // 한 INTERNAL_CURVE 안에 TURN 과 SLIGHT_TURN 이 같이 있는 케이스 — 둘 다 차단.
        val internalCurve = ann(
            startIdx = 0,
            distanceFromStartM = 100.0,
            type = PathSegmentType.INTERNAL_CURVE,
        ).copy(endWaypointIndex = 10)
        val turn = ann(
            startIdx = 3,
            distanceFromStartM = 105.0,
            type = PathSegmentType.TURN,
        )
        val slightTurn = ann(
            startIdx = 7,
            distanceFromStartM = 115.0,
            type = PathSegmentType.SLIGHT_TURN,
        )
        // INTERNAL_CURVE 가 이미 발화됐다고 가정. TURN/SLIGHT_TURN 윈도우 도달 시 모두 차단.
        val result = selectAnnouncementCandidate(
            annotations = listOf(internalCurve, turn, slightTurn),
            userCumulativeDistance = 95.0,
            announcedIds = setOf(0),
            config = config,
        )
        assertNull(result, "TURN/SLIGHT_TURN 둘 다 차단되어야 함")
    }

    @Test
    fun `INTERNAL_CURVE 범위 안 CURVE 는 차단 안 됨`() {
        // 정책은 TURN 계열만 차단. CURVE / SLIGHT_CURVE 는 영향 없음.
        val internalCurve = ann(
            startIdx = 0,
            distanceFromStartM = 100.0,
            type = PathSegmentType.INTERNAL_CURVE,
        ).copy(endWaypointIndex = 10)
        val nestedCurve = ann(
            startIdx = 3,
            distanceFromStartM = 105.0,
            type = PathSegmentType.CURVE,
        )
        val result = selectAnnouncementCandidate(
            annotations = listOf(internalCurve, nestedCurve),
            userCumulativeDistance = 95.0,
            announcedIds = setOf(0),  // INTERNAL_CURVE 발화됨
            config = config,
        )
        // CURVE 가 선택됨 — 차단 정책은 TURN 계열에만 적용.
        assertNotNull(result)
        assertEquals(3, result.startWaypointIndex)
        assertEquals(PathSegmentType.CURVE, result.type)
    }

    @Test
    fun `INTERNAL_CURVE 가 없으면 기존 동작과 동일`() {
        // 회귀 테스트 — INTERNAL_CURVE 가 리스트에 없으면 SHARP_TURN 정상 발화.
        val sharpTurn = ann(
            startIdx = 5,
            distanceFromStartM = 100.0,
            type = PathSegmentType.SHARP_TURN,
        )
        val result = selectAnnouncementCandidate(
            annotations = listOf(sharpTurn),
            userCumulativeDistance = 80.0,
            announcedIds = emptySet(),
            config = config,
        )
        assertNotNull(result)
        assertEquals(5, result.startWaypointIndex)
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
