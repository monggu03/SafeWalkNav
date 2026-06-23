//
//  SafetyNoticeView.swift
//  iosApp
//
//  첫 실행 안전 고지 게이트.
//  - AppRootView: 동의 여부에 따라 고지 / 차단 / 본 앱(ContentView) 분기
//  - SafetyNoticeView: 보조 시스템 고지 + 동의/비동의 버튼
//  - ConsentBlockedView: 비동의 시 차단 화면 + "다시 고지 보기"
//  시각장애인 대상 — 진입 시 VoiceOver 자동 포커스로 음성이 바로 나오게 한다.
//

import SwiftUI

// MARK: - 앱 진입 게이트
struct AppRootView: View {
    /// 동의 1회 통과 여부 (영구 저장). 앱 삭제 전까지 유지.
    @AppStorage("hasAgreedToSafetyNotice") private var hasAgreed = false
    /// 비동의 상태 (이번 실행 한정, 저장 안 함). 재실행하면 고지가 다시 뜬다.
    @State private var declined = false

    var body: some View {
        if hasAgreed {
            ContentView()                       // 기존 음성인식 네비게이션 (변경 없음)
        } else if declined {
            ConsentBlockedView(onReconsider: { declined = false })
        } else {
            SafetyNoticeView(
                onAgree:   { hasAgreed = true },
                onDecline: { declined = true }
            )
        }
    }
}

// MARK: - 고지 화면
struct SafetyNoticeView: View {
    let onAgree: () -> Void
    let onDecline: () -> Void
    @AccessibilityFocusState private var noticeFocused: Bool

    private let noticeText = "SafeWalkNav는 보행을 돕는 보조 안내 시스템입니다. 실제 도로 상황과 다를 수 있으니, 흰지팡이와 주변 소리 등 평소의 판단을 함께 사용해 주세요. 안전한 이동의 최종 판단은 사용자 본인에게 있습니다."

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text(noticeText)
                .font(.system(size: 24, weight: .semibold))
                .foregroundColor(.white)
                .multilineTextAlignment(.leading)
                .padding(.horizontal, 24)
                .accessibilityFocused($noticeFocused)
            Spacer()

            Button(action: onAgree) {
                Text("동의하고 시작하기")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(.black)
                    .frame(maxWidth: .infinity, minHeight: 88)
                    .background(Color(hex: 0xFFD700))
            }
            .accessibilityLabel("동의하고 시작하기")
            .accessibilityHint("두 번 탭하면 안내를 동의하고 앱을 시작합니다.")

            Button(action: onDecline) {
                Text("동의하지 않음")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, minHeight: 72)
                    .background(Color.white.opacity(0.15))
            }
            .accessibilityLabel("동의하지 않음")
            .accessibilityHint("두 번 탭하면 동의하지 않고 종료 안내 화면으로 이동합니다.")
        }
        .background(Color.black)
        .ignoresSafeArea()
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { noticeFocused = true }
        }
    }
}

// MARK: - 비동의 차단 화면
struct ConsentBlockedView: View {
    let onReconsider: () -> Void
    @AccessibilityFocusState private var messageFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text("동의하지 않으셔서 앱을 사용하실 수 없습니다.")
                .font(.system(size: 26, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .accessibilityFocused($messageFocused)
            Spacer()

            Button(action: onReconsider) {
                Text("다시 고지 보기")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(.black)
                    .frame(maxWidth: .infinity, minHeight: 88)
                    .background(Color(hex: 0xFFD700))
            }
            .accessibilityLabel("다시 고지 보기")
            .accessibilityHint("두 번 탭하면 안내 고지 화면으로 돌아갑니다.")
        }
        .background(Color.black)
        .ignoresSafeArea()
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { messageFocused = true }
        }
    }
}
