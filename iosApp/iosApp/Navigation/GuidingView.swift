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

    var body: some View {
        VStack(spacing: 0) {
            statusPanel
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            mapSection
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.black)
        .ignoresSafeArea(edges: .bottom)
    }

    // MARK: - 상단 상태 패널

    private var statusPanel: some View {
        VStack(spacing: 16) {
            Spacer()
            Text(coordinator.destinationName ?? "목적지")
                .font(.system(size: 30, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            Text(coordinator.remainingText ?? "안내 중")
                .font(.system(size: 26, weight: .semibold))
                .foregroundColor(.yellow)
                .multilineTextAlignment(.center)

            if let crosswalk = coordinator.nextCrosswalkText {
                Text(crosswalk)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundColor(.yellow.opacity(0.85))
                    .multilineTextAlignment(.center)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .background(Color.black)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        let name = coordinator.destinationName ?? "목적지"
        var parts = ["\(name)로 안내 중"]
        if let remaining = coordinator.remainingText { parts.append(remaining) }
        if let crosswalk = coordinator.nextCrosswalkText { parts.append(crosswalk) }
        return parts.joined(separator: ". ") + "."
    }

    // MARK: - 하단 지도

    @ViewBuilder
    private var mapSection: some View {
        if let route = coordinator.currentRoute, let dest = coordinator.destinationCoord {
            RouteMapView(route: route, destCoordinate: dest)
        } else {
            // 경로가 아직 없으면(이론상 도달 안 함) 지도 자리는 검정.
            Color.black
        }
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

    @State private var cameraPosition: MapCameraPosition

    init(route: TMapRoute, destCoordinate: CLLocationCoordinate2D) {
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
            MapCompass()
        }
        .ignoresSafeArea(edges: .bottom)
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
