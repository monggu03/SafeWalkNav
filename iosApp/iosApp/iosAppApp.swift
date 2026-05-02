//
//  iosAppApp.swift
//  iosApp
//

import SwiftUI

@main
struct iosAppApp: App {
    /// 앱 전체에서 공유되는 의존성 컨테이너
    @StateObject private var deps = AppDependencies()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(deps)
                .environmentObject(deps.navigationViewModel)
        }
    }
}
