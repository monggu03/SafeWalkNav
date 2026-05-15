//
//  SttManager.swift
//  iosApp
//
//  음성 인식 매니저 (한국어, 버튼 푸시-투-토크 방식)
//  - 버튼 누름 → startListening()
//  - 사용자 발화 종료 감지 시 finalResultPublisher로 결과 송출
//  - TtsManager와 오디오 세션 협력 (STT 시작 직전 TTS 강제 중지)
//

import Foundation
import Speech
import AVFoundation
import Combine

@MainActor
final class SttManager: ObservableObject {

    // MARK: - Published State (UI 바인딩용)
    /// 현재 듣고 있는 중인지
    @Published private(set) var isListening: Bool = false
    /// 부분 인식 결과 (실시간 자막 표시용)
    @Published private(set) var partialText: String = ""
    /// 권한 상태
    @Published private(set) var authorizationStatus: SFSpeechRecognizerAuthorizationStatus = .notDetermined
    /// 마지막 에러 메시지 (UI 표시 또는 TTS 안내용)
    @Published private(set) var lastError: String?

    // MARK: - 외부 결과 구독
    /// 최종 인식 결과 (isFinal 시점) — NavigationViewModel이 구독
    let finalResultPublisher = PassthroughSubject<String, Never>()

    // MARK: - Recognition 파이프라인
    private let speechRecognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()

    // MARK: - 협력 객체
    /// TTS 관리자 — STT 시작 직전 강제 중지 위해 의존
    private weak var tts: TtsManager?

    // MARK: - 안전장치 타이머
    /// 무음 상태에서 자동 종료 (사용자가 말 안 하면 5초 후 자동 stop)
    private var silenceTimer: Timer?
    private let silenceTimeout: TimeInterval = 5.0
    /// 최대 듣기 시간 (안전장치 — 무한 대기 방지)
    private var maxDurationTimer: Timer?
    private let maxDuration: TimeInterval = 15.0

    // MARK: - Init
    init(localeIdentifier: String = "ko-KR", tts: TtsManager? = nil) {
        self.speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: localeIdentifier))
        self.tts = tts
    }

    /// AppDependencies에서 순환 의존을 피하기 위해 init 후 주입
    func attach(tts: TtsManager) {
        self.tts = tts
    }

    // MARK: - 권한 요청
    /// 앱 시작 시 또는 첫 사용 직전에 호출
    func requestAuthorization() async -> Bool {
        // 1. Speech Recognition 권한
        let speechStatus = await withCheckedContinuation { (cont: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status)
            }
        }
        self.authorizationStatus = speechStatus
        guard speechStatus == .authorized else {
            lastError = "음성 인식 권한이 필요합니다."
            return false
        }

        // 2. 마이크 권한
        let micGranted = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            AVAudioApplication.requestRecordPermission { granted in
                cont.resume(returning: granted)
            }
        }
        guard micGranted else {
            lastError = "마이크 권한이 필요합니다."
            return false
        }

        return true
    }

    // MARK: - 시작
    /// 버튼 눌렀을 때 호출
    func startListening() {
        // 이미 동작 중이면 무시
        guard !isListening else { return }

        // 권한 체크
        guard authorizationStatus == .authorized else {
            lastError = "음성 인식 권한이 없습니다."
            Task { _ = await requestAuthorization() }
            return
        }
        guard let recognizer = speechRecognizer, recognizer.isAvailable else {
            lastError = "음성 인식기를 사용할 수 없습니다."
            return
        }

        // TTS 강제 중지 (자기 목소리가 마이크로 들어가는 것 방지)
        tts?.stop()
        
        do {
            try configureAudioSessionForRecording()
            try beginRecognition(with: recognizer)
            startTimers()
            isListening = true
            lastError = nil
        } catch {
            lastError = "음성 인식 시작 실패: \(error.localizedDescription)"
            cleanup()
        }
    }

    // MARK: - 중지
    /// 버튼에서 손 뗐거나, isFinal 도달했거나, 타임아웃 시 호출
    func stopListening() {
        guard isListening else { return }
        isListening = false
        cleanup()
        // 오디오 세션을 TTS 친화적인 모드로 되돌림
        try? configureAudioSessionForPlayback()
    }

    // MARK: - 내부: 인식 시작
    private func beginRecognition(with recognizer: SFSpeechRecognizer) throws {
        // 이전 task 정리
        recognitionTask?.cancel()
        recognitionTask = nil

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        // 온디바이스 처리 가능하면 사용 (네트워크 불필요, 프라이버시 우수)
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        self.recognitionRequest = request

        // 오디오 입력 노드 → request에 버퍼 전달
        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { buffer, _ in
            request.append(buffer)
        }

        audioEngine.prepare()
        try audioEngine.start()

        // 인식 task 시작
        recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self = self else { return }

            Task { @MainActor in
                if let result = result {
                    let text = result.bestTranscription.formattedString
                    self.partialText = text
                    // 사용자 발화가 들어오면 무음 타이머 리셋
                    self.resetSilenceTimer()

                    // 문장 종료 감지
                    if result.isFinal {
                        self.finalResultPublisher.send(text)
                        self.stopListening()
                    }
                }
                if let error = error {
                    // .canceled는 정상 종료 시 발생하므로 무시
                    let nsErr = error as NSError
                    if nsErr.domain == "kAFAssistantErrorDomain" && nsErr.code == 216 {
                        // 정상 종료, 무시
                    } else {
                        self.lastError = "음성 인식 오류: \(error.localizedDescription)"
                        self.stopListening()
                    }
                }
            }
        }
    }

    // MARK: - 오디오 세션 관리
    private func configureAudioSessionForRecording() throws {
        let session = AVAudioSession.sharedInstance()
        // .record + .measurement = STT 인식률에 최적화된 조합
        // .duckOthers = 백그라운드 음악 등을 일시적으로 줄임
        try session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    private func configureAudioSessionForPlayback() throws {
        // STT 종료 후 TTS가 다시 말할 수 있도록 .playback으로 복귀
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default, options: .duckOthers)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    // MARK: - 타이머
    private func startTimers() {
        resetSilenceTimer()
        // 최대 듣기 시간 — 도달 시 강제 종료
        maxDurationTimer = Timer.scheduledTimer(withTimeInterval: maxDuration, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.lastError = "음성 인식 시간 초과"
                self?.stopListening()
            }
        }
    }

    private func resetSilenceTimer() {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: silenceTimeout, repeats: false) { [weak self] _ in
            Task { @MainActor in
                // 무음 5초 → 자동 종료 (이미 들은 partial 결과가 있으면 그걸 final로 처리)
                if let self = self, !self.partialText.isEmpty {
                    self.finalResultPublisher.send(self.partialText)
                }
                self?.stopListening()
            }
        }
    }

    // MARK: - 정리
    private func cleanup() {
        silenceTimer?.invalidate()
        silenceTimer = nil
        maxDurationTimer?.invalidate()
        maxDurationTimer = nil

        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)

        recognitionRequest?.endAudio()
        recognitionRequest = nil

        recognitionTask?.cancel()
        recognitionTask = nil

        partialText = ""
    }

    deinit {
        // deinit는 nonisolated. cleanup은 main actor 격리이므로 직접 호출 불가.
        // 인스턴스 종료 시점엔 task가 곧 정리되므로 여기서는 엔진 정지만.
        audioEngine.stop()
    }
}
