//
//  TBFWDemoViewModel.swift
//  iosApp
//
//  TBFW (Trust-Based Forward Waypoint) 알고리즘 데모용 ViewModel.
//  기존 NavigationViewModel과 독립적으로 동작 — TBFW 단독 검증용.
//
//  데이터 흐름:
//    LocationTracker.currentLocation (GpsLocation, KMP)
//                  ↓
//    HeadingProvider.currentHeading (Double)
//                  ↓
//    UserState 생성 → TrustBasedNavigator.update()
//                  ↓
//    NavigationResult → @Published 프로퍼티들
//

import Foundation
import Combine
import shared    // KMP framework

@MainActor
final class TBFWDemoViewModel: ObservableObject {

    // MARK: - Published Output (UI에 노출)

    /// TBFW가 생성한 안내 문장 (TTS로 읽을 텍스트)
    @Published private(set) var message: String = "대기 중..."

    /// 현재 Trust Score (0~100)
    @Published private(set) var trustScore: Int = 0

    /// Trust 카테고리 (HIGH/MEDIUM/LOW/CRITICAL)
    @Published private(set) var trustLevel: String = "-"

    /// 현재 waypoint까지 거리 (m)
    @Published private(set) var distanceToWaypoint: Float = 0

    /// 현재 heading과 목표 bearing의 차이 (도)
    @Published private(set) var headingDiff: Float = 0

    /// 현재 목표 waypoint 인덱스
    @Published private(set) var currentWaypointIndex: Int = 0

    /// 전체 waypoint 개수
    @Published private(set) var totalWaypoints: Int = 0

    /// 마지막 update에서 waypoint를 통과했는지
    @Published private(set) var didPassWaypoint: Bool = false

    /// 모든 waypoint 통과 완료 여부
    @Published private(set) var isFinished: Bool = false

    /// TBFW 동작 중 여부
    @Published private(set) var isRunning: Bool = false

    // MARK: - Private Properties

    /// TBFW 핵심 객체 — start() 시점에 생성
    private var navigator: TrustBasedNavigator?

    /// 의존성 (외부에서 주입)
    private let locationTracker: LocationTracker
    private let headingProvider: HeadingProvider

    /// Combine 구독 토큰
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Init

    init(locationTracker: LocationTracker, headingProvider: HeadingProvider) {
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
    }

    // MARK: - Public API

    /// TBFW 시작 — 지정된 waypoint들로 navigator 초기화하고 위치 구독 시작.
    ///
    /// - Parameter waypoints: 따라갈 waypoint 리스트. nil이면 기본 데모 경로 사용.
    func start(waypoints: [Waypoint]? = nil) {
        let routeWaypoints = waypoints ?? Self.makeDemoRoute()

        // TrustBasedNavigator 생성 — 기본 config 사용
        self.navigator = TrustBasedNavigator(
            waypoints: routeWaypoints,
            config: NavigatorConfig.companion.defaults()
        )

        self.totalWaypoints = routeWaypoints.count
        self.currentWaypointIndex = 0
        self.isFinished = false
        self.isRunning = true
        self.message = "TBFW 시작됨. GPS 신호 대기 중..."

        print("[TBFWDemo] sink 구독 시작 - locationTracker: \(locationTracker)")
        print("[TBFWDemo] 현재 location 값: \(String(describing: locationTracker.currentLocation))")

        locationTracker.$currentLocation
            .sink { [weak self] gpsLocationOptional in
                print("[TBFWDemo] sink 발화! value: \(String(describing: gpsLocationOptional))")
                guard let gpsLocation = gpsLocationOptional else {
                    print("[TBFWDemo] gpsLocation이 nil")
                    return
                }
                print("[TBFWDemo] processUpdate 호출 직전")
                self?.processUpdate(gpsLocation: gpsLocation)
            }
            .store(in: &cancellables)

        print("[TBFWDemo] sink 구독 완료, cancellables 개수: \(cancellables.count)")

        print("[TBFWDemo] 시작 — \(routeWaypoints.count)개 waypoint")
    }

    /// TBFW 종료 — 구독 해제 및 상태 초기화.
    func stop() {
        cancellables.removeAll()
        navigator = nil
        isRunning = false
        message = "TBFW 종료됨"
        print("[TBFWDemo] 종료")
    }

    // MARK: - Private — 위치 업데이트 처리

    /// 새 GPS 위치가 들어올 때마다 호출.
    /// UserState 만들어서 navigator.update() 호출 → 결과를 @Published에 반영.
    private func processUpdate(gpsLocation: GpsLocation) {
        guard let navigator = self.navigator else { return }

        // heading은 HeadingProvider에서 가져옴 (magneticHeading 기반, 0~360°)
        // 추적 중일 때만 유효. 아니면 nil → GPS bearing fallback.
        let heading: KotlinFloat? = headingProvider.isTracking
            ? KotlinFloat(value: Float(headingProvider.currentHeading))
            : nil

        // UserState 조립
        let userState = UserState(
            location: gpsLocation,
            heading: heading
        )

        // TBFW 호출
        let result = navigator.update(userState: userState)

        // 결과를 @Published에 반영
        self.message = result.message
        self.trustScore = Int(result.trustScore)
        self.trustLevel = "\(result.trustLevel)"
        self.distanceToWaypoint = result.distanceToWaypoint
        self.headingDiff = result.headingDiff
        self.currentWaypointIndex = Int(result.currentWaypointIndex)
        self.didPassWaypoint = result.didPassWaypoint
        self.isFinished = result.isFinished

        // 콘솔 로그 — 디버깅용
        print("""
            [TBFW] msg=\(result.message)
                   trust=\(result.trustScore) (\(result.trustLevel))
                   dist=\(String(format: "%.1f", result.distanceToWaypoint))m
                   heading=\(String(format: "%.1f", result.headingDiff))°
                   idx=\(result.currentWaypointIndex)/\(totalWaypoints)
                   pass=\(result.didPassWaypoint) finished=\(result.isFinished)
            """)
    }

    // MARK: - Demo Route

    /// 데모용 기본 경로 — 서울 시청 근처 동쪽으로 3개 waypoint.
    /// 시뮬레이터 GPS를 Apple 본사가 아니라 직접 좌표 입력 시 사용.
    private static func makeDemoRoute() -> [Waypoint] {
        return [
            Waypoint(
                lat: 37.5666, lon: 126.97840,
                turnType: 0, description: "지점 1",
                distance: 13, roadType: 0, pointType: "TURN"
            ),
            Waypoint(
                lat: 37.5666, lon: 126.97855,
                turnType: 0, description: "횡단보도",
                distance: 13, roadType: 0, pointType: "CROSSWALK"
            ),
            Waypoint(
                lat: 37.5666, lon: 126.97870,
                turnType: 0, description: "목적지",
                distance: 0, roadType: 0, pointType: "DESTINATION"
            ),
        ]
    }
}
