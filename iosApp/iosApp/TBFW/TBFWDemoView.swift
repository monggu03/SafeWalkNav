//
//  TBFWDemoView.swift
//  iosApp
//
//  TBFW 알고리즘 데모 화면.
//  좌측 상단에 메시지, 하단에 디버그 정보, 우측 하단에 시작/종료 버튼.
//

import SwiftUI
import shared

struct TBFWDemoView: View {
    @EnvironmentObject var deps: AppDependencies
    @StateObject private var viewModel: TBFWDemoViewModel

    /// AppDependencies에서 의존성을 받아 ViewModel 초기화.
    /// SwiftUI의 @StateObject는 init에서 만들어야 해서 약간 번거로운 패턴.
    init(deps: AppDependencies) {
        _viewModel = StateObject(wrappedValue: TBFWDemoViewModel(
            locationTracker: deps.locationTracker,
            headingProvider: deps.headingProvider
        ))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {

                    // ─── 메시지 카드 (메인) ───
                    GroupBox {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Image(systemName: "location.fill")
                                    .foregroundColor(.blue)
                                Text("TBFW 안내")
                                    .font(.headline)
                                Spacer()
                                Circle()
                                    .fill(viewModel.isRunning ? Color.green : Color.gray)
                                    .frame(width: 10, height: 10)
                            }

                            Text(viewModel.message)
                                .font(.title3)
                                .multilineTextAlignment(.leading)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.top, 4)
                        }
                    }

                    // ─── Trust Score ───
                    GroupBox("🛡️ Trust Score") {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("점수")
                                Spacer()
                                Text("\(viewModel.trustScore) / 100")
                                    .fontWeight(.bold)
                                    .foregroundColor(trustColor)
                            }
                            HStack {
                                Text("카테고리")
                                Spacer()
                                Text(viewModel.trustLevel)
                                    .fontWeight(.bold)
                                    .foregroundColor(trustColor)
                            }
                            // 시각적 바 표시
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Rectangle()
                                        .fill(Color.gray.opacity(0.2))
                                    Rectangle()
                                        .fill(trustColor)
                                        .frame(width: geo.size.width * CGFloat(viewModel.trustScore) / 100)
                                }
                                .frame(height: 8)
                                .clipShape(Capsule())
                            }
                            .frame(height: 8)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    // ─── Waypoint 진행 상황 ───
                    GroupBox("📍 Waypoint 진행") {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("현재")
                                Spacer()
                                Text("\(viewModel.currentWaypointIndex) / \(viewModel.totalWaypoints)")
                                    .fontWeight(.bold)
                            }
                            HStack {
                                Text("다음까지 거리")
                                Spacer()
                                Text(String(format: "%.1f m", viewModel.distanceToWaypoint))
                                    .fontWeight(.bold)
                            }
                            HStack {
                                Text("방향 차이")
                                Spacer()
                                let absD = abs(viewModel.headingDiff)
                                let side = viewModel.headingDiff >= 0 ? "오른쪽" : "왼쪽"
                                Text(String(format: "%.1f° (%@)", absD, side))
                                    .fontWeight(.bold)
                                    .foregroundColor(absD < 30 ? .green : .orange)
                            }
                            if viewModel.didPassWaypoint {
                                Text("✅ 이번 업데이트에서 통과 처리")
                                    .font(.caption)
                                    .foregroundColor(.green)
                            }
                            if viewModel.isFinished {
                                Text("🏁 모든 waypoint 통과 완료")
                                    .font(.caption)
                                    .foregroundColor(.blue)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    // ─── 입력 데이터 (디버그용) ───
                    GroupBox("🔍 입력 데이터 (디버그)") {
                        VStack(alignment: .leading, spacing: 4) {
                            if let loc = deps.locationTracker.currentLocation {
                                Text("위도: \(loc.latitude, specifier: "%.6f")")
                                Text("경도: \(loc.longitude, specifier: "%.6f")")
                                Text("GPS accuracy: \(loc.accuracy, specifier: "%.1f")m (hasAccuracy: \(loc.hasAccuracy ? "✅" : "❌"))")
                                Text("속도: \(loc.speed, specifier: "%.2f") m/s")
                                Text("GPS bearing: \(loc.bearing, specifier: "%.1f")°")
                            } else {
                                Text("GPS 위치 없음").foregroundColor(.gray)
                            }
                            Divider()
                            Text("나침반 heading: \(deps.headingProvider.currentHeading, specifier: "%.1f")° (추적중: \(deps.headingProvider.isTracking ? "✅" : "❌"))")
                        }
                        .font(.system(.caption, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    // ─── 컨트롤 버튼 ───
                    HStack(spacing: 12) {
                        if !viewModel.isRunning {
                            Button(action: {
                                viewModel.start()
                            }) {
                                Label("TBFW 시작", systemImage: "play.fill")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                        } else {
                            Button(action: {
                                viewModel.stop()
                            }) {
                                Label("TBFW 종료", systemImage: "stop.fill")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                            .tint(.red)
                        }
                    }
                    .padding(.top, 8)

                }
                .padding()
            }
            .navigationTitle("TBFW 데모")
        }
    }

    /// Trust 카테고리에 따른 색상
    private var trustColor: Color {
        switch viewModel.trustLevel {
        case "HIGH": return .green
        case "MEDIUM": return .blue
        case "LOW": return .orange
        case "CRITICAL": return .red
        default: return .gray
        }
    }
}
