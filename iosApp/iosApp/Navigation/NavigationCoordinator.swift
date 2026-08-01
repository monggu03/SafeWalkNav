//
//  NavigationCoordinator.swift
//  iosApp
//
//  앱 전체 화면 흐름을 관장하는 상태기계.
//  .safetyNotice → .destinationInput → .guiding ⇄ .crossing → .arrived → (.destinationInput)
//
//  ⚠️ 5단계(골격): 전이 메서드는 phase 만 바꾸고 실제 로직(경로탐색·추종·횡단보도 감지)은
//  6단계에서 채운다. 여기서는 상태 라우팅과 detector start/stop 제어만 담당.
//
//  NOTE: tMapClient.searchPOI / searchPedestrianRoute 는 Kotlin suspend →
//  Swift 에서 async(try await) 로 호출된다. 관련 진입 메서드는 async 로 잡는다.
//

import Foundation
import Combine
import shared    // TMapApiClient, POIResult (KMM)

/// 앱 화면 흐름 단계.
enum NavPhase {
    case safetyNotice
    case destinationInput
    case guiding
    case crossing
    case arrived
}

@MainActor
final class NavigationCoordinator: ObservableObject {

    /// 현재 화면 단계. AppRootView 가 이 값으로 라우팅한다.
    /// 초기값은 안전 고지 1회 동의 여부에 따라 결정(init 에서 주입).
    @Published var phase: NavPhase

    // MARK: - 주입 의존성
    private let tts: TtsManager
    private let locationTracker: LocationTracker
    private let stt: SttManager
    private let tMapClient: TMapApiClient
    private let trafficLightDetector: TrafficLightDetector

    init(
        tts: TtsManager,
        locationTracker: LocationTracker,
        stt: SttManager,
        tMapClient: TMapApiClient,
        trafficLightDetector: TrafficLightDetector,
        initialPhase: NavPhase = .safetyNotice
    ) {
        self.tts = tts
        self.locationTracker = locationTracker
        self.stt = stt
        self.tMapClient = tMapClient
        self.trafficLightDetector = trafficLightDetector
        self.phase = initialPhase
    }

    // MARK: - 전이 (5단계 stub — phase 전환만)

    /// 안전 고지 동의 → 목적지 입력으로.
    func acknowledgeSafety() {
        // TODO(6단계): 목적지 입력 진입 시 TTS "목적지를 말씀해 주세요" 안내.
        phase = .destinationInput
    }

    /// 목적지 선택됨 → 경로 탐색 후 안내 시작.
    /// tMapClient.searchPedestrianRoute 는 suspend → async 로 호출.
    func onDestinationChosen(_ poi: POIResult) async {
        // TODO(6단계): searchPedestrianRoute → 거리·횡단보도 수 음성 요약 → 추종 시작.
        phase = .guiding
    }

    /// 경로상 횡단보도 진입 → 신호 인식 화면.
    func enterCrossing() {
        // TODO(6단계): FollowingController 가 반경 진입 감지 시 호출.
        trafficLightDetector.startDetection()
        phase = .crossing
    }

    /// 횡단보도 이탈 → 안내 화면 복귀.
    func exitCrossing() {
        trafficLightDetector.stopDetection()
        phase = .guiding
    }

    /// 목적지 도착.
    func arrive() {
        // TODO(6단계): "목적지에 도착했습니다." 발화 + 추종 종료.
        phase = .arrived
    }

    /// 처음(목적지 입력)으로 복귀.
    func reset() {
        trafficLightDetector.stopDetection()
        phase = .destinationInput
    }
}
