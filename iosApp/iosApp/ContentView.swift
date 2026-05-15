//
//  ContentView.swift
//  iosApp
//
//  메인 화면 — 탭 기반 통합 뷰
//  Tab 1: 내비게이션 (검색 + 경로 안내 + 지도)
//  Tab 2: 신호등 감지 (카메라 + YOLO)
//  Tab 3: 설정/상태 (GPS, 나침반, 디버그 정보)
//

import SwiftUI
import shared
import CoreLocation
import Speech
import Vision

// MARK: - 메인 탭 뷰
struct ContentView: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var navVM: NavigationViewModel

    /// 현재 선택된 탭 (0=내비, 1=신호등, 2=상태)
    @State private var selectedTab: Int = 0

    /// 횡단보도 자동 전환 직전의 탭 (빠져나올 때 복귀용)
    @State private var previousTab: Int = 0
    
    var body: some View {
        TabView {
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

            StatusTab()
                .environmentObject(deps)
                .environmentObject(navVM)
                .tabItem {
                    Image(systemName: "gearshape.fill")
                    Text("상태")
                }
                .tag(2)
        }
        .onAppear {
            deps.locationTracker.start()
            deps.headingProvider.start()
        }
        // 🆕 횡단보도 진입/이탈 자동 전환
        .onChange(of: navVM.isAtCrosswalk) { _, isAtCrosswalk in
            handleCrosswalkChange(isAtCrosswalk)
        }
    }
    /// 횡단보도 진입 시 자동으로 신호등 탭 전환, 빠져나오면 복귀
       private func handleCrosswalkChange(_ isAtCrosswalk: Bool) {
           if isAtCrosswalk {
               // 진입: 현재 탭 기억하고 신호등 탭(1)으로 전환
               // 이미 신호등 탭에 있으면 기억하지 않음 (복귀 시 무한 루프 방지)
               if selectedTab != 1 {
                   previousTab = selectedTab
                   selectedTab = 1
               }
           } else {
               // 이탈: 신호등 탭에 있을 때만 원래 탭으로 복귀
               // (사용자가 수동으로 다른 탭 갔으면 건드리지 않음)
               if selectedTab == 1 {
                   selectedTab = previousTab
               }
           }
       }
}

// MARK: - Tab 1: 내비게이션
struct NavigationTab: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var navVM: NavigationViewModel

    @State private var searchKeyword: String = ""
    @State private var showMap: Bool = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if navVM.isNavigating {
                        NavigationStatusCard()
                            .environmentObject(navVM)
                            .environmentObject(deps)
                    }

                    SearchSection(searchKeyword: $searchKeyword)
                        .environmentObject(navVM)
                        .environmentObject(deps)

                    if navVM.isNavigating {
                        Button(showMap ? "지도 숨기기" : "지도 보기") {
                            showMap.toggle()
                        }
                        .buttonStyle(.bordered)

                        if showMap {
                            MapView(
                                currentLocation: currentCLCoordinate,
                                routeCoordinates: [],
                                destinationName: navVM.guidanceMessage
                            )
                            .frame(height: 300)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("SafeWalk")
        }
    }

    private var currentCLCoordinate: CLLocationCoordinate2D? {
        guard let loc = deps.locationTracker.currentLocation else { return nil }
        return CLLocationCoordinate2D(latitude: loc.latitude, longitude: loc.longitude)
    }
}

// MARK: - 내비게이션 상태 카드
struct NavigationStatusCard: View {
    @EnvironmentObject var navVM: NavigationViewModel
    @EnvironmentObject var deps: AppDependencies

    /// 거리 표시 — Float.greatestFiniteMagnitude → Int 변환 크래시 방지
    private var distanceText: String {
        let d = navVM.distanceToDestination
        if d.isInfinite || d.isNaN || d > 100_000 {
            return "--m"
        }
        return "\(Int(d))m"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "location.fill")
                    .foregroundColor(.blue)
                    .font(.title2)
                Text(navVM.guidanceMessage)
                    .font(.headline)
                    .multilineTextAlignment(.leading)
            }

            HStack {
                Label(distanceText, systemImage: "figure.walk")
                Spacer()
                Text(arrivalText(navVM.arrivalState))
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(arrivalColor(navVM.arrivalState).opacity(0.2))
                    .cornerRadius(8)
            }

            if deps.headingProvider.isDrifting {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                    let direction = deps.headingProvider.driftDegrees > 0 ? "오른쪽" : "왼쪽"
                    Text("\(direction)으로 \(Int(abs(deps.headingProvider.driftDegrees)))° 벗어남")
                        .foregroundColor(.orange)
                }
            }

            if let err = navVM.errorMessage {
                Text("⚠️ \(err)")
                    .font(.caption)
                    .foregroundColor(.red)
            }

            Button("안내 종료") { navVM.stopNavigation() }
                .buttonStyle(.bordered)
                .tint(.red)
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
        .shadow(color: .black.opacity(0.1), radius: 4, y: 2)
    }

    private func arrivalText(_ state: ArrivalState) -> String {
        switch state {
        case .far:         return "이동 중"
        case .approaching: return "접근 중 (15m)"
        case .near:        return "거의 도착 (5m)"
        case .arrived:     return "도착! ✅"
        default:           return "?"
        }
    }

    private func arrivalColor(_ state: ArrivalState) -> Color {
        switch state {
        case .far:         return .blue
        case .approaching: return .orange
        case .near:        return .yellow
        case .arrived:     return .green
        default:           return .gray
        }
    }
}

// MARK: - 검색 섹션
struct SearchSection: View {
    @Binding var searchKeyword: String
    @EnvironmentObject var navVM: NavigationViewModel
    @EnvironmentObject var deps: AppDependencies

    private var voiceButtonText: String {
        switch navVM.voiceFlowStage {
        case .idle:               return "🎤 음성으로 목적지 말하기"
        case .listening:          return "듣는 중… 말씀하세요"
        case .searching:          return "검색 중…"
        case .startingNavigation: return "안내 시작 중…"
        }
    }

    private var voiceButtonColor: Color {
        switch navVM.voiceFlowStage {
        case .idle:      return .blue
        case .listening: return .red
        default:         return .gray
        }
    }

    var body: some View {
        // SwiftUI 가 실제로 searchResults 변경을 관찰해 body 를 다시 그리는지 확인하는 로그.
        // (@Published 가 발화해도 View 가 구독 안 하면 안 찍힘 → 바인딩 끊김 진단용)
        let _ = print("🖼️ [SearchSection.body] redraw — searchResults.count=\(navVM.searchResults.count) errorMessage=\(navVM.errorMessage ?? "nil")")

        return GroupBox("목적지 검색") {
            VStack(spacing: 12) {
                // 시각장애인용 음성 입력 버튼 (큰 면적, 한 번 누르면 자동 안내 시작)
                Button(action: handleVoiceTap) {
                    HStack {
                        Image(systemName: navVM.voiceFlowStage == .listening
                              ? "mic.fill"
                              : "mic.circle.fill")
                            .font(.title)
                        Text(voiceButtonText)
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .foregroundColor(.white)
                    .background(voiceButtonColor)
                    .cornerRadius(12)
                }
                .accessibilityLabel("음성으로 목적지 말하기")
                .accessibilityHint("버튼을 누르고 목적지 이름을 말하면 자동으로 안내가 시작됩니다.")

                if navVM.voiceFlowStage == .listening && !deps.stt.partialText.isEmpty {
                    Text("인식 중: \(deps.stt.partialText)")
                        .font(.subheadline)
                        .foregroundColor(.blue)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Divider()

                HStack {
                    TextField("예: 강남역, 스타벅스", text: $searchKeyword)
                        .textFieldStyle(.roundedBorder)
                    Button("검색") {
                        Task { await navVM.searchDestination(keyword: searchKeyword) }
                    }
                    .buttonStyle(.borderedProminent)
                }

                // 검색 결과가 0개면 사용자에게도 보이게 (errorMessage 가 비어있어도 빈 상태 표시)
                if navVM.searchResults.isEmpty {
                    Text(navVM.errorMessage ?? "검색 결과가 여기에 표시됩니다")
                        .font(.caption)
                        .foregroundColor(.gray)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                ForEach(Array(navVM.searchResults.prefix(5).enumerated()), id: \.offset) { _, poi in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(String(describing: poi.name))
                                .font(.headline)
                            Text("(\(poi.lat, specifier: "%.4f"), \(poi.lon, specifier: "%.4f"))")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                        Spacer()
                        Button("안내") {
                            Task { await navVM.startNavigation(to: poi) }
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(.vertical, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func handleVoiceTap() {
        if navVM.voiceFlowStage == .listening {
            navVM.cancelVoiceDestinationFlow()
        } else if navVM.voiceFlowStage == .idle {
            navVM.startVoiceDestinationFlow()
        }
    }
}

// MARK: - Tab 2: 신호등 감지
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
                // ⭐ 디버그 로그 추가 (신호 카드 위쪽)
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

// MARK: - Tab 3: 상태/디버그
struct StatusTab: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var navVM: NavigationViewModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {

                    GroupBox("📍 GPS") {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("권한: \(authText(deps.locationTracker.authorizationStatus))")
                            Text("추적 중: \(deps.locationTracker.isTracking ? "✅" : "❌")")
                            if let loc = deps.locationTracker.currentLocation {
                                Text("위도: \(loc.latitude, specifier: "%.6f")")
                                Text("경도: \(loc.longitude, specifier: "%.6f")")
                                Text("정확도: \(deps.locationTracker.lastAccuracy, specifier: "%.1f")m")
                            } else {
                                Text("위치 없음")
                                    .foregroundColor(.gray)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    GroupBox("🧭 나침반 (Drift Correction)") {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("추적 중: \(deps.headingProvider.isTracking ? "✅" : "❌")")
                            Text("현재 방향: \(Int(deps.headingProvider.currentHeading))°")
                            Text("Base 설정됨: \(deps.headingProvider.hasBaseHeading ? "✅" : "❌")")
                            if deps.headingProvider.hasBaseHeading {
                                HStack {
                                    Text("편차: \(deps.headingProvider.driftDegrees, specifier: "%.1f")°")
                                    if deps.headingProvider.isDrifting {
                                        Text("⚠️ 이탈!")
                                            .foregroundColor(.orange)
                                            .fontWeight(.bold)
                                    }
                                }
                            }

                            HStack {
                                Button("Base 설정") { deps.headingProvider.setBaseHeading() }
                                    .buttonStyle(.bordered)
                                Button("Base 초기화") { deps.headingProvider.clearBaseHeading() }
                                    .buttonStyle(.bordered)
                                    .tint(.red)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    GroupBox("🔊 TTS") {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("말하는 중: \(deps.tts.isSpeaking ? "✅" : "❌")")
                            Button("테스트 음성") {
                                deps.tts.speak("SafeWalk 음성 테스트입니다.")
                            }
                            .buttonStyle(.bordered)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    GroupBox("🎤 STT") {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("권한: \(sttAuthText)")
                            Text("듣는 중: \(deps.stt.isListening ? "✅" : "❌")")
                            if !deps.stt.partialText.isEmpty {
                                Text("인식: \(deps.stt.partialText)")
                                    .foregroundColor(.blue)
                            }
                            if let err = deps.stt.lastError {
                                Text("에러: \(err)").foregroundColor(.red)
                            }
                            HStack {
                                Button("권한 요청") {
                                    Task { _ = await deps.stt.requestAuthorization() }
                                }
                                .buttonStyle(.bordered)
                                Button(deps.stt.isListening ? "중지" : "듣기") {
                                    if deps.stt.isListening {
                                        deps.stt.stopListening()
                                    } else {
                                        deps.stt.startListening()
                                    }
                                }
                                .buttonStyle(.borderedProminent)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    GroupBox("🚦 신호등 감지") {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Circle()
                                    .fill(deps.trafficLightDetector.signalColor)
                                    .frame(width: 20, height: 20)
                                Text(deps.trafficLightDetector.statusText)
                            }
                            if deps.trafficLightDetector.confidence > 0 {
                                Text("신뢰도: \(Int(deps.trafficLightDetector.confidence * 100))%")
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding()
            }
            .navigationTitle("상태")
        }
    }

    /// STT 권한 상태를 문자열로 변환 (.rawValue 사용 안 함)
    private var sttAuthText: String {
        switch deps.stt.authorizationStatus {
        case .authorized:     return "허용 ✅"
        case .denied:         return "거부 ❌"
        case .restricted:     return "제한됨"
        case .notDetermined:  return "미정"
        @unknown default:     return "?"
        }
    }

    private func authText(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined:       return "미정"
        case .authorizedWhenInUse: return "사용 중 허용 ✅"
        case .authorizedAlways:    return "항상 허용 ✅"
        case .denied:              return "거부 ❌"
        case .restricted:          return "제한됨"
        @unknown default:          return "?"
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(AppDependencies())
}
