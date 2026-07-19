package com.example.safewalknav.navigation.signal

/**
 * 신호등 안내 결정 엔진 — **플랫폼 무관 순수 로직**.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 shared 에 있나
 * ─────────────────────────────────────────────────────────────────────────────
 * 이 결정 로직(신뢰도 비대칭 필터 · 3프레임 안정성 · 점멸 감지 · 색 확정 상태기계)은
 * "지금 안내해도 안전한가"를 판단하는 **안전 핵심**이다.
 *
 * 예전에는 이 로직이 Android(MainActivity.kt)와 iOS(TrafficLightDetector.swift)에
 * **따로** 구현돼 있었다. 그 결과 한쪽만 고치면 두 플랫폼의 안전 동작이 갈라졌다.
 * 실제로 2026-07 시점에 Android 는 빨강0.25/초록0.45 비대칭 + 안정성 + 점멸 감지가
 * 있었지만, iOS 는 `confidence >= 0.5` 한 줄뿐이었다. **같은 앱이 두 OS 에서 다르게
 * 위험했다.**
 *
 * 이 엔진을 commonMain 에 두고 양 플랫폼이 호출하면, 안전 규칙이 **구조적으로 갈라질 수
 * 없다.** 플랫폼별로 남는 것은 "카메라 텐서를 뽑는 부분(TFLite/CoreML)"과
 * "결정에 따른 부수효과(TTS·진동·로그)"뿐이다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 순수성 규칙
 * ─────────────────────────────────────────────────────────────────────────────
 * - 이 클래스는 **부수효과를 일으키지 않는다.** TTS·진동·네트워크·로그 전부 호출자 몫.
 * - 현재 시각은 [decide] 의 인자로 받는다 (테스트 결정성 + 순수성).
 * - 안내 문구도 여기서 만들지 않는다 — [SignalDecision] 은 "무엇을 할지"(enum)만 담고,
 *   실제 문자열(한국어/영어)은 플랫폼이 로컬라이즈한다. (미국 영어 대응 대비)
 *
 * 사용법 (호출자):
 *   val engine = SignalDecisionEngine()
 *   // 매 프레임:
 *   when (val d = engine.decide(detections, nowMs)) {
 *       is SignalDecision.Announce -> { speak(localize(d)); if (d.vibrate) vibrate() }
 *       is SignalDecision.Repeat   -> speak(localizeRepeat(d.color))
 *       is SignalDecision.Flicker  -> { speak(flickerMsg); vibrateWarning() }
 *       is SignalDecision.Silent   -> log(d.reason)   // 침묵도 정보다
 *   }
 *   // zone 이탈/네비 종료 시:
 *   engine.reset()
 */
class SignalDecisionEngine(
    private val config: SignalDecisionConfig = SignalDecisionConfig(),
) {
    /**
     * 무인자 편의 생성자.
     *
     * KMP 는 Kotlin 기본 인자를 Obj-C/Swift 로 노출하지 않는다. 그래서 iOS 에서는
     * `SignalDecisionEngine()` 나 `SignalDecisionConfig()` 를 무인자로 만들 수 없다.
     * 이 보조 생성자가 iOS 에 무인자 경로를 열어 준다(기본 config 사용).
     * (Android 는 원래대로 `SignalDecisionEngine()` 그대로 동작)
     */
    constructor() : this(SignalDecisionConfig())

    // ── 색 상수 (앱 전체 계약) ──
    //    0 = 빨강, 1 = 초록. TrafficLightDetector 가 이 계약을 보장한다.
    private val red get() = COLOR_RED
    private val green get() = COLOR_GREEN

    // ── 상태 (순수 데이터. 부수효과 없음) ──
    private var currentColorCandidate: Int = COLOR_NONE   // 안정성 필터에서 추적 중인 색
    private var colorStreak: Int = 0                       // 연속 검출 수
    private var lastConfirmedColor: Int = COLOR_NONE       // 확정된 마지막 색
    private var lastHeartbeatMs: Long = 0L                 // 마지막 반복 안내 시각
    private var lastValidatedMs: Long = 0L                 // 마지막 유효 검출 시각 (timeout 판정)
    private var lastTransitionMs: Long = 0L                // 마지막 색 전환 시각 (점멸 판정)
    private var flickerLockoutUntilMs: Long = 0L           // 점멸 락아웃 종료 시각

    /** 마지막 확정 색 (읽기용). 호출자의 문맥 판단·디버그에 쓴다. -1/0/1. */
    val confirmedColor: Int get() = lastConfirmedColor

    /**
     * zone 이탈 / 네비게이션 종료 / 카메라 정지 시 호출.
     * 다음 인식을 보수적으로 새로 시작하도록 상태를 완전히 비운다.
     */
    fun reset() {
        currentColorCandidate = COLOR_NONE
        colorStreak = 0
        lastConfirmedColor = COLOR_NONE
        lastHeartbeatMs = 0L
        lastValidatedMs = 0L
        lastTransitionMs = 0L
        flickerLockoutUntilMs = 0L
    }

    /**
     * 한 프레임의 검출 리스트를 받아 안내 결정을 돌려준다.
     *
     * @param detections 이번 프레임 검출들 (classId 0=빨강/1=초록, confidence, box 크기 정규화)
     * @param nowMs 현재 Unix epoch millis (호출자가 주입 — 순수성/테스트용)
     */
    fun decide(detections: List<RawSignalDetection>, nowMs: Long): SignalDecision {
        // ── 1) 물리적 하한(크기) + 색상별 신뢰도 ──
        //    크기는 두 색 공통의 낮은 바닥(서브픽셀 노이즈 제거)만.
        //    안전 마진은 신뢰도에 건다 — 초록 오탐(차도로 나감)이 빨강 오탐(더 기다림)보다
        //    비교할 수 없이 위험하므로 초록에 훨씬 높은 확신을 요구한다.
        val bigEnough = detections.filter {
            it.boxWidth >= config.minBoxDimension && it.boxHeight >= config.minBoxDimension
        }
        if (bigEnough.isEmpty()) {
            return SignalDecision.Silent(
                if (detections.isEmpty()) SilentReason.NO_DETECTION else SilentReason.ALL_TOO_SMALL
            )
        }
        val validated = bigEnough.filter { it.confidence >= requiredConfidence(it.classId) }
        if (validated.isEmpty()) {
            return SignalDecision.Silent(SilentReason.ALL_LOW_CONFIDENCE)
        }

        // ── 2) 판단 대상 하나 선택 ──
        val nearest = selectSignal(validated, lastConfirmedColor)
            ?: return SignalDecision.Silent(SilentReason.NO_DETECTION)
        val detectedColor = nearest.classId

        // ── 3) 점멸 락아웃 진행 중이면 무조건 침묵 ──
        if (flickerLockoutUntilMs > 0L && nowMs < flickerLockoutUntilMs) {
            return SignalDecision.Silent(SilentReason.FLICKER_LOCKOUT)
        }
        if (flickerLockoutUntilMs > 0L && nowMs >= flickerLockoutUntilMs) {
            // 락아웃 종료 — 상태를 비워 다음 프레임부터 보수적으로 재인식.
            // lastConfirmedColor 도 -1 로 둬서 락아웃 직후 초록이 "전환"으로 오인돼
            // "건너세요"가 나가는 일을 막는다.
            currentColorCandidate = COLOR_NONE
            colorStreak = 0
            lastConfirmedColor = COLOR_NONE
            flickerLockoutUntilMs = 0L
        }

        // ── 4) 검출 타임아웃 → 상태 리셋 ──
        //    유효 검출이 오래 끊겼다 다시 오면 이전 확정색을 신뢰하지 않는다
        //    (그 사이 신호가 바뀌었을 수 있음).
        if (lastValidatedMs > 0L && nowMs - lastValidatedMs > config.detectionTimeoutMs) {
            currentColorCandidate = COLOR_NONE
            colorStreak = 0
            lastConfirmedColor = COLOR_NONE
        }
        lastValidatedMs = nowMs

        // ── 5) 안정성 필터 — 빨강은 빠르게, 초록은 보수적으로 확정 ──
        if (detectedColor == currentColorCandidate) {
            colorStreak++
        } else {
            currentColorCandidate = detectedColor
            colorStreak = 1
        }
        val requiredFrames = when {
            detectedColor == red -> config.redStabilityFrames
            lastConfirmedColor == red -> config.greenTransitionStabilityFrames
            else -> config.greenStabilityFrames
        }
        if (colorStreak < requiredFrames) {
            return SignalDecision.Silent(SilentReason.STABILITY_PENDING, colorStreak, requiredFrames, detectedColor)
        }

        // ── 6) 안정성 통과 = 확정 ──
        val confirmed = currentColorCandidate
        val previous = lastConfirmedColor

        // (6-1) 같은 색 지속 → heartbeat 간격마다 반복 안내, 아니면 침묵
        if (confirmed == previous) {
            return if (nowMs - lastHeartbeatMs >= config.heartbeatIntervalMs) {
                lastHeartbeatMs = nowMs
                SignalDecision.Repeat(confirmed)
            } else {
                SignalDecision.Silent(SilentReason.SAME_COLOR_QUIET, color = confirmed)
            }
        }

        // (6-2) 색 변경 (또는 첫 확정)
        val isRedToGreen = previous == red && confirmed == green
        // 점멸 감지: 정상 신호 phase 는 최소 시간 지속한다. 그보다 짧은 간격의 전환은 점멸로 본다.
        // 단 R→G 전환과 첫 확정은 제외 (첫 확정은 항상 정상 안내).
        if (!isRedToGreen &&
            previous != COLOR_NONE &&
            lastTransitionMs > 0L &&
            nowMs - lastTransitionMs < config.minPhaseDurationMs
        ) {
            val gapMs = nowMs - lastTransitionMs
            // 락아웃 걸고 상태 비움 — 락아웃 종료 후 보수적 재시작.
            flickerLockoutUntilMs = nowMs + config.flickerLockoutMs
            lastConfirmedColor = COLOR_NONE
            currentColorCandidate = COLOR_NONE
            colorStreak = 0
            lastTransitionMs = nowMs
            lastHeartbeatMs = nowMs
            return SignalDecision.Flicker(gapMs, previous, confirmed)
        }

        // (6-3) 정상 전환 (또는 첫 확정) → 안내
        lastConfirmedColor = confirmed
        lastHeartbeatMs = nowMs
        lastTransitionMs = nowMs

        val transition = when {
            confirmed == red && previous == green -> SignalTransition.GREEN_TO_RED
            confirmed == red -> SignalTransition.RED_NEW
            confirmed == green && previous == red -> SignalTransition.RED_TO_GREEN
            else -> SignalTransition.STATIC_GREEN
        }
        // 진동: 초록→빨강 전환, R→G 전환에서만. 정적 초록/신규 빨강은 진동 없음.
        val vibrate = transition == SignalTransition.GREEN_TO_RED ||
            transition == SignalTransition.RED_TO_GREEN
        // 발화 인터럽트: 안전 관련 전환은 즉시 끼어들어 말한다.
        val interrupt = transition != SignalTransition.STATIC_GREEN

        return SignalDecision.Announce(
            color = confirmed,
            transition = transition,
            vibrate = vibrate,
            interrupt = interrupt,
            confidence = nearest.confidence,
        )
    }

    /**
     * 이 검출이 요구하는 최소 신뢰도. 초록은 오탐 비용이 비대칭적으로 크다.
     *
     * 순서 주의: 빨강 직후 초록(R→G)이 정적 초록보다 **더 높은** 확신을 요구한다.
     * 이 케이스만이 "건너세요"로 이어지므로, 가장 위험한 안내에 가장 높은 문턱을 둔다.
     */
    private fun requiredConfidence(classId: Int): Float = when {
        classId == red -> config.minConfidenceRed
        // 직전이 빨강 확정 = R→G 전환("건너세요") 문맥. 오탐이 치명적이라 프리미엄 문턱.
        lastConfirmedColor == red -> config.minConfidenceGreenAfterRed
        else -> config.minConfidenceGreen
    }

    /**
     * 판단 대상 하나 선택.
     *
     * 핵심 안전 규칙: 직전이 빨강(= R→G 전환 감시 중)일 때, 초록이 빨강을 이기려면
     * (1) 초록 신뢰도가 충분히 높고 (2) 빨강을 마진만큼 앞서야 한다. 애매하면 **빨강 유지**.
     * 성급하게 초록으로 넘어가 "건너세요"가 나가는 것을 막는다.
     */
    private fun selectSignal(detections: List<RawSignalDetection>, previousColor: Int): RawSignalDetection? {
        val bestRed = detections.filter { it.classId == red }
            .maxWithOrNull(compareBy({ it.confidence }, { it.boxArea }))
        val bestGreen = detections.filter { it.classId == green }
            .maxWithOrNull(compareBy({ it.confidence }, { it.boxArea }))

        if (previousColor == red && bestGreen != null) {
            if (bestRed == null) return bestGreen
            val greenStrongEnough = bestGreen.confidence >= config.greenTransitionMinConfidence
            val greenBeatsRed = bestGreen.confidence >= bestRed.confidence + config.greenOverRedConfidenceMargin
            if (greenStrongEnough && greenBeatsRed) return bestGreen
        }
        if (bestRed != null) return bestRed
        return detections.maxWithOrNull(compareBy({ it.confidence }, { it.boxArea }))
    }

    companion object {
        const val COLOR_NONE = -1
        const val COLOR_RED = 0
        const val COLOR_GREEN = 1
    }
}

/**
 * 검출 1건 — 플랫폼 무관 입력 타입.
 * Android TrafficLightDetection / iOS Detection 을 이 형태로 변환해 엔진에 넘긴다.
 * box 값은 화면 대비 정규화(0~1).
 */
data class RawSignalDetection(
    val classId: Int,        // 0 = 빨강, 1 = 초록
    val confidence: Float,
    val boxWidth: Float,
    val boxHeight: Float,
) {
    val boxArea: Float get() = boxWidth * boxHeight
}

/** 색 전환 종류 — 안내 문구·진동·인터럽트가 여기서 갈린다. */
enum class SignalTransition {
    RED_NEW,        // 신규 빨강 (첫 인식)
    GREEN_TO_RED,   // 초록 → 빨강
    RED_TO_GREEN,   // 빨강 → 초록 (유일하게 "건너세요" 계열 안내)
    STATIC_GREEN,   // 정적 초록 (전환 못 봄) — 절대 "건너세요" 안 함
}

/** 침묵한 이유 — 침묵도 정보다. 플랫폼이 로그·디버그에 쓴다. */
enum class SilentReason {
    NO_DETECTION,        // 검출 없음
    ALL_TOO_SMALL,       // 크기 하한 미달 (너무 멀거나 노이즈)
    ALL_LOW_CONFIDENCE,  // 크기는 통과했으나 확신 부족
    STABILITY_PENDING,   // 연속 프레임 아직 부족
    FLICKER_LOCKOUT,     // 점멸 감지 후 안내 차단 중
    SAME_COLOR_QUIET,    // 같은 색 유지 — 다음 반복까지 조용
}

/** 엔진의 결정. 호출자는 이걸 받아 TTS·진동·로그 등 부수효과를 수행한다. */
sealed class SignalDecision {
    /** 안내하지 않음. [reason] 으로 왜인지 안다. 부수효과 없음(로그만). */
    data class Silent(
        val reason: SilentReason,
        val streak: Int = 0,
        val requiredFrames: Int = 0,
        val color: Int = SignalDecisionEngine.COLOR_NONE,
    ) : SignalDecision()

    /** 같은 색이 지속되어 heartbeat 간격이 됨 → 같은 색 재안내. */
    data class Repeat(val color: Int) : SignalDecision()

    /** 점멸 감지 → 경고 발화 + 강한 진동. [gapMs] 는 직전 전환과의 간격. */
    data class Flicker(val gapMs: Long, val previousColor: Int, val newColor: Int) : SignalDecision()

    /** 정상 안내. 플랫폼이 [transition] 에 맞는 문구를 로컬라이즈해 발화한다. */
    data class Announce(
        val color: Int,
        val transition: SignalTransition,
        val vibrate: Boolean,
        val interrupt: Boolean,
        val confidence: Float,
    ) : SignalDecision()
}

/**
 * 결정 파라미터. 기본값은 2026-07 Android 운영값과 동일.
 * 두 플랫폼이 같은 config 를 쓰면 동작이 완전히 일치한다.
 */
data class SignalDecisionConfig(
    // 신뢰도 (초록 비대칭)
    val minConfidenceRed: Float = 0.25f,
    val minConfidenceGreen: Float = 0.45f,
    // ⚠️ 빨강 직후 초록(= R→G 전환 = 유일하게 "건너세요"를 부르는 케이스)은
    //    '할인'이 아니라 '프리미엄'이다. 오탐이 곧 사람을 차도로 내보내므로,
    //    가장 위험한 안내인 만큼 정적 초록(0.45)보다 더 높은 확신을 요구한다.
    //    (0.55 = selectSignal 의 R→G 게이트와 동일 — 두 게이트를 일치시킨다)
    val minConfidenceGreenAfterRed: Float = 0.55f,
    // 크기 하한 (서브픽셀 노이즈 제거용, 두 색 공통). 0.008 ≈ 27m
    val minBoxDimension: Float = 0.008f,
    // 안정성 (연속 프레임)
    // 2026-07: 추론이 3fps(약 333ms/프레임)라 프레임 수가 곧 시간이다.
    //   구값(빨강 2·초록 3)은 0.67~1.0초라, 화면에 색이 잠깐만 스쳐도 확정돼
    //   "켜자마자 빨간불" · "깜빡이지 않는데 깜빡임" 오작동을 냈다(모델 순간 오탐이 상태로 굳음).
    //   시간 기준으로 올려 순간 노이즈를 걸러낸다. 빨강 4≈1.3s / 초록 5≈1.7s / 전환 4≈1.3s.
    val redStabilityFrames: Int = 4,
    val greenStabilityFrames: Int = 5,
    val greenTransitionStabilityFrames: Int = 4,
    // 선택 단계 R→G 안전 바이어스 (빨강·초록 동시 검출 시 초록이 이기는 조건)
    val greenTransitionMinConfidence: Float = 0.55f,
    val greenOverRedConfidenceMargin: Float = 0.05f,
    // 타이밍
    //   heartbeat < timeout 이어야 한다: 같은 색을 계속 보고 있는데(연속 프레임)
    //   heartbeat 가 timeout 이상이면, heartbeat 직전에 timeout 리셋이 먼저 걸려
    //   반복 안내가 영영 안 나갈 수 있다.
    //   2026-07: 같은 신호 반복이 잦다는 피드백 → 8초 → 12초로 완화 (timeout 도 20초로 상향).
    val heartbeatIntervalMs: Long = 12_000L,
    val detectionTimeoutMs: Long = 20_000L,
    val minPhaseDurationMs: Long = 4_000L,   // 정상 phase 최소 지속 (이보다 짧은 전환 = 점멸)
    val flickerLockoutMs: Long = 6_000L,     // 점멸 감지 후 안내 차단 기간
)
