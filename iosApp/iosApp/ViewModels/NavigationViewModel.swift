//
//  NavigationViewModel.swift
//  iosApp
//
//  KMM의 NavigationManager를 SwiftUI에서 사용할 수 있게 래핑
//
//  역할:
//  - LocationTracker GPS → NavigationManager.updateLocation() 전달
//  - HeadingProvider heading → NavigationManager.updateCompassHeading() 전달
//  - DeviceOrientationMonitor 자세 → 비정상 시 음성 보정 요청
//  - NavigationManager의 StateFlow → Swift @Published로 노출
//  - 안내 메시지 변화 시 TtsManager로 음성 출력
//

import Foundation
import Combine
import shared

/// 내비게이션 통합 ViewModel
@MainActor
final class NavigationViewModel: ObservableObject {

    // MARK: - Published State (UI에서 관찰)
    /// 현재 안내 메시지 ("200미터 앞 좌회전" 등)
    @Published private(set) var guidanceMessage: String = ""

    /// 도착 단계
    @Published private(set) var arrivalState: ArrivalState = .far

    /// 내비게이션 활성 여부
    @Published private(set) var isNavigating: Bool = false

    /// 목적지까지 남은 거리 (m)
    @Published private(set) var distanceToDestination: Float = .greatestFiniteMagnitude

    /// 검색 결과
    @Published private(set) var searchResults: [POIResult] = []

    /// 마지막 에러 메시지
    @Published private(set) var errorMessage: String?

    // MARK: - Dependencies
    private let tts: TtsManager
    private let locationTracker: LocationTracker
    private let headingProvider: HeadingProvider
    private let orientationMonitor: DeviceOrientationMonitor   // ⭐ 추가

    /// KMM의 핵심 매니저
    private let navigationManager: NavigationManager

    // MARK: - Subscriptions
    private var cancellables = Set<AnyCancellable>()
    private var pollingTask: Task<Void, Never>?

    /// 직전 안내 메시지 (TTS 중복 방지)
    private var lastSpokenGuidance: String = ""

    // MARK: - Orientation Alert State (자세 경고 중복 방지)
    /// 직전에 안내한 자세 문제 (같은 문제 반복 안내 방지)
    private var lastSpokenIssue: OrientationIssue = .none
    /// 직전 자세 안내 시각
    private var lastOrientationSpeakTime: Date = .distantPast
    /// 같은 문제 재안내 최소 간격 (초)
    private let orientationRepeatInterval: TimeInterval = 5.0

    // MARK: - Init
    init(
        tts: TtsManager,
        locationTracker: LocationTracker,
        headingProvider: HeadingProvider,
        orientationMonitor: DeviceOrientationMonitor,   // ⭐ 추가
        navigationManager: NavigationManager
    ) {
        self.tts = tts
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
        self.orientationMonitor = orientationMonitor    // ⭐ 추가
        self.navigationManager = navigationManager

        bindLocationToNavigation()
        bindHeadingToNavigation()
        startPollingNavigationState()
    }

    deinit {
        pollingTask?.cancel()
    }

    // MARK: - Public API (UI가 호출)

    /// 목적지 키워드로 POI 검색
    func searchDestination(keyword: String) async {
        do {
            // Provide current location and a default radius (km) to match updated API
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

    /// 선택한 POI로 내비게이션 시작
    func startNavigation(to poi: POIResult) async {
        guard let currentLoc = locationTracker.currentLocation else {
            self.errorMessage = "현재 위치를 알 수 없습니다"
            return
        }

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

            if success.boolValue {
                // 직선 시작이므로 base heading 설정
                headingProvider.setBaseHeading()
            } else {
                self.errorMessage = (navigationManager.lastError as String?) ?? "경로를 찾을 수 없습니다"
            }
        } catch {
            self.errorMessage = "안내 시작 실패: \(error.localizedDescription)"
        }
    }

    /// 내비게이션 종료
    func stopNavigation() {
        navigationManager.stopNavigation()
        headingProvider.clearBaseHeading()
        tts.stop()
    }

    // MARK: - Private Bindings

    /// LocationTracker의 GPS 변화를 NavigationManager에 전달
    private func bindLocationToNavigation() {
        locationTracker.$currentLocation
            .compactMap { $0 }
            .removeDuplicates { $0 === $1 }
            .sink { [weak self] gpsLocation in
                guard let self else { return }
                Task {
                    do {
                        try await self.navigationManager.updateLocation(
                            location: gpsLocation
                        )
                    } catch {
                        print("[NavViewModel] updateLocation 실패: \(error)")
                    }
                }
            }
            .store(in: &cancellables)
    }

    /// HeadingProvider의 나침반 값을 NavigationManager에 전달 (CSV 로그용)
    private func bindHeadingToNavigation() {
        headingProvider.$currentHeading
            .sink { [weak self] heading in
                self?.navigationManager.updateCompassHeading(azimuth: Float(heading))
            }
            .store(in: &cancellables)
    }

    /// NavigationManager의 StateFlow 값들을 주기적으로 polling
    private func startPollingNavigationState() {
        pollingTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { return }

                // 1. 안내 메시지 polling
                let newGuidance = (self.navigationManager.guidanceMessage.value as? String) ?? ""
                if newGuidance != self.guidanceMessage {
                    self.guidanceMessage = newGuidance
                    self.handleGuidanceChange(newGuidance)
                }

                // 2. 도착 단계 polling
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

                // 5. drift correction 음성 안내
                self.handleDriftAlertIfNeeded()

                // 6. ⭐ 자세 경고 음성 안내 (신규)
                self.handleOrientationAlertIfNeeded()

                // 200ms 간격 polling
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
    }

    // MARK: - Side Effects

    /// 안내 메시지가 바뀔 때마다 호출 → TTS 출력
    private func handleGuidanceChange(_ message: String) {
        guard !message.isEmpty else { return }
        guard message != lastSpokenGuidance else { return }
        lastSpokenGuidance = message

        let priority: TtsManager.Priority = (arrivalState == .arrived) ? .high : .normal
        tts.speak(message, priority: priority)
    }

    /// drift correction: 직선 구간 + 임계값 초과 시 음성 알림
    private func handleDriftAlertIfNeeded() {
        guard isNavigating else { return }
        guard headingProvider.isDrifting else { return }

        let direction = headingProvider.driftDegrees > 0 ? "오른쪽" : "왼쪽"
        let absDeg = Int(abs(headingProvider.driftDegrees))
        tts.speak("\(direction)으로 \(absDeg)도 벗어났습니다", priority: .high)
    }

    /// ⭐ 자세 경고: pitch/roll/흔들림 비정상 시 음성으로 보정 요청
    /// - dangerous 상태에서만 음성 안내 (warning은 UI 표시만)
    /// - 같은 issue가 지속되면 5초마다 재안내
    /// - 정상 상태로 돌아가면 lastSpokenIssue 리셋
    private func handleOrientationAlertIfNeeded() {
        let currentStatus = orientationMonitor.status
        let currentIssue = orientationMonitor.issue

        // 정상 상태로 돌아왔으면 상태 리셋 (다음 위험 발생 시 즉시 안내 가능)
        if currentStatus == .normal {
            lastSpokenIssue = .none
            return
        }

        // dangerous 상태에서만 음성 안내 (warning은 화면 UI에만 표시)
        guard currentStatus == .dangerous else { return }
        guard currentIssue != .none else { return }

        // 안내 메시지 결정
        guard let message = currentIssue.ttsMessage else { return }

        let now = Date()
        let isSameIssue = (currentIssue == lastSpokenIssue)
        let timeSinceLast = now.timeIntervalSince(lastOrientationSpeakTime)

        // 같은 issue가 5초 이내 반복이면 스킵
        if isSameIssue && timeSinceLast < orientationRepeatInterval {
            return
        }

        // 안내 실행
        tts.speak(message, priority: .high)
        lastSpokenIssue = currentIssue
        lastOrientationSpeakTime = now
    }
}

