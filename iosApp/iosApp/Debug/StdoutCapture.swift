//
//  StdoutCapture.swift
//  iosApp
//
//  stdout/stderr 를 파이프로 가로채서 DebugLogger 로 미러링한다.
//  원래 Xcode 콘솔에도 그대로 흘려보내므로(=tee) 양쪽 모두 볼 수 있다.
//
//  앱 시작 시 한 번 setUp() 만 호출하면 그 뒤에 발생하는
//   - Swift print(...)
//   - Kotlin println(...) (KMP shared 모듈 포함)
//   - NSLog (일부)
//  가 모두 화면 하단 DebugLogOverlay 에 나타난다.
//

import Foundation
import Darwin

enum StdoutCapture {

    /// setUp() 이 호출됐는지 여부. DebugLogger 가 자기 로그를 다시 print 해서 무한 루프 도는 걸
    /// 막기 위해 참조한다.
    private(set) static var isActive: Bool = false

    private static var originalStdoutFD: Int32 = -1
    private static var originalStderrFD: Int32 = -1

    /// Pipe 인스턴스를 앱 수명만큼 강제 보관.
    /// 로컬 변수로 두면 ARC 가 setUp() 리턴 시점에 해제 → read end 가 닫히고
    /// 그 후 stdout 쓰기가 SIGPIPE 를 발생시켜 앱이 종료된다 (signal 13).
    private static var retainedPipes: [Pipe] = []

    /// 앱 시작 직후 한 번만 호출. 두 번째 호출은 무시.
    static func setUp() {
        guard !isActive else { return }
        isActive = true

        // 혹시라도 broken pipe 가 생기면 SIGPIPE 로 죽지 말고 write() 가 EPIPE 만 돌려주도록.
        // (위 retainedPipes 만으로 충분하지만 이중 안전장치)
        signal(SIGPIPE, SIG_IGN)

        // 원래 fd 를 복제해서 보관 — 캡처한 데이터를 여기로 다시 흘려보내야 Xcode 콘솔이 살아있음.
        originalStdoutFD = dup(fileno(stdout))
        originalStderrFD = dup(fileno(stderr))

        // line-buffered 가 기본인데, 캡처 직후 즉시 화면에 뜨도록 unbuffered 로 강제.
        setvbuf(stdout, nil, _IONBF, 0)
        setvbuf(stderr, nil, _IONBF, 0)

        installPipe(redirectFD: fileno(stdout), teeFD: originalStdoutFD)
        installPipe(redirectFD: fileno(stderr), teeFD: originalStderrFD)
    }

    /// 지정된 fd 로 가던 출력을 파이프로 돌려서, 한쪽은 원래 fd 로 tee, 다른 한쪽은
    /// 라인 단위로 DebugLogger 에 push 한다.
    private static func installPipe(redirectFD: Int32, teeFD: Int32) {
        let pipe = Pipe()
        retainedPipes.append(pipe)
        dup2(pipe.fileHandleForWriting.fileDescriptor, redirectFD)

        // chunk 사이에 라인이 잘릴 수 있으므로 partial 라인을 누적할 버퍼.
        var partialBuffer = ""
        let lock = NSLock()

        pipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            guard !data.isEmpty else { return }

            // 1) 원래 fd 로 그대로 흘려서 Xcode 콘솔 출력 유지.
            data.withUnsafeBytes { raw in
                if let base = raw.baseAddress {
                    _ = write(teeFD, base, data.count)
                }
            }

            // 2) UTF-8 디코드 후 줄 단위로 DebugLogger 에 push.
            guard let chunk = String(data: data, encoding: .utf8) else { return }

            lock.lock()
            partialBuffer += chunk
            let parts = partialBuffer.components(separatedBy: "\n")
            // 마지막 조각은 다음 chunk 와 이어질 수 있어 보관, 그 외엔 완성된 라인.
            partialBuffer = parts.last ?? ""
            let completed = parts.dropLast()
            lock.unlock()

            for line in completed where !line.isEmpty {
                DebugLogger.shared.appendCapturedLine(line)
            }
        }
    }
}
