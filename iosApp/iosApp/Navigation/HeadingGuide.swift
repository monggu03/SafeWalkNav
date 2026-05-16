//
//  HeadingGuide.swift
//  iosApp
//
//  초기 방향 안내 — 사용자가 출발하기 전, 어느 방향을 바라봐야 하는지
//  음성으로 안내한다. v1 에서는 IMU 단독 (자세 + true heading) 으로만 동작.
//
//  사용 흐름:
//    1. start(currentLocation:firstWaypoint:) 호출
//    2. CMDeviceMotion 으로 자세 감시 — 평평하지 않으면 자세 안내 발화
//    3. 평평한 자세가 잡히면 CLHeading.trueHeading 으로 방향 안내 발화
//    4. 정면 일치 (tolerance 이내) 가 되면 state = .ready
//    5. stop() 으로 종료
//

import Foundation
import CoreLocation
import CoreMotion
import Combine
import shared

/// 초기 방향 안내의 진행 상태.
enum HeadingGuideState {
    /// 자세가 평평하지 않아 측정 보류
    case waitingForFlatPose
    /// 자세 OK — 사용자가 정면을 잡을 때까지 대기
    case waitingForHeadingMatch
    /// 정면 일치, 출발 가능
    case ready
}

@MainActor
final class HeadingGuide: NSObject, ObservableObject {

    // MARK: - Public State

    @Published private(set) var state: HeadingGuideState = .waitingForFlatPose
    @Published private(set) var currentMessage: String = ""
    /// 디버깅/UI 용 — 현재 trueHeading (-1 이면 미수신/보정 필요)
    @Published private(set) var currentHeading: Double = -1
    /// 목표 bearing (현재 위치 → 첫 waypoint).
    @Published private(set) var targetBearing: Double = 0

    // MARK: - Dependencies (injected)

    private let tts: TtsManager
    private let config: NavigatorConfig

    // MARK: - Private Properties

    private let locationManager = CLLocationManager()
    private let motionManager = CMMotionManager()

    /// 동일 메시지 연속 발화 방지.
    private var lastSpokenMessage: String = ""

    /// CLLocationManagerDelegate 를 NSObject 한 클래스에 두기 위한 헬퍼 컨테이너.
    /// 메인 클래스가 @MainActor 라 delegate 메서드를 직접 못 받기 때문에 분리.
    private var headingDelegate: HeadingDelegate?

    // MARK: - Init

    init(tts: TtsManager, config: NavigatorConfig = NavigatorConfig.companion.defaults()) {
        self.tts = tts
        self.config = config
        super.init()
    }

    // MARK: - Public API

    /// 방향 안내 시작.
    ///
    /// - Parameters:
    ///   - currentLocation: 사용자의 현재 위치 (KMM GpsLocation)
    ///   - firstWaypoint: 따라갈 경로의 첫 waypoint (이 방향이 목표)
    func start(currentLocation: GpsLocation, firstWaypoint: Waypoint) {
        // 1. 목표 bearing 계산 (KMM 의 top-level bearing 함수 사용)
        let bearing = BearingMathKt.bearing(
            lat1: currentLocation.latitude,
            lon1: currentLocation.longitude,
            lat2: firstWaypoint.lat,
            lon2: firstWaypoint.lon,
        )
        self.targetBearing = Double(bearing)
        self.state = .waitingForFlatPose
        self.lastSpokenMessage = ""
        self.currentHeading = -1

        // 2. heading 시작 — trueHeading 이 필요하므로 GPS 도 같이 켜져 있어야 함.
        let delegate = HeadingDelegate { [weak self] heading in
            Task { @MainActor in
                self?.handleHeadingUpdate(trueHeading: heading)
            }
        }
        self.headingDelegate = delegate
        locationManager.delegate = delegate
        locationManager.headingFilter = 1.0
        if CLLocationManager.headingAvailable() {
            locationManager.startUpdatingHeading()
        } else {
            print("[HeadingGuide] 이 기기는 나침반을 지원하지 않음")
        }

        // 3. CMDeviceMotion 시작 — gravity 벡터로 자세 판단.
        motionManager.deviceMotionUpdateInterval = 0.1
        motionManager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            Task { @MainActor in
                self?.handleMotion(motion)
            }
        }
    }

    /// 안내 중단 — heading/motion 업데이트 모두 끔.
    func stop() {
        locationManager.stopUpdatingHeading()
        motionManager.stopDeviceMotionUpdates()
        headingDelegate = nil
    }

    // MARK: - Internal Handlers

    /// CMDeviceMotion 업데이트 — gravity 벡터로 평평한 자세 판단.
    private func handleMotion(_ motion: CMDeviceMotion?) {
        guard let g = motion?.gravity else { return }
        // 화면이 하늘을 향한 평평 자세 → gravity = (0, 0, -1) 근처
        let zOk = abs(g.z + 1.0) < config.flatPoseGravityZTolerance
        let xyOk = abs(g.x) < config.flatPoseGravityXYTolerance
            && abs(g.y) < config.flatPoseGravityXYTolerance
        let isFlat = zOk && xyOk

        if !isFlat {
            // 자세가 무너졌으면 ready 였더라도 다시 자세 안내로 돌아간다.
            state = .waitingForFlatPose
            announce(MessageBuilder.shared.buildFlatPosePromptMessage())
        } else if state == .waitingForFlatPose {
            // 자세 OK — heading 안내 단계로 전환. 다음 heading 업데이트가 메시지 발화.
            state = .waitingForHeadingMatch
        }
    }

    /// trueHeading 업데이트 — 자세가 OK 일 때만 안내.
    private func handleHeadingUpdate(trueHeading: Double) {
        currentHeading = trueHeading
        // 자세 미충족이면 heading 안내 자체를 보류 (혼란 방지).
        guard state != .waitingForFlatPose else { return }
        // 보정 필요 (-1) 이면 다음 업데이트 대기.
        guard trueHeading >= 0 else { return }

        var diff = targetBearing - trueHeading
        if diff > 180 { diff -= 360 }
        if diff < -180 { diff += 360 }

        let msg = MessageBuilder.shared.buildInitialHeadingMessage(
            diffDeg: diff,
            tolerance: config.initialHeadingToleranceDeg,
        )
        if abs(diff) < config.initialHeadingToleranceDeg {
            state = .ready
        } else {
            state = .waitingForHeadingMatch
        }
        announce(msg)
    }

    /// 안내 메시지를 TTS 로 발화. 동일 메시지 연속 발화는 무시.
    private func announce(_ msg: String) {
        guard !msg.isEmpty, msg != lastSpokenMessage else { return }
        lastSpokenMessage = msg
        currentMessage = msg
        tts.speak(msg)
    }
}

// MARK: - CLLocationManagerDelegate Container

/// CLLocationManagerDelegate 를 별도 NSObject 로 분리.
/// HeadingGuide 가 @MainActor 클래스라 delegate 콜백을 직접 받으면 컴파일 오류.
private final class HeadingDelegate: NSObject, CLLocationManagerDelegate {
    private let onHeading: (Double) -> Void

    init(onHeading: @escaping (Double) -> Void) {
        self.onHeading = onHeading
    }

    func locationManager(_ manager: CLLocationManager,
                         didUpdateHeading newHeading: CLHeading) {
        // trueHeading 사용 — 진북 기준. magnetic 은 자북 보정이 필요해 부정확.
        // -1 이면 보정 필요 — 호출자가 처리.
        onHeading(newHeading.trueHeading)
    }
}
