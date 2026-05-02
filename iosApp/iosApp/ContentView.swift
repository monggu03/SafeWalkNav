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

    var body: some View {
        TabView {
            NavigationTab()
                .environmentObject(deps)
                .environmentObject(navVM)
                .tabItem {
                    Image(systemName: "map.fill")
                    Text("내비게이션")
                }

            TrafficLightTab()
                .environmentObject(deps)
                .tabItem {
                    Image(systemName: "eye.fill")
                    Text("신호등")
                }

            StatusTab()
                .environmentObject(deps)
                .environmentObject(navVM)
                .tabItem {
                    Image(systemName: "gearshape.fill")
                    Text("상태")
                }
        }
        .onAppear {
            deps.locationTracker.start()
            deps.headingProvider.start()
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

    var body: some View {
        GroupBox("목적지 검색") {
            VStack(spacing: 8) {
                HStack {
                    TextField("예: 강남역, 스타벅스", text: $searchKeyword)
                        .textFieldStyle(.roundedBorder)
                    Button("검색") {
                        Task { await navVM.searchDestination(keyword: searchKeyword) }
                    }
                    .buttonStyle(.borderedProminent)
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
