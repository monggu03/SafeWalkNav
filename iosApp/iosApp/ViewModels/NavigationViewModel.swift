//
//  NavigationViewModel.swift
//  iosApp
//
//  KMM의 NavigationManager를 SwiftUI에서 사용할 수 있게 래핑
//

import Foundation
import Combine
import shared
import Speech

@MainActor
final class NavigationViewModel: ObservableObject {

    // MARK: - Published State
    @Published private(set) var guidanceMessage: String = ""
    @Published private(set) var arrivalState: ArrivalState = .far
    @Published private(set) var isNavigating: Bool = false
    @Published private(set) var distanceToDestination: Float = .greatestFiniteMagnitude
    @Published private(set) var searchResults: [POIResult] = []
    @Published private(set) var errorMessage: String?
    @Published private(set) var isAtCrosswalk: Bool = false

    /// 음성 인식 진행 단계 — UI에서 상태 안내용
    @Published private(set) var voiceFlowStage: VoiceFlowStage = .idle

    enum VoiceFlowStage {
        case idle
        case listening          // STT 듣는 중
        case searching          // 검색 중
        case results            // 🆕 검색 결과 표시 + 사용자 선택 대기
        case startingNavigation // 안내 시작 직전
    }

    /// 안내 시작 직후 IMU 캘리브레이션 단계
    @Published private(set) var calibrationStage: CalibrationStage = .done

    enum CalibrationStage {
        case inProgress  // "한 바퀴 돌아보세요" 안내 + heading 정렬 대기
        case done        // 캘리브레이션 끝 (또는 안내 비활성)
    }

    /// 캘리브레이션 정렬 임계값 (도). HeadingProvider.driftThresholdDegrees 와 동일.
    private let calibrationAlignmentDegrees: Double = 15.0
    /// 캘리브레이션 무한 대기 방지 타임아웃 (초).
    private let calibrationTimeoutSeconds: TimeInterval = 10.0

    // MARK: - Dependencies
    private let tts: TtsManager
    private let locationTracker: LocationTracker
    private let headingProvider: HeadingProvider
    private let stt: SttManager
    //private let orientationMonitor: DeviceOrientationMonitor
    private let navigationManager: NavigationManager

    // MARK: - Subscriptions
    private var cancellables = Set<AnyCancellable>()
    private var pollingTask: Task<Void, Never>?

    private var lastSpokenGuidance: String = ""

    // 캘리브레이션 흐름용
    private var calibrationCancellable: AnyCancellable?
    private var calibrationTimeoutTask: Task<Void, Never>?
    private var calibrationTargetBearing: Double?

    // MARK: - Orientation Alert State
    //private var lastSpokenIssue: OrientationIssue = .none
    //private var lastOrientationSpeakTime: Date = .distantPast
    //private let orientationRepeatInterval: TimeInterval = 5.0

    // 🆕 디버깅: polling 카운터 (로그 무한 출력 방지)
    private var pollCount: Int = 0

    // MARK: - Init
    init(
        tts: TtsManager,
        locationTracker: LocationTracker,
        headingProvider: HeadingProvider,
        stt: SttManager,
        //orientationMonitor: DeviceOrientationMonitor,
        navigationManager: NavigationManager
    ) {
        self.tts = tts
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
        self.stt = stt
        //self.orientationMonitor = orientationMonitor
        self.navigationManager = navigationManager

        print("🟢 [INIT] NavigationViewModel 생성됨")
        bindLocationToNavigation()
        bindHeadingToNavigation()
        bindVoiceFlow()
        startPollingNavigationState()
    }

    deinit {
        pollingTask?.cancel()
    }

    // MARK: - Public API

    func searchDestination(keyword: String) async {
        do {
            guard let loc = locationTracker.currentLocation else {
                self.errorMessage = "현재 위치를 알 수 없습니다"
                return
            }
            let results = try await navigationManager.searchDestination(
                keyword: keyword,
                currentLat: KotlinDouble(value: loc.latitude),
                currentLon: KotlinDouble(value: loc.longitude),
                radiusKm: 5.0
            )
            self.searchResults = results
            self.errorMessage = nil
        } catch {
            self.errorMessage = "검색 실패: \(error.localizedDescription)"
        }
    }

    func startNavigation(to poi: POIResult) async {
        guard let currentLoc = locationTracker.currentLocation else {
            self.errorMessage = "현재 위치를 알 수 없습니다"
            return
        }

        let poiName = String(describing: poi.name)
        print("🟢 [START] 안내 시작 호출 — \(poiName)")
        DebugLogger.shared.log("NAV", "startNavigation → \(poiName)")
        voiceFlowStage = .startingNavigation

        do {
            let success = try await navigationManager.startNavigation(
                startLat: currentLoc.latitude,
                startLon: currentLoc.longitude,
                endLat: poi.lat,
                endLon: poi.lon,
                endName: poiName,
                frontLat: poi.frontLat,
                frontLon: poi.frontLon
            )

            print("🟢 [START] 결과 — success=\(success.boolValue)")

            if success.boolValue {
                // 즉시 setBaseHeading() 하지 않고, 경로의 첫 진행 방향을 기준으로
                // 사용자가 그 방향을 향할 때까지 캘리브레이션
                let targetBearing = computeFirstSegmentBearing(fallbackStart: currentLoc, poi: poi)
                startCalibration(targetBearing: targetBearing)
                // isNavigating은 폴링에서 곧 true로 갱신됨 → 화면이 .navigating 으로 전환
                // 결과 리스트는 안내 종료 시 stopNavigation()에서 정리
            } else {
                voiceFlowStage = .results  // 실패 시 결과 화면 유지
                self.errorMessage = (navigationManager.lastError as String?) ?? "경로를 찾을 수 없습니다"
                DebugLogger.shared.log("NAV", "startNavigation 실패", level: .error)
            }
        } catch {
            voiceFlowStage = .results
            self.errorMessage = "안내 시작 실패: \(error.localizedDescription)"
            DebugLogger.shared.log("NAV", "startNavigation 예외: \(error.localizedDescription)", level: .error)
        }
    }

    func stopNavigation() {
        navigationManager.stopNavigation()
        headingProvider.clearBaseHeading()
        cancelCalibration()
        calibrationStage = .done
        tts.stop()
        voiceFlowStage = .idle
        searchResults = []
        DebugLogger.shared.log("NAV", "stopNavigation")
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

        // 이전 결과 정리 후 새 인식 시작
        searchResults = []
        errorMessage = nil
        voiceFlowStage = .listening
        DebugLogger.shared.log("VOICE", "listening 시작")
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
        searchResults = []
        DebugLogger.shared.log("VOICE", "취소")
    }

    // MARK: - Private Bindings

    /// 🔴 디버깅 강화: 모든 단계에 로그
    private func bindLocationToNavigation() {
        print("🟢 [BIND] bindLocationToNavigation 시작")

        locationTracker.$currentLocation
            .sink { [weak self] gpsLocation in
                print("🔵 [SINK] 발화 — value: \(String(describing: gpsLocation))")

                guard let self else {
                    print("🔴 [SINK] self가 nil — 바인딩 끊김")
                    return
                }
                guard let gpsLocation = gpsLocation else {
                    print("⚠️ [SINK] gpsLocation이 nil — 무시")
                    return
                }

                print("🔵 [SINK] GPS = \(gpsLocation.latitude), \(gpsLocation.longitude)")

                Task {
                    print("🔵 [TASK] updateLocation 호출 직전")
                    do {
                        try await self.navigationManager.updateLocation(location: gpsLocation)
                        print("🟢 [TASK] updateLocation 완료")
                    } catch {
                        print("🔴 [TASK] updateLocation 실패: \(error)")
                    }
                }
            }
            .store(in: &cancellables)

        print("🟢 [BIND] 구독 등록 완료, cancellables 개수: \(cancellables.count)")
    }

    private func bindHeadingToNavigation() {
        // ⚠️ Compass 기반 쏠림 비활성화 — shared 쪽 주석 처리와 연동
            // headingProvider.$currentHeading
            //     .sink { [weak self] heading in
            //         self?.navigationManager.updateCompassHeading(
            //             azimuth: Float(heading),
            //             currentTime: Int64(Date().timeIntervalSince1970 * 1000)
            //         )
            //     }
            //     .store(in: &cancellables)
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
        DebugLogger.shared.log("VOICE", "검색 키워드: \(keyword)")
        tts.speak("\(keyword)을(를) 검색합니다.", priority: .normal)

        await searchDestination(keyword: keyword)

        guard !searchResults.isEmpty else {
            voiceFlowStage = .idle
            DebugLogger.shared.log("VOICE", "결과 없음", level: .warn)
            tts.speak("검색 결과가 없습니다. 다시 말씀해 주세요.", priority: .high)
            return
        }

        // 🆕 자동으로 첫 결과를 시작하지 않고, 사용자가 3개 중에서 고르도록 결과 화면 표시
        voiceFlowStage = .results
        let count = min(searchResults.count, 3)
        let firstName = String(describing: searchResults[0].name)
        DebugLogger.shared.log("VOICE", "results \(count)개")
        tts.speak(
            "결과 \(count)개를 찾았습니다. 가장 가까운 곳은 \(firstName)입니다. 원하는 곳을 두 번 탭하세요.",
            priority: .high
        )
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

                // 🆕 5초마다 (poll 25회) 한 번씩 상태 요약 로그
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

                // 7. drift / orientation
                self.handleDriftAlertIfNeeded()
                //self.handleOrientationAlertIfNeeded()

                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
    }

    // MARK: - Side Effects

    private func handleGuidanceChange(_ message: String) {
        guard !message.isEmpty else { return }
        guard message != lastSpokenGuidance else { return }
        lastSpokenGuidance = message

        // 캘리브레이션 중이면 발화를 미루고, 끝난 직후 한 번만 발화
        guard calibrationStage == .done else { return }

        let priority: TtsManager.Priority = (arrivalState == .arrived) ? .high : .normal
        tts.speak(message, priority: priority)
    }

    private func handleDriftAlertIfNeeded() {
        // 사용자 결정: 걷는 중 drift 음성 알림은 silently 끔
        // (시각 표시는 ContentView 에서 headingProvider.isDrifting 으로 그대로 노출)
        return
    }

    // MARK: - 횡단보도 감지

    private func parseCrosswalkFromDebugMessage() -> Bool {
        guard let debug = navigationManager.debugMessage.value as? String else {
            return false
        }
        return debug.contains("횡단보도=true")
    }

    // MARK: - IMU 캘리브레이션 (안내 시작 시 1회)

    /// 안내 시작 직후 — 사용자가 경로의 첫 진행 방향을 향하도록 유도.
    /// 사용자가 ±15° 안으로 정렬되거나 10초가 지나면 setBaseHeading() 후 정상 안내 진입.
    private func startCalibration(targetBearing: Double) {
        cancelCalibration()

        calibrationTargetBearing = targetBearing
        calibrationStage = .inProgress

        print("🧭 [CALIB] 시작 — target=\(Int(targetBearing))°, current=\(Int(headingProvider.currentHeading))°")
        tts.speak("한 바퀴 천천히 돌아보세요. 올바른 방향이 되면 알려드릴게요.", priority: .high)

        // heading 업데이트마다 정렬 검사
        calibrationCancellable = headingProvider.$currentHeading
            .sink { [weak self] heading in
                guard let self = self else { return }
                guard self.calibrationStage == .inProgress else { return }
                guard let target = self.calibrationTargetBearing else { return }
                let diff = abs(self.signedAngleDifference(from: target, to: heading))
                if diff <= self.calibrationAlignmentDegrees {
                    self.finishCalibration(reason: .aligned)
                }
            }

        // 무한 대기 방지
        calibrationTimeoutTask = Task { @MainActor [weak self] in
            guard let self = self else { return }
            try? await Task.sleep(nanoseconds: UInt64(self.calibrationTimeoutSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            guard self.calibrationStage == .inProgress else { return }
            self.finishCalibration(reason: .timedOut)
        }
    }

    private enum CalibrationFinishReason {
        case aligned    // 사용자가 정렬됨
        case timedOut   // 10초 지남 — 그냥 현재 방향으로 base 잡고 진행
    }

    private func finishCalibration(reason: CalibrationFinishReason) {
        cancelCalibration()
        headingProvider.setBaseHeading()
        calibrationStage = .done

        switch reason {
        case .aligned:
            print("🧭 [CALIB] 정렬 완료 — base=\(Int(headingProvider.currentHeading))°")
            tts.speak("맞는 방향입니다. 직진하세요.", priority: .high)
        case .timedOut:
            print("🧭 [CALIB] 타임아웃 — 현재 방향(\(Int(headingProvider.currentHeading))°)으로 진행")
            tts.speak("방향 확인을 건너뜁니다. 현재 방향으로 안내를 시작합니다.", priority: .high)
        }

        // 캘리브레이션 동안 폴링이 받아둔 가이던스가 있으면 한 번 발화
        let pending = (navigationManager.guidanceMessage.value as? String) ?? ""
        if !pending.isEmpty && pending != lastSpokenGuidance {
            lastSpokenGuidance = pending
            tts.speak(pending, priority: .normal)
        }
    }

    private func cancelCalibration() {
        calibrationCancellable?.cancel()
        calibrationCancellable = nil
        calibrationTimeoutTask?.cancel()
        calibrationTimeoutTask = nil
        calibrationTargetBearing = nil
    }

    /// 경로의 첫 segment bearing 을 계산. waypoint 가 부족하면 출발지→POI 직선 bearing 으로 fallback.
    private func computeFirstSegmentBearing(fallbackStart: GpsLocation, poi: POIResult) -> Double {
        if let route = navigationManager.currentRoute, route.waypoints.count >= 2 {
            let a = route.waypoints[0]
            let b = route.waypoints[1]
            return computeBearing(a.lat, a.lon, b.lat, b.lon)
        }
        return computeBearing(fallbackStart.latitude, fallbackStart.longitude, poi.lat, poi.lon)
    }

    /// 두 좌표 사이의 진행 방향 (0°=북, 시계 방향, 0~360°)
    private func computeBearing(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let φ1 = lat1 * .pi / 180
        let φ2 = lat2 * .pi / 180
        let Δλ = (lon2 - lon1) * .pi / 180
        let y = sin(Δλ) * cos(φ2)
        let x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        let θ = atan2(y, x)
        let deg = θ * 180 / .pi
        return (deg + 360).truncatingRemainder(dividingBy: 360)
    }

    /// 두 heading 사이의 부호 있는 최소 각도 차이 (-180 ~ +180)
    private func signedAngleDifference(from base: Double, to current: Double) -> Double {
        var d = current - base
        while d > 180 { d -= 360 }
        while d < -180 { d += 360 }
        return d
    }

    // MARK: - Orientation

//    private func handleOrientationAlertIfNeeded() {
//        let currentStatus = orientationMonitor.status
//        let currentIssue = orientationMonitor.issue
//
//        if currentStatus == .normal {
//            lastSpokenIssue = .none
//            return
//        }
//
//        guard currentStatus == .dangerous else { return }
//        guard currentIssue != .none else { return }
//        guard let message = currentIssue.ttsMessage else { return }
//
//        let now = Date()
//        let isSameIssue = (currentIssue == lastSpokenIssue)
//        let timeSinceLast = now.timeIntervalSince(lastOrientationSpeakTime)
//
//        if isSameIssue && timeSinceLast < orientationRepeatInterval {
//            return
//        }
//
//        tts.speak(message, priority: .high)
//        lastSpokenIssue = currentIssue
//        lastOrientationSpeakTime = now
//    }
}
