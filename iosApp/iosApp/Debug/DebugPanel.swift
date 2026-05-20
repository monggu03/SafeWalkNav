//
//  DebugPanel.swift
//  iosApp
//
//  보행 테스트용 디버그 패널.
//  RouteAnnotator 임계값 튜닝을 위해 4개 섹션을 한 곳에 모아 본다:
//   1) 현재 GPS / heading / 정확도
//   2) 다가오는 annotation (가장 가까운 미발화)
//   3) 전체 annotation 명세
//   4) 발화 로그 + 복사 버튼
//
//  ⚠️ 개발자 전용. 시각장애 사용자에게는 노출하지 않음.
//

import SwiftUI
import shared
import CoreLocation

struct DebugPanel: View {
    @EnvironmentObject var navVM: NavigationViewModel
    @EnvironmentObject var deps: AppDependencies

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            DebugPanel_CurrentState()
                .environmentObject(deps)

            Divider()

            DebugPanel_CurrentAnnotation()
                .environmentObject(navVM)

            Divider()

            DebugPanel_AnnotationList()
                .environmentObject(navVM)

            Divider()

            DebugPanel_AnnouncementLog()
                .environmentObject(navVM)
        }
        .padding()
        .background(Color.gray.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .font(.system(.caption, design: .monospaced))
    }
}

// MARK: - Section 1: 현재 상태

private struct DebugPanel_CurrentState: View {
    @EnvironmentObject var deps: AppDependencies

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("📍 현재 상태").font(.headline)
            if let loc = deps.locationTracker.currentLocation {
                Text("위도: \(loc.latitude, specifier: "%.6f")")
                Text("경도: \(loc.longitude, specifier: "%.6f")")
                Text("정확도: \(deps.locationTracker.lastAccuracy, specifier: "%.1f") m")
                Text("속도: \(loc.speed, specifier: "%.2f") m/s")
            } else {
                Text("위치 정보 없음").foregroundColor(.secondary)
            }
            Text("Heading: \(deps.headingProvider.currentHeading, specifier: "%.1f")°")
        }
    }
}

// MARK: - Section 2: 다가오는 annotation

private struct DebugPanel_CurrentAnnotation: View {
    @EnvironmentObject var navVM: NavigationViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("🎯 다가오는 annotation").font(.headline)
            if let next = navVM.upcomingAnnotation {
                Text("Type: \(next.type)")
                Text("Direction: \(next.direction)")
                Text("Angle: \(next.totalAngle, specifier: "%.1f")°")
                Text("거리: \(next.distanceM, specifier: "%.1f") m")
                Text("Message: \(next.message)").lineLimit(2)
            } else {
                Text("(다가오는 annotation 없음)").foregroundColor(.secondary)
            }
        }
    }
}

// MARK: - Section 3: 전체 annotation 명세

private struct DebugPanel_AnnotationList: View {
    @EnvironmentObject var navVM: NavigationViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("📋 전체 annotation (\(navVM.annotationMarkers.count)개)").font(.headline)
            ForEach(navVM.annotationMarkers) { ann in
                Text("[\(ann.id)] \(ann.type) \(ann.direction) \(String(format: "%.1f°", ann.totalAngle))")
                    .lineLimit(1)
            }
            if navVM.annotationMarkers.isEmpty {
                Text("(annotation 없음)").foregroundColor(.secondary)
            }
        }
    }
}

// MARK: - Section 4: 발화 로그 + 복사

private struct DebugPanel_AnnouncementLog: View {
    @EnvironmentObject var navVM: NavigationViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("🗣 발화 로그").font(.headline)
                Spacer()
                Button("복사") {
                    UIPasteboard.general.string =
                        navVM.announcementLog.joined(separator: "\n")
                }
                .font(.caption)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(navVM.announcementLog.enumerated()), id: \.offset) { _, entry in
                        Text(entry).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 200)
        }
    }
}
