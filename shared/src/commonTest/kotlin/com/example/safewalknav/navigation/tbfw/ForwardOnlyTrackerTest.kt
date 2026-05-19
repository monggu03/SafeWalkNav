package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.tmap.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * ForwardOnlyTracker 단위 테스트.
 *
 * 검증 항목:
 *   1. 초기 상태
 *   2. 통과 조건 (거리/Trust/heading 모두 만족)
 *   3. Trust Level별 차등 동작 (HIGH/MEDIUM/LOW/CRITICAL)
 *   4. Forward-Only 보장 (인덱스 단조 증가)
 *   5. 종료 상태 처리
 */
class ForwardOnlyTrackerTest {

    private val config = NavigatorConfig()

    /**
     * 테스트용 waypoint 3개를 만든다.
     * 좌표는 의미 없음 (거리는 update 호출 시 직접 넘기므로).
     */
    private fun makeWaypoints(): List<Waypoint> = listOf(
        Waypoint(
            lat = 37.5000, lon = 127.0000,
            turnType = 0, description = "W1",
            distance = 50, roadType = 0, pointType = "TURN"
        ),
        Waypoint(
            lat = 37.5010, lon = 127.0010,
            turnType = 0, description = "W2",
            distance = 50, roadType = 0, pointType = "CROSSWALK"
        ),
        Waypoint(
            lat = 37.5020, lon = 127.0020,
            turnType = 0, description = "W3",
            distance = 0, roadType = 0, pointType = "DESTINATION"
        ),
    )

    // ─── 초기 상태 ───

    @Test
    fun `초기 인덱스는 0이다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())
        assertEquals(0, tracker.currentIndex)
    }

    @Test
    fun `초기에는 종료 상태가 아니다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())
        assertFalse(tracker.isFinished)
    }

    @Test
    fun `초기 currentTarget은 첫번째 waypoint다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())
        val target = tracker.currentTarget
        assertNotNull(target)
        assertEquals("W1", target.description)
    }

    // ─── 통과 조건 (HIGH) ───

    @Test
    fun `HIGH 신뢰도에서 모든 조건 만족하면 통과 처리된다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        val passed = tracker.update(
            distance = 5f,           // 8m 이내 ✓
            trustScore = 90,
            trustLevel = TrustLevel.HIGH,  // ✓
            headingDiff = 10f,       // 45도 이내 ✓
            config = config
        )

        assertTrue(passed)
        assertEquals(1, tracker.currentIndex)
    }

    @Test
    fun `HIGH 신뢰도여도 거리가 멀면 통과 안 된다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        val passed = tracker.update(
            distance = 20f,          // 8m 초과 ✗
            trustScore = 90,
            trustLevel = TrustLevel.HIGH,
            headingDiff = 0f,
            config = config
        )

        assertFalse(passed)
        assertEquals(0, tracker.currentIndex)
    }

    @Test
    fun `HIGH 신뢰도여도 heading이 너무 어긋나면 통과 안 된다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        val passed = tracker.update(
            distance = 5f,
            trustScore = 90,
            trustLevel = TrustLevel.HIGH,
            headingDiff = 60f,       // 45도 초과 ✗
            config = config
        )

        assertFalse(passed)
        assertEquals(0, tracker.currentIndex)
    }

    @Test
    fun `Trust Score가 통과 기준 이하면 통과 안 된다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        val passed = tracker.update(
            distance = 5f,
            trustScore = 60,         // trustScoreForPass(60) 이하 ✗
            trustLevel = TrustLevel.MEDIUM,
            headingDiff = 0f,
            config = config
        )

        assertFalse(passed)
    }

    // ─── Trust Level별 차등 동작 ───

    @Test
    fun `MEDIUM 신뢰도에서 12m 이내면 통과 처리된다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        // HIGH였으면 8m 초과로 통과 안 됐을 거리
        val passed = tracker.update(
            distance = 11f,
            trustScore = 70,
            trustLevel = TrustLevel.MEDIUM,
            headingDiff = 0f,
            config = config
        )

        assertTrue(passed, "MEDIUM은 12m까지 허용해야 함")
    }

    @Test
    fun `MEDIUM 신뢰도여도 12m 초과면 통과 안 된다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        val passed = tracker.update(
            distance = 15f,
            trustScore = 70,
            trustLevel = TrustLevel.MEDIUM,
            headingDiff = 0f,
            config = config
        )

        assertFalse(passed)
    }

    @Test
    fun `LOW 신뢰도면 거리가 가까워도 통과 처리 안 한다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        val passed = tracker.update(
            distance = 1f,           // 코앞에 있어도
            trustScore = 50,
            trustLevel = TrustLevel.LOW,
            headingDiff = 0f,
            config = config
        )

        assertFalse(passed, "Trust LOW면 통과 보류해야 함")
        assertEquals(0, tracker.currentIndex)
    }

    @Test
    fun `CRITICAL 신뢰도면 통과 처리 안 한다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        val passed = tracker.update(
            distance = 1f,
            trustScore = 20,
            trustLevel = TrustLevel.CRITICAL,
            headingDiff = 0f,
            config = config
        )

        assertFalse(passed, "Trust CRITICAL이면 통과 동결해야 함")
        assertEquals(0, tracker.currentIndex)
    }

    // ─── Forward-Only 보장 ───

    @Test
    fun `한 번 통과한 인덱스는 절대 감소하지 않는다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        // W1 통과
        tracker.update(
            distance = 5f, trustScore = 90, trustLevel = TrustLevel.HIGH,
            headingDiff = 0f, config = config
        )
        val indexAfterPass = tracker.currentIndex
        assertEquals(1, indexAfterPass)

        // GPS가 튀어서 W1 근처로 돌아간 척
        // (이전 waypoint와 다시 가까워진 상황을 시뮬레이션)
        tracker.update(
            distance = 100f,         // 멀어졌다고 가정
            trustScore = 50,
            trustLevel = TrustLevel.LOW,
            headingDiff = 30f,
            config = config
        )

        // 인덱스는 여전히 1이어야 함 (감소 X)
        assertEquals(1, tracker.currentIndex, "Forward-Only가 깨졌음")
    }

    @Test
    fun `여러 waypoint 순차 통과시 인덱스가 단조 증가한다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        // W1 통과
        tracker.update(5f, 90, TrustLevel.HIGH, 0f, config)
        assertEquals(1, tracker.currentIndex)

        // W2 통과
        tracker.update(5f, 90, TrustLevel.HIGH, 0f, config)
        assertEquals(2, tracker.currentIndex)

        // W3 통과 (마지막)
        tracker.update(5f, 90, TrustLevel.HIGH, 0f, config)
        assertEquals(3, tracker.currentIndex)
    }

    // ─── 종료 상태 ───

    @Test
    fun `모든 waypoint 통과하면 isFinished가 true다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        // 3개 다 통과
        repeat(3) {
            tracker.update(5f, 90, TrustLevel.HIGH, 0f, config)
        }

        assertTrue(tracker.isFinished)
    }

    @Test
    fun `종료 상태에서 currentTarget은 null이다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        repeat(3) {
            tracker.update(5f, 90, TrustLevel.HIGH, 0f, config)
        }

        assertNull(tracker.currentTarget)
    }

    @Test
    fun `종료 상태에서 update 호출해도 false를 반환한다`() {
        val tracker = ForwardOnlyTracker(makeWaypoints())

        // 3개 다 통과
        repeat(3) {
            tracker.update(5f, 90, TrustLevel.HIGH, 0f, config)
        }

        // 그 후 호출
        val result = tracker.update(5f, 90, TrustLevel.HIGH, 0f, config)

        assertFalse(result)
        assertEquals(3, tracker.currentIndex, "종료 후엔 인덱스 변화 없어야 함")
    }

    // ─── 빈 waypoint 리스트 ───

    @Test
    fun `빈 waypoint 리스트면 처음부터 종료 상태다`() {
        val tracker = ForwardOnlyTracker(emptyList())

        assertTrue(tracker.isFinished)
        assertNull(tracker.currentTarget)
    }
}
