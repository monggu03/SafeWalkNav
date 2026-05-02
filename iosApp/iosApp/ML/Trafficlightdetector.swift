//
//  TrafficLightDetector.swift
//  iosApp
//
//  신호등 감지 매니저
//  - best.mlpackage (커스텀 YOLO) 로 보행자 신호등 감지
//  - ped_red / ped_green 분류
//  - TtsManager를 통해 음성 안내
//  - ContentView에서 detections를 받아 바운딩 박스 표시
//

import Foundation
import SwiftUI
import AVFoundation
import Vision
import CoreML
import Combine

// MARK: - Detection 모델
/// YOLO 감지 결과 1개를 표현
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
/// 카메라 프레임을 받아 CoreML YOLO 추론 → 신호등 상태 판별
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

    // MARK: - TTS (통합 앱에서는 TtsManager 사용)
    /// nil이면 자체 synthesizer 사용 (단독 실행 시), 주입되면 TtsManager 사용
    private weak var tts: TtsManager?
    private let fallbackSynthesizer = AVSpeechSynthesizer()

    // MARK: - Debounce / Timeout
    private var lastSpokenSignal: String = ""
    private var lastSpeakTime: Date = .distantPast
    private let speakInterval: TimeInterval = 3.0

    private var lastDetectionTime: Date = Date()
    private let noDetectionTimeout: TimeInterval = 5.0

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
            wide.videoZoomFactor = 2.0
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
        DispatchQueue.global(qos: .userInitiated).async {
            self.captureSession.startRunning()
        }
    }

    func stopDetection() {
        captureSession.stopRunning()
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

        let filtered = observations.compactMap { obs -> Detection? in
            guard let label = obs.labels.first, label.confidence >= 0.5 else { return nil }
            return Detection(
                label: label.identifier,
                confidence: label.confidence,
                boundingBox: obs.boundingBox
            )
        }

        let bestDetection = filtered.max(by: { $0.confidence < $1.confidence })

        DispatchQueue.main.async {
            self.detections = filtered

            if let det = bestDetection {
                self.lastDetectionTime = Date()
                self.confidence = det.confidence

                switch det.label {
                case "ped_green":
                    self.statusText = "초록불 — 건너세요"
                    self.signalColor = .green
                    self.speak("초록불입니다. 건너세요.", signal: "green")
                case "ped_red":
                    self.statusText = "빨간불 — 기다리세요"
                    self.signalColor = .red
                    self.speak("빨간불입니다. 기다리세요.", signal: "red")
                default:
                    self.statusText = "신호등을 찾는 중..."
                    self.signalColor = .gray
                    self.confidence = 0
                }
            } else {
                if Date().timeIntervalSince(self.lastDetectionTime) > self.noDetectionTimeout {
                    self.statusText = "신호등을 찾는 중..."
                    self.signalColor = .gray
                    self.confidence = 0
                    self.lastSpokenSignal = ""
                    self.detections = []
                }
            }
        }
    }

    // MARK: - TTS

    private func speak(_ text: String, signal: String) {
        let now = Date()
        // 같은 신호 반복 방지
        if signal == lastSpokenSignal && now.timeIntervalSince(lastSpeakTime) < speakInterval { return }

        lastSpokenSignal = signal
        lastSpeakTime = now

        // TtsManager가 있으면 그걸 사용, 없으면 자체 synthesizer
        if let tts = tts {
            let priority: TtsManager.Priority = (signal != lastSpokenSignal) ? .high : .normal
            tts.speak(text, priority: priority)
        } else {
            // 단독 실행 모드 (기존 동작 유지)
            if fallbackSynthesizer.isSpeaking {
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
        processFrame(pixelBuffer)
    }
}
