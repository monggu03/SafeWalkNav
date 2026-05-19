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

    /// 출발 시 사용자 위치 — 이 점에서 멀어진 거리로 "움직였다" 를 판단.
    private var startLat: Double = 0
    private var startLon: Double = 0

    /// GPS 움직임 감지 시 호출. nil 이면 자동 종료 안 함 (기존 동작).
    private var movementCallback: (() -> Void)?

    /// 외부에서 주입된 GPS 위치 publisher 구독.
    private var locationCancellable: AnyCancellable?

    /// 움직임 판정 임계값.
    /// - speed: 0.5 m/s 이상이면 분명한 보행 → 즉시 종료.
    /// - distance: 시작 지점에서 3m 이상 멀어지면 종료. (속도 미보고 기기 대비 안전망)
    private let movementSpeedThreshold: Double = 0.5
    private let movementDistanceThreshold: Double = 3.0

    /// 이미 움직임 감지로 stop() 한 뒤 추가 GPS 가 들어와도 callback 중복 호출 안 하기 위함.
    private var didDetectMovement: Bool = false

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
    ///   - currentLocation: 사용자의 현재 위치 (KMM GpsLocation). 움직임 감지의 기준점도 됨.
    ///   - firstWaypoint: 따라갈 경로의 첫 waypoint (이 방향이 목표)
    ///   - locationPublisher: GPS 업데이트 publisher (optional). 주어지면 이 publisher 가 보내는
    ///     위치를 모니터링해 사용자가 실제로 걷기 시작하면 자동으로 stop() + onMovementDetected 호출.
    ///   - onMovementDetected: GPS 움직임 감지 시 호출 (optional). 호출자가 안내 화면을 dismiss 할 때 사용.
    func start(
        currentLocation: GpsLocation,
        firstWaypoint: Waypoint,
        locationPublisher: AnyPublisher<GpsLocation?, Never>? = nil,
        onMovementDetected: (() -> Void)? = nil,
    ) {
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

        // 움직임 감지 초기화.
        self.startLat = currentLocation.latitude
        self.startLon = currentLocation.longitude
        self.movementCallback = onMovementDetected
        self.didDetectMovement = false

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

        // 4. GPS 위치 업데이트 구독 (움직임 자동 감지).
        if let pub = locationPublisher {
            locationCancellable = pub
                .compactMap { $0 }
                .sink { [weak self] loc in
                    Task { @MainActor in
                        self?.checkMovement(loc)
                    }
                }
        }
    }

    /// 안내 중단 — heading/motion 업데이트 모두 끔.
    func stop() {
        locationManager.stopUpdatingHeading()
        motionManager.stopDeviceMotionUpdates()
        headingDelegate = nil
        locationCancellable?.cancel()
        locationCancellable = nil
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

    /// 새 GPS 위치가 들어왔을 때 움직임 여부 판정. 임계 초과 시 stop + callback.
    private func checkMovement(_ loc: GpsLocation) {
        guard !didDetectMovement else { return }

        // 속도 기반 (m/s) — 0.5 이상이면 분명한 보행. iOS 시뮬레이터 GPX 재생도 speed 가 채워짐.
        let speed = Double(loc.speed)
        let movedBySpeed = speed >= movementSpeedThreshold

        // 거리 기반 — 시작 지점에서 멀어진 정도.
        let distM = BearingMathKt.distanceBetween(
            lat1: startLat, lon1: startLon,
            lat2: loc.latitude, lon2: loc.longitude,
        )
        let movedByDistance = Double(distM) >= movementDistanceThreshold

        guard movedBySpeed || movedByDistance else { return }

        didDetectMovement = true
        print("[HeadingGuide] 움직임 감지 — speed=\(speed) m/s, distFromStart=\(distM) m → 종료")
        stop()
        movementCallback?()
        movementCallback = nil
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
