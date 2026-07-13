//
//  NavLogFile.swift
//  iosApp
//
//  외출 1회 단위로 파일에 로그 누적. Android 의 MainActivity.startNavLog / appendNavLog /
//  closeNavLog 와 같은 포맷으로 떨궈서 같은 파서/그래프 스크립트로 양쪽 데이터를 다룰 수 있게 한다.
//
//  파일 위치: <Documents>/walk_logs/walk_<yyyyMMdd_HHmmss>.log
//      → Files 앱(내 iPhone → iosApp → walk_logs) 또는 Xcode → Devices → Download Container 로 추출
//
//  파일 포맷 (Android 와 1:1):
//      === SafeWalkNav 외출 로그 시작 <Date> ===
//      [HH:mm:ss.SSS] <message>
//      ...
//      [HH:mm:ss.SSS] === 종료 ===
//

import Foundation

final class NavLogFile {
    static let shared = NavLogFile()

    private let ioQueue = DispatchQueue(label: "com.safewalknav.navlogfile", qos: .utility)
    private var handle: FileHandle?
    private var currentPath: String?

    private let lineFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private let fileNameFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f
    }()

    private init() {}

    /// 안내 시작 시 호출. 이미 열려있으면 먼저 닫고 새 파일을 연다.
    func start() {
        ioQueue.async { [weak self] in
            guard let self else { return }
            self.closeLocked()

            let fm = FileManager.default
            guard let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first else {
                NSLog("[NavLogFile] Documents 디렉토리 없음 — 파일 로그 비활성")
                return
            }
            let dir = docs.appendingPathComponent("walk_logs", isDirectory: true)
            do {
                try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            } catch {
                NSLog("[NavLogFile] dir 생성 실패: \(error)")
                return
            }

            let ts = self.fileNameFormatter.string(from: Date())
            let url = dir.appendingPathComponent("walk_\(ts).log")
            fm.createFile(atPath: url.path, contents: nil)

            guard let h = try? FileHandle(forWritingTo: url) else {
                NSLog("[NavLogFile] FileHandle 열기 실패: \(url.path)")
                return
            }
            self.handle = h
            self.currentPath = url.path

            let header = "=== SafeWalkNav 외출 로그 시작 \(Date()) ===\n"
            if let data = header.data(using: .utf8) {
                h.write(data)
            }
            NSLog("[NavLogFile] open: \(url.path)")
        }
    }

    /// 캡처된 한 줄을 파일에 append. 활성 상태 아니면 무시.
    func append(_ msg: String) {
        ioQueue.async { [weak self] in
            guard let self, let h = self.handle else { return }
            let line = "[\(self.lineFormatter.string(from: Date()))] \(msg)\n"
            guard let data = line.data(using: .utf8) else { return }
            h.write(data)
        }
    }

    /// 안내 종료 시 호출.
    func close() {
        ioQueue.async { [weak self] in
            self?.closeLocked()
        }
    }

    /// 반드시 ioQueue 안에서만 호출.
    private func closeLocked() {
        guard let h = handle else { return }
        let trailer = "[\(lineFormatter.string(from: Date()))] === 종료 ===\n"
        if let data = trailer.data(using: .utf8) {
            h.write(data)
        }
        try? h.close()
        if let p = currentPath {
            NSLog("[NavLogFile] close: \(p)")
        }
        handle = nil
        currentPath = nil
    }
}
