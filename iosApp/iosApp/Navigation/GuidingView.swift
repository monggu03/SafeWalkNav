//
//  GuidingView.swift
//  iosApp
//
//  §4-2/§4-3 경로 안내 화면(.guiding).
//  목적지 이름 + 남은 거리를 큰 글씨로. 음성이 주 채널이고 화면은 보조.
//  방향 안내는 넣지 않는다(§1 제외 항목).
//

import SwiftUI

struct GuidingView: View {
    @EnvironmentObject var coordinator: NavigationCoordinator

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 20) {
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

                Spacer()
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        let name = coordinator.destinationName ?? "목적지"
        if let remaining = coordinator.remainingText {
            return "\(name)로 안내 중. \(remaining)."
        }
        return "\(name)로 안내 중."
    }
}
