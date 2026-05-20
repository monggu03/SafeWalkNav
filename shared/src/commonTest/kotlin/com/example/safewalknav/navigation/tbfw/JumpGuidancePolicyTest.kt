package com.example.safewalknav.navigation.tbfw

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JumpGuidancePolicy 단위 테스트.
 *
 * 검증 항목:
 *   1. NORMAL/SUSPECT 는 항상 SILENT
 *   2. JUMPED 첫 3초 이내는 SILENT
 *   3. JUMPED 3초 이상 → ANNOUNCE_DEGRADED 1회
 *   4. 같은 점프 세션에서 두 번째 호출은 SILENT
 *   5. 횡단보도 zone 안에서는 JUMPED여도 SILENT
 *   6. JUMPED → NORMAL → JUMPED 시 새 세션으로 처리
 */
class JumpGuidancePolicyTest {
    private val config = NavigatorConfig()

    @Test
    fun `NORMAL이면 항상 SILENT`() {
        val policy = JumpGuidancePolicy(config)
        val action = policy.decide(GpsJumpLevel.NORMAL, 1_000, false)
        assertEquals(JumpGuidanceAction.SILENT, action)
    }

    @Test
    fun `JUMPED 첫 3초 이내는 SILENT`() {
        val policy = JumpGuidancePolicy(config)
        policy.decide(GpsJumpLevel.JUMPED, 1_000, false)
        val action = policy.decide(GpsJumpLevel.JUMPED, 3_500, false)  // 2.5초 경과
        assertEquals(JumpGuidanceAction.SILENT, action)
    }

    @Test
    fun `JUMPED 3초 이상이면 ANNOUNCE_DEGRADED 1회 발화`() {
        val policy = JumpGuidancePolicy(config)
        policy.decide(GpsJumpLevel.JUMPED, 1_000, false)
        val action = policy.decide(GpsJumpLevel.JUMPED, 4_500, false)  // 3.5초 경과
        assertEquals(JumpGuidanceAction.ANNOUNCE_DEGRADED, action)
    }

    @Test
    fun `같은 점프 세션에서 두 번째 호출은 SILENT`() {
        val policy = JumpGuidancePolicy(config)
        policy.decide(GpsJumpLevel.JUMPED, 1_000, false)
        policy.decide(GpsJumpLevel.JUMPED, 4_500, false)  // 첫 발화
        val action = policy.decide(GpsJumpLevel.JUMPED, 6_000, false)  // 두 번째 시도
        assertEquals(JumpGuidanceAction.SILENT, action)
    }

    @Test
    fun `횡단보도 zone 안에서는 JUMPED여도 SILENT`() {
        val policy = JumpGuidancePolicy(config)
        policy.decide(GpsJumpLevel.JUMPED, 1_000, true)
        val action = policy.decide(GpsJumpLevel.JUMPED, 10_000, true)
        assertEquals(JumpGuidanceAction.SILENT, action)
    }

    @Test
    fun `JUMPED에서 NORMAL로 복구 후 다시 JUMPED면 새 세션으로 처리된다`() {
        val policy = JumpGuidancePolicy(config)
        policy.decide(GpsJumpLevel.JUMPED, 1_000, false)
        policy.decide(GpsJumpLevel.JUMPED, 4_500, false)   // 첫 발화
        policy.decide(GpsJumpLevel.NORMAL, 5_000, false)   // 복구
        // 복구 후 쿨다운 5초 경과 후 다시 JUMPED
        policy.decide(GpsJumpLevel.JUMPED, 11_000, false)
        val action = policy.decide(GpsJumpLevel.JUMPED, 14_500, false)  // 3.5초 경과
        assertEquals(JumpGuidanceAction.ANNOUNCE_DEGRADED, action)
    }

    @Test
    fun `SUSPECT는 SILENT이고 점프 세션을 종료한다`() {
        val policy = JumpGuidancePolicy(config)
        // 점프 세션 시작
        policy.decide(GpsJumpLevel.JUMPED, 1_000, false)
        // SUSPECT 한 번 — 세션 종료
        val a1 = policy.decide(GpsJumpLevel.SUSPECT, 2_000, false)
        // 다시 JUMPED — 새 세션 시작 (3초 이내라 SILENT)
        val a2 = policy.decide(GpsJumpLevel.JUMPED, 2_500, false)
        assertEquals(JumpGuidanceAction.SILENT, a1)
        assertEquals(JumpGuidanceAction.SILENT, a2)
    }
}
