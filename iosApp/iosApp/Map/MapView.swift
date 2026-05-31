//
//  MapView.swift
//  iosApp
//
//  Apple MapKit 기반 지도 표시 뷰
//  - 현재 위치 중심 표시
//  - 내비게이션 경로 폴리라인 표시
//  - 목적지 핀 표시
//
//  ⚠️ 시각장애인 앱이라 지도는 보조 기능 (발표 시연 + 디버깅용)
//     핵심 안내는 TTS로 이루어짐
//

import SwiftUI
import MapKit
import CoreLocation

// MARK: - MapView (SwiftUI Wrapper)
struct MapView: View {
    /// 현재 GPS 위치 (LocationTracker에서 받음)
    let currentLocation: CLLocationCoordinate2D?
    /// 경로 좌표 배열 (NavigationManager에서 받음)
    let routeCoordinates: [CLLocationCoordinate2D]
    /// waypoint 핀 (CROSSWALK/TURN/기타 색상 구분)
    let waypointPins: [NavigationViewModel.WaypointPin]
    /// RouteAnnotator annotation 마커 (CURVE/TURN 시각화)
    let annotationMarkers: [NavigationViewModel.AnnotationMarker]
    /// 목적지 이름 (핀에 표시)
    let destinationName: String?

    init(
        currentLocation: CLLocationCoordinate2D?,
        routeCoordinates: [CLLocationCoordinate2D],
        waypointPins: [NavigationViewModel.WaypointPin] = [],
        annotationMarkers: [NavigationViewModel.AnnotationMarker] = [],
        destinationName: String?
    ) {
        self.currentLocation = currentLocation
        self.routeCoordinates = routeCoordinates
        self.waypointPins = waypointPins
        self.annotationMarkers = annotationMarkers
        self.destinationName = destinationName
    }

    /// 지도 카메라 위치
    @State private var cameraPosition: MapCameraPosition = .automatic

    var body: some View {
        Map(position: $cameraPosition) {
            // 1. 현재 위치 마커
            if let location = currentLocation {
                Annotation("현재 위치", coordinate: location) {
                    ZStack {
                        Circle()
                            .fill(.blue.opacity(0.3))
                            .frame(width: 30, height: 30)
                        Circle()
                            .fill(.blue)
                            .frame(width: 14, height: 14)
                        Circle()
                            .stroke(.white, lineWidth: 2)
                            .frame(width: 14, height: 14)
                    }
                }
            }

            // 2. 경로 폴리라인
            if routeCoordinates.count >= 2 {
                MapPolyline(coordinates: routeCoordinates)
                    .stroke(.blue, lineWidth: 5)
            }

            // 3. 실제 waypoint 핀 (가상 제외) — pointType 별 색상.
            ForEach(waypointPins.filter { !$0.isVirtual }) { pin in
                Marker(pin.pointType, coordinate: pin.coordinate)
                    .tint(colorForWaypoint(pin.pointType))
            }

            // 3-b. 가상 waypoint — 작은 점. 곡선 방향으로 색 구분(왼=teal / 오른=green).
            ForEach(waypointPins.filter { $0.isVirtual }) { pin in
                Annotation("", coordinate: pin.coordinate) {
                    Circle()
                        .fill(pin.curveDirection == "LEFT" ? .teal : .green)
                        .frame(width: 9, height: 9)
                        .overlay(Circle().stroke(.white, lineWidth: 1.5))
                }
            }

            // 4. annotation 마커 — type 별 색상.
            //    CURVE/SLIGHT_CURVE/INTERNAL_CURVE 보라색 / TURN 계열 빨간색.
            ForEach(annotationMarkers) { ann in
                Marker(
                    "\(ann.type)\(ann.direction == "NONE" ? "" : "·\(ann.direction)")",
                    coordinate: ann.coordinate
                )
                .tint(colorForAnnotation(ann.type))
            }

            // 5. 목적지 핀
            if let destName = destinationName,
               let lastCoord = routeCoordinates.last {
                Marker(destName, coordinate: lastCoord)
                    .tint(.red)
            }
        }
        .mapControls {
            MapCompass()
            MapScaleView()
        }
        .onChange(of: currentLocation?.latitude) { _, _ in
            updateCamera()
        }
        .onAppear {
            updateCamera()
        }
    }

    // MARK: - 마커 색상

    private func colorForWaypoint(_ pointType: String) -> Color {
        switch pointType {
        case "CROSSWALK":   return .yellow
        case "TURN":        return .gray
        case "DESTINATION": return .red
        case "STAIRS":      return .orange
        default:            return .blue
        }
    }

    private func colorForAnnotation(_ type: String) -> Color {
        switch type {
        case "SHARP_TURN":                              return .red      // 급회전 — 최대 강조
        case "TURN", "SLIGHT_TURN":                     return .orange   // 일반 회전
        case "CURVE", "SLIGHT_CURVE", "INTERNAL_CURVE": return .purple   // 곡선
        default:                                        return .gray
        }
    }

    // MARK: - Camera Control

    private func updateCamera() {
        guard let location = currentLocation else { return }

        // 경로가 있으면 전체 경로가 보이도록, 없으면 현재 위치 중심
        if routeCoordinates.count >= 2 {
            // 경로 전체를 포함하는 영역 계산
            let allCoords = [location] + routeCoordinates
            let region = regionThatFits(allCoords)
            cameraPosition = .region(region)
        } else {
            cameraPosition = .region(MKCoordinateRegion(
                center: location,
                span: MKCoordinateSpan(latitudeDelta: 0.005, longitudeDelta: 0.005)
            ))
        }
    }

    /// 좌표 배열을 모두 포함하는 MKCoordinateRegion 계산
    private func regionThatFits(_ coordinates: [CLLocationCoordinate2D]) -> MKCoordinateRegion {
        guard !coordinates.isEmpty else {
            return MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: 37.5665, longitude: 126.9780), // 서울 기본값
                span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
            )
        }

        var minLat = coordinates[0].latitude
        var maxLat = coordinates[0].latitude
        var minLon = coordinates[0].longitude
        var maxLon = coordinates[0].longitude

        for coord in coordinates {
            minLat = min(minLat, coord.latitude)
            maxLat = max(maxLat, coord.latitude)
            minLon = min(minLon, coord.longitude)
            maxLon = max(maxLon, coord.longitude)
        }

        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )
        // 여유 20% 추가
        let span = MKCoordinateSpan(
            latitudeDelta: (maxLat - minLat) * 1.2 + 0.002,
            longitudeDelta: (maxLon - minLon) * 1.2 + 0.002
        )

        return MKCoordinateRegion(center: center, span: span)
    }
}

// MARK: - Preview
#Preview {
    MapView(
        currentLocation: CLLocationCoordinate2D(latitude: 37.5665, longitude: 126.9780),
        routeCoordinates: [
            CLLocationCoordinate2D(latitude: 37.5665, longitude: 126.9780),
            CLLocationCoordinate2D(latitude: 37.5680, longitude: 126.9800),
            CLLocationCoordinate2D(latitude: 37.5700, longitude: 126.9810)
        ],
        destinationName: "목적지"
    )
}
