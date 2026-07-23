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
        let stt = SttManager(tts: tts)

        // 2. KMM 매니저 — TMap 경로/검색 (Secrets.tMapAppKey)
        //    신호 공공데이터(T-Data 잔여시간·서울 신호등 위치) API 는 폐기 — 신호 인식은
        //    사용자가 횡단보도에서 카메라로 직접 확인한다.
        let tMapAppKey = Secrets.tMapAppKey
        #if DEBUG
        print("[AppDependencies] TMap 키: \(tMapAppKey.isEmpty ? "없음" : "설정됨")")
        #endif

        let tMapClient = TMapApiClient(appKey: tMapAppKey)
        let navigationManager = NavigationManager(
            tMapApiClient: tMapClient,
            headingLogger: NoopHeadingLogger.shared
        )

        // 3. 통합 ViewModel — STT까지 주입해서 음성 목적지 입력 지원
        let navigationViewModel = NavigationViewModel(
            tts: tts,
            locationTracker: locationTracker,
            headingProvider: headingProvider,
            stt: stt,
            navigationManager: navigationManager
        )

        // 4. 저장
        self.tts = tts
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
        self.navigationManager = navigationManager
        self.stt = stt
        self.navigationViewModel = navigationViewModel
        self.trafficLightDetector = TrafficLightDetector(tts: tts)
    }
}
