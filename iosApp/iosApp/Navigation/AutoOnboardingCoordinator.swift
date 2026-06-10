//
//  AutoOnboardingCoordinator.swift
//  iosApp
//
//  안내 시작 직후 자동 실행되는 온보딩 시퀀스.
//
//  흐름:
//    1. summary       — 경로 요약 발화 → summaryDelaySec 후 자동 진행
//    2. flatPose      — 평평 자세 대기 (CMDeviceMotion gravity). maxFlatPoseSec 초과 시 강제 진행.
//    3. rotating      — "천천히 도세요" + trueHeading 일치 진입 대기.
//                       maxRotatingSec 초과 시 maxRotationRetries 회까지 재안내, 그래도 안 잡히면 강제 finish.
//    4. confirming    — 일치 감지됨, trueHeading 이 confirmHoldSec 동안 허용오차 안에 머무는지 확인.
//                       중간에 벗어나면 rotating 으로 복귀(멈춤 멘트 재발화 X).
//    5. done          — "정면입니다. 직진하세요" 발화 후 onCompleted() 호출.
//
//  설계 v4 §6 참조. 두 폴백(평평 자세 / 회전) 모두 박혀 있다.
//

import Foundation
import Combine
import CoreLocation
import CoreMotion
import shared

enum OnboardingStage {
    case summary
    case flatPose
    case rotating
    case confirming
    case done
}

@MainActor
final class AutoOnboardingCoordinator: NSObject, ObservableObject {

    // MARK: - Published State
    @Published private(set) var stage: OnboardingStage = .summary

    // MARK: - Dependencies
    private let tts: TtsManager
    private let config: NavigatorConfig

    // MARK: - Sensor Managers
    // Coordinator 전용 CLLocationManager — HeadingProvider/LocationTracker 와 별개로
    // trueHeading 만 잠깐 받기 위해 사용한다(HeadingProvider 는 magneticHeading 기반).
    private let locationManager = CLLocationManager()
    private let motionManager = CMMotionManager()
    private var headingDelegate: HeadingDelegate?

    // MARK: - State
    private var targetBearing: Double = 0
    private var confirmStartTime: TimeInterval = 0
    private var rotatingStartTime: TimeInterval = 0
    private var flatPoseStartTime: TimeInterval = 0
    private var rotationRetries: Int = 0
    private var onCompleted: (() -> Void)?

    // MARK: - Config
    private let summaryDelaySec: Double = 4.0
    private let confirmHoldSec: Double = 1.0
    private let maxRotatingSec: Double = 15.0
    private let maxFlatPoseSec: Double = 15.0
    private let maxRotationRetries: Int = 1

    init(
        tts: TtsManager,
        config: NavigatorConfig = NavigatorConfig.companion.defaults()
    ) {
        self.tts = tts
        self.config = config
        super.init()
    }

    // MARK: - Public API

    func start(
        summary: String,
        currentLocation: GpsLocation,
        firstWaypoint: Waypoint,
        onCompleted: @escaping () -> Void
    ) {
        self.onCompleted = onCompleted
        self.rotationRetries = 0

        let b = BearingMathKt.bearing(
            lat1: currentLocation.latitude,
            lon1: currentLocation.longitude,
            lat2: firstWaypoint.lat,
            lon2: firstWaypoint.lon
        )
        self.targetBearing = Double(b)

        print("[Onboarding] start — targetBearing=\(b)")

        // Step 1: 요약
        stage = .summary
        if !summary.isEmpty {
            tts.speak(summary, priority: .high, display: true)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + summaryDelaySec) { [weak self] in
            Task { @MainActor in
                self?.startFlatPoseStage()
            }
        }
    }

    func stop() {
        locationManager.stopUpdatingHeading()
        motionManager.stopDeviceMotionUpdates()
        headingDelegate = nil
    }

    // MARK: - Stage 2: 평평 자세 대기

    private func startFlatPoseStage() {
        guard stage == .summary else { return }
        stage = .flatPose
        flatPoseStartTime = Date().timeIntervalSince1970
        tts.speak("스마트폰을 평평하게 들어주세요.", priority: .high, display: true)

        guard motionManager.isDeviceMotionAvailable else {
            // 모션 센서 없으면 자세 감지 생략하고 다음 단계로
            print("[Onboarding] DeviceMotion 없음 — 자세 단계 건너뜀")
            startRotatingStage()
            return
        }

        motionManager.deviceMotionUpdateInterval = 0.1
        motionManager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            Task { @MainActor in
                self?.handleMotion(motion)
            }
        }
    }

    private func handleMotion(_ motion: CMDeviceMotion?) {
        guard stage == .flatPose else { return }

        // 폴백 — 15초 안에 평평 자세 못 잡으면 안내 후 강제 다음 단계.
        let elapsed = Date().timeIntervalSince1970 - flatPoseStartTime
        if elapsed > maxFlatPoseSec {
            motionManager.stopDeviceMotionUpdates()
            tts.speak("그대로 진행합니다.", priority: .high, display: true)
            startRotatingStage()
            return
        }

        guard let g = motion?.gravity else { return }

        let zOk = abs(g.z + 1.0) < config.flatPoseGravityZTolerance
        let xyOk = abs(g.x) < config.flatPoseGravityXYTolerance
            && abs(g.y) < config.flatPoseGravityXYTolerance

        if zOk && xyOk {
            // 자세 OK — 이후 폰 자세는 무관.
            motionManager.stopDeviceMotionUpdates()
            startRotatingStage()
        }
    }

    // MARK: - Stage 3/4: 회전 + 일치 감지

    private func startRotatingStage() {
        stage = .rotating
        rotatingStartTime = Date().timeIntervalSince1970
        tts.speak("천천히 한 바퀴 도세요.", priority: .high, display: true)

        guard CLLocationManager.headingAvailable() else {
            // 나침반 없으면 정면 판정 불가 — 안내 후 그대로 출발
            print("[Onboarding] heading 사용 불가 — 정면 확인 생략하고 출발")
            tts.speak("출발합니다.", priority: .high, display: true)
            finish()
            return
        }

        let delegate = HeadingDelegate { [weak self] heading in
            Task { @MainActor in
                self?.handleHeadingUpdate(trueHeading: heading)
            }
        }
        self.headingDelegate = delegate
        locationManager.delegate = delegate
        locationManager.headingFilter = 1.0
        locationManager.startUpdatingHeading()
    }

    private func handleHeadingUpdate(trueHeading: Double) {
        // trueHeading < 0 → 나침반 보정 필요. 보정될 때까지 무시.
        guard trueHeading >= 0 else { return }

        var diff = targetBearing - trueHeading
        if diff > 180 { diff -= 360 }
        if diff < -180 { diff += 360 }
        let inTolerance = abs(diff) < config.initialHeadingToleranceDeg

        switch stage {
        case .rotating:
            // 최대 회전 시간 초과
            let elapsed = Date().timeIntervalSince1970 - rotatingStartTime
            if elapsed > maxRotatingSec {
                if rotationRetries < maxRotationRetries {
                    rotationRetries += 1
                    rotatingStartTime = Date().timeIntervalSince1970
                    tts.speak("다시 한번 천천히 도세요.", priority: .high, display: true)
                } else {
                    // 재시도까지 실패 — 강제 finish
                    tts.speak("정면을 잡지 못했습니다. 그대로 출발합니다.", priority: .high, display: true)
                    finish()
                }
                return
            }

            if inTolerance {
                stage = .confirming
                confirmStartTime = Date().timeIntervalSince1970
                tts.speak("방향이 맞습니다. 멈춰주세요.", priority: .high, display: true)
            }

        case .confirming:
            if !inTolerance {
                // 1초 유지 실패 — rotating 복귀 (멈춤 멘트는 재발화 X)
                stage = .rotating
                rotatingStartTime = Date().timeIntervalSince1970
                return
            }

            let held = Date().timeIntervalSince1970 - confirmStartTime
            if held >= confirmHoldSec {
                tts.speak("정면입니다. 직진하세요.", priority: .high, display: true)
                finish()
            }

        default:
            break
        }
    }

    private func finish() {
        stop()
        stage = .done
        onCompleted?()
        onCompleted = nil
    }
}

// MARK: - HeadingDelegate Container
// CLLocationManagerDelegate 콜백은 NSObject 가 받아야 하므로 별도 컨테이너.
private final class HeadingDelegate: NSObject, CLLocationManagerDelegate {
    private let onHeading: (Double) -> Void
    init(onHeading: @escaping (Double) -> Void) {
        self.onHeading = onHeading
    }
    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        onHeading(newHeading.trueHeading)
    }
}
