//
//  NavigationViewModel.swift
//  iosApp
//
//  KMM의 NavigationManager를 SwiftUI에서 사용할 수 있게 래핑
//
//  역할:
//  - LocationTracker GPS → NavigationManager.updateLocation() 전달
//  - HeadingProvider heading → NavigationManager.updateCompassHeading() 전달
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

    /// KMM의 핵심 매니저
    private let navigationManager: NavigationManager

    // MARK: - Subscriptions
    private var cancellables = Set<AnyCancellable>()
    private var pollingTask: Task<Void, Never>?

    /// 직전 안내 메시지 (TTS 중복 방지)
    private var lastSpokenGuidance: String = ""

    // MARK: - Init
    init(
        tts: TtsManager,
        locationTracker: LocationTracker,
        headingProvider: HeadingProvider,
        navigationManager: NavigationManager
    ) {
        self.tts = tts
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
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
            let results = try await navigationManager.searchDestination(keyword: keyword)
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
                self.errorMessage = (navigationManager.lastError as String?) ?? "경로를 찾을 수 없습니다"            }
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
            .compactMap { $0 }                          // nil 무시
            .removeDuplicates { $0 === $1 }            // 같은 객체 중복 무시
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
    /// (Kotlin Flow → Swift @Published 변환의 가장 단순한 방법)
    /// NavigationManager의 StateFlow 값들을 주기적으로 polling
    /// (Kotlin Flow → Swift @Published 변환의 가장 단순한 방법)
    private func startPollingNavigationState() {
        pollingTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { return }

                // 1. 안내 메시지 polling (KMM에서 Any?로 와서 String 캐스팅 필요)
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

                // 5. drift correction 음성 안내 (별도 처리)
                self.handleDriftAlertIfNeeded()

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

        // 도착 단계에 따라 priority 조정
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
}
