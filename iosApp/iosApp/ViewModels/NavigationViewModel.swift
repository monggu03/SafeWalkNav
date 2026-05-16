//
//  NavigationViewModel.swift
//  iosApp
//
//  KMM의 NavigationManager를 SwiftUI에서 사용할 수 있게 래핑
//

import Foundation
import Combine
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

        print("🟢 [START] 안내 시작 호출 — \(poi.name)")

        do {
            let success = try await navigationManager.startNavigation(
                startLat: currentLoc.latitude,
                startLon: currentLoc.longitude,
                endLat: poi.lat,
                endLon: poi.lon,
                endName: String(describing: poi.name),
                frontLat: poi.frontLat,
                frontLon: poi.frontLon
            )

            print("🟢 [START] 결과 — success=\(success.boolValue)")

            if success.boolValue {
                headingProvider.setBaseHeading()
            } else {
                self.errorMessage = (navigationManager.lastError as String?) ?? "경로를 찾을 수 없습니다"
            }
        } catch {
            self.errorMessage = "안내 시작 실패: \(error.localizedDescription)"
        }
    }

    func stopNavigation() {
        navigationManager.stopNavigation()
        headingProvider.clearBaseHeading()
        tts.stop()
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

    /// HeadingProvider 의 drift 알림 — 직선 구간에서 base heading 대비 ±15° 이상 벗어나면 1회 발화.
    /// option (b) 작업에서 보존: feature/walking-drift 의 쏠림 보정 핵심 동작.
    private func handleDriftAlertIfNeeded() {
        guard isNavigating else { return }
        guard headingProvider.isDrifting else { return }

        let direction = headingProvider.driftDegrees > 0 ? "오른쪽" : "왼쪽"
        let absDeg = Int(abs(headingProvider.driftDegrees))
        tts.speak("\(direction)으로 \(absDeg)도 벗어났습니다", priority: .high)
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
        tts.speak("\(name)으로 안내를 시작합니다.", priority: .high)

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
                }

                // 3. 내비 활성 여부
                if let newIsNavigatingObj = self.navigationManager.isNavigating.value as? KotlinBoolean {
                    let newIsNavigating = newIsNavigatingObj.boolValue
                    if newIsNavigating != self.isNavigating {
                        self.isNavigating = newIsNavigating
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

                // 7. drift 알림 (HeadingProvider 기반)
                self.handleDriftAlertIfNeeded()

                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
    }

    // MARK: - Side Effects

    private func handleGuidanceChange(_ message: String) {
        guard !message.isEmpty else { return }
        guard message != lastSpokenGuidance else { return }
        lastSpokenGuidance = message

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
}
