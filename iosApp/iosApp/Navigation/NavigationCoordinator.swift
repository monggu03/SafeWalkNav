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
import CoreLocation
import shared    // TMapApiClient, POIResult, TMapRoute (KMM)

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

    // MARK: - 안내 상태 (GuidingView 표시용)
    /// 탐색된 경로. §4-3 FollowingController 가 소비.
    @Published private(set) var currentRoute: TMapRoute?
    /// 목적지 이름(안내 화면 표시).
    @Published private(set) var destinationName: String?
    /// 목적지까지 남은 거리 문구(§4-3에서 갱신). nil 이면 "안내 중" 표시.
    @Published private(set) var remainingText: String?

    /// 목적지 좌표(§4-3 도착 판정용).
    private(set) var destinationCoord: CLLocationCoordinate2D?

    // MARK: - 주입 의존성
    private let tts: TtsManager
    private let locationTracker: LocationTracker
    private let stt: SttManager
    private let tMapClient: TMapApiClient
    private let trafficLightDetector: TrafficLightDetector

    // MARK: - §4-3 추종
    private var following: FollowingController!
    /// 마지막으로 음성 안내한 남은거리(m) — "약 N미터" 스팸 방지.
    private var lastSpokenRemaining: Int?

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

        self.following = FollowingController(
            locationTracker: locationTracker,
            callbacks: .init(
                enterCrossing:   { [weak self] in self?.enterCrossing() },
                exitCrossing:    { [weak self] in self?.exitCrossing() },
                arrive:          { [weak self] in self?.arrive() },
                updateRemaining: { [weak self] m in self?.updateRemaining(m) }
            )
        )
    }

    // MARK: - 전이 (5단계 stub — phase 전환만)

    /// 안전 고지 동의 → 목적지 입력으로.
    func acknowledgeSafety() {
        // TODO(6단계): 목적지 입력 진입 시 TTS "목적지를 말씀해 주세요" 안내.
        phase = .destinationInput
    }

    /// 목적지 선택됨 → 경로 탐색 후 안내 시작(§4-2).
    /// tMapClient.searchPedestrianRoute 는 suspend → async 로 호출.
    func onDestinationChosen(_ poi: POIResult) async {
        // 1) 출발 좌표 — 첫 GPS 픽스가 없으면 최대 ~5초 폴링.
        locationTracker.start()
        var start = locationTracker.currentLocation
        if start == nil {
            for _ in 0..<10 {                          // 0.5s × 10 = 5s
                try? await Task.sleep(nanoseconds: 500_000_000)
                if let c = locationTracker.currentLocation { start = c; break }
            }
        }
        guard let startCoord = start else {
            tts.speak("현재 위치를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", display: true)
            reset()
            return
        }

        // 2) 목적지 좌표 — 건물 입구(front) 좌표 우선.
        let destLat = poi.frontLat?.doubleValue ?? poi.lat
        let destLon = poi.frontLon?.doubleValue ?? poi.lon

        // 3) 경로 탐색.
        tts.speak("경로를 탐색합니다.", display: true)
        let route: TMapRoute?
        do {
            route = try await tMapClient.searchPedestrianRoute(
                startLat: startCoord.latitude,
                startLon: startCoord.longitude,
                endLat: destLat,
                endLon: destLon,
                startName: "출발지",
                endName: poi.name
            )
        } catch {
            tts.speak("경로를 찾지 못했습니다. 목적지를 다시 확인해 주세요.", display: true)
            reset()
            return
        }

        // 4) 실패 처리.
        guard let route, !route.waypoints.isEmpty else {
            tts.speak("경로를 찾지 못했습니다. 목적지를 다시 확인해 주세요.", display: true)
            reset()
            return
        }

        // 5) 요약 음성.
        let distanceM = Int(route.totalDistance)
        let count = route.waypoints.filter { $0.pointType == "CROSSWALK" }.count
        let distanceText: String
        if distanceM < 1000 {
            distanceText = "\((distanceM / 10) * 10)미터"
        } else {
            distanceText = String(format: "%.1f킬로미터", Double(distanceM) / 1000.0)
        }
        tts.speak("도착지까지 \(distanceText), 횡단보도는 \(count)개입니다. 경로 안내를 시작하겠습니다.", display: true)

        // 6) 상태 저장 후 안내 화면으로 + 추종 시작(§4-3).
        let destCoord = CLLocationCoordinate2D(latitude: destLat, longitude: destLon)
        self.currentRoute = route
        self.destinationName = poi.name
        self.destinationCoord = destCoord
        self.remainingText = nil
        self.lastSpokenRemaining = nil
        phase = .guiding
        following.start(route: route, destination: destCoord)
    }

    /// 경로상 횡단보도 진입(FollowingController 콜백) → 신호 인식 화면.
    func enterCrossing() {
        guard phase == .guiding else { return }
        tts.speak("횡단보도입니다. 신호를 확인하세요.", display: true)
        trafficLightDetector.startDetection()
        phase = .crossing
    }

    /// 횡단보도 이탈(FollowingController 콜백) → 안내 화면 복귀.
    func exitCrossing() {
        guard phase == .crossing else { return }
        trafficLightDetector.stopDetection()
        phase = .guiding
    }

    /// 목적지 도착(FollowingController 콜백).
    func arrive() {
        following.stop()
        trafficLightDetector.stopDetection()
        tts.speakImmediately("목적지에 도착했습니다.", display: true)
        phase = .arrived
    }

    /// 목적지까지 남은 직선거리 갱신(FollowingController 콜백).
    /// 화면 문구는 매번 갱신, 음성은 ~50m 단위로만.
    private func updateRemaining(_ meters: Int) {
        let rounded = (meters / 10) * 10
        remainingText = "목적지까지 약 \(rounded)미터"
        if let last = lastSpokenRemaining, last - meters < 50 { return }
        lastSpokenRemaining = meters
        // 크로싱 중엔 신호 안내가 우선 — 남은거리 음성은 생략.
        if phase == .guiding {
            tts.speak("목적지까지 약 \(rounded)미터", display: false)
        }
    }

    /// 처음(목적지 입력)으로 복귀.
    func reset() {
        following.stop()
        trafficLightDetector.stopDetection()
        currentRoute = nil
        destinationName = nil
        remainingText = nil
        destinationCoord = nil
        lastSpokenRemaining = nil
        phase = .destinationInput
    }
}
