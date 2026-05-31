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
    ///   - priority: 호환용 인자. 현재는 큐잉 정책 통일로 .high 도 끊지 않고 뒤에 붙는다.
    func speak(_ text: String, priority: Priority = .normal) {
        // 1. 빈 문자열은 무시
        guard !text.isEmpty else { return }

        // 2. 같은 메시지 반복 방지
        if shouldSkipDuplicate(text: text) {
            return
        }

        // 3. 큐잉 정책 — 현재 발화 중이면 native 큐 뒤에 붙는다.
        //    AVSpeechSynthesizer 는 직전 utterance 의 didFinish 후에야 다음 utterance 를 시작하므로
        //    멘트가 중간에 잘리지 않는다. priority 인자는 호환을 위해 남기되 끊지 않는다.
        //    (자기 목소리가 마이크로 들어가는 걸 막아야 하는 STT 진입 등은 명시적 stop() 사용)

        // 4. 발화
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
        utterance.rate = 0.5            // 0.0(느림) ~ 1.0(빠름), 기본 0.5
        utterance.pitchMultiplier = 1.0 // 0.5 ~ 2.0, 1.0이 기본
        utterance.volume = 1.0

        synthesizer.speak(utterance)

        // 5. 상태 갱신
        lastSpokenText = text
        lastSpeakTime = Date()
    }

    /// 선점 발화 — 현재 발화와 큐를 끊고 즉시 출력.
    /// 회전 직전(IMMINENT) "지금 …" 처럼 타이밍 생명선 안내에만 사용.
    ///
    /// 일반 speak() 와 차이:
    ///   - 3초 중복 필터(shouldSkipDuplicate)를 우회 — 직전은 무조건 발화.
    ///   - stopSpeaking(.immediate) 로 현재+큐 비움 (대기 안내가 있으면 같이 사라지는 점 인지).
    func speakImmediately(_ text: String) {
        guard !text.isEmpty else { return }

        // 큐 전체 비우기 — 대기 중인 횡단보도 등 안내가 있으면 함께 사라진다.
        synthesizer.stopSpeaking(at: .immediate)

        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
        utterance.rate = 0.5
        utterance.pitchMultiplier = 1.0
        utterance.volume = 1.0
        synthesizer.speak(utterance)

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
