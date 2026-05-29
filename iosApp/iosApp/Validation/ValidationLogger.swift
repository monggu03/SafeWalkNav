//
//  ValidationLogger.swift
//  iosApp
//
//  IMU 신뢰도 검증 전용 로거 (측정/분석 목적, 안내 로직 비침습)
//  - CLLocationManager: 위치 + heading 수집
//  - CMMotionManager: 디바이스 자세(pitch/roll) 수집
//  - heading 콜백마다 최신 위치/자세를 묶어 CSV 한 행으로 기록
//  - 정지 시 Documents 디렉토리에 CSV flush, UIActivityViewController로 공유
//
//  ⚠️ 검증 전용 모듈. 실제 안내 동작에 어떤 영향도 주지 않는다.
//

import Combine
import CoreLocation
import CoreMotion
import UIKit

/// 보행 중 GPS / heading / 자세 값을 CSV로 로깅하는 검증 전용 매니저
final class ValidationLogger: NSObject, ObservableObject, CLLocationManagerDelegate {

    // MARK: - Constants
    /// CSV 헤더 (4절 스키마와 정확히 일치해야 함)
    private static let csvHeader =
        "timestamp,gpsLat,gpsLon,gpsAccuracy,gpsSpeed,gpsCourse,trueHeading,magneticHeading,headingAccuracy,pitchDeg,rollDeg"

    // MARK: - Published State
    /// 로깅 진행 중 여부
    @Published private(set) var isLogging = false

    /// 누적된 데이터 행 수 (헤더 제외)
    @Published private(set) var rowCount = 0

    /// 마지막으로 저장된 CSV 파일 URL (공유용)
    @Published private(set) var lastSavedURL: URL?

    // MARK: - Private Properties
    private let locationManager = CLLocationManager()
    private let motionManager = CMMotionManager()

    /// 헤더 + 누적 데이터 행
    private var rows: [String] = []

    /// motion 클로저가 갱신하고 heading 콜백이 읽는 최신 자세값 (도 단위)
    private var latestPitchDeg = 0.0
    private var latestRollDeg = 0.0

    private let iso = ISO8601DateFormatter()

    // MARK: - Init
    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.headingFilter = 1.0  // 1° 변화마다 갱신 (급변 포착)
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    }

    // MARK: - Public API

    /// 로깅 시작
    func start() {
        guard !isLogging else { return }

        rows = [Self.csvHeader]
        rowCount = 0
        lastSavedURL = nil

        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()

        guard CLLocationManager.headingAvailable() else {
            print("[ValidationLogger] 이 기기는 나침반을 지원하지 않음")
            return
        }
        locationManager.startUpdatingHeading()

        if motionManager.isDeviceMotionAvailable {
            // 자세값은 .main 큐로 받아 최신값을 프로퍼티에 보관 → heading 콜백에서 읽음
            motionManager.deviceMotionUpdateInterval = 0.1
            motionManager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
                guard let self, let m = motion else { return }
                self.latestPitchDeg = m.attitude.pitch * 180 / .pi
                self.latestRollDeg = m.attitude.roll * 180 / .pi
            }
        } else {
            print("[ValidationLogger] 이 기기는 device motion을 지원하지 않음")
        }

        isLogging = true
        print("[ValidationLogger] 로깅 시작")
    }

    /// 로깅 정지 후 CSV를 Documents에 flush. 저장된 파일 URL 반환
    @discardableResult
    func stop() -> URL? {
        guard isLogging else { return lastSavedURL }

        locationManager.stopUpdatingHeading()
        locationManager.stopUpdatingLocation()
        motionManager.stopDeviceMotionUpdates()
        isLogging = false

        let name = "imu_validation_\(fileTimestamp()).csv"
        let url = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(name)

        do {
            try rows.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
            lastSavedURL = url
            print("[ValidationLogger] 저장 완료: \(name) (\(rowCount)행)")
            return url
        } catch {
            print("[ValidationLogger] 저장 실패: \(error)")
            return nil
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateHeading h: CLHeading) {
        guard isLogging else { return }

        // 콜백 시점의 가장 최근 위치와 (motion 클로저가 갱신한) 최신 자세를 함께 기록
        let loc = manager.location
        let row = [
            iso.string(from: Date()),
            "\(loc?.coordinate.latitude ?? 0)",
            "\(loc?.coordinate.longitude ?? 0)",
            "\(loc?.horizontalAccuracy ?? -1)",
            "\(loc?.speed ?? -1)",         // 저속/정지 시 -1, 거르지 않고 그대로 기록
            "\(loc?.course ?? -1)",        // 무효 시 -1, 그대로 기록
            "\(h.trueHeading)",            // 진북 기준 (분석 기본값)
            "\(h.magneticHeading)",        // 자북 기준 (편각 혼동 방지용 보존)
            "\(h.headingAccuracy)",        // 음수면 캘리브레이션 필요
            "\(latestPitchDeg)",           // 도 단위
            "\(latestRollDeg)"             // 도 단위
        ].joined(separator: ",")

        rows.append(row)
        rowCount = rows.count - 1
    }

    // MARK: - Private Helpers

    private func fileTimestamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f.string(from: Date())
    }
}
