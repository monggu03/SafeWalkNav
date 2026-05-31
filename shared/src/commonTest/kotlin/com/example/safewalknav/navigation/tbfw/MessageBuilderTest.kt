package com.example.safewalknav.navigation.tbfw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MessageBuilder 단위 테스트.
 *
 * 2026-05-23 — TrustBasedNavigator 폐기 후 남은 메서드만 검증.
 *   - buildAnnotationAnnounce
 *   - buildInitialHeadingMessage
 *   - buildFlatPosePromptMessage
 */
class MessageBuilderTest {

    private fun ann(
        type: PathSegmentType,
        direction: TurnDirection,
    ): PathAnnotation = PathAnnotation(
        startWaypointIndex = 0,
        endWaypointIndex = 1,
        type = type,
        direction = direction,
        totalAngle = 0.0,
        peakAngle = 0.0,
        distanceFromStartM = 0.0,
        announceMessage = "",
    )

    // ─── buildAnnotationAnnounce ───

    @Test
    fun `STRAIGHT 면 빈 문자열을 돌려준다`() {
        val msg = MessageBuilder.buildAnnotationAnnounce(
            ann(PathSegmentType.STRAIGHT, TurnDirection.LEFT),
        )
        assertEquals("", msg)
    }

    @Test
    fun `direction이 NONE 이면 빈 문자열을 돌려준다`() {
        val msg = MessageBuilder.buildAnnotationAnnounce(
            ann(PathSegmentType.CURVE, TurnDirection.NONE),
        )
        assertEquals("", msg)
    }

    @Test
    fun `CURVE LEFT 면 왼쪽 곡선 안내가 나온다`() {
        val msg = MessageBuilder.buildAnnotationAnnounce(
            ann(PathSegmentType.CURVE, TurnDirection.LEFT),
        )
        assertTrue(msg.contains("왼쪽"), "왼쪽 안내 빠짐: $msg")
        assertTrue(msg.contains("휘어집"), "곡선 표현 빠짐: $msg")
    }

    @Test
    fun `SHARP_TURN RIGHT 면 오른쪽 급회전 안내가 나온다`() {
        val msg = MessageBuilder.buildAnnotationAnnounce(
            ann(PathSegmentType.SHARP_TURN, TurnDirection.RIGHT),
        )
        assertTrue(msg.contains("오른쪽"), "오른쪽 안내 빠짐: $msg")
        assertTrue(msg.contains("크게"), "급회전 표현 빠짐: $msg")
    }

    // ─── buildImminentAnnounce ───

    @Test
    fun `imminent - LEFT 면 지금 왼쪽으로`() {
        val msg = MessageBuilder.buildImminentAnnounce(
            ann(PathSegmentType.TURN, TurnDirection.LEFT),
        )
        assertEquals("지금 왼쪽으로", msg)
    }

    @Test
    fun `imminent - RIGHT 면 지금 오른쪽으로`() {
        val msg = MessageBuilder.buildImminentAnnounce(
            ann(PathSegmentType.SHARP_TURN, TurnDirection.RIGHT),
        )
        assertEquals("지금 오른쪽으로", msg)
    }

    @Test
    fun `imminent - direction NONE 이면 빈 문자열`() {
        val msg = MessageBuilder.buildImminentAnnounce(
            ann(PathSegmentType.CURVE, TurnDirection.NONE),
        )
        assertEquals("", msg)
    }

    @Test
    fun `imminent - CURVE 와 SHARP_TURN 모두 같은 통일된 문구`() {
        // 직전은 심각도 표현 빼고 통일.
        val curve = MessageBuilder.buildImminentAnnounce(
            ann(PathSegmentType.CURVE, TurnDirection.RIGHT),
        )
        val sharp = MessageBuilder.buildImminentAnnounce(
            ann(PathSegmentType.SHARP_TURN, TurnDirection.RIGHT),
        )
        assertEquals(curve, sharp)
    }

    // ─── buildInitialHeadingMessage ───

    @Test
    fun `허용 오차 이내면 정면 직진 안내다`() {
        val msg = MessageBuilder.buildInitialHeadingMessage(diffDeg = 5.0, tolerance = 15.0)
        assertTrue(msg.contains("정면"))
        assertTrue(msg.contains("직진"))
    }

    @Test
    fun `양수 diff 면 오른쪽 회전 안내다`() {
        val msg = MessageBuilder.buildInitialHeadingMessage(diffDeg = 30.0, tolerance = 15.0)
        assertTrue(msg.contains("오른쪽"), "오른쪽 안내 빠짐: $msg")
    }

    @Test
    fun `음수 diff 면 왼쪽 회전 안내다`() {
        val msg = MessageBuilder.buildInitialHeadingMessage(diffDeg = -30.0, tolerance = 15.0)
        assertTrue(msg.contains("왼쪽"), "왼쪽 안내 빠짐: $msg")
    }

    @Test
    fun `절대값이 135도 이상이면 뒤로 돌아 안내다`() {
        val msg = MessageBuilder.buildInitialHeadingMessage(diffDeg = 170.0, tolerance = 15.0)
        assertEquals("뒤로 돌아주세요.", msg)
    }
}
