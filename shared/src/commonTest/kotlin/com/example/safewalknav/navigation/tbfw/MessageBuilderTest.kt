package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MessageBuilder 단위 테스트.
 *
 * 검증 항목:
 *   1. 메시지 우선순위 (isFinished > CRITICAL > LOW > 통과 > 일반)
 *   2. Trust Level별 메시지
 *   3. Waypoint pointType별 분기 (CROSSWALK, DESTINATION, 기타)
 *   4. 거리 구간별 메시지 (3m / 10m / 그 외)
 */
class MessageBuilderTest {

    /** 테스트용 Waypoint 헬퍼 (pointType만 다르게) */
    private fun waypointOf(pointType: String): Waypoint = Waypoint(
        lat = 37.5, lon = 127.0,
        turnType = 0, description = "test",
        distance = 0, roadType = 0, pointType = pointType
    )

    // ─── 우선순위 1: 종료 ───

    @Test
    fun `isFinished true면 도착 메시지가 최우선이다`() {
        // 다른 조건이 어떻든 isFinished가 true면 도착 메시지
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.CRITICAL,  // CRITICAL이어도
            distance = 100f,                    // 멀어도
            didPassWaypoint = false,
            isFinished = true,
            currentTarget = null
        )
        assertEquals(MessageBuilder.MSG_ARRIVED_DESTINATION, msg)
    }

    // ─── 우선순위 2: CRITICAL ───

    @Test
    fun `Trust CRITICAL이면 정지 안내 메시지다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.CRITICAL,
            distance = 5f,
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("TURN")
        )
        assertEquals(MessageBuilder.MSG_TRUST_CRITICAL, msg)
    }

    @Test
    fun `Trust CRITICAL이면 통과 처리 여부 무시하고 정지 안내다`() {
        // didPassWaypoint=true여도 CRITICAL이 우선
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.CRITICAL,
            distance = 5f,
            didPassWaypoint = true,             // 통과했다 해도
            isFinished = false,
            currentTarget = waypointOf("TURN")
        )
        assertEquals(MessageBuilder.MSG_TRUST_CRITICAL, msg)
    }

    // ─── 우선순위 3: LOW ───

    @Test
    fun `Trust LOW면 보수적 안내 메시지다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.LOW,
            distance = 5f,
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("TURN")
        )
        assertEquals(MessageBuilder.MSG_TRUST_LOW, msg)
    }

    @Test
    fun `Trust LOW면 거리 안내가 들어가지 않는다`() {
        // GPS를 못 믿는 상태에서 거리 숫자를 쓰면 위험
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.LOW,
            distance = 8f,
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("TURN")
        )
        // 거리 숫자(8)가 메시지에 포함되면 안 됨
        assertTrue(
            !msg.contains("8") && !msg.contains("미터"),
            "Trust LOW 메시지에 거리가 포함됨: $msg"
        )
    }

    // ─── 우선순위 4: waypoint 통과 ───

    @Test
    fun `통과 처리됐고 일반 타입이면 기본 통과 메시지다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 5f,
            didPassWaypoint = true,
            isFinished = false,
            currentTarget = waypointOf("TURN")
        )
        assertEquals(MessageBuilder.MSG_PASSED_GENERIC, msg)
    }

    @Test
    fun `통과 처리됐고 다음 목표가 CROSSWALK면 횡단보도 안내가 들어간다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 5f,
            didPassWaypoint = true,
            isFinished = false,
            currentTarget = waypointOf("CROSSWALK")
        )
        assertTrue(msg.contains("횡단보도"), "CROSSWALK 안내가 빠짐: $msg")
    }

    @Test
    fun `통과 처리됐고 다음 목표가 DESTINATION이면 도착 임박 메시지다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 5f,
            didPassWaypoint = true,
            isFinished = false,
            currentTarget = waypointOf("DESTINATION")
        )
        assertTrue(msg.contains("도착"), "도착 안내가 빠짐: $msg")
    }

    @Test
    fun `통과 처리됐는데 currentTarget이 null이면 기본 통과 메시지다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 5f,
            didPassWaypoint = true,
            isFinished = false,
            currentTarget = null
        )
        assertEquals(MessageBuilder.MSG_PASSED_GENERIC, msg)
    }

    // ─── 우선순위 5: 일반 거리 안내 (3m 미만) ───

    @Test
    fun `매우 가까운 거리에서 DESTINATION이면 도착 메시지다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 2f,                      // 3m 미만
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("DESTINATION")
        )
        assertEquals(MessageBuilder.MSG_ARRIVED_DESTINATION, msg)
    }

    @Test
    fun `매우 가까운 거리에서 CROSSWALK면 횡단보도 앞 안내다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 2f,
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("CROSSWALK")
        )
        assertTrue(msg.contains("횡단보도"), "메시지: $msg")
        assertTrue(msg.contains("신호"), "신호 확인 안내가 빠짐: $msg")
    }

    @Test
    fun `매우 가까운 거리에서 일반 타입이면 곧 도착 안내다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 2f,
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("TURN")
        )
        assertEquals("곧 다음 지점입니다.", msg)
    }

    // ─── 우선순위 5: 일반 거리 안내 (3~10m) ───

    @Test
    fun `가까운 거리에서 CROSSWALK면 거리와 함께 횡단보도 안내다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 7f,                      // 3~10m
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("CROSSWALK")
        )
        assertTrue(msg.contains("7"), "거리 숫자 빠짐: $msg")
        assertTrue(msg.contains("횡단보도"), "횡단보도 안내 빠짐: $msg")
    }

    @Test
    fun `가까운 거리에서 DESTINATION이면 거리와 함께 목적지 안내다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 7f,
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("DESTINATION")
        )
        assertTrue(msg.contains("7"))
        assertTrue(msg.contains("목적지"))
    }

    // ─── 우선순위 5: 일반 거리 안내 (10m 이상) ───

    @Test
    fun `먼 거리면 일반 거리 안내다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 50f,
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("CROSSWALK")
        )
        // 10m 이상에서는 pointType과 무관하게 일반 안내
        assertEquals("50미터 앞으로 이동하세요.", msg)
    }

    @Test
    fun `거리는 정수로 변환된다`() {
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.HIGH,
            distance = 23.7f,                   // 소수점 있어도
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("TURN")
        )
        assertTrue(msg.contains("23"), "정수 변환 실패: $msg")
        assertTrue(!msg.contains("23.7"), "소수점이 그대로 노출됨: $msg")
    }

    // ─── MEDIUM 신뢰도 처리 ───

    @Test
    fun `Trust MEDIUM도 일반 안내가 정상 작동한다`() {
        // MEDIUM은 별도 분기 없이 일반 흐름을 따라야 함
        val msg = MessageBuilder.build(
            trustLevel = TrustLevel.MEDIUM,
            distance = 30f,
            didPassWaypoint = false,
            isFinished = false,
            currentTarget = waypointOf("TURN")
        )
        assertEquals("30미터 앞으로 이동하세요.", msg)
    }
}
