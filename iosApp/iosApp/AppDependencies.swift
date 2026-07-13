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

    // KMP SpatialBeeper 에 iOS AVAudioEngine 재생을 주입하는 어댑터.
    // strong ref 로 보관해야 클로저(iosImpl) 가 유효하다.
    private let spatialBeeperImpl: SpatialBeeperImpl

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

        // 2. KMM 매니저
        //   - TMap: 경로/검색 (Secrets.tMapAppKey)
        //   - T-Data: 신호제어기 잔여시간 (Secrets.tDataApiKey)
        //   - 서울 열린데이터: 신호제어기 위치 (Secrets.seoulApiKey)
        let tMapAppKey = Secrets.tMapAppKey
        let tDataApiKey = Secrets.tDataApiKey
        let seoulApiKey = Secrets.seoulApiKey
        // 키 관련 로그는 릴리스에서 남기지 않는다 (키 존재/길이도 노출 정보).
        #if DEBUG
        print("[AppDependencies] TMap 키: \(tMapAppKey.isEmpty ? "없음" : "설정됨"), " +
              "T-Data 키: \(tDataApiKey.isEmpty ? "없음" : "설정됨"), " +
              "Seoul 키: \(seoulApiKey.isEmpty ? "없음" : "설정됨")")
        #endif

        let signalClient = SignalApiClient(apiKey: tDataApiKey)
        let tMapClient = TMapApiClient(appKey: tMapAppKey)
        let navigationManager = NavigationManager(
            tMapApiClient: tMapClient,
            signalApiClient: signalClient,
            headingLogger: NoopHeadingLogger.shared,
            trafficSignals: []
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
        // SpatialBeeper 콜백 주입 — NavigationManager.handleVirtualWaypointPassed 가
        // 호출되는 순간 AVAudioEngine 으로 비프가 재생되게 한다.
        self.spatialBeeperImpl = SpatialBeeperImpl(kmpBeeper: navigationManager.spatialBeeper)

        // 5. 신호제어기 위치 데이터 로드 (Android MainActivity 의 loadTrafficSignalLocations 와 동일)
        //    - 캐시가 있으면 즉시 사용, 없으면 Seoul Open API 에서 받아 캐시 후 사용.
        //    - Seoul API 키가 없거나 캐시도 없으면 빈 배열로 통과(앱 자체는 계속 동작).
        let trafficSignalRepository = TrafficSignalRepository(
            apiClient: SeoulTrafficSignalLocationApiClient(apiKey: seoulApiKey),
            cache: TrafficSignalCache(),
            apiKeyAvailable: !seoulApiKey.isEmpty
        )

        Task { @MainActor in
            let signals = await trafficSignalRepository.getTrafficSignals()
            print("[AppDependencies] 신호제어기 위치 로드 완료: \(signals.count)건")
            navigationManager.updateTrafficSignals(signals: signals)
        }
    }
}
