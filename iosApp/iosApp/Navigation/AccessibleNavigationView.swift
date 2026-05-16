//
//  AccessibleNavigationView.swift
//  iosApp
//
//  시각장애인용 접근성 우선 메인 화면.
//  상태(IDLE / LISTENING / SEARCHING / RESULTS / NAVIGATING)에 따라
//  큰 글자 + 고대비 배경으로 풀스크린 안내.
//
//  - VoiceOver ON  : Magic Tap (두 손가락 더블탭) + 결과 버튼 더블탭 활성화
//  - VoiceOver OFF : 화면 2초 길게 눌러 STT 시작 + 결과 버튼 싱글탭
//
//  하단에는 DebugLogOverlay 를 그대로 붙여 개발자가 상태 전환을 눈으로 확인.
//

import SwiftUI
import UIKit
import shared
import CoreLocation

struct AccessibleNavigationView: View {
    @EnvironmentObject var navVM: NavigationViewModel
    @EnvironmentObject var deps: AppDependencies

    @State private var isVoiceOverRunning: Bool = UIAccessibility.isVoiceOverRunning

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                stateContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                DebugLogOverlay()
            }
        }
        // VoiceOver 사용자는 어디서든 두 손가락 더블탭으로 STT 트리거
        .accessibilityAction(.magicTap) {
            handleMagicTap()
        }
        .onReceive(NotificationCenter.default.publisher(
            for: UIAccessibility.voiceOverStatusDidChangeNotification
        )) { _ in
            isVoiceOverRunning = UIAccessibility.isVoiceOverRunning
            DebugLogger.shared.log("A11Y", "VoiceOver=\(isVoiceOverRunning ? "ON" : "OFF")")
        }
        .onChange(of: screenState) { _, newState in
            DebugLogger.shared.log("STATE", "\(newState.rawValue.uppercased())")
            // VoiceOver 포커스를 새 화면 최상단 요소로 이동시킴
            UIAccessibility.post(notification: .screenChanged, argument: nil)
        }
        .onAppear {
            DebugLogger.shared.log("A11Y", "VoiceOver=\(isVoiceOverRunning ? "ON" : "OFF")")
            DebugLogger.shared.log("STATE", screenState.rawValue.uppercased())
        }
    }

    // MARK: - State Resolution

    private enum ScreenState: String, Equatable {
        case idle, listening, searching, results, navigating
    }

    private var screenState: ScreenState {
        if navVM.isNavigating { return .navigating }
        switch navVM.voiceFlowStage {
        case .idle:
            return navVM.searchResults.isEmpty ? .idle : .results
        case .listening:          return .listening
        case .searching:          return .searching
        case .results:            return .results
        case .startingNavigation: return .results
        }
    }

    @ViewBuilder
    private var stateContent: some View {
        switch screenState {
        case .idle:       idleView
        case .listening:  listeningView
        case .searching:  searchingView
        case .results:    resultsView
        case .navigating: navigatingView
        }
    }

    // MARK: - IDLE

    private var idleView: some View {
        Text("화면을 2초간\n길게 눌러주세요")
            .font(.system(size: 56, weight: .bold))
            .foregroundColor(.yellow)
            .multilineTextAlignment(.center)
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            // VoiceOver OFF: 2초 길게 누르면 STT
            .onLongPressGesture(minimumDuration: 2.0) {
                DebugLogger.shared.log("GESTURE", "long-press → start STT")
                navVM.startVoiceDestinationFlow()
            }
            // VoiceOver ON: 한 번 탭으로 포커스, 두 번 탭으로 활성화
            .accessibilityElement()
            .accessibilityLabel("음성 안내 시작")
            .accessibilityHint("두 번 탭하여 목적지를 말씀하세요. 또는 두 손가락으로 더블탭하세요.")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction {
                DebugLogger.shared.log("GESTURE", "VoiceOver activate → start STT")
                navVM.startVoiceDestinationFlow()
            }
    }

    // MARK: - LISTENING

    private var listeningView: some View {
        VStack(spacing: 24) {
            Image(systemName: "mic.fill")
                .font(.system(size: 96))
                .foregroundColor(.red)

            Text("듣는 중…")
                .font(.system(size: 48, weight: .bold))
                .foregroundColor(.yellow)

            if !deps.stt.partialText.isEmpty {
                Text(deps.stt.partialText)
                    .font(.system(size: 28))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }

            Button {
                DebugLogger.shared.log("GESTURE", "cancel listening")
                navVM.cancelVoiceDestinationFlow()
            } label: {
                Text("취소")
                    .font(.title2.bold())
                    .padding(.horizontal, 40)
                    .padding(.vertical, 16)
                    .background(Color.gray)
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .accessibilityLabel("취소")
            .accessibilityHint("두 번 탭하여 음성 인식을 중단합니다")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
    }

    // MARK: - SEARCHING

    private var searchingView: some View {
        VStack(spacing: 24) {
            ProgressView()
                .scaleEffect(2.5)
                .tint(.yellow)
            Text("검색 중…")
                .font(.system(size: 48, weight: .bold))
                .foregroundColor(.yellow)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("목적지를 검색하는 중입니다")
    }

    // MARK: - RESULTS (3 stacked yellow buttons)

    private var resultsView: some View {
        VStack(spacing: 2) {
            ForEach(Array(navVM.searchResults.prefix(3).enumerated()), id: \.offset) { idx, poi in
                Button {
                    let name = String(describing: poi.name)
                    DebugLogger.shared.log("PICK", "[\(idx)] \(name)")
                    Task { await navVM.startNavigation(to: poi) }
                } label: {
                    VStack(spacing: 8) {
                        Text(String(describing: poi.name))
                            .font(.system(size: 36, weight: .bold))
                            .multilineTextAlignment(.center)
                            .foregroundColor(.black)
                        Text("\(distanceM(to: poi))미터")
                            .font(.system(size: 32, weight: .semibold))
                            .foregroundColor(.black)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.yellow)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(String(describing: poi.name)), \(distanceM(to: poi))미터")
                .accessibilityHint("두 번 탭하여 이곳으로 안내를 시작합니다")
                .accessibilityAddTraits(.isButton)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - NAVIGATING

    private var navigatingView: some View {
        VStack(spacing: 20) {
            Spacer()

            Text(navVM.guidanceMessage.isEmpty ? "안내 준비 중…" : navVM.guidanceMessage)
                .font(.system(size: 32, weight: .bold))
                .foregroundColor(.yellow)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
                .accessibilityLabel(navVM.guidanceMessage)

            HStack(spacing: 12) {
                Image(systemName: "figure.walk")
                Text(distanceText)
            }
            .font(.system(size: 56, weight: .bold))
            .foregroundColor(.white)
            .accessibilityLabel("남은 거리 \(distanceText)")

            if deps.headingProvider.isDrifting {
                let direction = deps.headingProvider.driftDegrees > 0 ? "오른쪽" : "왼쪽"
                Text("\(direction)으로 \(Int(abs(deps.headingProvider.driftDegrees)))° 벗어남")
                    .font(.title2.bold())
                    .foregroundColor(.orange)
            }

            Spacer()

            Button {
                DebugLogger.shared.log("GESTURE", "stop navigation")
                navVM.stopNavigation()
            } label: {
                Text("안내 종료")
                    .font(.title2.bold())
                    .padding(.horizontal, 40)
                    .padding(.vertical, 16)
                    .background(Color.red)
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .accessibilityLabel("안내 종료")
            .accessibilityHint("두 번 탭하여 현재 안내를 멈춥니다")
            .padding(.bottom, 20)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Helpers

    private var distanceText: String {
        let d = navVM.distanceToDestination
        if d.isInfinite || d.isNaN || d > 100_000 {
            return "--m"
        }
        return "\(Int(d))m"
    }

    private func distanceM(to poi: POIResult) -> Int {
        guard let loc = deps.locationTracker.currentLocation else { return 0 }
        let from = CLLocation(latitude: loc.latitude, longitude: loc.longitude)
        let to = CLLocation(latitude: poi.lat, longitude: poi.lon)
        return Int(from.distance(from: to))
    }

    private func handleMagicTap() {
        DebugLogger.shared.log("GESTURE", "MagicTap (stage=\(navVM.voiceFlowStage))")
        switch navVM.voiceFlowStage {
        case .idle:
            if navVM.isNavigating {
                navVM.stopNavigation()
            } else {
                navVM.startVoiceDestinationFlow()
            }
        case .listening:
            navVM.cancelVoiceDestinationFlow()
        case .searching, .results, .startingNavigation:
            navVM.cancelVoiceDestinationFlow()
            navVM.startVoiceDestinationFlow()
        }
    }
}
