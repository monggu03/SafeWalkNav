package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.platform.GpsLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GpsJumpDetector 단위 테스트.
 *
 * 검증 항목:
 *   1. 첫 호출은 항상 NORMAL
 *   2. 보행 범위 내 속도는 NORMAL
 *   3. 5 m/s 이상은 SUSPECT
 *   4. 10 m/s 이상은 즉시 JUMPED
 *   5. SUSPECT 2회 연속이면 JUMPED 승격
 *   6. dt가 비정상(0 이하)이면 NORMAL
 */
class GpsJumpDetectorTest {

    private val config = NavigatorConfig()

    // 헬퍼: 동일 위치(speed=0) 두 점
    private fun stay(lat: Double, lon: Double) = GpsLocation(
        latitude = lat, longitude = lon,
        speed = 0f, bearing = 0f,
        accuracy = 5f, hasAccuracy = true,
    )

    // 헬퍼: 동쪽으로 N미터 이동한 점
    private fun move(from: GpsLocation, eastMeters: Double): GpsLocation {
        val degPerMeter = 1.0 / 87_000.0
        return from.copy(longitude = from.longitude + eastMeters * degPerMeter)
    }

    @Test
    fun `첫 호출은 무조건 NORMAL이다`() {
        val detector = GpsJumpDetector(config)
        val result = detector.update(stay(37.5666, 126.9784), 1_000)
        assertEquals(GpsJumpLevel.NORMAL, result)
    }

    @Test
    fun `1초간 1m 이동은 NORMAL이다`() {
        val detector = GpsJumpDetector(config)
        val p1 = stay(37.5666, 126.9784)
        detector.update(p1, 1_000)
        val result = detector.update(move(p1, 1.0), 2_000)
        assertEquals(GpsJumpLevel.NORMAL, result)
    }

    @Test
    fun `1초간 6m 이동은 SUSPECT이다`() {
        val detector = GpsJumpDetector(config)
        val p1 = stay(37.5666, 126.9784)
        detector.update(p1, 1_000)
        val result = detector.update(move(p1, 6.0), 2_000)
        assertEquals(GpsJumpLevel.SUSPECT, result)
    }

    @Test
    fun `1초간 15m 점프는 즉시 JUMPED다`() {
        val detector = GpsJumpDetector(config)
        val p1 = stay(37.5666, 126.9784)
        detector.update(p1, 1_000)
        val result = detector.update(move(p1, 15.0), 2_000)
        assertEquals(GpsJumpLevel.JUMPED, result)
    }

    @Test
    fun `SUSPECT 2회 연속이면 JUMPED로 승격된다`() {
        val detector = GpsJumpDetector(config)
        val p1 = stay(37.5666, 126.9784)
        detector.update(p1, 1_000)
        val r1 = detector.update(move(p1, 6.0), 2_000)
        // 직전 위치(6m 동쪽)에서 다시 6m 더 동쪽 → 1초 간 6m → SUSPECT
        val p2 = move(p1, 6.0)
        val r2 = detector.update(move(p2, 6.0), 3_000)
        assertEquals(GpsJumpLevel.SUSPECT, r1)
        assertEquals(GpsJumpLevel.JUMPED, r2)
    }

    @Test
    fun `dt가 0이거나 음수면 안전하게 NORMAL이다`() {
        val detector = GpsJumpDetector(config)
        val p1 = stay(37.5666, 126.9784)
        detector.update(p1, 2_000)
        val result = detector.update(move(p1, 100.0), 1_000)  // 시간 거꾸로
        assertEquals(GpsJumpLevel.NORMAL, result)
    }

    @Test
    fun `dt가 길면 평균 속도로 계산된다`() {
        // 30초간 30m 이동 = 1 m_s → NORMAL
        val detector = GpsJumpDetector(config)
        val p1 = stay(37.5666, 126.9784)
        detector.update(p1, 1_000)
        val result = detector.update(move(p1, 30.0), 31_000)
        assertEquals(GpsJumpLevel.NORMAL, result)
    }
}
