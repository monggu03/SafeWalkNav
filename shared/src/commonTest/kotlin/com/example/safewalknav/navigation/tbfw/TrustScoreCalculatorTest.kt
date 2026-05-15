package com.example.safewalknav.navigation.tbfw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TrustScoreCalculator 단위 테스트.
 *
 * 검증 항목:
 *   1. 개별 점수 함수 (GPS, heading, speed)
 *   2. 종합 점수 합산
 *   3. TrustLevel 분류 경계값
 */
class TrustScoreCalculatorTest {

    private val config = NavigatorConfig()  // 기본값 사용

    // ─── GPS Accuracy 점수 ───

    @Test
    fun `GPS 정확도 5m면 HIGH 구간으로 40점이다`() {
        val score = TrustScoreCalculator.scoreGpsAccuracy(5f, config)
        assertEquals(40, score)
    }

    @Test
    fun `GPS 정확도 10m면 HIGH 경계값으로 40점이다`() {
        // 10m 이하가 HIGH이므로 정확히 10m도 포함
        val score = TrustScoreCalculator.scoreGpsAccuracy(10f, config)
        assertEquals(40, score)
    }

    @Test
    fun `GPS 정확도 20m면 MEDIUM 구간으로 25점이다`() {
        val score = TrustScoreCalculator.scoreGpsAccuracy(20f, config)
        assertEquals(25, score)
    }

    @Test
    fun `GPS 정확도 40m면 LOW 구간으로 10점이다`() {
        val score = TrustScoreCalculator.scoreGpsAccuracy(40f, config)
        assertEquals(10, score)
    }

    @Test
    fun `GPS 정확도 100m면 0점이다`() {
        val score = TrustScoreCalculator.scoreGpsAccuracy(100f, config)
        assertEquals(0, score)
    }

    // ─── Heading 점수 ───

    @Test
    fun `heading 차이 0도면 정방향으로 30점이다`() {
        val score = TrustScoreCalculator.scoreHeading(0f, config)
        assertEquals(30, score)
    }

    @Test
    fun `heading 차이 10도면 30점이다`() {
        val score = TrustScoreCalculator.scoreHeading(10f, config)
        assertEquals(30, score)
    }

    @Test
    fun `heading 차이 음수도 절댓값으로 처리된다`() {
        // 좌우 무관하게 같은 점수가 나와야 함
        val scorePositive = TrustScoreCalculator.scoreHeading(20f, config)
        val scoreNegative = TrustScoreCalculator.scoreHeading(-20f, config)
        assertEquals(scorePositive, scoreNegative)
    }

    @Test
    fun `heading 차이 30도면 MID 구간으로 20점이다`() {
        val score = TrustScoreCalculator.scoreHeading(30f, config)
        assertEquals(20, score)
    }

    @Test
    fun `heading 차이 50도면 FAR 구간으로 10점이다`() {
        val score = TrustScoreCalculator.scoreHeading(50f, config)
        assertEquals(10, score)
    }

    @Test
    fun `heading 차이 90도면 0점이다`() {
        val score = TrustScoreCalculator.scoreHeading(90f, config)
        assertEquals(0, score)
    }

    // ─── Speed 점수 ───

    @Test
    fun `보행 속도 1점0 m_s면 정상으로 20점이다`() {
        val score = TrustScoreCalculator.scoreSpeed(1.0f, config)
        assertEquals(20, score)
    }

    @Test
    fun `보행 속도 0점3 m_s면 정상 경계값으로 20점이다`() {
        val score = TrustScoreCalculator.scoreSpeed(0.3f, config)
        assertEquals(20, score)
    }

    @Test
    fun `정지 상태 0 m_s면 비정상으로 5점이다`() {
        val score = TrustScoreCalculator.scoreSpeed(0f, config)
        assertEquals(5, score)
    }

    @Test
    fun `뛰는 속도 3점0 m_s면 비정상으로 5점이다`() {
        val score = TrustScoreCalculator.scoreSpeed(3.0f, config)
        assertEquals(5, score)
    }

    // ─── 종합 점수 ───

    @Test
    fun `이상적 조건이면 만점 90점이다`() {
        // GPS 5m + heading 5도 + 속도 1.0 = 40 + 30 + 20 = 90
        val score = TrustScoreCalculator.calculate(
            gpsAccuracy = 5f,
            headingDiff = 5f,
            speed = 1.0f,
            config = config
        )
        assertEquals(90, score)
    }

    @Test
    fun `최악 조건이면 5점이다`() {
        // GPS 100m + heading 90도 + 속도 0 = 0 + 0 + 5 = 5
        val score = TrustScoreCalculator.calculate(
            gpsAccuracy = 100f,
            headingDiff = 90f,
            speed = 0f,
            config = config
        )
        assertEquals(5, score)
    }

    @Test
    fun `종합 점수는 0과 100 사이다`() {
        // 어떤 입력이 들어와도 점수 범위는 보장돼야 함
        val score = TrustScoreCalculator.calculate(
            gpsAccuracy = 15f,
            headingDiff = 20f,
            speed = 1.5f,
            config = config
        )
        assertTrue(score in 0..100, "점수는 0~100 범위여야 하는데 $score 임")
    }

    // ─── TrustLevel 분류 ───

    @Test
    fun `90점은 HIGH로 분류된다`() {
        assertEquals(TrustLevel.HIGH, TrustScoreCalculator.classify(90))
    }

    @Test
    fun `80점은 HIGH 경계값이다`() {
        assertEquals(TrustLevel.HIGH, TrustScoreCalculator.classify(80))
    }

    @Test
    fun `70점은 MEDIUM이다`() {
        assertEquals(TrustLevel.MEDIUM, TrustScoreCalculator.classify(70))
    }

    @Test
    fun `50점은 LOW다`() {
        assertEquals(TrustLevel.LOW, TrustScoreCalculator.classify(50))
    }

    @Test
    fun `30점은 CRITICAL이다`() {
        assertEquals(TrustLevel.CRITICAL, TrustScoreCalculator.classify(30))
    }

    @Test
    fun `0점은 CRITICAL이다`() {
        assertEquals(TrustLevel.CRITICAL, TrustScoreCalculator.classify(0))
    }
}
