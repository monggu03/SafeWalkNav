//
//  Deviceorientationmonitor.swift
//  iosApp
//
//  Created by 이지민 on 5/5/26.
//

//
//  DeviceOrientationMonitor.swift
//  iosApp
//
//  IMU 센서 기반 폰 자세(pitch/roll/yaw) 모니터링
//  - chest-mount 카메라가 정상 자세인지 감시
//  - 비정상 자세 시 사용자에게 음성으로 보정 요청
//  - 팀원 Android 자료의 임계값을 그대로 적용
//
//  사용 예:
//    monitor.start()
//    monitor.$status.sink { status in
//        if status == .dangerous { ... }
//    }
//

import Foundation
import CoreMotion
import Combine

/// 폰 자세 상태 (팀원 자료 기준)
enum OrientationStatus: String {
    case normal      // 정상 — 안내 불필요
    case warning     // 경고 — 부드러운 안내
    case dangerous   // 위험 — 즉시 보정 요청
}

/// 어떤 종류의 자세 문제인지 (TTS 메시지 결정용)
enum OrientationIssue: Equatable {
    case none
    case pitchTooLow       // 폰을 너무 아래로 숙임
    case pitchTooHigh      // 폰을 너무 위로 들음
    case rollTilted        // 폰이 좌우로 기울어짐
    case shaking           // 가속도 분산 너무 큼 (흔들림)

    /// 사용자에게 안내할 메시지
    var ttsMessage: String? {
        switch self {
        case .none:           return nil
        case .pitchTooLow:    return "휴대폰을 가슴 높이까지 들어주세요"
        case .pitchTooHigh:   return "휴대폰을 살짝 아래로 내려주세요"
        case .rollTilted:     return "휴대폰을 바르게 세워주세요"
        case .shaking:        return "잠시 멈춰 휴대폰을 안정적으로 들어주세요"
        }
    }
}

/// IMU 자세 모니터링
@MainActor
final class DeviceOrientationMonitor: ObservableObject {

    // MARK: - Thresholds (팀원 자료 그대로)

    /// Pitch 정상 범위
    private let pitchNormalRange: ClosedRange<Double> = -15.0 ... 30.0
    /// Pitch 위험 임계값 (이 밖으로 나가면 dangerous)
    private let pitchDangerLow: Double = -30.0
    private let pitchDangerHigh: Double = 45.0

    /// Roll 정상 범위
    private let rollNormalRange: ClosedRange<Double> = -10.0 ... 10.0
    /// Roll 위험 임계값
    private let rollDangerAbs: Double = 20.0

    /// 가속도 분산 임계값 (m/s²)
    private let accelVarianceWarning: Double = 2.0
    private let accelVarianceDanger: Double = 5.0

    /// 가속도 분산 계산용 윈도우 크기 (최근 N개 샘플로 분산 계산)
    private let accelWindowSize: Int = 20  // 0.1초 간격이므로 2초

    // MARK: - Published State (UI/ViewModel 관찰용)

    /// 현재 pitch (도)
    @Published private(set) var pitch: Double = 0
    /// 현재 roll (도)
    @Published private(set) var roll: Double = 0
    /// 현재 yaw (도)
    @Published private(set) var yaw: Double = 0
    /// 가속도 분산 (m/s²)
    @Published private(set) var accelerationVariance: Double = 0

    /// 종합 상태
    @Published private(set) var status: OrientationStatus = .normal
    /// 어떤 문제인지 (TTS 메시지 결정용)
    @Published private(set) var issue: OrientationIssue = .none

    /// 모니터링 중 여부
    @Published private(set) var isMonitoring: Bool = false

    // MARK: - Private

    private let manager = CMMotionManager()
    /// 최근 가속도 크기 샘플들 (분산 계산용)
    private var accelMagnitudes: [Double] = []

    // MARK: - Lifecycle

    /// 모니터링 시작
    func start() {
        guard manager.isDeviceMotionAvailable else {
            print("[OrientationMonitor] Device Motion 사용 불가")
            return
        }
        guard !isMonitoring else { return }

        // 0.1초(10Hz)마다 업데이트 — 너무 자주 하면 배터리 소모 심함
        manager.deviceMotionUpdateInterval = 0.1

        // .xMagneticNorthZVertical: yaw가 자기 북쪽 기준
        // → 절대 방향이 필요 없으면 .xArbitraryZVertical도 가능 (실내에서 더 안정)
        manager.startDeviceMotionUpdates(
            using: .xArbitraryZVertical,
            to: .main
        ) { [weak self] motion, error in
            guard let self else { return }
            if let error {
                print("[OrientationMonitor] motion 오류: \(error)")
                return
            }
            guard let motion else { return }
            self.handleMotionUpdate(motion)
        }

        isMonitoring = true
        print("[OrientationMonitor] 모니터링 시작")
    }

    /// 모니터링 중단
    func stop() {
        guard isMonitoring else { return }
        manager.stopDeviceMotionUpdates()
        isMonitoring = false
        accelMagnitudes.removeAll()
        print("[OrientationMonitor] 모니터링 중단")
    }

    // MARK: - Motion Handling

    /// CoreMotion이 매 0.1초마다 호출
    private func handleMotionUpdate(_ motion: CMDeviceMotion) {
        // 1. 자세 (라디안 → 도)
        let rawPitchDeg = motion.attitude.pitch * 180.0 / .pi
        let pitchDeg = 90.0 - rawPitchDeg
        let rollDeg  = motion.attitude.roll  * 180.0 / .pi
        let yawDeg   = motion.attitude.yaw   * 180.0 / .pi

        // 2. 사용자 가속도 (중력 제외) — 흔들림 측정용
        // userAcceleration: 사용자 움직임만 (걷기, 흔들림)
        // gravity: 중력 방향 (자세 계산에 이미 사용됨)
        let userAccel = motion.userAcceleration
        let accelMagnitude = sqrt(
            userAccel.x * userAccel.x +
            userAccel.y * userAccel.y +
            userAccel.z * userAccel.z
        )

        // 3. 분산 계산을 위해 최근 샘플 버퍼에 추가
        accelMagnitudes.append(accelMagnitude)
        if accelMagnitudes.count > accelWindowSize {
            accelMagnitudes.removeFirst()
        }
        let variance = computeVariance(accelMagnitudes)

        // 4. 상태 판정
        let (newStatus, newIssue) = evaluate(
            pitch: pitchDeg,
            roll: rollDeg,
            accelVariance: variance
        )

        // 5. Published 갱신
        self.pitch = pitchDeg
        self.roll = rollDeg
        self.yaw = yawDeg
        self.accelerationVariance = variance
        self.status = newStatus
        self.issue = newIssue
    }

    // MARK: - Status Evaluation

    /// 임계값에 따라 상태 판정
    /// 우선순위: 흔들림(shaking) → pitch 위험 → roll 위험 → pitch 경고 → roll 경고 → 정상
    private func evaluate(
        pitch: Double,
        roll: Double,
        accelVariance: Double
    ) -> (OrientationStatus, OrientationIssue) {

        // 1. 가장 위험: 심한 흔들림
        if accelVariance > accelVarianceDanger {
            return (.dangerous, .shaking)
        }

        // 2. Pitch 위험 범위
        if pitch < pitchDangerLow {
            return (.dangerous, .pitchTooLow)
        }
        if pitch > pitchDangerHigh {
            return (.dangerous, .pitchTooHigh)
        }

        // 3. Roll 위험 범위
        if abs(roll) > rollDangerAbs {
            return (.dangerous, .rollTilted)
        }

        // 4. 가속도 경고 범위
        if accelVariance > accelVarianceWarning {
            return (.warning, .shaking)
        }

        // 5. Pitch 경고 범위 (정상 범위 밖)
        if !pitchNormalRange.contains(pitch) {
            let issue: OrientationIssue = (pitch < pitchNormalRange.lowerBound)
                ? .pitchTooLow : .pitchTooHigh
            return (.warning, issue)
        }

        // 6. Roll 경고 범위 (정상 범위 밖)
        if !rollNormalRange.contains(roll) {
            return (.warning, .rollTilted)
        }

        // 7. 모두 정상
        return (.normal, .none)
    }

    /// 표본 분산 계산 (Welford 알고리즘 안 쓰고 단순 방식 — 윈도우 작아서 충분)
    private func computeVariance(_ samples: [Double]) -> Double {
        guard samples.count >= 2 else { return 0 }
        let mean = samples.reduce(0, +) / Double(samples.count)
        let squaredDiffs = samples.map { ($0 - mean) * ($0 - mean) }
        return squaredDiffs.reduce(0, +) / Double(samples.count)
    }
}
