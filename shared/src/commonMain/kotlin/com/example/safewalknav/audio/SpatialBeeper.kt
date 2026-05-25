package com.example.safewalknav.audio

/**
 * 좌우 채널 분리(스테레오 패닝) 비프음 재생기.
 *
 * 시각장애 보행자에게 곡선 구간에서 "어느 방향으로 가야 하는지" 를 비프로 알려주는 데 쓴다.
 * iOS / Android 각 actual 에서 실제 사운드 소스를 만들어 재생한다 (현재 두 플랫폼 모두
 * 사인파를 메모리에서 합성하므로 외부 리소스 파일이 필요 없다).
 *
 * 비프 톤:
 *   - LOW  : 600 Hz  150 ms — 약한 이탈/주기적 확인용
 *   - HIGH : 1200 Hz 100 ms — 강한 이탈/주의 환기용
 *
 * 호출자 (NavigationManager.handleVirtualWaypointPassed) 책임:
 *   - 이탈 정도에 따라 톤/pan/반복 선택
 *   - 임계 초과 시엔 비프 대신 음성 안내로 전환 (handleVirtualWaypointPassed 가 직접 분기)
 */
expect class SpatialBeeper() {
    /**
     * @param pan -1.0 (완전 왼쪽) ~ +1.0 (완전 오른쪽), 0.0 = 중앙
     * @param tone LOW 또는 HIGH
     * @param repeatCount 반복 횟수 (기본 1)
     */
    fun playBeep(pan: Float, tone: BeepTone, repeatCount: Int)

    /** 재생 중인 비프 중단. */
    fun stop()
}

enum class BeepTone { LOW, HIGH }

/**
 * Swift / Java 가 default 인자(repeatCount = 1) 를 못 쓰므로 편의 함수 제공.
 * Kotlin 호출자는 직접 [SpatialBeeper.playBeep] 를 써도 된다.
 */
fun SpatialBeeper.playBeepOnce(pan: Float, tone: BeepTone) = playBeep(pan, tone, 1)
