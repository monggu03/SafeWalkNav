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

    /// RouteAnnotator 가 분석한 경로 annotation 개수 (디버그/UI 용).
    @Published private(set) var annotationCount: Int = 0

    /// 사전 분석된 경로 — TBFWDemoView 가 HeadingGuideView 로 첫 waypoint 를 넘길 때 참고.
    @Published private(set) var routeWaypoints: [Waypoint] = []

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
    /// 사전 안내 (annotation) 발화용. nil 이면 콘솔에만 로그.
    private let tts: TtsManager?

    /// Combine 구독 토큰
    private var cancellables = Set<AnyCancellable>()

    /// 직전에 TTS 로 발화한 annotation 메시지 — 같은 문장 연달아 발화 방지.
    private var lastSpokenAnnouncement: String = ""

    // MARK: - Init

    init(
        locationTracker: LocationTracker,
        headingProvider: HeadingProvider,
        tts: TtsManager? = nil,
    ) {
        self.locationTracker = locationTracker
        self.headingProvider = headingProvider
        self.tts = tts
    }

    // MARK: - Public API

    /// TBFW 시작 — 지정된 waypoint들로 navigator 초기화하고 위치 구독 시작.
    ///
    /// - Parameter waypoints: 따라갈 waypoint 리스트. nil이면 기본 데모 경로 사용.
    func start(waypoints: [Waypoint]? = nil) {
        let routeWaypoints = waypoints ?? Self.makeDemoRoute()
        let config = NavigatorConfig.companion.defaults()

        // 1. 경로 사전 분석 — RouteAnnotator 가 곡선/회전을 미리 잡아낸다.
        //    사람이 읽기 좋은 디버그 로그는 RouteAnnotationLogger 가 출력한다.
        // segments 는 Swift 에서 Kotlin default 인자가 안 보이므로 명시적으로 빈 배열 전달.
        // INTERNAL_CURVE 검출을 켜려면 TMap 응답의 RouteSegment 리스트를 여기에 넘기면 됨 (Step 2).
        let annotated = RouteAnnotator(config: config).annotate(
            waypoints: routeWaypoints,
            segments: []
        )
        self.annotationCount = annotated.annotations.count
        self.routeWaypoints = routeWaypoints
        RouteAnnotationLogger.shared.log(
            annotated: annotated,
            routeName: "TBFW 데모 경로",
            totalDistanceM: nil,
            config: config,
        )

        // 2. TrustBasedNavigator 생성 — annotation 도 함께 넘겨 사전 안내 활성화.
        self.navigator = TrustBasedNavigator(
            waypoints: routeWaypoints,
            config: config,
            annotations: annotated.annotations,
        )

        self.totalWaypoints = routeWaypoints.count
        self.currentWaypointIndex = 0
        self.isFinished = false
        self.isRunning = true
        self.lastSpokenAnnouncement = ""
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

        // 사전 안내 (annotation) — TrustBasedNavigator 가 한 번만 돌려준다.
        if let announcement = result.annotationAnnouncement,
           !announcement.isEmpty,
           announcement != lastSpokenAnnouncement {
            lastSpokenAnnouncement = announcement
            tts?.speak(announcement)
            print("[TBFW] 🔔 annotation: \(announcement)")
        }

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

    /// HeadingGuideView 등이 미리 첫 waypoint 를 알아야 할 때 쓰는 외부 노출 헬퍼.
    /// 내부적으로 makeDemoRoute() 를 그대로 부른다.
    static func makeDemoRoutePublic() -> [Waypoint] {
        return makeDemoRoute()
    }

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
