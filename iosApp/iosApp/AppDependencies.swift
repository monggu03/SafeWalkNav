//
//  AppDependencies.swift
//  iosApp
//
//  앱 전역 의존성 컨테이너 (DI Container)
//  - KMM 객체들의 생명주기를 단 하나로 보장
//  - SwiftUI에 environment object로 주입
//

import Foundation
import shared
import Combine

/// 앱 전역에서 공유되는 매니저들의 컨테이너
@MainActor
final class AppDependencies: ObservableObject {

    // MARK: - Native Swift Managers
    let tts: TtsManager
    let locationTracker: LocationTracker
    let headingProvider: HeadingProvider
    let stt: SttManager
    let trafficLightDetector: TrafficLightDetector
    let orientationMonitor: DeviceOrientationMonitor

    // MARK: - KMM Managers
    let navigationManager: NavigationManager

    // MARK: - ViewModel
    let navigationViewModel: NavigationViewModel

    // MARK: - Init
    init() {
        // 1. Swift native 매니저들
        let tts = TtsManager()
        let locationTracker = LocationTracker()
        let headingProvider = HeadingProvider()
        let orientationMonitor = DeviceOrientationMonitor()

        // 2. KMM 매니저 (TMap API 키로 초기화)
        let apiKey = Secrets.tMapAppKey
        print("[AppDependencies] API 키 길이: \(apiKey.count)")
        print("[AppDependencies] API 키 앞 5자: \(apiKey.prefix(5))")
        print("[AppDependencies] API 키 뒤 3자: \(apiKey.suffix(3))")

        let tMapClient = TMapApiClient(appKey: apiKey)
        let navigationManager = NavigationManager(
            tMapApiClient: tMapClient,
            headingLogger: NoopHeadingLogger.shared
        )

        // 3. 통합 ViewModel — orientationMonitor도 주입
        let navigationViewModel = NavigationViewModel(
            tts: tts,
            locationTracker: locationTracker,
            headingProvider: headingProvider,
            orientationMonitor: orientationMonitor,
            navigationManager: navigationManager
        )

        // 4. 저장
        self.tts = tts
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
        self.orientationMonitor = orientationMonitor
        self.navigationManager = navigationManager
        self.stt = SttManager(tts: tts)
        self.navigationViewModel = navigationViewModel
        self.trafficLightDetector = TrafficLightDetector(tts: tts)

        // 5. 자세 모니터링 자동 시작 (앱 켜는 순간부터 감시)
        orientationMonitor.start()
    }
}
