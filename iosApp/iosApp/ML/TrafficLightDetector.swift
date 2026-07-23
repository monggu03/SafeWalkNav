//
//  TrafficLightDetector.swift
//  iosApp
//
//  신호등 감지 매니저
//  - best.mlpackage (커스텀 YOLO) 로 보행자 신호등 감지
//  - ped_red / ped_green 분류
//  - ⭐ 안전 판정은 shared 모듈의 SignalDecisionEngine 에 위임 (Android 와 동일 로직)
//  - TtsManager를 통해 음성 안내 + 햅틱
//  - ContentView에서 detections를 받아 바운딩 박스 표시
//
//  ─────────────────────────────────────────────────────────────────────────
//  2026-07 통일 작업:
//    예전 iOS 는 `confidence >= 0.5` 한 줄로 판정하고, 정적 초록에도 "건너세요"를
//    말하고, 점멸 감지·비대칭 방어·진동이 전부 없었다. Android 와 안전 동작이 달랐다.
//    이제 두 플랫폼이 shared/SignalDecisionEngine 하나를 공유한다:
//      - 카메라 텐서 추출(CoreML/Vision)만 플랫폼별로 남고,
//      - "지금 안내해도 되는가" 판정은 공유 엔진이 내린다.
//    → 신뢰도 비대칭(빨강0.25/초록0.45/R→G 0.55), 3프레임 안정성, 점멸 감지·락아웃,
//      heartbeat 반복, 정적 초록엔 "건너세요" 금지 — 전부 Android 와 동일하게 적용됨.
//  ─────────────────────────────────────────────────────────────────────────
//

import Foundation
import SwiftUI
import UIKit          // 햅틱 (UIImpactFeedbackGenerator)
import AVFoundation
import Vision
import CoreML
import Combine
import shared         // ⭐ SignalDecisionEngine, RawSignalDetection, SignalDecision...

// MARK: - Detection 모델
/// YOLO 감지 결과 1개를 표현 (UI 오버레이 표시용)
struct Detection: Identifiable {
    let id = UUID()
    let label: String
    let confidence: Float
    let boundingBox: CGRect  // Vision 정규화 좌표 (0~1)

    var color: Color {
        switch label {
        case "ped_green": return .green
        case "ped_red":   return .red
        default:          return .yellow
        }
    }
}

// MARK: - TrafficLightDetector
/// 카메라 프레임을 받아 CoreML YOLO 추론 → shared 엔진으로 신호 판정
final class TrafficLightDetector: NSObject, ObservableObject {

    // MARK: - Published State (UI 바인딩)
    @Published var statusText: String = "신호등을 찾는 중..."
    @Published var signalColor: Color = .gray
    @Published var confidence: Float = 0
    @Published var detections: [Detection] = []

    // MARK: - Camera
    let captureSession = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let processingQueue = DispatchQueue(label: "video.processing", qos: .userInitiated)

    // MARK: - ML
    private var visionModel: VNCoreMLModel?

    // MARK: - ⭐ 공유 안전 판정 엔진 (Android 와 동일)
    //   부수효과 없는 순수 판정. 이 인스턴스는 세션 하나당 상태를 들고 있으므로
    //   stopDetection() 에서 reset() 한다 (Android stopCamera 와 동형).
    private let signalEngine = SignalDecisionEngine()

    /// 엔진에 넘길 "실제 후보"의 최소 신뢰도. Android 검출기 기본 임계(≈0.25)와 맞춘다.
    /// 이보다 낮은 Vision 관측은 노이즈로 보고 버린다(= 미탐지로 취급).
    /// ⚠️ 예전의 0.5 하드 필터를 대체한다 — 색상별 비대칭 임계는 엔진이 담당하므로
    ///    여기서 0.5로 미리 자르면 빨강을 과하게 버린다.
    private let baseCandidateFloor: Float = 0.25

    // MARK: - 햅틱 (iOS 에는 원래 진동이 없었음 — 통일 작업에서 추가)
    private let impactStrong = UIImpactFeedbackGenerator(style: .heavy)

    // MARK: - TTS (통합 앱에서는 TtsManager 사용)
    /// nil이면 자체 synthesizer 사용 (단독 실행 시), 주입되면 TtsManager 사용
    private weak var tts: TtsManager?
    private let fallbackSynthesizer = AVSpeechSynthesizer()

    // MARK: - Debug Logging
    private var frameLogCounter: Int = 0
    private let logEveryNFrames: Int = 30   // 30프레임당 1번 (약 1초)

    // MARK: - 미탐지 안내 (2026-07 Android 와 통일)
    //   구: 3/6/9초에 서로 다른 3문장을 연타하고 9초 후 영구 침묵 → 부스에서 말이 폭주했다.
    //   신(Android handleTrafficLightNoDetection 과 동일):
    //     · 카메라 켜고 6초까지는 침묵 (겨눌 시간). → 첫 6초가 Android 의 5초 speech-hold 를 겸함.
    //     · 이후 한 문장을 12초 간격으로만 반복.
    //     · 20초 넘게 계속 못 잡으면 '소리 주의' 폴백을 딱 한 번. 이후에도 12초 반복은 계속.
    private var noDetectionStartedAt: Date? = nil
    private var noDetectionLastSpeakAt: Date = .distantPast
    private var noDetectionSafetyDone: Bool = false
    private let noDetFirst: TimeInterval = 6.0     // 첫 안내까지 대기
    private let noDetRepeat: TimeInterval = 12.0   // 이후 반복 간격
    private let noDetSafety: TimeInterval = 20.0   // 이 시간 넘으면 소리주의 1회
    /// 신뢰도가 floor 미만이라도 화면에 신호등스러운 후보가 보이면 미탐지 안내를 억제한다
    /// (Android WEAK_CANDIDATE_SUPPRESS_NO_DET, peakConfidence>=0.25 와 동형).
    private let weakCandidateFloor: Float = 0.15

    // MARK: - Init

    /// 통합 앱에서 사용 시: TtsManager 주입
    init(tts: TtsManager? = nil) {
        self.tts = tts
        super.init()
        setupModel()
        setupCamera()
        setupAudio()
    }

    /// AppDependencies에서 나중에 TtsManager를 주입할 때
    func attach(tts: TtsManager) {
        self.tts = tts
    }

    // MARK: - Setup

    private func setupModel() {
        do {
            let config = MLModelConfiguration()
            config.computeUnits = .all
            let mlModel = try best(configuration: config).model
            visionModel = try VNCoreMLModel(for: mlModel)
            print("[TrafficLightDetector] 모델 로드 성공")
        } catch {
            print("[TrafficLightDetector] 모델 로드 실패: \(error)")
        }
    }

    private func setupAudio() {
        // TtsManager가 없을 때(단독 실행)만 자체 오디오 세션 설정
        guard tts == nil else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: .mixWithOthers)
            try session.setActive(true)
        } catch {
            print("[TrafficLightDetector] 오디오 세션 설정 실패: \(error)")
        }
    }

    private func setupCamera() {
        captureSession.sessionPreset = .hd1280x720

        // 2배 망원 렌즈 우선, 없으면 광각 + 디지털 줌
        let camera: AVCaptureDevice?

        if let telephoto = AVCaptureDevice.default(.builtInTelephotoCamera, for: .video, position: .back) {
            camera = telephoto
        } else if let wide = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
            try? wide.lockForConfiguration()
            let targetZoom: CGFloat = 3.0
            wide.videoZoomFactor = min(targetZoom, wide.maxAvailableVideoZoomFactor)
            wide.unlockForConfiguration()
            camera = wide
        } else {
            print("[TrafficLightDetector] 카메라 접근 불가")
            return
        }

        guard let camera,
              let input = try? AVCaptureDeviceInput(device: camera) else { return }

        if captureSession.canAddInput(input) { captureSession.addInput(input) }

        videoOutput.setSampleBufferDelegate(self, queue: processingQueue)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]

        if captureSession.canAddOutput(videoOutput) { captureSession.addOutput(videoOutput) }

        if let connection = videoOutput.connection(with: .video) {
            connection.videoRotationAngle = 90
        }
    }

    // MARK: - Public API

    func startDetection() {
        resetNoDetection()            // 미탐지 상태 초기화 — 진입 후 6초 침묵부터 시작
        signalEngine.reset()          // 안전 판정 상태도 깨끗이 (Android zone 진입과 동형)
        impactStrong.prepare()        // 햅틱 예열 (첫 진동 지연 방지)
        DispatchQueue.global(qos: .userInitiated).async {
            self.captureSession.startRunning()
        }
    }

    func stopDetection() {
        captureSession.stopRunning()
        signalEngine.reset()          // 다음 진입 시 보수적으로 새로 시작 (Android stopCamera 와 동형)
    }

    // MARK: - Frame Processing

    private func processFrame(_ pixelBuffer: CVPixelBuffer) {
        guard let model = visionModel else { return }

        let request = VNCoreMLRequest(model: model) { [weak self] request, _ in
            self?.handleResults(request.results)
        }
        request.imageCropAndScaleOption = .scaleFill

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        try? handler.perform([request])
    }

    private func handleResults(_ results: [VNObservation]?) {
        guard let observations = results as? [VNRecognizedObjectObservation] else { return }

        // 1) Vision 관측 → Detection (오버레이 표시 + 후보 판단). baseCandidateFloor 이상만.
        let filtered = observations.compactMap { obs -> Detection? in
            guard let label = obs.labels.first,
                  label.confidence >= baseCandidateFloor else { return nil }
            return Detection(
                label: label.identifier,
                confidence: label.confidence,
                boundingBox: obs.boundingBox
            )
        }

        // 2) Detection → RawSignalDetection (엔진 입력). 빨강→0, 초록→1. 그 외(횡단보도 등) 무시.
        //    두 모델 라벨을 모두 받는다:
        //      - 구 자체 모델: ped_red / ped_green
        //      - kairess(한국 특화): R_Signal(빨강) / G_Signal(초록), Zebra_Cross 는 무시
        //    (Android TrafficLightDetector 의 리매핑과 동일한 계약)
        let raw: [RawSignalDetection] = filtered.compactMap { det in
            let cls: Int32
            switch det.label {
            case "ped_red", "R_Signal":   cls = 0   // SignalDecisionEngine.COLOR_RED
            case "ped_green", "G_Signal": cls = 1   // SignalDecisionEngine.COLOR_GREEN
            default:                      return nil
            }
            return RawSignalDetection(
                classId: cls,
                confidence: det.confidence,
                boxWidth: Float(det.boundingBox.width),
                boxHeight: Float(det.boundingBox.height)
            )
        }

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        // 약한 후보(floor 미만이지만 화면엔 신호등스러운 게 보임) 여부 — 미탐지 안내 억제용.
        let weakCandidatePresent = observations.contains { obs in
            (obs.labels.first?.confidence ?? 0) >= weakCandidateFloor
        }

        DispatchQueue.main.async {
            self.detections = filtered   // 바운딩 박스 오버레이

            if raw.isEmpty {
                // 신호등 후보가 하나도 없음 → 미탐지 안내 (Android 와 동일한 6/12/20초 로직)
                self.handleNoDetection(weakCandidatePresent: weakCandidatePresent)
                return
            }

            // ⭐ 안전 판정은 공유 엔진이 내린다. 여기는 결정 → 부수효과 변환만.
            let decision = self.signalEngine.decide(detections: raw, nowMs: nowMs)
            self.applyDecision(decision)
        }
    }

    // MARK: - 엔진 결정 → 부수효과 (Android MainActivity 의 when(decision) 과 동형)

    private func applyDecision(_ decision: SignalDecision) {
        // 후보를 봤으므로 미탐지 상태 해제 (Android: 각 결정 분기의 resetTrafficLightNoDetectionEscalation)
        self.resetNoDetection()

        switch decision {
        case let a as SignalDecision.Announce:
            let mapped = self.messageForTransition(a.transition)
            self.statusText = mapped.status
            self.signalColor = mapped.color
            self.confidence = a.confidence
            if a.vibrate { self.triggerHaptic(strong: true) }
            self.speakEngine(mapped.message, interrupt: a.interrupt)

        case let r as SignalDecision.Repeat:
            // 같은 색 유지 반복 안내 (heartbeat).
            // Android 와 동일: 반복 안내는 다른 발화를 끊지 않는다(interrupt=false), 빨강이라도.
            // 부스 화면이 색을 유지하도록 signalColor/statusText 도 갱신.
            if r.color == 0 {
                self.signalColor = .red
                self.statusText = "빨간불 — 정지"
            } else {
                self.signalColor = .green
                self.statusText = "초록불 — 다음 신호 대기"
            }
            self.speakEngine(self.repeatMessage(r.color), interrupt: false)

        case is SignalDecision.Flicker:
            // 점멸 감지 → 경고 + 강한 햅틱(3연속 펄스, Android vibrateWarning 과 동형). "건너세요" 아님.
            self.statusText = "신호 깜빡임 — 대기"
            self.signalColor = .red
            self.triggerWarningHaptic()
            self.speakEngine("신호가 깜빡입니다. 멈춰서 다음 신호를 기다리세요.", interrupt: true)

        case is SignalDecision.Silent:
            // 확신 부족 / 안정성 대기 / 락아웃 / 같은색 조용 → 발화 없음.
            // 현재 표시를 유지한다(놀라게 하지 않음). 침묵도 정보다.
            break

        default:
            break
        }
    }

    /// SignalTransition → (발화 문구, 상태 텍스트, 화면 색). Android 문구와 1:1 일치.
    /// KMP 열거형의 Swift 바인딩 이름 불확실성을 피하려 `.name`(코틀린 원본명) 으로 분기.
    private func messageForTransition(_ t: SignalTransition) -> (message: String, status: String, color: Color) {
        switch t.name {
        case "RED_NEW":
            return ("빨간불입니다. 정지하세요.", "빨간불 — 정지", .red)
        case "GREEN_TO_RED":
            return ("빨간불입니다. 정지하세요.", "빨간불 — 정지", .red)
        case "RED_TO_GREEN":
            // 유일하게 건너기를 안내하는 케이스 (강한 진동 동반)
            return ("방금 초록불로 바뀌었습니다. 안전을 확인하고 건너세요.", "초록불 — 건너세요", .green)
        case "STATIC_GREEN":
            // 정적 초록: 절대 "건너세요" 안 함. 다음 주기 대기.
            return ("초록불입니다. 일단 멈춰서 다음 신호를 기다리세요.", "초록불 — 다음 신호 대기", .green)
        default:
            return ("", statusText, signalColor)
        }
    }

    private func repeatMessage(_ color: Int32) -> String {
        return color == 0 ? "빨간불입니다. 정지하세요." : "초록불입니다."
    }

    // MARK: - 미탐지 안내 (Android handleTrafficLightNoDetection 과 동일 로직)

    /// 미탐지 상태를 깨끗이 (진입 시 / 실제 결정이 나올 때마다).
    private func resetNoDetection() {
        noDetectionStartedAt = nil
        noDetectionLastSpeakAt = .distantPast
        noDetectionSafetyDone = false
    }

    private func handleNoDetection(weakCandidatePresent: Bool) {
        self.confidence = 0

        // 약한 후보가 화면에 보이면 "못 찾겠다" 안내를 억제 (Android WEAK_CANDIDATE_SUPPRESS_NO_DET).
        if weakCandidatePresent { return }

        let now = Date()
        if noDetectionStartedAt == nil { noDetectionStartedAt = now }
        let elapsed = now.timeIntervalSince(noDetectionStartedAt!)

        // 첫 6초는 침묵 — 겨눌 시간을 준다 (Android 의 5초 speech-hold 겸함).
        if elapsed < noDetFirst { return }

        statusText = "신호등을 찾는 중..."
        signalColor = .gray
        detections = []

        // 20초 넘게 계속 못 잡으면 '소리 주의' 폴백을 딱 한 번.
        if elapsed >= noDetSafety && !noDetectionSafetyDone {
            noDetectionSafetyDone = true
            noDetectionLastSpeakAt = now
            speakEngine("신호등이 잘 잡히지 않습니다. 주변 소리에 주의하세요.", interrupt: false)
            return
        }

        // 그 외에는 같은 문장을 12초 간격으로만 반복 (연타 금지).
        if now.timeIntervalSince(noDetectionLastSpeakAt) >= noDetRepeat {
            noDetectionLastSpeakAt = now
            speakEngine("신호등을 찾고 있습니다. 카메라를 천천히 좌우로 움직여 주세요.", interrupt: false)
        }
    }

    // MARK: - 햅틱

    private func triggerHaptic(strong: Bool) {
        if strong {
            impactStrong.impactOccurred()
        }
    }

    /// 점멸 경고 햅틱 — 3연속 펄스 (Android vibrateWarning 파형과 동형).
    private func triggerWarningHaptic() {
        let gen = impactStrong
        gen.impactOccurred()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { gen.impactOccurred() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.50) { gen.impactOccurred() }
    }

    // MARK: - TTS

    /// 엔진 결정 발화 — 반복/디바운스는 엔진이 이미 관리하므로 시간 기반 억제 없이 바로 말한다.
    private func speakEngine(_ text: String, interrupt: Bool) {
        if text.isEmpty { return }
        if let tts = tts {
            tts.speak(text, priority: interrupt ? .high : .normal)
        } else {
            if interrupt && fallbackSynthesizer.isSpeaking {
                fallbackSynthesizer.stopSpeaking(at: .immediate)
            }
            let utterance = AVSpeechUtterance(string: text)
            utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
            utterance.rate = 0.5
            utterance.pitchMultiplier = 1.1
            fallbackSynthesizer.speak(utterance)
        }
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate
extension TrafficLightDetector: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        // 30프레임마다 1번씩만 로그 출력 (약 1초 간격)
        frameLogCounter += 1
        let shouldLog = (frameLogCounter % logEveryNFrames == 0)

        if shouldLog {
            print("📸 [TrafficLightDetector] 프레임 수신 (\(frameLogCounter)번째)")
        }

        processFrame(pixelBuffer)
    }
}
