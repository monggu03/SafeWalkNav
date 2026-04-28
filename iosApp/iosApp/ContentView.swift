// SafeWalk iOS — Placeholder
//
// 이 파일은 jiminlyy 가 Mac 환경에서 Xcode 프로젝트 셋업 후 본격 작업할 영역의
// 골격을 표시하기 위한 placeholder 입니다. 실제 SwiftUI 화면은 여기서 작성.
//
// 작업 시작 가이드: ../README.md 참조.

import SwiftUI
// import shared   // shared.framework 통합 후 활성화

struct ContentView: View {
    var body: some View {
        VStack(spacing: 24) {
            Text("SafeWalk")
                .font(.largeTitle)
                .bold()

            Text("iOS 앱 — 작업 진행 예정")
                .font(.headline)
                .foregroundColor(.secondary)

            Text("KMM shared 모듈을 import 해서\nNavigationManager 를 호출하는 구조로 구현")
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
