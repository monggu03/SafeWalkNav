//
//  ContentView.swift
//  iosApp
//
//  카메라 단일 기능 메인 화면 (OKO 식).
//  앱 진입(안전 고지 통과) 즉시 후방 카메라 + 신호등 인식이 켜지고,
//  신호 색을 전체화면 색 오버레이 + 큰 글씨 + 음성/햅틱으로 알린다.
//
//  판정·발화·햅틱은 TrafficLightDetector 안의 shared SignalDecisionEngine 이 담당한다
//  (Android 와 동일 로직). 이 화면은 detector 가 publish 하는 값을 관찰해 얹기만 한다.
//
//  ⚠️ Swift 빌드는 Mac 에서 검증할 것.
//

import SwiftUI

struct ContentView: View {
    @EnvironmentObject var deps: AppDependencies

    var body: some View {
        SignalScreen(detector: deps.trafficLightDetector)
    }
}

/// 카메라 프리뷰 + 신호 색 오버레이 + 큰 상태 글씨. detector 의 @Published 변화를 관찰한다.
private struct SignalScreen: View {
    @ObservedObject var detector: TrafficLightDetector

    var body: some View {
        ZStack {
            // 1) 후방 카메라 프리뷰
            CameraPreview(session: detector.captureSession)
                .ignoresSafeArea()

            // 2) 신호 색 전체화면 오버레이 (반투명 — 카메라가 비쳐 보임)
            overlayColor
                .opacity(overlayColor == .clear ? 0.0 : 0.4)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            // 3) 큰 상태 글씨 (초록불/빨간불/안내). 시각장애인은 음성으로 듣고,
            //    저시력자/보호자는 색·글씨로 확인.
            VStack {
                Spacer()
                Text(detector.statusText)
                    .accessibleText(.signal)
                    .foregroundColor(.white)
                    .shadow(color: .black, radius: 12)
                    .padding(24)
                Spacer()
            }
        }
        // start/stop 은 NavigationCoordinator 가 제어(.crossing 진입/이탈).
        // 화면이 사라지면 안전하게 정지만 보장한다.
        .onDisappear { detector.stopDetection() }
        .accessibleFloor()
    }

    /// signalColor 가 초록/빨강이면 그 색으로 화면을 물들이고, 그 외(회색=신호 없음)면 투명.
    /// SwiftUI Color 는 enum 이 아니므로 switch 가 아니라 == 로 비교한다.
    private var overlayColor: Color {
        if detector.signalColor == .green { return .green }
        if detector.signalColor == .red { return .red }
        return .clear
    }
}
