package com.example.safewalknav.navigation.tbfw

/**
 * GPS 점프 상태에서의 안내 행동 결정.
 *
 * SILENT             — TTS 발화 안 함. 사용자에게 보이지 않음.
 * ANNOUNCE_DEGRADED  — "위치 안내가 잠시 어렵습니다. 평소처럼 보행하세요" 1회 발화.
 *                      이번 점프 세션 동안 다시는 발화하지 않는다.
 */
enum class JumpGuidanceAction {
    SILENT,
    ANNOUNCE_DEGRADED,
}

/**
 * GPS 점프 감지 시 안내 정책 결정기.
 *
 * 호출 패턴:
 *   매 navigator.update() 안에서 decide() 호출.
 *   결과가 ANNOUNCE_DEGRADED 면 호출자가 TTS 발화 + 진동 짧게 1회.
 *
 * 설계 원칙 (반드시 유지):
 *   1. "멈추세요" 안내 절대 금지 — GPS 회복은 보행 중 일어남.
 *   2. 한 점프 세션 동안 최대 1회만 안내 (피로감 방지).
 *   3. 횡단보도 zone 안에서는 SILENT (사용자 불안 방지).
 *   4. 복구 시 별도 안내 없이 정상 안내가 자연스레 재개되도록 함.
 *
 * @param config 임계값 설정
 */
class JumpGuidancePolicy(
    private val config: NavigatorConfig = NavigatorConfig(),
) {
    private var jumpStartTimeMs: Long = 0L
    private var announcedThisJump: Boolean = false
    private var lastAnnounceTimeMs: Long = 0L

    /**
     * 다음 안내 행동을 결정한다.
     *
     * @param jumpLevel GpsJumpDetector의 출력
     * @param nowMs 현재 시각 (ms)
     * @param isInCrosswalkZone 호출자가 알려주는 횡단보도 zone 여부
     * @return 안내 행동
     */
    fun decide(
        jumpLevel: GpsJumpLevel,
        nowMs: Long,
        isInCrosswalkZone: Boolean,
    ): JumpGuidanceAction {

        // JUMPED 가 아니면 = 정상 / 의심 — 점프 세션 종료 처리
        if (jumpLevel != GpsJumpLevel.JUMPED) {
            jumpStartTimeMs = 0L
            announcedThisJump = false
            return JumpGuidanceAction.SILENT
        }

        // 횡단보도 안 — 절대 안내 안 함
        if (isInCrosswalkZone) {
            return JumpGuidanceAction.SILENT
        }

        // 점프 세션 시작 시점 기록
        if (jumpStartTimeMs == 0L) {
            jumpStartTimeMs = nowMs
        }

        val duration = nowMs - jumpStartTimeMs

        // 3초 이내 — 침묵 (대부분 짧은 튐은 곧 복구됨)
        if (duration < config.jumpSilentWindowMs) {
            return JumpGuidanceAction.SILENT
        }

        // 이번 점프 세션에서 이미 안내했으면 더 이상 안 함
        if (announcedThisJump) {
            return JumpGuidanceAction.SILENT
        }

        // 직전 안내로부터 최소 쿨다운 검사 (서로 다른 점프 세션 사이).
        // lastAnnounceTimeMs == 0 이면 아직 발화 이력 없음 — 쿨다운 검사 skip.
        if (lastAnnounceTimeMs > 0L &&
            nowMs - lastAnnounceTimeMs < config.jumpRecoveryAnnounceCooldownMs
        ) {
            return JumpGuidanceAction.SILENT
        }

        // 3초 이상, 첫 발화 — 안내
        announcedThisJump = true
        lastAnnounceTimeMs = nowMs
        return JumpGuidanceAction.ANNOUNCE_DEGRADED
    }
}
