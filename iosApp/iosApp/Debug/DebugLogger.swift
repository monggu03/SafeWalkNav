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

    /// 명시적 태그/레벨 로그 — 호출자가 직접 사용.
    func log(_ tag: String, _ message: String, level: LogEntry.Level = .info) {
        append(LogEntry(timestamp: Date(), tag: tag, message: message, level: level))

        // StdoutCapture 가 켜진 뒤에는 여기서 print() 하면 파이프 → 다시 화면 push 되는
        // 무한 루프(=이중 표시) 가 된다. 캡처가 알아서 콘솔로 흘려주므로 생략.
        if !StdoutCapture.isActive {
            print("[\(tag)] \(message)")
        }
    }

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
        DispatchQueue.main.async {
            self.entries.append(entry)
            if self.entries.count > self.maxCount {
                self.entries.removeFirst(self.entries.count - self.maxCount)
            }
        }
    }
}
