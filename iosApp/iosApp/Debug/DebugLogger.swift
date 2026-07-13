//
//  DebugLogger.swift
//  iosApp
//

import Foundation
import SwiftUI
import Combine

/// 화면에 표시할 디버그 로그 한 줄
struct LogEntry: Identifiable {
    let id = UUID()
    let timestamp: Date
    let tag: String
    let message: String
    let level: Level

    enum Level {
        case info, warn, error
        var color: Color {
            switch self {
            case .info:  return .white
            case .warn:  return .yellow
            case .error: return .red
            }
        }
    }
}

/// 앱 전역에서 공유하는 로거 (싱글턴)
final class DebugLogger: ObservableObject {        // ⭐ ObservableObject 채택
    static let shared = DebugLogger()

    @Published private(set) var entries: [LogEntry] = []   // ⭐ @Published 필수
    // StdoutCapture 로 print/println 까지 모두 들어오면 양이 커지므로 여유 있게 보관.
    private let maxCount = 200

    private init() {}

    /// StdoutCapture 가 stdout/stderr 에서 가로챈 한 줄을 화면에만 push.
    /// 콘솔에는 이미 tee 로 흘러갔으므로 다시 print 하지 않는다.
    func appendCapturedLine(_ line: String) {
        append(LogEntry(timestamp: Date(), tag: "", message: line, level: .info))
    }

    func clear() {
        DispatchQueue.main.async {
            self.entries.removeAll()
        }
    }

    private func append(_ entry: LogEntry) {
        // 파일 로그가 활성 상태일 때 같은 줄을 디스크에도 떨궤준다.
        // (start/close 는 NavigationViewModel 에서 안내 lifecycle 에 맞춰 호출)
        let fileLine = entry.tag.isEmpty ? entry.message : "[\(entry.tag)] \(entry.message)"
        NavLogFile.shared.append(fileLine)

        DispatchQueue.main.async {
            self.entries.append(entry)
            if self.entries.count > self.maxCount {
                self.entries.removeFirst(self.entries.count - self.maxCount)
            }
        }
    }
}
