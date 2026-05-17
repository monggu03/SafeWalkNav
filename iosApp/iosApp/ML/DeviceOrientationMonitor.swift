//
//  DeviceOrientationMonitor.swift
//  iosApp
//
//  IMU 센서 기반 폰 자세(pitch/roll/yaw) 모니터링
//  - chest-mount 카메라가 정상 자세인지 감시
//  - 비정상 자세 시 사용자에게 음성으로 보정 요청
//
//  ⭐ 좌표계 보정: 실측 결과 정상 자세에서 rawPitch ≈ 86°
//     → pitchDeg = rawPitch - 86 으로 변환하여
//     정상 자세 = 0°, 위로 기울이면 음수, 아래로 숙이면 양수
//
/*
import Foundation
import CoreMotion
import Combine

/// 폰 자세 상태
enum OrientationStatus: String {
    case normal      // 정상 — 안내 불필요
    case warning     // 경고 — 부드러운 안내
    case dangerous   // 위험 — 즉시 보정 요청
}

/// 어떤 종류의 자세 문제인지 (TTS 메시지 결정용)
enum OrientationIssue: Equatable {
    case none
    case pitchTooLow       // 카메라가 너무 위 (폰을 너무 들어올림)
    case pitchTooHigh      // 카메라가 너무 아래 (폰을 너무 숙임)
    case rollTilted        // 폰이 좌우로 기울어짐
    case shaking           // 흔들림

    var ttsMessage: String? {
        switch self {
        case .none:           return nil
        case .pitchTooLow:    return "휴대폰을 살짝 아래로 내려주세요"
        case .pitchTooHigh:   return "휴대폰을 가슴 높이까지 들어주세요"
        case .rollTilted:     return "휴대폰을 바르게 세워주세요"
        case .shaking:        return "잠시 멈춰 휴대폰을 안정적으로 들어주세요"
        }
    }
}

@MainActor
final class DeviceOrientationMonitor: ObservableObject {

    // MARK: - Thresholds (보정된 pitch 기준, 정상 자세 = 0°)
    /// Pitch 정상 범위: 정상 자세에서 위/아래 ±15° 이내면 정상
    private let pitchNormalRange: ClosedRange<Double> = -15.0 ... 15.0
    /// Pitch 위험: ±30° 초과
    private let pitchDangerAbs: Double = 30.0

    /// Roll 정상 범위
    private let rollNormalRange: ClosedRange<Double> = -10.0 ... 10.0
    private let rollDangerAbs: Double = 20.0

    /// 가속도 분산 임계값
    private let accelVarianceWarning: Double = 2.0
    private let accelVarianceDanger: Double = 5.0
    private let accelWindowSize: Int = 20

    /// ⭐ chest-mount 기준 자세 보정 오프셋 (실측: 정상 자세에서 rawPitch ≈ 86°)
    private let pitchOffset: Double = 86.0

    // MARK: - Published (보정된 값)
    @Published private(set) var pitch: Double = 0
    @Published private(set) var roll: Double = 0
    @Published private(set) var yaw: Double = 0

    // MARK: - Published (raw 값, 디버깅용 — 그대로 유지)
    @Published private(set) var rawPitch: Double = 0
    @Published private(set) var rawRoll: Double = 0
    @Published private(set) var rawYaw: Double = 0

    // MARK: - Published (중력 벡터, 디버깅용)
    @Published private(set) var gravityX: Double = 0
    @Published private(set) var gravityY: Double = 0
    @Published private(set) var gravityZ: Double = 0

    @Published private(set) var accelerationVariance: Double = 0
    @Published private(set) var status: OrientationStatus = .normal
    @Published private(set) var issue: OrientationIssue = .none
    @Published private(set) var isMonitoring: Bool = false

    // MARK: - Private
    private let manager = CMMotionManager()
    private var accelMagnitudes: [Double] = []

    // MARK: - Lifecycle

    /// CoreMotion의 Device Motion 스트림(0.1초 간격) 구독을 시작한다.
    /// 이미 모니터링 중이거나 디바이스가 지원하지 않으면 조용히 무시.
    func start() {
        guard manager.isDeviceMotionAvailable else {
            print("[OrientationMonitor] Device Motion 사용 불가")
            return
        }
        guard !isMonitoring else { return }

        manager.deviceMotionUpdateInterval = 0.1

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

    /// 모니터링을 중단하고 가속도 윈도우 버퍼를 비운다.
    func stop() {
        guard isMonitoring else { return }
        manager.stopDeviceMotionUpdates()
        isMonitoring = false
        accelMagnitudes.removeAll()
        print("[OrientationMonitor] 모니터링 중단")
    }

    // MARK: - Motion Handling

    /// CMDeviceMotion 콜백마다 호출 — raw 각도를 °로 변환하고
    /// chest-mount 오프셋(86°)을 적용한 뒤, 가속도 분산으로 흔들림까지 평가해 Published에 반영한다.
    private func handleMotionUpdate(_ motion: CMDeviceMotion) {
        // 1. raw 값
        let rawPitchDeg = motion.attitude.pitch * 180.0 / .pi
        let rawRollDeg  = motion.attitude.roll  * 180.0 / .pi
        let rawYawDeg   = motion.attitude.yaw   * 180.0 / .pi

        // 2. ⭐ chest-mount 보정: 정상 자세 = 0°가 되도록 86° 빼기
        //    위로 기울이면 음수 (-), 아래로 숙이면 양수 (+)
        let pitchDeg = rawPitchDeg - pitchOffset
        let rollDeg  = rawRollDeg
        let yawDeg   = rawYawDeg

        // 3. 중력 벡터
        let gx = motion.gravity.x
        let gy = motion.gravity.y
        let gz = motion.gravity.z

        // 4. 사용자 가속도 (중력 제외) — 흔들림 측정용
        let userAccel = motion.userAcceleration
        let accelMagnitude = sqrt(
            userAccel.x * userAccel.x +
            userAccel.y * userAccel.y +
            userAccel.z * userAccel.z
        )

        accelMagnitudes.append(accelMagnitude)
        if accelMagnitudes.count > accelWindowSize {
            accelMagnitudes.removeFirst()
        }
        let variance = computeVariance(accelMagnitudes)

        // 5. 상태 판정 활성화
        let (newStatus, newIssue) = evaluate(
            pitch: pitchDeg,
            roll: rollDeg,
            accelVariance: variance
        )

        // 6. Published 갱신
        self.rawPitch = rawPitchDeg
        self.rawRoll = rawRollDeg
        self.rawYaw = rawYawDeg
        self.gravityX = gx
        self.gravityY = gy
        self.gravityZ = gz

        self.pitch = pitchDeg
        self.roll = rollDeg
        self.yaw = yawDeg
        self.accelerationVariance = variance
        self.status = newStatus
        self.issue = newIssue
    }

    // MARK: - Status Evaluation

    /// 임계값에 따라 상태 판정
    /// 우선순위: 흔들림 → pitch 위험 → roll 위험 → 가속도 경고 → pitch 경고 → roll 경고 → 정상
    private func evaluate(
        pitch: Double,
        roll: Double,
        accelVariance: Double
    ) -> (OrientationStatus, OrientationIssue) {

        // 1. 가장 위험: 심한 흔들림
        if accelVariance > accelVarianceDanger {
            return (.dangerous, .shaking)
        }

        // 2. Pitch 위험 범위 (보정된 값 기준 ±30°)
        if pitch < -pitchDangerAbs {
            // 음수 = 폰을 너무 위로 들어 카메라가 위 봄
            return (.dangerous, .pitchTooLow)
        }
        if pitch > pitchDangerAbs {
            // 양수 = 폰을 너무 숙여 카메라가 땅 봄
            return (.dangerous, .pitchTooHigh)
        }

        // 3. Roll 위험 범위
        if abs(roll) > rollDangerAbs {
            return (.dangerous, .rollTilted)
        }

        // 4. 가속도 경고
        if accelVariance > accelVarianceWarning {
            return (.warning, .shaking)
        }

        // 5. Pitch 경고 범위
        if !pitchNormalRange.contains(pitch) {
            let issue: OrientationIssue = (pitch < pitchNormalRange.lowerBound)
                ? .pitchTooLow : .pitchTooHigh
            return (.warning, issue)
        }

        // 6. Roll 경고 범위
        if !rollNormalRange.contains(roll) {
            return (.warning, .rollTilted)
        }

        // 7. 모두 정상
        return (.normal, .none)
    }

    /// 가속도 크기 샘플의 분산을 계산 — 흔들림 정도 정량화에 사용.
    /// 샘플이 2개 미만이면 0 반환.
    private func computeVariance(_ samples: [Double]) -> Double {
        guard samples.count >= 2 else { return 0 }
        let mean = samples.reduce(0, +) / Double(samples.count)
        let squaredDiffs = samples.map { ($0 - mean) * ($0 - mean) }
        return squaredDiffs.reduce(0, +) / Double(samples.count)
    }
}
*/
