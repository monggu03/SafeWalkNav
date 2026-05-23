package com.example.safewalknav.audio

/**
 * iOS actual — 실제 재생은 Swift 측 SpatialBeeperImpl 가 담당한다.
 * KMP 코드(NavigationManager) 가 [playBeep] 를 호출하면 [iosImpl] / [iosStop] 콜백을
 * Swift 가 주입한 클로저에 위임한다.
 *
 * 사용 순서:
 *   1. AppDependencies 에서 SpatialBeeper() 생성
 *   2. SpatialBeeperImpl(kmpBeeper:) 가 iosImpl/iosStop 을 채움
 *   3. NavigationManager 가 playBeep 호출 → iosImpl 호출 → AVAudioEngine 재생
 *
 * 콜백이 nil 인 상태에서 호출되면 조용히 무시 (시뮬레이터/테스트 환경).
 */
actual class SpatialBeeper actual constructor() {

    /** Swift 측이 채워주는 재생 클로저. (pan, toneName, repeatCount) */
    var iosImpl: ((Float, String, Int) -> Unit)? = null

    /** Swift 측이 채워주는 중단 클로저. */
    var iosStop: (() -> Unit)? = null

    actual fun playBeep(pan: Float, tone: BeepTone, repeatCount: Int) {
        iosImpl?.invoke(pan, tone.name, repeatCount)
    }

    actual fun stop() {
        iosStop?.invoke()
    }
}
