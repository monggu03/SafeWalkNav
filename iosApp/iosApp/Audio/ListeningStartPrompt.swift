import AVFoundation
import UIKit

/// 안내 발화가 완료되기 전에는 녹음을 시작하지 않는다.
@MainActor
final class ListeningStartPrompt: NSObject, AVSpeechSynthesizerDelegate {
    static let message = "듣기를 시작합니다. 안내가 끝나면 목적지를 말씀해 주세요."

    private let synthesizer = AVSpeechSynthesizer()
    private let center: NotificationCenter
    private let voiceOverRunning: @MainActor () -> Bool
    private let postAnnouncement: @MainActor (String) -> Void
    private let timeoutNanoseconds: UInt64
    private var completion: CheckedContinuation<Bool, Never>?
    private var requestID: UUID?
    private var observers: [NSObjectProtocol] = []
    private var timeout: Task<Void, Never>?
    private var activeUtteranceID: ObjectIdentifier?

    init(center: NotificationCenter = .default,
         voiceOverRunning: @escaping @MainActor () -> Bool = { UIAccessibility.isVoiceOverRunning },
         postAnnouncement: @escaping @MainActor (String) -> Void = {
             UIAccessibility.post(notification: .announcement, argument: $0)
         },
         timeoutNanoseconds: UInt64 = 20_000_000_000) {
        self.center = center
        self.voiceOverRunning = voiceOverRunning
        self.postAnnouncement = postAnnouncement
        self.timeoutNanoseconds = timeoutNanoseconds
        super.init()
        synthesizer.delegate = self
    }

    func play() async -> Bool {
        cancel()
        let id = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                guard !Task.isCancelled else {
                    continuation.resume(returning: false)
                    return
                }
                requestID = id
                completion = continuation
                timeout = Task { [weak self] in
                    guard let self else { return }
                    do { try await Task.sleep(nanoseconds: self.timeoutNanoseconds) }
                    catch { return }
                    self.finish(id: id, success: false)
                }
                if voiceOverRunning() {
                    // 짧은 발화의 완료도 놓치지 않도록 발화 전에 알림을 구독한다.
                    observers.append(center.addObserver(
                        forName: UIAccessibility.announcementDidFinishNotification,
                        object: nil, queue: .main
                    ) { [weak self] notification in
                        let text = notification.userInfo?[UIAccessibility.announcementStringValueUserInfoKey] as? String
                        let success = notification.userInfo?[UIAccessibility.announcementWasSuccessfulUserInfoKey] as? Bool ?? false
                        Task { @MainActor [weak self] in
                            guard text == Self.message else { return }
                            self?.finish(id: id, success: success)
                        }
                    })
                    observers.append(center.addObserver(
                        forName: UIAccessibility.voiceOverStatusDidChangeNotification,
                        object: nil, queue: .main
                    ) { [weak self] _ in
                        Task { @MainActor [weak self] in self?.finish(id: id, success: false) }
                    })
                    postAnnouncement(Self.message)
                } else {
                    let utterance = AVSpeechUtterance(string: Self.message)
                    utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
                    utterance.rate = 0.5
                    activeUtteranceID = ObjectIdentifier(utterance)
                    synthesizer.speak(utterance)
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in self?.finish(id: id, success: false) }
        }
    }

    func cancel() {
        guard let id = requestID else { return }
        finish(id: id, success: false)
    }

    private func finish(id: UUID, success: Bool) {
        guard requestID == id else { return }
        requestID = nil
        timeout?.cancel()
        timeout = nil
        observers.forEach { center.removeObserver($0) }
        observers.removeAll()
        activeUtteranceID = nil
        let pending = completion
        completion = nil
        if !success { synthesizer.stopSpeaking(at: .immediate) }
        pending?.resume(returning: success)
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let utteranceID = ObjectIdentifier(utterance)
        Task { @MainActor [weak self] in
            guard let self, self.activeUtteranceID == utteranceID, let id = self.requestID else { return }
            self.finish(id: id, success: true)
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        let utteranceID = ObjectIdentifier(utterance)
        Task { @MainActor [weak self] in
            guard let self, self.activeUtteranceID == utteranceID, let id = self.requestID else { return }
            self.finish(id: id, success: false)
        }
    }
}
