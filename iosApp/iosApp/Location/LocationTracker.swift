//
//  LocationTracker.swift
//  iosApp
//
//  GPS 위치 추적 매니저
//  - CLLocationManager 래핑
//  - CLLocation → KMM의 GpsLocation으로 변환
//  - 권한 요청 처리
//

import Foundation
import CoreLocation
import Combine
import shared    // ⭐ KMM의 GpsLocation, toGpsLocation() 사용

/// GPS 위치를 추적해서 KMM의 GpsLocation 형태로 publish
final class LocationTracker: NSObject, ObservableObject {

    // MARK: - Published State
    /// 가장 최근의 GPS 위치 (KMM 형식)
    @Published private(set) var currentLocation: GpsLocation?

    /// 권한 상태
    @Published private(set) var authorizationStatus: CLAuthorizationStatus = .notDetermined

    /// GPS 추적 중 여부
    @Published private(set) var isTracking: Bool = false

    /// 디버깅용 - 마지막 raw CLLocation의 정확도
    @Published private(set) var lastAccuracy: Double = 0

    // MARK: - Private Properties
    private let manager = CLLocationManager()

    // MARK: - Init
    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest         // 최고 정확도
        manager.distanceFilter = 1.0                              // 1m마다 업데이트
        manager.activityType = .fitness                            // 도보 활동에 최적화
        authorizationStatus = manager.authorizationStatus
    }

    // MARK: - Public API

    /// GPS 추적 시작 (권한 없으면 자동으로 요청)
    func start() {
        switch manager.authorizationStatus {
        case .notDetermined:
            // 처음 사용 - 권한 요청
            manager.requestWhenInUseAuthorization()
            // 권한 허용되면 didChangeAuthorization에서 자동으로 startUpdating 호출
        case .authorizedWhenInUse, .authorizedAlways:
            startUpdating()
        case .denied, .restricted:
            print("[LocationTracker] 위치 권한 거부됨. 설정 앱에서 허용 필요")
        @unknown default:
            print("[LocationTracker] 알 수 없는 권한 상태")
        }
    }

    /// GPS 추적 중단
    func stop() {
        manager.stopUpdatingLocation()
        isTracking = false
    }

    // MARK: - Private Helpers
    private func startUpdating() {
        manager.startUpdatingLocation()
        isTracking = true
        print("[LocationTracker] GPS 추적 시작")
    }
}

// MARK: - CLLocationManagerDelegate
extension LocationTracker: CLLocationManagerDelegate {

    /// 권한 상태 변경 시 호출 (iOS 14+)
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        DispatchQueue.main.async {
            self.authorizationStatus = manager.authorizationStatus
        }

        // 권한이 막 허용된 경우 → 추적 자동 시작
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            startUpdating()
        case .denied, .restricted:
            print("[LocationTracker] 위치 권한 거부 - 사용자가 설정에서 허용해야 함")
        default:
            break
        }
    }

    /// 새 위치가 들어올 때마다 호출
    func locationManager(_ manager: CLLocationManager,
                         didUpdateLocations locations: [CLLocation]) {
        guard let clLocation = locations.last else { return }

        // ⭐ 핵심: KMM의 extension으로 변환
        // CLLocationConverter.kt의 toGpsLocation() 호출
        let gpsLocation = CLLocationConverterKt.toGpsLocation(clLocation)
        
        DispatchQueue.main.async {
            self.currentLocation = gpsLocation
            self.lastAccuracy = clLocation.horizontalAccuracy
        }
    }

    /// 에러 발생 시
    func locationManager(_ manager: CLLocationManager,
                         didFailWithError error: Error) {
        print("[LocationTracker] 위치 업데이트 실패: \(error.localizedDescription)")
    }
}
