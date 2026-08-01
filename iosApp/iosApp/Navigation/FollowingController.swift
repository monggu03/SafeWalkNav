//
//  FollowingController.swift
//  iosApp
//
//  §4-3 GPS 추종 + 횡단보도 접근 감지 + 도착 판정.
//  방향 안내는 하지 않는다(§1 제외). 이 컨트롤러의 실시간 판정은 오직
//  "경로상 다음 횡단보도 반경 진입/이탈"과 "목적지 도착"뿐.
//
//  순수 지오펜싱 센서로 설계 — 위치 업데이트를 이벤트(콜백)로 매핑만 하고,
//  부수효과(TTS·신호등 검출기·phase 전환)는 NavigationCoordinator 콜백이 담당한다.
//

import Foundation
import Combine
import CoreLocation
import shared   // TMapRoute

@MainActor
final class FollowingController {

    /// 좌표 이벤트를 받는 쪽(coordinator)이 채우는 콜백 묶음.
    struct Callbacks {
        let enterCrossing: () -> Void          // 다음 횡단보도 반경 진입
        let exitCrossing: () -> Void           // 횡단보도 반경 이탈(히스테리시스)
        let arrive: () -> Void                 // 목적지 도착
        let updateRemaining: (_ meters: Int) -> Void   // 목적지까지 남은 직선거리
    }

    // MARK: 파라미터
    private let rEnter: Double = 25    // 진입 반경(m)
    private let rExit: Double = 35     // 이탈 반경(m) — 깜빡임 방지 히스테리시스
    private let arrivalR: Double = 20  // 도착 반경(m)

    // MARK: 상태
    private let locationTracker: LocationTracker
    private let callbacks: Callbacks

    private var crosswalks: [CLLocationCoordinate2D] = []   // 경로 순서 유지
    private var destination: CLLocationCoordinate2D?
    private var nextIdx = 0
    private var crossingActive = false
    private var cancellable: AnyCancellable?

    init(locationTracker: LocationTracker, callbacks: Callbacks) {
        self.locationTracker = locationTracker
        self.callbacks = callbacks
    }

    // MARK: 시작/정지

    func start(route: TMapRoute, destination: CLLocationCoordinate2D) {
        crosswalks = route.waypoints
            .filter { $0.pointType == "CROSSWALK" }
            .map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        self.destination = destination
        nextIdx = 0
        crossingActive = false

        locationTracker.start()
        cancellable = locationTracker.$currentLocation
            .compactMap { $0 }
            .sink { [weak self] cur in self?.onLocation(cur) }

        print("🚶 [Following] start — 횡단보도 \(crosswalks.count)개, 도착반경 \(Int(arrivalR))m")
        #if DEBUG
        for (i, c) in crosswalks.enumerated() {
            print("🚦 [Following] CW[\(i)] = \(c.latitude),\(c.longitude)")
        }
        print("🏁 [Following] DEST = \(destination.latitude),\(destination.longitude)")
        #endif
    }

    func stop() {
        cancellable?.cancel()
        cancellable = nil
    }

    // MARK: 위치 업데이트 판정

    private func onLocation(_ cur: CLLocationCoordinate2D) {
        // 1) 도착 판정 우선.
        if let dest = destination {
            let dDest = haversine(cur, dest)
            if dDest <= arrivalR {
                stop()
                callbacks.arrive()
                return
            }
            callbacks.updateRemaining(Int(dDest.rounded()))
        }

        // 2) 남은 횡단보도 없으면 도착까지 직행.
        guard nextIdx < crosswalks.count else { return }

        // 3) 다음 횡단보도 진입/이탈.
        let dCross = haversine(cur, crosswalks[nextIdx])
        if !crossingActive && dCross <= rEnter {
            crossingActive = true
            print("🚦 [Following] 횡단보도[\(nextIdx)] 진입 (\(Int(dCross))m)")
            callbacks.enterCrossing()
        } else if crossingActive && dCross > rExit {
            crossingActive = false
            print("🚦 [Following] 횡단보도[\(nextIdx)] 이탈 (\(Int(dCross))m)")
            nextIdx += 1
            callbacks.exitCrossing()
        }
    }

    // MARK: haversine (직선거리 m)

    private func haversine(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let R = 6_371_000.0
        let p1 = a.latitude * .pi / 180
        let p2 = b.latitude * .pi / 180
        let dp = (b.latitude - a.latitude) * .pi / 180
        let dl = (b.longitude - a.longitude) * .pi / 180
        let h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }
}
