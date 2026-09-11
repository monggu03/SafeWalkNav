package com.example.safewalknav

import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.abs

/**
 * 조준 진동 — "소나". 대상이 화면 중앙에 가까울수록 진동이 **빨라진다**.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 소리가 아니라 진동인가 (2026-09 현장 피드백)
 * ─────────────────────────────────────────────────────────────────────────────
 * 사용자가 "앱에서 소리가 너무 많이 난다"고 했다. 게다가 시각장애인의 보행에서
 * **청각은 이미 다른 일에 쓰이고 있다** — 차 소리, 사람 소리, 음향신호기. 조준 안내로
 * 그 채널을 더 채우면 안전을 깎아 먹는다. 촉각은 비어 있다.
 *
 * 그래서 "어느 쪽인가"(좌/우)만 아주 가끔 음성으로 말하고,
 * "얼마나 맞았나"(정렬 정도)는 전부 진동이 맡는다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 펄스 '간격'인가 — 세기가 아니라
 * ─────────────────────────────────────────────────────────────────────────────
 * 진동 세기는 손·장갑·주머니·기기별 모터 차이로 절대값을 못 믿는다. 반면 **박자**는
 * 그런 것에 흔들리지 않고, 사람이 "빨라진다/느려진다"를 즉시 알아챈다. 주차 센서와 같은 원리다.
 *
 *   |x − 0.5| < 0.10  →  150 ms 간격  (거의 맞음 — 다다다다)
 *   |x − 0.5| < 0.25  →  400 ms 간격  (근처)
 *   그 밖                →  800 ms 간격  (멀다)
 *   대상 없음            →  진동 없음   (조용 — 아무 정보도 없을 땐 침묵이 정답)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 판정 진동과의 충돌 — 취소만이 아니라 '덮어쓰기'도 막아야 한다
 * ─────────────────────────────────────────────────────────────────────────────
 * 기기에는 진동 모터가 하나뿐이라 두 종류의 위험이 있다.
 *   (1) [Vibrator.cancel] 은 **모든** 진동을 끊는다.
 *   (2) [Vibrator.vibrate] 는 진행 중인 진동을 **덮어쓴다**.
 * (2)가 특히 위험하다. 점멸 경고 진동은 650 ms 짜리 패턴인데 다음 추론은 333 ms 뒤에
 * 오므로, 그 프레임이 조준 진동을 새로 걸면 경고가 중간에 잘린다. 안전 신호가
 * 조준 보조에게 잘리는 건 있을 수 없는 일이다.
 *
 * 그래서 호출자는 안전 진동 직전에 [holdOff] 를 부른다. 그 시간 동안 [update] 는
 * 아무것도 걸지 않는다. [stop] 만으로는 (2)를 못 막는다.
 *
 * 또한 파형은 **티어가 바뀔 때만** 다시 건다. 프레임마다 vibrate() 를 부르면 파형이
 * 매번 처음부터 재시작해 뚝뚝 끊긴 느낌이 난다.
 */
class AimingFeedback(private val vibrator: Vibrator) {

    /** 정렬 정도 구간. [gapMs] 는 펄스 시작 사이의 주기. */
    private enum class Tier(val gapMs: Long) {
        NONE(0L),
        ALIGNED(150L),
        NEAR(400L),
        FAR(800L),
    }

    private val usable: Boolean = vibrator.hasVibrator()

    /** 진폭 제어가 없는 기기에서는 amplitude 배열을 쓰지 않는다 (무시되거나 이상하게 동작). */
    private val hasAmplitudeControl: Boolean = vibrator.hasAmplitudeControl()

    private var currentTier: Tier = Tier.NONE

    /**
     * 한 번이라도 파형을 건 적이 있는가.
     * [currentTier] 와 따로 두는 이유: 반복 파형(repeat=0)은 [Vibrator.cancel] 전까지
     * 영원히 뛴다. tier 만 보고 조기 반환하면 "tier 는 NONE 인데 모터는 돌고 있는"
     * 상태를 영영 못 끄게 된다.
     */
    private var armed = false

    /** 이 기기가 반복 파형을 거부했다. 매 프레임 재시도하지 않기 위한 영구 플래그. */
    private var armFailed = false

    /** 이 시각까지는 아무것도 걸지 않는다 — 안전 진동 보호 구간. */
    private var holdOffUntilMs = 0L

    /**
     * 이번 프레임의 조준 대상 위치로 진동을 갱신한다.
     *
     * @param targetCenterX 대상의 화면 가로 중심(0~1). 대상이 없으면 null 또는 음수.
     */
    fun update(targetCenterX: Float?) {
        if (!usable || armFailed) return
        if (System.currentTimeMillis() < holdOffUntilMs) return   // 안전 진동 보호 중
        val tier = tierFor(targetCenterX)
        if (tier == currentTier) return   // 같은 구간이면 파형 유지 — 재설정하면 끊긴다
        currentTier = tier
        if (tier == Tier.NONE) cancel() else arm(tier.gapMs)
    }

    /**
     * 조준 진동 중단. 화면을 떠나거나 조준 단계가 끝났을 때.
     *
     * [armed] 가 true 면 tier 와 무관하게 실제로 취소한다 — 그러지 않으면 백그라운드
     * 진입 직후 도착한 늦은 콜백이 건 파형을 아무도 못 끄는 경로가 생긴다.
     */
    fun stop() {
        if (!usable) return
        currentTier = Tier.NONE
        if (!armed) return   // 판정 진동을 취소하지 않기 위한 조기 반환
        cancel()
    }

    /**
     * [durationMs] 동안 조준 진동을 걸지 않는다. **안전 진동을 울리기 직전에** 호출한다.
     * 이미 뛰고 있던 조준 파형은 즉시 끈다.
     */
    fun holdOff(durationMs: Long) {
        if (!usable) return
        stop()
        holdOffUntilMs = System.currentTimeMillis() + durationMs
    }

    private fun tierFor(targetCenterX: Float?): Tier {
        if (targetCenterX == null || targetCenterX < 0f) return Tier.NONE
        val offset = abs(targetCenterX - 0.5f)
        return when {
            offset < ALIGNED_OFFSET -> Tier.ALIGNED
            offset < NEAR_OFFSET -> Tier.NEAR
            else -> Tier.FAR
        }
    }

    /** repeat = 0 → timings 를 인덱스 0 부터 무한 반복. [cancel] 전까지 계속 뛴다. */
    private fun arm(gapMs: Long) {
        val off = (gapMs - PULSE_MS).coerceAtLeast(1L)
        val timings = longArrayOf(0L, PULSE_MS, off)
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, PULSE_AMPLITUDE, 0), 0)
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
        // ⚠️ vibrate() **전에** 세운다. 모터가 이미 돌기 시작한 뒤에 예외가 나는 경우,
        // armed 가 false 로 남으면 stop() 이 조기 반환해 그 무한 파형을 아무도 못 끈다.
        // 거짓 양성(실제로는 안 걸렸는데 armed=true)의 대가는 불필요한 cancel() 한 번뿐이다.
        armed = true
        try {
            vibrator.vibrate(effect)
        } catch (_: Exception) {
            // 일부 기기가 반복 파형을 거부한다. 조준 진동은 보조 채널이므로 조용히 포기하되,
            // 매 프레임(333ms) 재시도하며 예외를 쏟지 않도록 영구히 꺼 둔다.
            armFailed = true
            currentTier = Tier.NONE
        }
    }

    private fun cancel() {
        // ⚠️ armed 는 cancel() 이 **정상 반환한 뒤에만** 내린다.
        // 먼저 내려 버리면, cancel() 이 실패했을 때 이후의 stop() 이 전부 조기 반환해
        // 무한 파형을 끌 주체가 사라진다 ([arm] 과 정확히 대칭인 함정).
        try {
            vibrator.cancel()
            armed = false
        } catch (_: Exception) {
            // armed 는 true 로 남긴다 — 다음 stop() 이 다시 시도한다.
        }
    }

    companion object {
        /** 한 번의 톡 — 짧게. 길면 연속 진동처럼 뭉개져 박자가 안 느껴진다. */
        private const val PULSE_MS = 20L

        /** 0~255. 걸으면서도 느껴지되 손이 피로하지 않은 선. */
        private const val PULSE_AMPLITUDE = 160

        private const val ALIGNED_OFFSET = 0.10f
        private const val NEAR_OFFSET = 0.25f
    }
}
