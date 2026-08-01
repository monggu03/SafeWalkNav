//
//  AppDependencies.swift
//  iosApp
//
//  앱 전역 의존성 컨테이너 (DI Container)
//  - SwiftUI에 environment object로 주입
//
//  2026-08 — iOS 내비 MVP 복원: 목적지 음성입력 → 경로 요약 → GPS 추종 → 횡단보도 신호 인식.
//            TTS·신호등 검출기에 더해 위치추적·STT·TMap 클라이언트·화면 상태기계를 다시 둔다.
//

import Foundation
import Combine
import shared    // TMapApiClient (KMM)

/// 앱 전역에서 공유되는 매니저들의 컨테이너
@MainActor
final class AppDependencies: ObservableObject {

    let tts: TtsManager
    let trafficLightDetector: TrafficLightDetector
    let locationTracker: LocationTracker
    let stt: SttManager
    let tMapClient: TMapApiClient
    let coordinator: NavigationCoordinator

    init() {
        let tts = TtsManager()
        self.tts = tts
        // 신호 판정은 detector 안의 shared SignalDecisionEngine 이 담당 (Android 와 동일 로직).
        let trafficLightDetector = TrafficLightDetector(tts: tts)
        self.trafficLightDetector = trafficLightDetector

        let locationTracker = LocationTracker()
        self.locationTracker = locationTracker
        let stt = SttManager(tts: tts)
        self.stt = stt
        let tMapClient = TMapApiClient(appKey: Secrets.tMapAppKey)
        self.tMapClient = tMapClient

        // 안전 고지 1회 동의 여부 → 초기 phase. 동의 이력 있으면 목적지 입력부터 시작.
        let hasAgreed = UserDefaults.standard.bool(forKey: "hasAgreedToSafetyNotice")
        self.coordinator = NavigationCoordinator(
            tts: tts,
            locationTracker: locationTracker,
            stt: stt,
            tMapClient: tMapClient,
            trafficLightDetector: trafficLightDetector,
            initialPhase: hasAgreed ? .destinationInput : .safetyNotice
        )
    }
}
