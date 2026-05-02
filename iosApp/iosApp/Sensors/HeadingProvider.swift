//
//  HeadingProvider.swift
//  iosApp
//
//  나침반 heading 추적 매니저 (drift correction용)
//  - CLLocationManager.heading으로 진행 방향 측정
//  - 베이스 heading 대비 편차 계산
//  - ±15° 임계값 초과 시 알림 (시각장애인 좌우 이탈 감지)
//

import Foundation
import CoreLocation
import Combine

/// 나침반 heading을 추적하고 base 대비 편차를 계산
final class HeadingProvider: NSObject, ObservableObject {

    // MARK: - Constants
    /// drift 임계값 (도) - 이 이상 벗어나면 alert
    static let driftThresholdDegrees: Double = 15.0

    // MARK: - Published State
    /// 현재 진행 방향 (0° = 북, 90° = 동, ...) - magnetic heading
    @Published private(set) var currentHeading: Double = 0

    /// base heading 대비 편차 (도). 음수 = 왼쪽 이탈, 양수 = 오른쪽 이탈
    @Published private(set) var driftDegrees: Double = 0

    /// drift 임계값 초과 여부
    @Published private(set) var isDrifting: Bool = false

    /// 추적 중 여부
    @Published private(set) var isTracking: Bool = false

    /// 베이스 heading이 설정되었는지 여부
    @Published private(set) var hasBaseHeading: Bool = false

    // MARK: - Private Properties
    private let manager = CLLocationManager()

    /// 직선 구간 시작 시점의 heading (drift 계산 기준)
    private var baseHeading: Double?

    // MARK: - Init
    override init() {
        super.init()
        manager.delegate = self
        manager.headingFilter = 1.0  // 1° 변화마다 업데이트
    }

    // MARK: - Public API

    /// heading 추적 시작
    func start() {
        guard CLLocationManager.headingAvailable() else {
            print("[HeadingProvider] 이 기기는 나침반을 지원하지 않음")
            return
        }
        manager.startUpdatingHeading()
        isTracking = true
        print("[HeadingProvider] heading 추적 시작")
    }

    /// heading 추적 중단
    func stop() {
        manager.stopUpdatingHeading()
        isTracking = false
    }

    /// 현재 heading을 base로 설정 (직선 구간 시작 시 호출)
    /// 예: NavigationManager가 "직진 시작" 신호를 줄 때
    func setBaseHeading() {
        baseHeading = currentHeading
        hasBaseHeading = true
        driftDegrees = 0
        isDrifting = false
        print("[HeadingProvider] base heading 설정: \(currentHeading)°")
    }

    /// base heading 초기화 (커브/회전 시점에 호출)
    func clearBaseHeading() {
        baseHeading = nil
        hasBaseHeading = false
        driftDegrees = 0
        isDrifting = false
    }

    // MARK: - Private Helpers

    /// 두 heading 사이의 부호 있는 최소 각도 차이 계산 (-180 ~ +180)
    /// 예: base=350°, current=10° → 차이는 -340°가 아니라 +20°
    private func signedAngleDifference(from base: Double, to current: Double) -> Double {
        var diff = current - base
        // -180 ~ 180 범위로 정규화
        while diff > 180 { diff -= 360 }
        while diff < -180 { diff += 360 }
        return diff
    }
}

// MARK: - CLLocationManagerDelegate
extension HeadingProvider: CLLocationManagerDelegate {

    func locationManager(_ manager: CLLocationManager,
                         didUpdateHeading newHeading: CLHeading) {
        // magneticHeading: 자기 북쪽 기준 (실시간 사용 가능)
        // trueHeading: 진북 기준 (GPS 필요, 정확하지만 실내에서 -1)
        // → magneticHeading 사용 (실내에서도 동작)
        let heading = newHeading.magneticHeading

        // 정확도가 너무 낮으면 무시 (캘리브레이션 필요한 상태)
        guard newHeading.headingAccuracy >= 0 else { return }

        DispatchQueue.main.async {
            self.currentHeading = heading

            // base가 설정되어 있으면 drift 계산
            if let base = self.baseHeading {
                let drift = self.signedAngleDifference(from: base, to: heading)
                self.driftDegrees = drift
                self.isDrifting = abs(drift) > Self.driftThresholdDegrees
            }
        }
    }
}
