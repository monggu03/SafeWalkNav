//
//  HeadingGuideView.swift
//  iosApp
//
//  초기 방향 안내 UI. HeadingGuide 의 state 에 따라 시각 피드백을 보여준다.
//  실제 사용자는 시각장애인이지만, 시연/디버그 및 동행인용으로 시각 표시도 제공.
//

import SwiftUI
import shared

struct HeadingGuideView: View {
    @StateObject private var guide: HeadingGuide
    @Environment(\.dismiss) private var dismiss

    private let currentLocation: GpsLocation
    private let firstWaypoint: Waypoint
    /// state == .ready 도달 시 호출. 데모/실서비스 흐름에서 다음 단계로 넘어갈 때 사용.
    private let onReady: () -> Void

    /// HeadingGuide(StateObject)를 내부에서 생성하며, 출발 전 단계에 필요한
    /// 위치/첫 waypoint/완료 콜백을 주입받는다.
    init(
        tts: TtsManager,
        currentLocation: GpsLocation,
        firstWaypoint: Waypoint,
        onReady: @escaping () -> Void = {},
    ) {
        _guide = StateObject(wrappedValue: HeadingGuide(tts: tts))
        self.currentLocation = currentLocation
        self.firstWaypoint = firstWaypoint
        self.onReady = onReady
    }

    var body: some View {
        VStack(spacing: 24) {
            stateIcon
                .font(.system(size: 80))
                .foregroundColor(stateColor)
                .padding(.top, 40)

            Text(guide.currentMessage.isEmpty ? "준비 중..." : guide.currentMessage)
                .font(.title3)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            // 현재 heading / 목표 bearing 디버그 표시
            GroupBox {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("현재 방향")
                        Spacer()
                        Text(headingText)
                            .fontWeight(.bold)
                    }
                    HStack {
                        Text("목표 방향")
                        Spacer()
                        Text("\(Int(guide.targetBearing))°")
                            .fontWeight(.bold)
                    }
                    HStack {
                        Text("상태")
                        Spacer()
                        Text(stateText).fontWeight(.bold).foregroundColor(stateColor)
                    }
                }
                .font(.system(.body, design: .monospaced))
            }
            .padding(.horizontal)

            Spacer()

            HStack(spacing: 12) {
                Button("종료") {
                    guide.stop()
                    dismiss()
                }
                .buttonStyle(.bordered)

                if guide.state == .ready {
                    Button("출발") {
                        guide.stop()
                        onReady()
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding()
        }
        .navigationTitle("초기 방향 안내")
        .onAppear {
            guide.start(currentLocation: currentLocation, firstWaypoint: firstWaypoint)
        }
        .onDisappear {
            guide.stop()
        }
    }

    // MARK: - View Helpers

    /// 현재 guide.state에 맞는 SF Symbol 아이콘을 그린다 (자세 대기/방향 대기/준비 완료).
    private var stateIcon: some View {
        switch guide.state {
        case .waitingForFlatPose:
            return Image(systemName: "hand.raised")
        case .waitingForHeadingMatch:
            return Image(systemName: "arrow.up.circle")
        case .ready:
            return Image(systemName: "checkmark.circle.fill")
        }
    }

    /// 상태별 강조 색상 (자세 대기 = 주황, 방향 대기 = 파랑, ready = 초록).
    private var stateColor: Color {
        switch guide.state {
        case .waitingForFlatPose: return .orange
        case .waitingForHeadingMatch: return .blue
        case .ready: return .green
        }
    }

    /// 상태를 한국어 라벨로 변환 (디버그 GroupBox용).
    private var stateText: String {
        switch guide.state {
        case .waitingForFlatPose: return "자세 대기"
        case .waitingForHeadingMatch: return "방향 대기"
        case .ready: return "출발 가능"
        }
    }

    /// 현재 trueHeading을 도(°) 표기로 변환. -1(보정 필요)은 안내 문구로 표시.
    private var headingText: String {
        guide.currentHeading < 0 ? "보정 필요" : "\(Int(guide.currentHeading))°"
    }
}
