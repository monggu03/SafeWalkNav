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
import CoreImage
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

    // MARK: - Center Crop (망원 효과)
    /// 신호등 탐지에 사용할 화면 중앙 ROI (정규화 좌표).
    /// 0.5×0.5 = 면적 1/4 → 선형 2× 줌과 동등한 디지털 크롭.
    /// 카메라는 1×로 두고 OpticalFlow는 풀 프레임을 그대로 사용,
    /// YOLO에는 이 ROI만 잘라서 입력한다.
    private let cropROI = CGRect(x: 0.25, y: 0.25, width: 0.5, height: 0.5)

    /// CIImage → CVPixelBuffer 렌더링용. 매번 새로 만들면 비싸서 1회만 생성.
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])

    /// 잘라낸 BGRA 버퍼 풀 (프레임마다 재할당하지 않도록)
    private var cropPixelBufferPool: CVPixelBufferPool?
    private var cropPoolSize: (width: Int, height: Int) = (0, 0)

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

    /// 통합 앱에서 사용 시: TtsManager 주입.
    /// OpticalFlow는 자체 카메라 세션을 가지므로 더 이상 주입받지 않음.
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

        // 광각 카메라를 1× 그대로 사용한다.
        // - OpticalFlow는 이 1× 풀 프레임을 그대로 받음
        // - 신호등 YOLO만 매 프레임 중앙 ROI를 잘라서 입력 (cropROI 참고)
        // 망원 렌즈를 신호등에만 쓰려면 AVCaptureMultiCamSession이 필요한데
        // 단일 카메라 단순화 정책에 따라 디지털 크롭으로 통일.
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera) else {
            print("[TrafficLightDetector] 카메라 접근 불가")
            return
        }

        try? camera.lockForConfiguration()
        camera.videoZoomFactor = 1.0
        camera.unlockForConfiguration()

        // 진단용 — 어떤 렌즈 / 어떤 줌이 잡혔는지 콘솔에서 바로 확인
        print("📷 [TrafficLightDetector] device=\(camera.localizedName) " +
              "deviceType=\(camera.deviceType.rawValue) " +
              "zoom=\(camera.videoZoomFactor) " +
              "minZoom=\(camera.minAvailableVideoZoomFactor) " +
              "maxZoom=\(camera.maxAvailableVideoZoomFactor)")

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

        // 중앙 ROI만 잘라서 YOLO에 입력 → 디지털 망원 효과
        guard let cropped = cropCenter(pixelBuffer) else {
            return
        }

        let request = VNCoreMLRequest(model: model) { [weak self] request, _ in
            self?.handleResults(request.results)
        }
        request.imageCropAndScaleOption = .scaleFill

        let handler = VNImageRequestHandler(cvPixelBuffer: cropped, options: [:])
        try? handler.perform([request])
    }

    /// 입력 버퍼의 중앙 ROI 영역을 잘라 새 BGRA 버퍼로 반환.
    /// 결과 버퍼 크기는 cropROI에 비례.
    private func cropCenter(_ pixelBuffer: CVPixelBuffer) -> CVPixelBuffer? {
        let srcW = CVPixelBufferGetWidth(pixelBuffer)
        let srcH = CVPixelBufferGetHeight(pixelBuffer)

        let cropW = Int(CGFloat(srcW) * cropROI.width)
        let cropH = Int(CGFloat(srcH) * cropROI.height)
        let cropX = Int(CGFloat(srcW) * cropROI.minX)
        let cropY = Int(CGFloat(srcH) * cropROI.minY)
        guard cropW > 0, cropH > 0 else { return nil }

        // 풀이 없거나 크기가 바뀌었으면 재생성
        if cropPixelBufferPool == nil || cropPoolSize != (cropW, cropH) {
            let attrs: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: cropW,
                kCVPixelBufferHeightKey as String: cropH,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:]
            ]
            var pool: CVPixelBufferPool?
            CVPixelBufferPoolCreate(
                kCFAllocatorDefault,
                nil,
                attrs as CFDictionary,
                &pool
            )
            cropPixelBufferPool = pool
            cropPoolSize = (cropW, cropH)
        }

        guard let pool = cropPixelBufferPool else { return nil }
        var outBuffer: CVPixelBuffer?
        let status = CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &outBuffer)
        guard status == kCVReturnSuccess, let out = outBuffer else { return nil }

        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let cropRect = CGRect(x: cropX, y: cropY, width: cropW, height: cropH)
        let cropped = ciImage
            .cropped(to: cropRect)
            .transformed(by: CGAffineTransform(translationX: -CGFloat(cropX), y: -CGFloat(cropY)))

        ciContext.render(cropped, to: out)
        return out
    }

    /// 잘라낸 ROI 안의 정규화 박스를 풀 프레임 정규화 좌표로 환원.
    private func remapToFullFrame(_ box: CGRect) -> CGRect {
        return CGRect(
            x: cropROI.minX + box.minX * cropROI.width,
            y: cropROI.minY + box.minY * cropROI.height,
            width: box.width * cropROI.width,
            height: box.height * cropROI.height
        )
    }

    private func handleResults(_ results: [VNObservation]?) {
        guard let observations = results as? [VNRecognizedObjectObservation] else { return }

        let filtered = observations.compactMap { obs -> Detection? in
            guard let label = obs.labels.first, label.confidence >= 0.5 else { return nil }
            // obs.boundingBox는 잘라낸 ROI 기준 정규화 좌표 → 풀 프레임 좌표로 환원
            return Detection(
                label: label.identifier,
                confidence: label.confidence,
                boundingBox: remapToFullFrame(obs.boundingBox)
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

        // 30프레임마다 1번씩만 로그 출력 (약 1초 간격)
        frameLogCounter += 1
        if frameLogCounter % logEveryNFrames == 0 {
            print("📸 [TrafficLightDetector] 프레임 수신 (\(frameLogCounter)번째)")
        }

        processFrame(pixelBuffer)
    }
}
