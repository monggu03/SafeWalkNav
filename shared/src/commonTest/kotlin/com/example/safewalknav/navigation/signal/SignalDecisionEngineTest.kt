package com.example.safewalknav.navigation.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * SignalDecisionEngine 안전 로직 테스트.
 *
 * 이 로직은 "지금 건너도 되는지"를 판단한다. 규칙이 하나라도 깨지면 사람이 다칠 수 있으므로
 * 각 안전 규칙을 개별로 못 박는다. (Android/iOS 가 이 하나를 공유하므로 여기만 지키면 됨)
 */
class SignalDecisionEngineTest {

    private val cfg = SignalDecisionConfig()  // 운영 기본값

    // 헬퍼: 충분히 크고 확신 있는 검출 하나
    private fun red(conf: Float = 0.9f) = RawSignalDetection(0, conf, 0.1f, 0.1f)
    private fun green(conf: Float = 0.9f) = RawSignalDetection(1, conf, 0.1f, 0.1f)

    /** 안정성 프레임을 채워 특정 색을 확정 상태로 만든다. */
    private fun SignalDecisionEngine.confirm(det: RawSignalDetection, frames: Int, startMs: Long): Long {
        var t = startMs
        repeat(frames) { decide(listOf(det), t); t += 100 }
        return t
    }

    // ─────────────────────────────────────────────────────────────
    // 1) 크기 하한
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 너무_작은_박스는_침묵_ALL_TOO_SMALL() {
        val engine = SignalDecisionEngine(cfg)
        val tiny = RawSignalDetection(0, 0.9f, 0.005f, 0.005f)  // 0.5% < 0.8%
        val d = engine.decide(listOf(tiny), 1000)
        assertIs<SignalDecision.Silent>(d)
        assertEquals(SilentReason.ALL_TOO_SMALL, d.reason)
    }

    @Test
    fun 검출이_아예_없으면_NO_DETECTION() {
        val engine = SignalDecisionEngine(cfg)
        val d = engine.decide(emptyList(), 1000)
        assertIs<SignalDecision.Silent>(d)
        assertEquals(SilentReason.NO_DETECTION, d.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // 2) 신뢰도 비대칭 — 이게 안전의 핵심
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 초록은_045미만이면_침묵한다() {
        val engine = SignalDecisionEngine(cfg)
        // 0.40 초록 — 빨강 임계(0.25)는 넘지만 초록 임계(0.45)는 못 넘음
        val d = engine.decide(listOf(green(0.40f)), 1000)
        assertIs<SignalDecision.Silent>(d)
        assertEquals(SilentReason.ALL_LOW_CONFIDENCE, d.reason)
    }

    @Test
    fun 빨강은_같은_040신뢰도로도_통과한다() {
        val engine = SignalDecisionEngine(cfg)
        // 같은 0.40 이라도 빨강은 임계 0.25 를 넘으므로 후보로 살아남아 안정성 단계로 감
        val d = engine.decide(listOf(red(0.40f)), 1000)
        // 아직 1프레임이라 STABILITY_PENDING (침묵이지만 이유가 다름 = 필터는 통과)
        assertIs<SignalDecision.Silent>(d)
        assertEquals(SilentReason.STABILITY_PENDING, d.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // 3) 안정성 프레임
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 빨강은_2프레임에_확정된다() {
        val engine = SignalDecisionEngine(cfg)
        assertIs<SignalDecision.Silent>(engine.decide(listOf(red()), 1000))  // 1프레임
        val d = engine.decide(listOf(red()), 1100)                           // 2프레임 → 확정
        assertIs<SignalDecision.Announce>(d)
        assertEquals(SignalDecisionEngine.COLOR_RED, d.color)
        assertEquals(SignalTransition.RED_NEW, d.transition)
    }

    @Test
    fun 초록은_3프레임_필요하다() {
        val engine = SignalDecisionEngine(cfg)
        assertIs<SignalDecision.Silent>(engine.decide(listOf(green()), 1000))  // 1
        assertIs<SignalDecision.Silent>(engine.decide(listOf(green()), 1100))  // 2
        val d = engine.decide(listOf(green()), 1200)                           // 3 → 확정
        assertIs<SignalDecision.Announce>(d)
        assertEquals(SignalTransition.STATIC_GREEN, d.transition)  // 첫 초록은 정적 초록
    }

    // ─────────────────────────────────────────────────────────────
    // 4) 정적 초록은 "건너세요"가 아니다 — 진동 없음, STATIC_GREEN
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 첫_초록은_정적초록이며_진동하지_않는다() {
        val engine = SignalDecisionEngine(cfg)
        var t = 1000L
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.greenStabilityFrames) { last = engine.decide(listOf(green()), t); t += 100 }
        assertIs<SignalDecision.Announce>(last)
        assertEquals(SignalTransition.STATIC_GREEN, last.transition)
        assertTrue(!last.vibrate, "정적 초록은 진동하면 안 된다 (출발 신호로 오인 위험)")
    }

    // ─────────────────────────────────────────────────────────────
    // 5) R→G 전환만이 유일하게 진동+인터럽트하는 "건너세요" 케이스
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 빨강에서_초록_전환은_RED_TO_GREEN이고_진동한다() {
        val engine = SignalDecisionEngine(cfg)
        // 빨강 확정
        var t = engine.confirm(red(), cfg.redStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_RED, engine.confirmedColor)
        // 시간 충분히 흘려 점멸 오판 방지 (minPhaseDuration 초과)
        t += cfg.minPhaseDurationMs + 1000
        // 강한 초록(0.9) 으로 전환 — greenTransitionMinConfidence(0.55) 넘고 빨강 없음
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.greenTransitionStabilityFrames) { last = engine.decide(listOf(green()), t); t += 100 }
        assertIs<SignalDecision.Announce>(last)
        assertEquals(SignalTransition.RED_TO_GREEN, last.transition)
        assertTrue(last.vibrate, "R→G 전환은 강한 진동 필수")
        assertTrue(last.interrupt, "R→G 전환은 즉시 인터럽트 발화")
    }

    // ─────────────────────────────────────────────────────────────
    // 6) R→G 선택 안전 바이어스 — 애매하면 빨강 유지
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 빨강확정_상태에서_약한초록은_빨강을_이기지_못한다() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(red(0.9f), cfg.redStabilityFrames, 1000)
        t += cfg.minPhaseDurationMs + 1000
        // 초록 0.50 (< 0.55 임계) + 빨강 0.9 동시 존재 → 초록이 이기면 안 됨 → 빨강 유지
        val d = engine.decide(listOf(green(0.50f), red(0.9f)), t)
        // 빨강이 선택돼 같은 색 유지 → SAME_COLOR_QUIET 또는 Repeat (둘 다 빨강 유지 의미)
        val stillRed = when (d) {
            is SignalDecision.Silent -> d.reason == SilentReason.SAME_COLOR_QUIET
            is SignalDecision.Repeat -> d.color == SignalDecisionEngine.COLOR_RED
            else -> false
        }
        assertTrue(stillRed, "애매한 초록으로 성급히 넘어가면 안 된다 (빨강 유지 기대), got=$d")
    }

    // ─────────────────────────────────────────────────────────────
    // 7) 점멸 감지 — 짧은 간격의 초록↔빨강 반복은 Flicker + 락아웃
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 짧은간격_전환은_점멸로_감지되고_이후_락아웃된다() {
        val engine = SignalDecisionEngine(cfg)
        // 초록 확정
        var t = engine.confirm(green(), cfg.greenStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_GREEN, engine.confirmedColor)
        // 곧바로(=minPhaseDuration 이내) 빨강으로 전환 시도 → 점멸
        var flick: SignalDecision? = null
        repeat(cfg.redStabilityFrames) { flick = engine.decide(listOf(red()), t); t += 100 }
        assertIs<SignalDecision.Flicker>(flick)

        // 락아웃 동안은 무조건 침묵
        val during = engine.decide(listOf(red()), t + 100)
        assertIs<SignalDecision.Silent>(during)
        assertEquals(SilentReason.FLICKER_LOCKOUT, during.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // 8) heartbeat — 같은 색 유지 시 간격마다 재안내
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 같은색_유지시_heartbeat_간격마다_재안내() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(red(), cfg.redStabilityFrames, 1000)  // 빨강 확정(첫 Announce)
        // 바로 다음 프레임 — 조용
        val quiet = engine.decide(listOf(red()), t + 100)
        assertIs<SignalDecision.Silent>(quiet)
        assertEquals(SilentReason.SAME_COLOR_QUIET, quiet.reason)
        // heartbeat 간격 경과 후 — 재안내
        val repeat = engine.decide(listOf(red()), t + cfg.heartbeatIntervalMs + 200)
        assertIs<SignalDecision.Repeat>(repeat)
        assertEquals(SignalDecisionEngine.COLOR_RED, repeat.color)
    }

    // ─────────────────────────────────────────────────────────────
    // 9) reset — 상태 완전 초기화
    // ─────────────────────────────────────────────────────────────

    @Test
    fun reset하면_확정색이_사라진다() {
        val engine = SignalDecisionEngine(cfg)
        engine.confirm(red(), cfg.redStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_RED, engine.confirmedColor)
        engine.reset()
        assertEquals(SignalDecisionEngine.COLOR_NONE, engine.confirmedColor)
    }

    // ─────────────────────────────────────────────────────────────
    // 10) 정상 G→R 전환 — 진동 있는 안전 안내 (점멸 아님)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 정상_초록에서_빨강_전환은_진동한다() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(green(), cfg.greenStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_GREEN, engine.confirmedColor)
        // 점멸로 오판되지 않도록 정상 phase(minPhaseDuration) 초과 대기
        t += cfg.minPhaseDurationMs + 1000
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.redStabilityFrames) { last = engine.decide(listOf(red()), t); t += 100 }
        assertIs<SignalDecision.Announce>(last)
        assertEquals(SignalTransition.GREEN_TO_RED, last.transition)
        assertTrue(last.vibrate, "G→R 전환도 진동으로 주의 환기")
        assertTrue(last.interrupt, "G→R 전환은 즉시 인터럽트")
    }

    // ─────────────────────────────────────────────────────────────
    // 11) R→G 프리미엄 문턱 — 빨강 직후 약한 초록(단독)은 "건너세요" 안 됨
    //     (가장 위험한 안내이므로 정적 초록보다 높은 확신 요구)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 빨강직후_약한초록_단독은_건너세요로_확정되지_않는다() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(red(), cfg.redStabilityFrames, 1000)
        t += cfg.minPhaseDurationMs + 1000
        // 빨강 사라지고 초록만 존재하되 신뢰도 0.50 (< minConfidenceGreenAfterRed 0.55)
        // → 필터에서 걸러져 침묵. R→G "건너세요" 로 넘어가면 안 된다.
        var last: SignalDecision = SignalDecision.Announce(
            SignalDecisionEngine.COLOR_GREEN, SignalTransition.RED_TO_GREEN, true, true, 1f
        )
        repeat(cfg.greenTransitionStabilityFrames + 1) {
            last = engine.decide(listOf(green(0.50f)), t); t += 100
        }
        val notCrossing = when (last) {
            is SignalDecision.Silent -> (last as SignalDecision.Silent).reason == SilentReason.ALL_LOW_CONFIDENCE
            else -> false
        }
        assertTrue(notCrossing, "빨강 직후 0.50 초록은 확신 부족으로 침묵해야 한다, got=$last")

        // 반면 강한 초록(0.60 ≥ 0.55)은 정상적으로 R→G 확정
        var t2 = t + 1000
        var strong: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.greenTransitionStabilityFrames) { strong = engine.decide(listOf(green(0.60f)), t2); t2 += 100 }
        assertIs<SignalDecision.Announce>(strong)
        assertEquals(SignalTransition.RED_TO_GREEN, strong.transition)
    }
}
