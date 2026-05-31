//
//  NavigationViewModel.swift
//  iosApp
//
//  KMM의 NavigationManager를 SwiftUI에서 사용할 수 있게 래핑
//

import Foundation
import Combine
import CoreLocation
import Speech
import shared

@MainActor
final class NavigationViewModel: ObservableObject {

    // MARK: - Published State
    @Published private(set) var guidanceMessage: String = ""
    @Published private(set) var arrivalState: ArrivalState = .far
    @Published private(set) var isNavigating: Bool = false
    @Published private(set) var distanceToDestination: Float = .greatestFiniteMagnitude
    // didSet 으로 @Published 발화 시점/개수 확인 (SwiftUI 가 실제로 업데이트 받는지 검증)
    @Published private(set) var searchResults: [POIResult] = [] {
        didSet {
            print("📣 [NavigationViewModel] searchResults didSet — count=\(searchResults.count)")
            searchResults.enumerated().forEach { i, poi in
                print("    [\(i)] \(poi.name) (\(poi.lat), \(poi.lon))")
            }
        }
    }
    @Published private(set) var errorMessage: String? {
        didSet {
            if let msg = errorMessage {
                print("📣 [NavigationViewModel] errorMessage didSet — '\(msg)'")
            }
        }
    }
    @Published private(set) var isAtCrosswalk: Bool = false

    // MARK: - 지도 시각화용 데이터

    /// 지도 폴리라인용 좌표 배열
    @Published private(set) var routeCoordinates: [CLLocationCoordinate2D] = []

    /// 지도 waypoint 핀용 데이터
    struct WaypointPin: Identifiable {
        let id: Int
        let coordinate: CLLocationCoordinate2D
        let pointType: String
        let description: String
    }
    @Published private(set) var waypointPins: [WaypointPin] = []

    /// 지도 annotation 마커용 데이터 (RouteAnnotator 가 분류한 곡선/회전 지점)
    struct AnnotationMarker: Identifiable {
        let id: Int
        let coordinate: CLLocationCoordinate2D
        let type: String       // "SLIGHT_CURVE", "CURVE", ...
        let direction: String  // "LEFT", "RIGHT", "NONE"
        let totalAngle: Double
    }
    @Published private(set) var annotationMarkers: [AnnotationMarker] = []

    /// 디버그 패널 — 발화 로그 (최근 20개)
    @Published private(set) var announcementLog: [String] = []

    /// 디버그 패널 — 사용자가 다가가고 있는 미발화 annotation 정보
    struct UpcomingAnnotation {
        let type: String
        let direction: String
        let totalAngle: Double
        let distanceM: Double
        let message: String
    }
    @Published private(set) var upcomingAnnotation: UpcomingAnnotation?

    /// 음성 인식 진행 단계 — UI에서 상태 안내용
    @Published private(set) var voiceFlowStage: VoiceFlowStage = .idle

    enum VoiceFlowStage {
        case idle
        case listening          // STT 듣는 중
        case searching          // 검색 중
        case startingNavigation // 안내 시작 직전
    }

    // MARK: - Dependencies
    private let tts: TtsManager
    private let locationTracker: LocationTracker
    private let headingProvider: HeadingProvider
    private let stt: SttManager
    private let navigationManager: NavigationManager

    // MARK: - Subscriptions
    private var cancellables = Set<AnyCancellable>()
    private var pollingTask: Task<Void, Never>?

    private var lastSpokenGuidance: String = ""

    // 디버깅: polling 카운터 (로그 무한 출력 방지)
    private var pollCount: Int = 0

    // 안내 시작 직후 자동 온보딩 시퀀스 진행 중인지.
    // true 동안에는 polling 의 guidanceMessage 발화를 보류해 멘트 겹침을 방지한다.
    private var isOnboarding: Bool = false

    // 활성 Coordinator 보관 — strong 참조가 사라지지 않게 ViewModel 이 들고 있는다.
    private var onboardingCoordinator: AutoOnboardingCoordinator?

    // 보행 중 자이로를 NavigationManager 로 흘려보내는 피더(GPS+자이로 상보 필터).
    private let gyroFeeder: GyroHeadingFeeder

    // MARK: - Init
    init(
        tts: TtsManager,
        locationTracker: LocationTracker,
        headingProvider: HeadingProvider,
        stt: SttManager,
        navigationManager: NavigationManager
    ) {
        self.tts = tts
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
        self.stt = stt
        self.navigationManager = navigationManager
        self.gyroFeeder = GyroHeadingFeeder(navigationManager: navigationManager)

        print("🟢 [INIT] NavigationViewModel 생성됨")
        bindLocationToNavigation()
        bindVoiceFlow()
        startPollingNavigationState()
    }

    deinit {
        pollingTask?.cancel()
    }

    // MARK: - Public API

    func searchDestination(keyword: String) async {
        print("🔎 [searchDestination] 시작 — keyword='\(keyword)'")

        // 1) 위치 확인 (없으면 검색 자체가 실행 안 됨)
        guard let loc = locationTracker.currentLocation else {
            print("🔴 [searchDestination] currentLocation == nil — 검색 중단")
            self.errorMessage = "현재 위치를 알 수 없습니다"
            return
        }
        print("🔎 [searchDestination] currentLocation = (\(loc.latitude), \(loc.longitude))")

        // 2) try? 가 아니라 do-catch 로 실제 에러를 그대로 출력
        do {
            // 반경 50km — 도보 거리는 아니지만 특정 장소명 검색(예: "동국대학교")이
            // 현재 위치에서 멀리 있어도 잡히도록 충분히 넓게 둔다.
            // TMap API 가 centerLat/centerLon 기준 거리순 정렬해서 돌려주므로
            // 가까운 결과가 항상 먼저 노출됨.
            let results = try await navigationManager.searchDestination(
                keyword: keyword,
                currentLat: KotlinDouble(value: loc.latitude),
                currentLon: KotlinDouble(value: loc.longitude),
                radiusKm: 50.0
            )
            print("🟢 [searchDestination] navigationManager 반환 — results.count=\(results.count)")
            results.enumerated().forEach { i, poi in
                print("    [\(i)] \(poi.name) (\(poi.lat), \(poi.lon)) addr='\(poi.address)'")
            }

            // shared 모듈에서 본문 파싱은 성공해도 결과 0개일 수 있음 — lastError 도 함께 확인
            if let lastErr = navigationManager.lastError as String?, !lastErr.isEmpty {
                print("⚠️ [searchDestination] navigationManager.lastError='\(lastErr)'")
            }

            self.searchResults = results
            self.errorMessage = results.isEmpty
                ? "검색 결과가 없습니다 (\(navigationManager.lastError as String? ?? "조용한 실패"))"
                : nil
            print("🟢 [searchDestination] @Published 할당 직후 self.searchResults.count=\(self.searchResults.count)")
        } catch {
            print("🔴 [searchDestination] 예외 — \(type(of: error)): \(error)")
            print("🔴 [searchDestination] localizedDescription=\(error.localizedDescription)")
            self.errorMessage = "검색 실패: \(error.localizedDescription)"
        }
    }

    func startNavigation(to poi: POIResult) async {
        guard let currentLoc = locationTracker.currentLocation else {
            self.errorMessage = "현재 위치를 알 수 없습니다"
            return
        }

        // 외출 단위 파일 로그 시작 (Android MainActivity.startNavLog 와 동일 포맷).
        // 안내 종료/도착 시 close() — startNavigation 이 success=false 로 끝나도 stopNavigation 으로 정리됨.
        NavLogFile.shared.start()

        print("🟢 [START] 안내 시작 호출 — \(poi.name)")

        do {
            let success = try await navigationManager.startNavigation(
                startLat: currentLoc.latitude,
                startLon: currentLoc.longitude,
                endLat: poi.lat,
                endLon: poi.lon,
                endName: String(describing: poi.name),
                frontLat: poi.frontLat,
                frontLon: poi.frontLon,
                suppressInitialSummary: true   // 요약 발화는 AutoOnboardingCoordinator 가 담당
            )

            print("🟢 [START] 결과 — success=\(success.boolValue)")

            if success.boolValue {
                headingProvider.setBaseHeading()
                // 보행 중 자이로 융합 시작 — updateGyro 는 isNavigating 동안에만 내부 처리.
                gyroFeeder.start()
                await runAutoOnboarding()
            } else {
                self.errorMessage = (navigationManager.lastError as String?) ?? "경로를 찾을 수 없습니다"
            }
        } catch {
            self.errorMessage = "안내 시작 실패: \(error.localizedDescription)"
        }
    }

    /// 안내 시작 직후 자동 실행되는 온보딩 시퀀스.
    /// 경로 요약 → 평평 자세 → 회전 → 정면 일치 1초 유지 → 본격 안내.
    /// 진행 동안 isOnboarding = true 로 두어 polling 의 guidanceMessage 발화는 보류된다.
    private func runAutoOnboarding() async {
        guard let route = navigationManager.currentRoute,
              let firstWp = route.waypoints.first,
              let currentLoc = locationTracker.currentLocation else {
            return
        }

        let summary = navigationManager.buildInitialSummary()
        let coordinator = AutoOnboardingCoordinator(tts: self.tts)
        self.onboardingCoordinator = coordinator
        self.isOnboarding = true

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            coordinator.start(
                summary: summary,
                currentLocation: currentLoc,
                firstWaypoint: firstWp,
                onCompleted: { continuation.resume() }
            )
        }

        self.isOnboarding = false
        self.onboardingCoordinator = nil
    }

    func stopNavigation() {
        // 온보딩 도중 종료 누르면 coordinator 도 즉시 정리.
        onboardingCoordinator?.stop()
        onboardingCoordinator = nil
        isOnboarding = false

        navigationManager.stopNavigation()
        headingProvider.clearBaseHeading()
        gyroFeeder.stop()
        tts.stop()

        NavLogFile.shared.close()
    }

    // MARK: - Voice Destination Input

    /// 시각장애인용 음성 목적지 입력 — 버튼 한 번 누르면:
    /// 1) "어디로 갈까요?" 안내
    /// 2) STT 듣기 시작
    /// 3) 인식된 키워드로 POI 검색
    /// 4) 가장 가까운 결과로 자동 안내 시작
    func startVoiceDestinationFlow() {
        // 권한이 없으면 먼저 요청
        guard stt.authorizationStatus == .authorized else {
            Task {
                let granted = await stt.requestAuthorization()
                if granted {
                    self.startVoiceDestinationFlow()
                } else {
                    self.errorMessage = "음성 인식 권한이 필요합니다"
                    self.tts.speak("음성 인식 권한이 필요합니다. 설정에서 허용해 주세요.", priority: .high)
                }
            }
            return
        }

        voiceFlowStage = .listening
        tts.speak("어디로 갈까요? 목적지를 말씀하세요.", priority: .high)

        // TTS가 끝난 후 STT 시작 (1.5초 정도면 TTS 종료 추정)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { [weak self] in
            guard let self = self else { return }
            guard self.voiceFlowStage == .listening else { return }
            self.stt.startListening()
        }
    }

    /// STT 듣기를 즉시 중단 (사용자가 취소할 때)
    func cancelVoiceDestinationFlow() {
        stt.stopListening()
        voiceFlowStage = .idle
    }

    // MARK: - Private Bindings

    private func bindLocationToNavigation() {
        print("🟢 [BIND] bindLocationToNavigation 시작")

        locationTracker.$currentLocation
            .sink { [weak self] gpsLocation in
                guard let self else { return }
                guard let gpsLocation = gpsLocation else { return }

                Task {
                    do {
                        try await self.navigationManager.updateLocation(location: gpsLocation)
                    } catch {
                        print("🔴 [TASK] updateLocation 실패: \(error)")
                    }
                }
            }
            .store(in: &cancellables)
    }

    /// STT 최종 결과 → 검색 → 자동 안내 시작
    private func bindVoiceFlow() {
        stt.finalResultPublisher
            .sink { [weak self] recognizedText in
                guard let self = self else { return }
                Task { @MainActor in
                    let keyword = recognizedText.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !keyword.isEmpty else {
                        self.voiceFlowStage = .idle
                        self.tts.speak("목적지를 인식하지 못했습니다. 다시 시도해 주세요.", priority: .high)
                        return
                    }
                    await self.handleRecognizedDestination(keyword)
                }
            }
            .store(in: &cancellables)
    }

    private func handleRecognizedDestination(_ keyword: String) async {
        voiceFlowStage = .searching
        tts.speak("\(keyword)을(를) 검색합니다.", priority: .normal)

        await searchDestination(keyword: keyword)

        guard let best = searchResults.first else {
            voiceFlowStage = .idle
            tts.speak("검색 결과가 없습니다. 다시 말씀해 주세요.", priority: .high)
            return
        }

        voiceFlowStage = .startingNavigation
        let name = String(describing: best.name)
        // "안내를 시작합니다" 는 AutoOnboardingCoordinator 가 요약 멘트에 포함하므로 여기선 검색 결과 안내만.
        tts.speak("\(name)으로 경로를 탐색합니다.", priority: .high)

        await startNavigation(to: best)
        voiceFlowStage = .idle
    }

    private func startPollingNavigationState() {
        pollingTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.pollCount += 1

                // 1. 안내 메시지
                let newGuidance = (self.navigationManager.guidanceMessage.value as? String) ?? ""
                if newGuidance != self.guidanceMessage {
                    self.guidanceMessage = newGuidance
                    self.handleGuidanceChange(newGuidance)
                }

                // 2. 도착 단계
                if let newArrivalState = self.navigationManager.arrivalState.value as? ArrivalState,
                   newArrivalState != self.arrivalState {
                    self.arrivalState = newArrivalState
                    if newArrivalState == .arrived {
                        NavLogFile.shared.close()
                    }
                }

                // 3. 내비 활성 여부
                if let newIsNavigatingObj = self.navigationManager.isNavigating.value as? KotlinBoolean {
                    let newIsNavigating = newIsNavigatingObj.boolValue
                    if newIsNavigating != self.isNavigating {
                        self.isNavigating = newIsNavigating
                        // 도착 등으로 내비가 내부적으로 종료되면 자이로 센서도 정리.
                        if !newIsNavigating {
                            self.gyroFeeder.stop()
                        }
                    }
                }

                // 4. 거리
                if let newDistanceObj = self.navigationManager.distanceToDestination.value as? KotlinFloat {
                    let newDistance = newDistanceObj.floatValue
                    if newDistance != self.distanceToDestination {
                        self.distanceToDestination = newDistance
                    }
                }

                // 5초마다 (poll 25회) 한 번씩 상태 요약 로그
                if self.pollCount % 25 == 0 {
                    let dbg = self.navigationManager.debugMessage.value
                    let gpsStr = self.locationTracker.currentLocation
                        .map { "\($0.latitude), \($0.longitude)" } ?? "nil"
                    print("""
                    ══════════ 📊 [POLL #\(self.pollCount)] ══════════
                    GPS: \(gpsStr)
                    isNavigating: \(self.isNavigating)
                    distanceToDestination: \(self.distanceToDestination)m
                    arrivalState: \(self.arrivalState)
                    guidanceMessage: \(self.guidanceMessage)
                    debugMessage: \(String(describing: dbg))
                    ════════════════════════════════════
                    """)
                }

                // 6. 횡단보도 감지
                let newIsAtCrosswalk = parseCrosswalkFromDebugMessage()
                if newIsAtCrosswalk != self.isAtCrosswalk {
                    print("🚦 [CROSSWALK] \(self.isAtCrosswalk) → \(newIsAtCrosswalk)")
                    self.isAtCrosswalk = newIsAtCrosswalk
                }

                // 7. 지도 시각화 갱신
                self.refreshRouteVisualization()
                self.refreshAnnouncementLog()
                self.refreshUpcomingAnnotation()

                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
    }

    // MARK: - Side Effects

    private func handleGuidanceChange(_ message: String) {
        guard !message.isEmpty else { return }
        guard message != lastSpokenGuidance else { return }
        lastSpokenGuidance = message

        // 온보딩 진행 중에는 coordinator 가 발화 흐름을 잡고 있으므로 보류.
        // (요약 자체는 suppressInitialSummary=true 로 NavigationManager 가 emit 하지 않지만,
        //  updateWaypointGuidance/도착 알림 등은 그대로 발생할 수 있다.)
        guard !isOnboarding else { return }

        let priority: TtsManager.Priority = (arrivalState == .arrived) ? .high : .normal
        tts.speak(message, priority: priority)
    }

    // MARK: - 횡단보도 감지

    private func parseCrosswalkFromDebugMessage() -> Bool {
        guard let debug = navigationManager.debugMessage.value as? String else {
            return false
        }
        return debug.contains("횡단보도=true")
    }

    // MARK: - 지도 시각화 갱신

    /// NavigationManager.currentRoute + annotations 를 Swift 친화 형태로 변환.
    /// 폴리라인 좌표, waypoint 핀, annotation 마커를 한꺼번에 갱신.
    private func refreshRouteVisualization() {
        guard let route = navigationManager.currentRoute else {
            if !routeCoordinates.isEmpty { routeCoordinates = [] }
            if !waypointPins.isEmpty { waypointPins = [] }
            if !annotationMarkers.isEmpty { annotationMarkers = [] }
            return
        }

        // 폴리라인
        let coords: [CLLocationCoordinate2D] = route.routePoints.map {
            CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
        }
        if coords.count != routeCoordinates.count {
            routeCoordinates = coords
        }

        // waypoint 핀
        let pins: [WaypointPin] = route.waypoints.enumerated().map { (i, wp) in
            WaypointPin(
                id: i,
                coordinate: CLLocationCoordinate2D(latitude: wp.lat, longitude: wp.lon),
                pointType: wp.pointType,
                description: wp.description_
            )
        }
        if pins.count != waypointPins.count {
            waypointPins = pins
        }

        // annotation 마커
        let anns = navigationManager.annotations.value as? [PathAnnotation] ?? []
        let markers: [AnnotationMarker] = anns.compactMap { ann in
            let startIdx = Int(ann.startWaypointIndex)
            guard startIdx >= 0, startIdx < route.waypoints.count else { return nil }
            let wp = route.waypoints[startIdx]
            return AnnotationMarker(
                id: startIdx,
                coordinate: CLLocationCoordinate2D(latitude: wp.lat, longitude: wp.lon),
                type: ann.type.name,
                direction: ann.direction.name,
                totalAngle: ann.totalAngle
            )
        }
        if markers.count != annotationMarkers.count {
            annotationMarkers = markers
        }
    }

    /// 발화 로그 StateFlow → @Published 동기화.
    private func refreshAnnouncementLog() {
        let log = (navigationManager.announcementLog.value as? [String]) ?? []
        if log.count != announcementLog.count {
            announcementLog = log
        }
    }

    /// 다가오는(아직 발화 안 된, 현재 waypoint 이후의) annotation 중 가장 가까운 것 1건.
    private func refreshUpcomingAnnotation() {
        guard let route = navigationManager.currentRoute,
              let loc = locationTracker.currentLocation,
              let anns = navigationManager.annotations.value as? [PathAnnotation],
              !anns.isEmpty
        else {
            if upcomingAnnotation != nil { upcomingAnnotation = nil }
            return
        }

        var best: (PathAnnotation, Double)? = nil
        for ann in anns {
            if ann.announceMessage.isEmpty { continue }
            let startIdx = Int(ann.startWaypointIndex)
            guard startIdx >= 0, startIdx < route.waypoints.count else { continue }
            let wp = route.waypoints[startIdx]
            let dist = haversineMeters(
                loc.latitude, loc.longitude, wp.lat, wp.lon
            )
            if best == nil || dist < best!.1 {
                best = (ann, dist)
            }
        }

        if let (ann, dist) = best {
            upcomingAnnotation = UpcomingAnnotation(
                type: ann.type.name,
                direction: ann.direction.name,
                totalAngle: ann.totalAngle,
                distanceM: dist,
                message: ann.announceMessage
            )
        } else if upcomingAnnotation != nil {
            upcomingAnnotation = nil
        }
    }

    /// Haversine 거리 (m) — RouteAnnotator 와 같은 결과를 내야 할 만큼 정확하지 않아도 됨 (디버그 표시용).
    private func haversineMeters(
        _ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double
    ) -> Double {
        let r = 6371000.0
        let dLat = (lat2 - lat1) * .pi / 180.0
        let dLon = (lon2 - lon1) * .pi / 180.0
        let a = sin(dLat / 2) * sin(dLat / 2)
              + cos(lat1 * .pi / 180.0) * cos(lat2 * .pi / 180.0)
              * sin(dLon / 2) * sin(dLon / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
