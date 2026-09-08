//
//  ContentView.swift
//  iosApp
//
//  동의 후 메인 화면 — 하단 2탭 구조(§3).
//   - Tab 0 "내비게이션": phase 에 따른 목적지 입력 / 안내 / 도착 화면(NavigationRootView)
//   - Tab 1 "신호등":     SignalScreen (카메라 + 신호 색 오버레이)
//
//  카메라 화면 표시 여부는 NavigationCoordinator.selectedTab 하나로 일원화한다(§3-2).
//  자동 전환(횡단보도 zone)과 사용자 수동 조작의 우선순위는 §4 규칙을 따른다.
//
//  판정·발화·햅틱은 TrafficLightDetector 안의 shared SignalDecisionEngine 이 담당한다
//  (Android 와 동일 로직). 이 화면은 detector 가 publish 하는 값을 관찰해 얹기만 한다.
//
//  ⚠️ Swift 빌드는 Mac 에서 검증할 것.
//

import SwiftUI

// MARK: - 메인 2탭 컨테이너

/// 동의 후 진입하는 하단 2탭 컨테이너(§3-3).
/// 탭 상태는 NavigationCoordinator 가 소유하고, 여기서는 관찰·조작만 한다.
struct MainTabView: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var coordinator: NavigationCoordinator

    /// 사용자 조작을 가로채기 위한 커스텀 Binding.
    /// get 은 coordinator.selectedTab 를 읽고, set(사용자 탭 조작)은 selectTabByUser 로 흘린다.
    /// 자동 전환은 selectedTab 을 직접 대입하므로 이 set 을 거치지 않는다 → userOverride 비대칭(§4).
    private var tabBinding: Binding<Int> {
        Binding(
            get: { coordinator.selectedTab },
            set: { coordinator.selectTabByUser($0) }
        )
    }

    var body: some View {
        TabView(selection: tabBinding) {
            NavigationRootView()
                .tabItem {
                    Label("내비게이션", systemImage: "map.fill")
                        .accessibilityLabel("내비게이션")
                        .accessibilityHint("경로 안내 화면으로 이동합니다.")
                }
                .tag(0)

            // §5 — detector lifecycle 은 오직 이 탭의 onAppear/onDisappear 단 한 곳에 연동한다.
            SignalScreen(detector: deps.trafficLightDetector)
                .onAppear  { deps.trafficLightDetector.startDetection() }
                .onDisappear { deps.trafficLightDetector.stopDetection() }
                .tabItem {
                    Label("신호등", systemImage: "eye.fill")
                        .accessibilityLabel("신호등 확인")
                        .accessibilityHint("신호등 카메라 화면으로 이동합니다.")
                }
                .tag(1)
        }
    }
}

// MARK: - 탭 0 내용 (내비게이션 phase 라우팅)

/// 탭 0 "내비게이션" 내용. phase 에 따라 목적지 입력 / 안내 / 도착 화면을 렌더한다.
/// (.safetyNotice 는 탭 밖 AppRootView 가 처리하므로 여기 도달하지 않는다.)
struct NavigationRootView: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var coordinator: NavigationCoordinator

    var body: some View {
        switch coordinator.phase {
        case .destinationInput:
            // §4-1 목적지 음성 입력.
            DestinationInputScreen(deps: deps, coordinator: coordinator)
        case .guiding:
            // §4-2 경로 안내 화면(§4-3에서 남은거리 갱신).
            GuidingView()
        case .arrived:
            PhasePlaceholderView(title: "도착", onReset: { coordinator.reset() })
        case .safetyNotice:
            // 도달 불가(안전 고지는 탭 밖에서 처리). 방어적 빈 배경.
            Color.black.ignoresSafeArea().accessibleFloor()
        }
    }
}

// MARK: - 신호등 화면

/// 카메라 프리뷰 + 신호 색 오버레이 + 큰 상태 글씨. detector 의 @Published 변화를 관찰한다.
/// start/stop 은 MainTabView 의 탭 1 onAppear/onDisappear 가 소유한다(§5) — 이 뷰는 표시만 담당.
struct SignalScreen: View {
    @ObservedObject var detector: TrafficLightDetector

    var body: some View {
        ZStack {
            // 1) 후방 카메라 프리뷰
            CameraPreview(session: detector.captureSession)
                .ignoresSafeArea()
                .accessibilityHidden(true)

            // 2) 신호 색 전체화면 오버레이 (반투명 — 카메라가 비쳐 보임)
            overlayColor
                .opacity(overlayColor == .clear ? 0.0 : 0.4)
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)

            // 3) 큰 상태 글씨 (초록불/빨간불/안내). 시각장애인은 음성으로 듣고,
            //    저시력자/보호자는 색·글씨로 확인.
            VStack {
                Spacer()
                Text(detector.statusText)
                    .accessibleText(.signal)
                    .foregroundColor(.white)
                    .shadow(color: .black, radius: 12)
                    .padding(24)
                    // 라벨/값 분리 — 포커스 시 현재 신호 상태가 읽힌다. 자동 알림은 TTS 담당.
                    .accessibilityLabel("신호등 상태")
                    .accessibilityValue(detector.statusText)
                    .accessibilityIdentifier("signal.status")
                Spacer()
            }
        }
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
