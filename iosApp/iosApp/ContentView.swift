import SwiftUI
import shared
import CoreLocation

struct ContentView: View {
    @EnvironmentObject var deps: AppDependencies
    @EnvironmentObject var navVM: NavigationViewModel

    @State private var searchKeyword: String = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {

                // MARK: - GPS 상태
                GroupBox("📍 GPS") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("권한: \(authText(deps.locationTracker.authorizationStatus))")
                        Text("추적 중: \(deps.locationTracker.isTracking ? "✅" : "❌")")
                        if let loc = deps.locationTracker.currentLocation {
                            Text("위도: \(loc.latitude, specifier: "%.6f")")
                            Text("경도: \(loc.longitude, specifier: "%.6f")")
                            Text("정확도: \(deps.locationTracker.lastAccuracy, specifier: "%.1f")m")
                        }
                        Button("GPS 시작") { deps.locationTracker.start() }
                            .buttonStyle(.borderedProminent)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                // MARK: - 목적지 검색
                GroupBox("🔍 목적지 검색") {
                    VStack(spacing: 8) {
                        HStack {
                            TextField("예: 강남역, 스타벅스", text: $searchKeyword)
                                .textFieldStyle(.roundedBorder)
                            Button("검색") {
                                Task {
                                    await navVM.searchDestination(keyword: searchKeyword)
                                }
                            }
                            .buttonStyle(.borderedProminent)
                        }

                        ForEach(Array(navVM.searchResults.prefix(5).enumerated()), id: \.offset) { index, poi in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(String(describing: poi.name))
                                        .font(.headline)
                                    Text("(\(poi.lat, specifier: "%.4f"), \(poi.lon, specifier: "%.4f"))")
                                        .font(.caption)
                                        .foregroundColor(.gray)
                                }
                                Spacer()
                                Button("안내 시작") {
                                    Task {
                                        await navVM.startNavigation(to: poi)
                                    }
                                }
                                .buttonStyle(.bordered)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                // MARK: - 내비게이션 상태
                GroupBox("🧭 내비게이션 상태") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("활성: \(navVM.isNavigating ? "✅" : "❌")")
                        Text("도착 단계: \(arrivalText(navVM.arrivalState))")
                        Text("거리: \(navVM.distanceToDestination, specifier: "%.0f") m")
                        Text("안내: \(navVM.guidanceMessage)")
                            .font(.subheadline)
                            .foregroundColor(.blue)
                            .multilineTextAlignment(.leading)
                        if let err = navVM.errorMessage {
                            Text("⚠️ \(err)").foregroundColor(.red)
                        }

                        Button("안내 종료") { navVM.stopNavigation() }
                            .buttonStyle(.bordered)
                            .tint(.red)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding()
        }
    }

    // MARK: - Helpers
    private func authText(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: return "미정"
        case .authorizedWhenInUse: return "사용 중 허용 ✅"
        case .authorizedAlways: return "항상 허용 ✅"
        case .denied: return "거부 ❌"
        case .restricted: return "제한됨"
        @unknown default: return "?"
        }
    }

    private func arrivalText(_ state: ArrivalState) -> String {
        switch state {
        case .far: return "FAR (먼 거리)"
        case .approaching: return "APPROACHING (15m)"
        case .near: return "NEAR (5m)"
        case .arrived: return "ARRIVED (도착)"
        default: return "?"
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(AppDependencies())
}
