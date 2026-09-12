//
//  GuidingView.swift
//  iosApp
//
//  §4-2/§4-3 경로 안내 화면(.guiding).
//  세로로 반반 — 상단: 검정 배경 + 노란 큰 글씨 상태 패널(저시력자·보호자·시연 보조),
//  하단: 애플 지도(MapKit) 로 경로선·횡단보도·목적지·현재위치 표시.
//
//  시각장애인 사용자의 주 채널은 음성(TtsManager). 지도는 얹기만 하며
//  §4-2/§4-3 음성 로직은 그대로 유지한다. 방향 안내는 하지 않는다(§1 제외).
//

import SwiftUI
import MapKit
import shared   // TMapRoute

struct GuidingView: View {
    @EnvironmentObject var coordinator: NavigationCoordinator
    @EnvironmentObject var deps: AppDependencies

    /// 준비 화면 진입 시 VoiceOver 포커스를 「안내 시작」 버튼으로 옮기기 위한 상태.
    @AccessibilityFocusState private var startButtonFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            // 안내 중이면 StatusPanel 전체 화면, 준비 중이면 버튼·지도와 반반.
            statusPanel
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            if !coordinator.guidanceStarted {
                StartGuidanceButton { coordinator.startGuidance() }
                    .accessibilityFocused($startButtonFocused)

                mapSection
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Color.black)
        .ignoresSafeArea(edges: .bottom)
        .accessibleFloor()
        .onAppear {
            // 경로 요약 TTS 발화가 끝난 뒤 포커스를 버튼으로 이동(자동 알림은 앱 TTS 담당).
            if !coordinator.guidanceStarted {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    startButtonFocused = true
                }
            }
        }
    }

    // MARK: - 상단 상태 패널

    private var statusPanel: some View {
        VStack(spacing: 16) {
            Spacer()
            // 목적지명(보조) — 긴 이름은 minimumScaleFactor 로 화면 안에 수렴.
            Text(coordinator.destinationName ?? "목적지")
                .accessibleText(.secondary)
                .foregroundColor(.white)
                .padding(.horizontal, 24)

            // 남은 거리(주) — 가장 크게.
            Text(coordinator.remainingText ?? "안내 중")
                .accessibleText(.primary)
                .foregroundColor(.yellow)
                .padding(.horizontal, 16)

            if let crosswalk = coordinator.nextCrosswalkText {
                Text(crosswalk)
                    .accessibleText(.secondary)
                    .foregroundColor(.yellow.opacity(0.85))
                    .padding(.horizontal, 24)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .background(Color.black)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(panelA11yLabel)
        .accessibilityValue(panelA11yValue)
        .accessibilityAddTraits(.isStaticText)
        .accessibilityIdentifier("navigation.routeStatus")
    }

    // 라벨(무엇인지)과 값(현재 수치)을 분리 — 포커스할 때마다 갱신된 값이 읽힌다.
    // 자동 알림은 앱 TTS 담당이므로 announcement 는 쓰지 않는다.
    private var panelA11yLabel: String {
        "\(coordinator.destinationName ?? "목적지")로 안내 중"
    }

    private var panelA11yValue: String {
        var parts: [String] = []
        if let remaining = coordinator.remainingText { parts.append(remaining) }
        if let crosswalk = coordinator.nextCrosswalkText { parts.append(crosswalk) }
        return parts.isEmpty ? "안내 중" : parts.joined(separator: ". ")
    }

    // MARK: - 하단 지도

    @ViewBuilder
    private var mapSection: some View {
        if let route = coordinator.currentRoute, let dest = coordinator.destinationCoord {
            RouteMapView(route: route, destCoordinate: dest, locationTracker: deps.locationTracker)
        } else {
            // 경로가 아직 없으면(이론상 도달 안 함) 지도 자리는 검정.
            Color.black
        }
    }
}

// MARK: - 「안내 시작」 버튼

/// StatusPanel 과 지도 사이에 놓이는 큰 노란 버튼(§4-3).
/// 저시력자 기준 큰 글씨(.action = 48pt bold)와 넉넉한 터치 영역(≥88pt).
struct StartGuidanceButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("안내 시작")
                .accessibleText(.action)
                .foregroundColor(.black)
                .frame(maxWidth: .infinity, minHeight: 88)
        }
        .background(Color.yellow)
        .accessibilityLabel("안내 시작")
        .accessibilityHint("두 번 누르면 경로 안내를 시작하고 지도를 숨깁니다.")
        .accessibilityIdentifier("navigation.startGuidance")
    }
}

// MARK: - 지도 (SwiftUI Map, iOS 17+)

private struct CrosswalkPin: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
}

struct RouteMapView: View {
    private let routeCoords: [CLLocationCoordinate2D]
    private let crosswalks: [CrosswalkPin]
    private let destCoordinate: CLLocationCoordinate2D

    /// 현재 위치 관찰 — 첫 유효 픽스에 자동 추종으로 전환하기 위함.
    @ObservedObject private var locationTracker: LocationTracker

    @State private var cameraPosition: MapCameraPosition
    /// 자동 추종으로 이미 전환했는지(첫 전환 1회, 이후 사용자 조작 존중).
    @State private var didFollow = false

    init(route: TMapRoute, destCoordinate: CLLocationCoordinate2D, locationTracker: LocationTracker) {
        self.locationTracker = locationTracker
        // 경로선: routePoints 우선, 비어있으면 waypoints 좌표로 폴백.
        let coords: [CLLocationCoordinate2D]
        if !route.routePoints.isEmpty {
            coords = route.routePoints.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        } else {
            coords = route.waypoints.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        }
        self.routeCoords = coords
        self.crosswalks = route.waypoints
            .filter { $0.pointType == "CROSSWALK" }
            .map { CrosswalkPin(coordinate: CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)) }
        self.destCoordinate = destCoordinate

        // 첫 진입 시 경로 전체가 보이도록 fit. 이후엔 사용자 조작 존중.
        let all = coords + [destCoordinate]
        _cameraPosition = State(initialValue: .region(RouteMapView.fitRegion(for: all)))
    }

    var body: some View {
        Map(position: $cameraPosition) {
            // 1) 경로선 (파랑)
            if routeCoords.count >= 2 {
                MapPolyline(coordinates: routeCoords)
                    .stroke(.blue, lineWidth: 6)
            }
            // 2) 횡단보도 (노랑 핀)
            ForEach(crosswalks) { pin in
                Marker("횡단보도", systemImage: "figure.walk", coordinate: pin.coordinate)
                    .tint(.yellow)
            }
            // 3) 목적지 (빨강 핀)
            Marker("도착", systemImage: "flag.fill", coordinate: destCoordinate)
                .tint(.red)
            // 4) 현재 위치 (파란 점) — 위치권한 없으면 자동으로 표시 안 됨(크래시 없음)
            UserAnnotation()
        }
        .mapControls {
            MapUserLocationButton()
                .accessibilityLabel("현재 위치로 이동")
                .accessibilityValue(locationAccessibilityValue)
                .accessibilityHint("지도를 현재 위치 중심으로 이동합니다.")
                .accessibilityIdentifier("navigation.currentLocation")
            MapCompass()
                .accessibilityLabel("지도 북쪽 방향 맞추기")
                .accessibilityHint("지도의 위쪽을 북쪽으로 맞춥니다.")
                .accessibilityIdentifier("navigation.compass")
        }
        .ignoresSafeArea(edges: .bottom)
        // 첫 유효 위치 픽스 → 자동 추종으로 전환(북쪽 고정).
        .onChange(of: locationTracker.currentLocation?.latitude) { _, newLat in
            guard !didFollow, newLat != nil else { return }
            startFollowing()
        }
        // 폴백: 위치가 안 와도 안내 시작 ~2초 후 추종 모드로.
        .task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if !didFollow { startFollowing() }
        }
    }

    /// 이미 공개된 위치 상태만 읽는다. 좌표 존재를 현재 GPS 수신 정상으로 단정하지 않는다.
    private var locationAccessibilityValue: String {
        switch locationTracker.authorizationStatus {
        case .denied: return "위치 권한이 꺼져 있습니다"
        case .restricted: return "위치 서비스 사용이 제한되어 있습니다"
        case .notDetermined: return "위치 권한 확인이 필요합니다"
        case .authorizedAlways, .authorizedWhenInUse:
            guard locationTracker.isTracking else { return "위치 추적 중지됨" }
            return locationTracker.currentLocation == nil
                ? "현재 위치 확인 중"
                : "마지막으로 확인한 위치 사용 가능"
        @unknown default: return "위치 권한 상태를 확인할 수 없습니다"
        }
    }

    /// 현재 위치 중심 자동 추종(회전 없음). 1회만 강제 전환하고
    /// 이후 사용자가 손으로 밀면 그 조작을 존중(MapUserLocationButton 으로 복귀).
    private func startFollowing() {
        didFollow = true
        withAnimation {
            cameraPosition = .userLocation(followsHeading: false, fallback: .automatic)
        }
    }

    /// 좌표들을 모두 담는 영역(약간 여유 padding). 좌표가 없으면 서울 기본.
    private static func fitRegion(for coords: [CLLocationCoordinate2D]) -> MKCoordinateRegion {
        guard let first = coords.first else {
            return MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: 37.5666, longitude: 126.9784),
                span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
            )
        }
        var minLat = first.latitude, maxLat = first.latitude
        var minLon = first.longitude, maxLon = first.longitude
        for c in coords {
            minLat = min(minLat, c.latitude);  maxLat = max(maxLat, c.latitude)
            minLon = min(minLon, c.longitude); maxLon = max(maxLon, c.longitude)
        }
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )
        let span = MKCoordinateSpan(
            latitudeDelta: max((maxLat - minLat) * 1.4, 0.003),
            longitudeDelta: max((maxLon - minLon) * 1.4, 0.003)
        )
        return MKCoordinateRegion(center: center, span: span)
    }
}
