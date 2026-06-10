//
//  ContentView.swift
//  iosApp
//
//  메인 화면 — 탭 기반 통합 뷰 (Android 정합)
//  Tab 1: 내비게이션 — 풀스크린 상태 화면(IDLE / RESULTS / NAVIGATING)
//  Tab 2: 신호등 감지 (카메라 + YOLO)
//
//  횡단보도 진입 시 신호등 탭으로 자동 전환(기존 로직 유지).
//

import SwiftUI
import shared
import CoreLocation
import Vision

// MARK: - 메인 탭 뷰
struct ContentView: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var navVM: NavigationViewModel

    /// 현재 선택된 탭 (0=내비, 1=신호등)
    @State private var selectedTab: Int = 0

    /// 횡단보도 자동 전환 직전의 탭 (빠져나올 때 복귀용)
    @State private var previousTab: Int = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationTab()
                .environmentObject(deps)
                .environmentObject(navVM)
                .tabItem {
                    Image(systemName: "map.fill")
                    Text("내비게이션")
                }
                .tag(0)

            TrafficLightTab()
                .environmentObject(deps)
                .tabItem {
                    Image(systemName: "eye.fill")
                    Text("신호등")
                }
                .tag(1)
        }
        .onAppear {
            deps.locationTracker.start()
            deps.headingProvider.start()
        }
        // 횡단보도 진입/이탈 자동 전환 — 기존 로직 그대로
        .onChange(of: navVM.isAtCrosswalk) { _, isAtCrosswalk in
            handleCrosswalkChange(isAtCrosswalk)
        }
    }

    /// 횡단보도 진입 시 자동으로 신호등 탭 전환, 빠져나오면 복귀
    private func handleCrosswalkChange(_ isAtCrosswalk: Bool) {
        if isAtCrosswalk {
            if selectedTab != 1 {
                previousTab = selectedTab
                selectedTab = 1
            }
        } else {
            if selectedTab == 1 {
                selectedTab = previousTab
            }
        }
    }
}

// MARK: - Tab 1: 내비게이션 (풀스크린 상태 라우터)
struct NavigationTab: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var navVM: NavigationViewModel

    private enum NavTabState { case idle, results, navigating }

    /// 새 저장 상태 추가 없이 기존 @Published 로 파생.
    /// 우선순위: NAVIGATING > RESULTS > IDLE.
    private var state: NavTabState {
        if navVM.isNavigating { return .navigating }
        if !navVM.searchResults.isEmpty { return .results }
        return .idle
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            switch state {
            case .idle:
                NavIdleView()
                    .environmentObject(navVM)
            case .results:
                NavResultsView()
                    .environmentObject(navVM)
                    .environmentObject(deps)
            case .navigating:
                NavNavigatingView()
                    .environmentObject(navVM)
            }
        }
    }
}

// MARK: - IDLE — "화면을 두번 눌러주세요"
/// 화면 전체가 단일 접근성 버튼.
/// VoiceOver: 한 번 탭=초점/읽기, 두 번 탭=실행.
/// 커스텀 TapGesture(count:2) 를 쓰지 않음(VoiceOver 가 가로채 동작이 깨짐).
struct NavIdleView: View {
    @EnvironmentObject var navVM: NavigationViewModel

    var body: some View {
        Button(action: { navVM.startVoiceDestinationFlow() }) {
            Text("화면을 두번 눌러주세요")
                .font(.system(size: 40, weight: .bold))
                .foregroundColor(Color(hex: 0xFFD700))
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.black)
        .ignoresSafeArea()
        .accessibilityLabel("화면을 두번 눌러주세요")
        .accessibilityHint("두 번 탭하면 목적지 음성 입력이 시작됩니다.")
    }
}

// MARK: - RESULTS — 노랑 풀스크린 결과 버튼
/// 최대 5개 결과를 세로 균등 분배. 각 버튼: 노랑 배경, 검정 글씨, "장소명 + N미터".
/// 선택 시 navVM.startNavigation(to:) 호출 → NAVIGATING 으로 전환.
struct NavResultsView: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var navVM: NavigationViewModel

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(navVM.searchResults.prefix(5).enumerated()), id: \.offset) { _, poi in
                Button(action: { Task { await navVM.startNavigation(to: poi) } }) {
                    VStack(spacing: 8) {
                        Text(String(describing: poi.name))
                            .font(.system(size: 30, weight: .bold))
                            .multilineTextAlignment(.center)
                        if let m = distanceMeters(to: poi) {
                            Text("\(m)미터")
                                .font(.system(size: 26, weight: .semibold))
                        }
                    }
                    .foregroundColor(.black)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(hex: 0xFFD700))
                    .border(Color.black, width: 2)
                }
                .accessibilityLabel(accessibilityLabel(for: poi))
                .accessibilityHint("두 번 탭하면 이 목적지로 안내가 시작됩니다.")
            }
        }
        .ignoresSafeArea()
    }

    /// 현재 위치 → POI 직선 거리(정수 미터). 위치 없으면 nil.
    private func distanceMeters(to poi: POIResult) -> Int? {
        guard let cur = deps.locationTracker.currentLocation else { return nil }
        let from = CLLocation(latitude: cur.latitude, longitude: cur.longitude)
        let target = CLLocation(latitude: poi.lat, longitude: poi.lon)
        return Int(target.distance(from: from))
    }

    private func accessibilityLabel(for poi: POIResult) -> String {
        let name = String(describing: poi.name)
        if let m = distanceMeters(to: poi) {
            return "\(name), \(m)미터"
        }
        return name
    }
}

// MARK: - NAVIGATING — 검정 배경 + 중앙 안내 멘트
/// 중앙: TTS 안내 멘트(navVM.guidanceDisplayText) 큰 노랑 글씨. 다음 안내 전까지 유지.
/// 하단: 작은 "안내 종료" 버튼(VoiceOver 두 번 탭으로 종료).
struct NavNavigatingView: View {
    @EnvironmentObject var navVM: NavigationViewModel

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text(navVM.guidanceDisplayText)
                .font(.system(size: 40, weight: .bold))
                .foregroundColor(Color(hex: 0xFFD700))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
            Spacer()

            Button(action: { navVM.stopNavigation() }) {
                Text("안내 종료")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(Color.white.opacity(0.15))
            }
            .accessibilityLabel("안내 종료")
            .accessibilityHint("두 번 탭하면 현재 안내를 종료합니다.")
        }
        .background(Color.black)
        .ignoresSafeArea()
    }
}

// MARK: - Tab 2: 신호등 감지 (변경 금지)
struct TrafficLightTab: View {
    @EnvironmentObject var deps: AppDependencies

    var body: some View {
        ZStack {
            CameraPreview(session: deps.trafficLightDetector.captureSession)
                .ignoresSafeArea()

            GeometryReader { geo in
                ForEach(deps.trafficLightDetector.detections) { det in
                    let rect = VNImageRectForNormalizedRect(
                        det.boundingBox,
                        Int(geo.size.width),
                        Int(geo.size.height)
                    )
                    let flipped = CGRect(
                        x: rect.minX,
                        y: geo.size.height - rect.maxY,
                        width: rect.width,
                        height: rect.height
                    )

                    ZStack(alignment: .topLeading) {
                        Rectangle()
                            .stroke(det.color, lineWidth: 3)
                            .frame(width: flipped.width, height: flipped.height)
                            .position(x: flipped.midX, y: flipped.midY)

                        Text("\(det.label) \(Int(det.confidence * 100))%")
                            .font(.caption)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .padding(4)
                            .background(det.color)
                            .cornerRadius(4)
                            .position(x: flipped.minX + 40, y: flipped.minY - 10)
                    }
                }
            }
            .ignoresSafeArea()

            VStack {
                Spacer()
                DebugLogOverlay()
                VStack(spacing: 12) {
                    Circle()
                        .fill(deps.trafficLightDetector.signalColor)
                        .frame(width: 60, height: 60)
                        .overlay(Circle().stroke(Color.white, lineWidth: 3))
                        .shadow(radius: 8)

                    Text(deps.trafficLightDetector.statusText)
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)

                    if deps.trafficLightDetector.confidence > 0 {
                        Text("신뢰도: \(Int(deps.trafficLightDetector.confidence * 100))%")
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.8))
                    }
                }
                .padding(20)
                .background(RoundedRectangle(cornerRadius: 20).fill(Color.black.opacity(0.6)))
                .padding(.bottom, 50)
            }
        }
        .onAppear { deps.trafficLightDetector.startDetection() }
        .onDisappear { deps.trafficLightDetector.stopDetection() }
    }
}

// MARK: - Color(hex:) 헬퍼
// 명세서 부록 — 프로젝트에 hex 이니셜라이저가 없으므로 추가.
extension Color {
    init(hex: UInt) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}

#Preview {
    ContentView()
        .environmentObject(AppDependencies())
}
