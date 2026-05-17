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

    /// 싱글턴 강제 — 외부 인스턴스 생성 차단.
    private init() {}

    /// 로그 한 줄 추가. UI 갱신을 위해 main 큐로 디스패치하고,
    /// 동시에 stdout(`print`)에도 출력해 Xcode 콘솔에서 확인 가능.
    /// maxCount(50) 초과 시 오래된 항목부터 제거.
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

    /// 누적된 로그 전부 비움 (오버레이의 Clear 버튼에서 호출).
    func clear() {
        DispatchQueue.main.async {
            self.entries.removeAll()
        }
    }
}
