//
//  ContentView.swift
//  iosApp
//
//  2026-06-15 — 기존 UI 전면 폐기 (서울임팩트 단계 UI 재설계).
//
//  이 파일은 앱이 빌드되도록 남겨둔 **빈 껍데기**입니다.
//  로직(카메라·ML·네비게이션·음성·센서)은 전부 살아 있으며,
//  아래 주입된 의존성으로 새 UI를 쌓아 올리면 됩니다.
//
//  살아있는 로직 진입점:
//    - deps.navigationViewModel : 안내 상태·경로·이벤트 (폴링 200ms)
//    - deps.trafficLightDetector: CoreML 신호등 인식 (detections / signalColor / statusText)
//    - deps.locationTracker     : GPS
//    - deps.headingProvider     : 나침반 (신호등 조준용)
//    - deps.tts / deps.stt      : 음성 출력 / 음성 인식
//    - CameraPreview(session:)  : 카메라 프리뷰 뷰 (재사용 가능, 보존됨)
//

import SwiftUI

struct ContentView: View {

    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var viewModel: NavigationViewModel

    /// 부스/행사 데모 화면 표시 여부 (인터뷰·데모 전용).
    @State private var showBooth = false

    var body: some View {
        VStack(spacing: 16) {
            Text("SafeWalk")
                .font(.largeTitle)
                .bold()

            Text("UI 재설계 중")
                .font(.headline)
                .foregroundStyle(.secondary)

            Text("로직은 모두 살아 있습니다.\n이 화면부터 새 UI를 구성하세요.")
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            // 부스/행사 데모 시작 버튼 (안드로이드 부스 모드와 동형).
            Button(action: { showBooth = true }) {
                Text("부스 데모 시작")
                    .font(.title2)
                    .bold()
                    .foregroundColor(.black)
                    .padding(.horizontal, 32)
                    .padding(.vertical, 16)
                    .background(Color(red: 1.0, green: 0.84, blue: 0.0))
                    .cornerRadius(12)
            }
            .padding(.top, 32)
            .accessibilityLabel("부스 데모 모드 시작. 신호등 인식을 바로 시작합니다.")
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .fullScreenCover(isPresented: $showBooth) {
            BoothView(detector: deps.trafficLightDetector, onExit: { showBooth = false })
        }
    }
}
