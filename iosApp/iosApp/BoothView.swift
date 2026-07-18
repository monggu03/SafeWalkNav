//
//  BoothView.swift
//  iosApp
//
//  부스/행사 데모 화면 (인터뷰·데모 전용, 상용 아님).
//  안드로이드의 부스 모드와 동형:
//    - GPS·네비 없이 카메라 + AI(신호등 인식)만 돌린다.
//    - 신호등을 비추면 전체화면 색(빨강/초록) + 큰 글씨 + 음성·햅틱.
//  판정·발화·햅틱은 TrafficLightDetector 안의 공유 SignalDecisionEngine 이 담당하고,
//  이 화면은 detector 가 publish 하는 signalColor / statusText 를 관찰해 얹기만 한다.
//
//  ⚠️ Mac 빌드 전 작성. 검증은 Mac(친구) 확보 후 iOS 빌드에서.
//

import SwiftUI

struct BoothView: View {

    /// AppDependencies 의 단일 detector 인스턴스를 관찰한다.
    @ObservedObject var detector: TrafficLightDetector

    /// 데모 종료 콜백 (부모가 fullScreenCover 를 닫음). 버전 무관하게 동작.
    let onExit: () -> Void

    var body: some View {
        ZStack {
            // 1) 카메라 프리뷰 (실제 신호등을 비추는 것을 관람객이 봄)
            CameraPreview(session: detector.captureSession)
                .ignoresSafeArea()

            // 2) 신호 색 전체화면 오버레이 (반투명 — 카메라가 비쳐 보임)
            overlayColor
                .opacity(overlayColor == .clear ? 0.0 : 0.4)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            // 3) 큰 상태 글씨 + 종료 버튼
            VStack {
                HStack {
                    Spacer()
                    Button(action: onExit) {
                        Text("데모 종료")
                            .font(.headline)
                            .foregroundColor(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(Color.black.opacity(0.55))
                            .cornerRadius(10)
                    }
                    .accessibilityLabel("부스 데모 종료")
                }
                .padding(16)

                Spacer()

                Text(detector.statusText)
                    .font(.system(size: 46, weight: .bold))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .shadow(color: .black, radius: 12)
                    .padding(24)

                Spacer()
            }
        }
        .onAppear { detector.startDetection() }
        .onDisappear { detector.stopDetection() }
    }

    /// signalColor 가 회색(신호 없음)이면 투명, 아니면 그 색으로 화면을 물들인다.
    private var overlayColor: Color {
        switch detector.signalColor {
        case .green: return .green
        case .red: return .red
        default: return .clear
        }
    }
}
