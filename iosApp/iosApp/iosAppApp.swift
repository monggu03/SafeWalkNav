//
//  iosAppApp.swift
//  iosApp
//

import SwiftUI

@main
struct iosAppApp: App {
    /// 앱 전체에서 공유되는 의존성 컨테이너
    @StateObject private var deps = AppDependencies()

    init() {
        // 모든 print / Kotlin println 을 화면 DebugLogOverlay 에도 흘려보냄.
        // 반드시 AppDependencies 초기화나 다른 로깅 이전에 호출.
        StdoutCapture.setUp()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(deps)
                .environmentObject(deps.navigationViewModel)
        }
    }
}
