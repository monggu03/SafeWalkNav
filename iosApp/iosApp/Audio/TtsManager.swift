//
//  TtsManager.swift
//  iosApp
//
//  음성 안내(TTS) 매니저
//  - AVSpeechSynthesizer 래핑
//  - 한국어 음성으로 안내 메시지 출력
//  - 같은 메시지 반복 방지 (debounce)
//

import Foundation
import AVFoundation
import Combine

/// 시각장애인용 음성 안내 매니저
final class TtsManager: NSObject, ObservableObject {

    // MARK: - Published State
    /// 현재 음성 출력 중인지 여부 (UI에서 확인 가능)
    @Published private(set) var isSpeaking: Bool = false

    // MARK: - Private Properties
    private let synthesizer = AVSpeechSynthesizer()

    /// 직전에 말한 텍스트 (중복 방지용)
    private var lastSpokenText: String = ""

    /// 직전에 말한 시각
    private var lastSpeakTime: Date = .distantPast

    /// 같은 메시지를 다시 말하기까지의 최소 간격 (초)
    private let minRepeatInterval: TimeInterval = 3.0

    // MARK: - Init
    override init() {
        super.init()
        synthesizer.delegate = self
        configureAudioSession()
    }

    // MARK: - Audio Session 설정
    /// 다른 앱 소리(예: 음악)와 섞여서 재생되도록 설정
    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playback,
                mode: .voicePrompt,         // 음성 안내용 모드 (덕킹 자동 처리)
                options: [.mixWithOthers, .duckOthers]
            )
            try session.setActive(true)
        } catch {
            print("[TtsManager] 오디오 세션 설정 실패: \(error)")
        }
    }

    // MARK: - Public API

    /// 텍스트를 한국어 음성으로 출력
    /// - Parameters:
    ///   - text: 말할 내용
    ///   - priority: .high이면 현재 출력 중인 음성을 끊고 즉시 재생
    func speak(_ text: String, priority: Priority = .normal) {
        // 1. 빈 문자열은 무시
        guard !text.isEmpty else { return }

        // 2. 같은 메시지 반복 방지
        if shouldSkipDuplicate(text: text) {
            return
        }

        // 3. high priority면 기존 음성 중단
        if priority == .high && synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }

        // 4. 현재 말하고 있으면 큐에 쌓이도록 그냥 둠 (normal priority)
        // AVSpeechSynthesizer는 자동으로 큐잉 처리

        // 5. 발화
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
        utterance.rate = 0.5            // 0.0(느림) ~ 1.0(빠름), 기본 0.5
        utterance.pitchMultiplier = 1.0 // 0.5 ~ 2.0, 1.0이 기본
        utterance.volume = 1.0

        synthesizer.speak(utterance)

        // 6. 상태 갱신
        lastSpokenText = text
        lastSpeakTime = Date()
    }

    /// 현재 음성 즉시 중단
    func stop() {
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
    }

    // MARK: - Private Helpers

    /// 같은 메시지를 너무 빨리 다시 말하려는지 확인
    private func shouldSkipDuplicate(text: String) -> Bool {
        let now = Date()
        let isSameText = (text == lastSpokenText)
        let timeSinceLast = now.timeIntervalSince(lastSpeakTime)

        return isSameText && timeSinceLast < minRepeatInterval
    }
}

// MARK: - Priority
extension TtsManager {
    enum Priority {
        case normal  // 큐에 쌓임
        case high    // 즉시 출력 (기존 발화 중단)
    }
}

// MARK: - AVSpeechSynthesizerDelegate
extension TtsManager: AVSpeechSynthesizerDelegate {
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                           didStart utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = true
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                           didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = false
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                           didCancel utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = false
        }
    }
}
