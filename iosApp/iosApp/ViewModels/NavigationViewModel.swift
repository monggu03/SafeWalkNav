//
//  NavigationViewModel.swift
//  iosApp
//
//  KMM의 NavigationManager를 SwiftUI에서 사용할 수 있게 래핑
//

import Foundation
import Combine
import shared

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

    // MARK: - Dependencies
    private let tts: TtsManager
    private let locationTracker: LocationTracker
    private let headingProvider: HeadingProvider
    //private let orientationMonitor: DeviceOrientationMonitor
    private let navigationManager: NavigationManager

    // MARK: - Subscriptions
    private var cancellables = Set<AnyCancellable>()
    private var pollingTask: Task<Void, Never>?

    private var lastSpokenGuidance: String = ""

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
        //orientationMonitor: DeviceOrientationMonitor,
        navigationManager: NavigationManager
    ) {
        self.tts = tts
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
        //self.orientationMonitor = orientationMonitor
        self.navigationManager = navigationManager

        print("🟢 [INIT] NavigationViewModel 생성됨")
        bindLocationToNavigation()
        bindHeadingToNavigation()
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

        let priority: TtsManager.Priority = (arrivalState == .arrived) ? .high : .normal
        tts.speak(message, priority: priority)
    }

    private func handleDriftAlertIfNeeded() {
        guard isNavigating else { return }
        guard headingProvider.isDrifting else { return }

        let direction = headingProvider.driftDegrees > 0 ? "오른쪽" : "왼쪽"
        let absDeg = Int(abs(headingProvider.driftDegrees))
        tts.speak("\(direction)으로 \(absDeg)도 벗어났습니다", priority: .high)
    }

    // MARK: - 횡단보도 감지

    private func parseCrosswalkFromDebugMessage() -> Bool {
        guard let debug = navigationManager.debugMessage.value as? String else {
            return false
        }
        return debug.contains("횡단보도=true")
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
