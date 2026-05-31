//
//  GyroHeadingFeeder.swift
//  iosApp
//
//  보행 중 자이로(각속도)를 NavigationManager 로 흘려보내, GPS bearing 과 상보 필터로 융합되게 한다.
//  굽은 길 진입을 GPS 500ms 보다 빠르게 감지해 가상 waypoint 통과(스테레오 비프) 타이밍을 개선하는 용도.
//
//  Android 대응: MainActivity.handleGyro (TYPE_GYROSCOPE + TYPE_GRAVITY).
//  공통 로직: shared/commonMain ComplementaryHeading + NavigationManager.updateGyro.
//

import Foundation
import CoreMotion
import shared

/// CMDeviceMotion 의 rotationRate(자이로) + gravity 로 월드 yaw 각속도를 만들어
/// NavigationManager.updateGyro 로 전달하는 피더.
///
/// HeadingGuide(출발 전 방향 안내)와 달리, 이쪽은 **보행 중 내내** 동작한다.
/// NavigationViewModel 이 안내 시작/종료에 맞춰 start()/stop() 한다.
final class GyroHeadingFeeder {

    private let navigationManager: NavigationManager
    private let motionManager = CMMotionManager()

    init(navigationManager: NavigationManager) {
        self.navigationManager = navigationManager
    }

    /// 자이로 구독 시작. 이미 동작 중이면 무시.
    func start() {
        guard motionManager.isDeviceMotionAvailable else {
            print("[GyroHeadingFeeder] deviceMotion 미지원 기기 — 자이로 융합 비활성")
            return
        }
        guard !motionManager.isDeviceMotionActive else { return }

        // 50Hz — 자이로의 빠른 응답이 핵심. Android SENSOR_DELAY_GAME 와 동급.
        motionManager.deviceMotionUpdateInterval = 1.0 / 50.0
        motionManager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            guard let self, let motion else { return }
            self.handleMotion(motion)
        }
    }

    /// 자이로 구독 종료.
    func stop() {
        if motionManager.isDeviceMotionActive {
            motionManager.stopDeviceMotionUpdates()
        }
    }

    /// CMDeviceMotion → 월드 yaw 각속도(deg/s, 시계 방향 +) → updateGyro.
    ///
    /// 변환:
    ///   - iOS gravity 는 "아래" 방향 단위벡터(face-up 시 ≈(0,0,-1)). worldUp = -gravity.
    ///   - rotationRate(rad/s, 디바이스 프레임)를 worldUp 에 정사영하면 up 축 각속도(CCW-from-above +).
    ///   - bearing 은 시계 방향이 +(오른쪽)이라 부호 반전 → (rot · gravity).
    ///   - 자세와 무관하게 좌우 회전 속도를 주므로 자력계 불필요(자기 간섭 면역).
    ///
    /// 부호가 기기/축 정의로 뒤집혀도, 곡선 통과는 방향이 안 맞으면 GPS 판정으로 안전 폴백(오발화 없음).
    private func handleMotion(_ motion: CMDeviceMotion) {
        let r = motion.rotationRate          // rad/s, 디바이스 프레임
        let g = motion.gravity               // 단위벡터, 아래 방향

        // upRate(CCW+) = r · worldUp = r · (-g) = -(r·g). yawForBearing(시계+) = -(upRate) = (r·g).
        let dot = r.x * g.x + r.y * g.y + r.z * g.z
        let yawRateDegPerSec = Float(dot * 180.0 / Double.pi)
        let tsMillis = Int64(motion.timestamp * 1000.0)  // 초 → ms (dt 계산용)

        navigationManager.updateGyro(yawRateDegPerSec: yawRateDegPerSec, timestampMillis: tsMillis)
    }
}
