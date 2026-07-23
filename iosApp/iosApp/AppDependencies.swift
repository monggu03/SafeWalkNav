//
//  AppDependencies.swift
//  iosApp
//
//  앱 전역 의존성 컨테이너 (DI Container)
//  - SwiftUI에 environment object로 주입
//
//  2026-07 — 도보 내비게이션(TMap 경로·GPS·나침반·목적지 음성입력)을 전면 제거하고
//            "열면 바로 신호 인식" 카메라 단일 기능으로 축소. 이제 TTS 와 신호등 검출기만 둔다.
//

import Foundation
import Combine

/// 앱 전역에서 공유되는 매니저들의 컨테이너
@MainActor
final class AppDependencies: ObservableObject {

    let tts: TtsManager
    let trafficLightDetector: TrafficLightDetector

    init() {
        let tts = TtsManager()
        self.tts = tts
        // 신호 판정은 detector 안의 shared SignalDecisionEngine 이 담당 (Android 와 동일 로직).
        self.trafficLightDetector = TrafficLightDetector(tts: tts)
    }
}
