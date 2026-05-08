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
    private let maxCount = 50

    private init() {}

    func log(_ tag: String, _ message: String, level: LogEntry.Level = .info) {
        let entry = LogEntry(
            timestamp: Date(),
            tag: tag,
            message: message,
            level: level
        )

        DispatchQueue.main.async {
            self.entries.append(entry)
            if self.entries.count > self.maxCount {
                self.entries.removeFirst(self.entries.count - self.maxCount)
            }
        }

        print("[\(tag)] \(message)")
    }

    func clear() {
        DispatchQueue.main.async {
            self.entries.removeAll()
        }
    }
}
