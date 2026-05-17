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
    
    // MARK: - Optical Flow (선택적)
    /// 주입되면 매 프레임을 옵티컬 플로우 분석에도 전달
    private weak var opticalFlow: OpticalFlowAnalyzer?

    // MARK: - TTS (통합 앱에서는 TtsManager 사용)
    /// nil이면 자체 synthesizer 사용 (단독 실행 시), 주입되면 TtsManager 사용
    private weak var tts: TtsManager?
    private let fallbackSynthesizer = AVSpeechSynthesizer()
    
    // MARK: - Debug Logging
    private var frameLogCounter: Int = 0
    private let logEveryNFrames: Int = 30   // 30프레임당 1번 (약 1초)
    
    // MARK: - Debounce / Timeout
    private var lastSpokenSignal: String = ""
    private var lastSpeakTime: Date = .distantPast
    private let speakInterval: TimeInterval = 3.0

    private var lastDetectionTime: Date = Date()
    private let noDetectionTimeout: TimeInterval = 5.0

    // MARK: - Init

    /// 통합 앱에서 사용 시: TtsManager 주입
    init(tts: TtsManager? = nil, opticalFlow: OpticalFlowAnalyzer? = nil) {
        self.tts = tts
        self.opticalFlow = opticalFlow
        print("🔧 [TrafficLightDetector] init — opticalFlow nil? \(opticalFlow == nil)")
        super.init()
        setupModel()
        setupCamera()
        setupAudio()
    }

    /// AppDependencies에서 나중에 TtsManager를 주입할 때.
    /// init 시점에 tts가 없었으면 이걸로 뒤늦게 연결.
    func attach(tts: TtsManager) {
        self.tts = tts
    }

    // MARK: - Setup

    /// best.mlpackage(보행자 신호등 커스텀 YOLO)를 로드해 VNCoreMLModel로 감싼다.
    /// computeUnits=.all 이라 Neural Engine까지 사용.
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

    /// 단독 실행 모드(TtsManager 미주입)일 때만 자체 AVAudioSession을 설정.
    /// 통합 앱에서는 TtsManager가 세션 관리를 책임지므로 이중 설정을 피한다.
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

    /// 후면 카메라 캡처 세션 구성.
    /// 망원 렌즈가 있으면 우선 사용(먼 신호등 식별 유리), 없으면 광각 + 디지털 2배 줌으로 폴백.
    /// 출력은 BGRA32, 세로 방향(90°) 회전으로 고정.
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

    /// 캡처 세션 시작. startRunning이 블로킹이라 글로벌 큐로 보낸다.
    func startDetection() {
        DispatchQueue.global(qos: .userInitiated).async {
            self.captureSession.startRunning()
        }
    }

    /// 캡처 세션 정지. 화면을 떠나거나 다른 모드로 전환할 때 호출.
    func stopDetection() {
        captureSession.stopRunning()
    }

    // MARK: - Frame Processing

    /// 한 프레임에 대해 YOLO 추론을 트리거. 결과는 handleResults로 콜백.
    /// scaleFill로 잘림 없이 모델 입력 크기에 맞춤.
    private func processFrame(_ pixelBuffer: CVPixelBuffer) {
        guard let model = visionModel else { return }

        let request = VNCoreMLRequest(model: model) { [weak self] request, _ in
            self?.handleResults(request.results)
        }
        request.imageCropAndScaleOption = .scaleFill

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        try? handler.perform([request])
    }

    /// YOLO 추론 결과를 받아 confidence ≥ 0.5만 필터링하고
    /// 가장 확신도 높은 1개로 화면/TTS 상태를 갱신.
    /// 일정 시간(noDetectionTimeout) 동안 감지 없으면 "찾는 중" 상태로 복귀.
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

    /// 음성 안내 송출. 같은 신호(`green`/`red`)가 speakInterval 안에 반복되면 무시해
    /// 시끄러운 중복 발화를 막는다. TtsManager가 주입돼 있으면 우선 사용.
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
    /// AVCaptureVideoDataOutput delegate — 매 프레임 호출된다.
    /// YOLO 추론에 넘김과 동시에 (있다면) opticalFlow 분석에도 같은 버퍼를 전달.
    /// 로그는 30프레임당 1번만 찍어 콘솔 스팸을 줄임.
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
        
        // opticalFlow nil 체크는 처음 의심될 때만 알려주면 충분
        if shouldLog && opticalFlow == nil {
            print("⚠️ [TrafficLightDetector] opticalFlow가 nil — 호출 안 됨")
        }

        opticalFlow?.analyze(pixelBuffer: pixelBuffer, roi: nil)
    }
}
