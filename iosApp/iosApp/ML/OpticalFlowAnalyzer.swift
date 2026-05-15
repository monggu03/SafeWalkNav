//
//  OpticalFlowAnalyzer.swift
//  iosApp
//
//  Vision 기반 dense optical flow 분석기
//  - 두 프레임 간 픽셀 이동 벡터를 계산
//  - 관심 영역(ROI) 내부의 평균 이동량으로 움직임/정지 판단
//  - YOLO 차량 탐지의 bounding box를 ROI로 받아 사용
//
//  ⚠️ 카메라 자체 움직임(보행자의 걸음)에 의한 전역 흐름은
//     이 단계에서는 보정하지 않음. YOLO 통합 후 실측 데이터로 튜닝 예정.
//

import Foundation
import Vision
import CoreVideo
import CoreImage
import Combine

/// 옵티컬 플로우 분석 결과
/// 단순 값 타입이라 Sendable 명시
struct OpticalFlowResult: Sendable {
    /// ROI 내부 평균 가로 이동량 (픽셀, 양수=오른쪽)
    let averageDx: Float
    /// ROI 내부 평균 세로 이동량 (픽셀, 양수=아래쪽)
    let averageDy: Float
    /// 평균 이동 벡터의 크기 (sqrt(dx² + dy²))
    let magnitude: Float
    /// "움직이는 중"으로 판정되는지 여부
    let isMoving: Bool
}

/// 클래스 자체는 @MainActor가 아님.
/// - 무거운 Vision 처리는 직렬 큐에서 동기 수행
/// - @Published 업데이트만 MainActor.run으로 메인에 보냄
/// - 내부 가변 상태는 직렬 큐(processingQueue)로 보호 → @unchecked Sendable로 명시
final class OpticalFlowAnalyzer: ObservableObject, @unchecked Sendable {

    // MARK: - Constants

    /// 움직임 판정 임계값 (픽셀). 가슴 마운트 + 도보 환경에서 실측 후 조정 필요.
    private let movementThreshold: Float = 1.5

    /// 분석 주기 제한 (너무 자주 돌리면 발열/배터리 낭비)
    private let minIntervalSeconds: TimeInterval = 0.1  // 약 10 FPS

    // MARK: - Published State (UI 바인딩용)

    @Published private(set) var lastResult: OpticalFlowResult?
    @Published private(set) var isAnalyzing: Bool = false

    // MARK: - Debug Logging
    private var analyzeCallCounter: Int = 0
    private var analysisDoneCounter: Int = 0
    private let logEveryNCalls: Int = 30
    
    // MARK: - Private State (processingQueue 안에서만 접근)

    private var previousBuffer: CVPixelBuffer?
    private var lastAnalysisTime: Date = .distantPast

    /// Vision 처리 + 내부 상태 접근을 직렬화하는 큐
    /// 한 번에 하나의 분석만 돌게 만들어서 동시성 문제 차단
    private let processingQueue = DispatchQueue(
        label: "opticalflow.processing",
        qos: .userInitiated
    )

    // MARK: - Public API

    /// 새 프레임을 분석에 투입
    /// - Parameters:
    ///   - pixelBuffer: 현재 카메라 프레임
    ///   - roi: 관심 영역 (정규화 좌표 0~1). nil이면 전체 프레임.
    ///
    /// 호출은 어느 스레드에서 해도 안전. 내부에서 자체 큐로 보냄.
    func analyze(pixelBuffer: CVPixelBuffer, roi: CGRect? = nil) {
        analyzeCallCounter += 1
        if analyzeCallCounter % logEveryNCalls == 0 {
            DebugLogger.shared.log("OpticalFlow", "analyze 호출 (\(analyzeCallCounter)번째)")
        }
        processingQueue.async { [weak self] in
            self?.performAnalysis(pixelBuffer: pixelBuffer, roi: roi)
        }
    }

    /// 분석 상태 초기화 (탭 전환, 횡단보도 벗어남 등). 어느 스레드에서 호출해도 안전.
    func reset() {
        processingQueue.async { [weak self] in
            guard let self = self else { return }
            self.previousBuffer = nil
            self.lastAnalysisTime = .distantPast

            Task { @MainActor [weak self] in
                self?.lastResult = nil
                self?.isAnalyzing = false
            }

        }
    }

    // MARK: - Internal: processingQueue에서만 호출됨

    private func performAnalysis(pixelBuffer: CVPixelBuffer, roi: CGRect?) {
        let now = Date()
        guard now.timeIntervalSince(lastAnalysisTime) >= minIntervalSeconds else {
            // interval 스킵 로그는 너무 자주 발생하므로 제거
            return
        }
        lastAnalysisTime = now

        // 첫 프레임은 한 번만 발생하니 그대로 둠 (스팸 X)
        guard let prev = previousBuffer else {
            previousBuffer = pixelBuffer
            DebugLogger.shared.log("OpticalFlow", "첫 프레임 저장 — 분석 시작 준비 완료")
            return
        }

        Task { @MainActor [weak self] in
            self?.isAnalyzing = true
        }

        let result = computeOpticalFlow(from: prev, to: pixelBuffer, roi: roi)
        
        // 분석 완료 로그는 30번에 1번만 출력
        analysisDoneCounter += 1
        if analysisDoneCounter % logEveryNCalls == 0 {
            let mag = result?.magnitude ?? -1
            let moving = result?.isMoving ?? false
            let level: LogEntry.Level = moving ? .info : .warn   // 정지면 노랑
            DebugLogger.shared.log(
                "OpticalFlow",
                "#\(analysisDoneCounter) mag=\(String(format: "%.2f", mag)) moving=\(moving)",
                level: level
            )
        }

        previousBuffer = pixelBuffer

        Task { @MainActor [weak self] in
            self?.lastResult = result
            self?.isAnalyzing = false
        }
    }
    
    // MARK: - Core Computation

    private func computeOpticalFlow(
        from previous: CVPixelBuffer,
        to current: CVPixelBuffer,
        roi: CGRect?
    ) -> OpticalFlowResult? {

        let request = VNGenerateOpticalFlowRequest(targetedCVPixelBuffer: current)
        request.computationAccuracy = .medium  // .low / .medium / .high

        let handler = VNImageRequestHandler(cvPixelBuffer: previous, options: [:])

        do {
            try handler.perform([request])
        } catch {
            DebugLogger.shared.log("OpticalFlow", "Vision request 실패: \(error)", level: .error)
            return nil
        }

        guard let observation = request.results?.first as? VNPixelBufferObservation else {
            return nil
        }

        return averageFlow(in: observation.pixelBuffer, roi: roi)
    }

    /// 플로우 버퍼의 ROI 영역 평균 (dx, dy) 계산
    private func averageFlow(in flowBuffer: CVPixelBuffer, roi: CGRect?) -> OpticalFlowResult? {

        CVPixelBufferLockBaseAddress(flowBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(flowBuffer, .readOnly) }

        let width = CVPixelBufferGetWidth(flowBuffer)
        let height = CVPixelBufferGetHeight(flowBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(flowBuffer)

        guard let baseAddress = CVPixelBufferGetBaseAddress(flowBuffer) else {
            return nil
        }

        let (xStart, yStart, xEnd, yEnd) = pixelRange(
            for: roi,
            width: width,
            height: height
        )

        var sumDx: Float = 0
        var sumDy: Float = 0
        var count: Int = 0

        // 픽셀 포맷: 2 channel Float32 (각 픽셀당 dx, dy)
        for y in yStart..<yEnd {
            let rowPointer = baseAddress.advanced(by: y * bytesPerRow)
            let floatPointer = rowPointer.assumingMemoryBound(to: Float.self)

            for x in xStart..<xEnd {
                let dx = floatPointer[x * 2]
                let dy = floatPointer[x * 2 + 1]
                sumDx += dx
                sumDy += dy
                count += 1
            }
        }

        guard count > 0 else { return nil }

        let avgDx = sumDx / Float(count)
        let avgDy = sumDy / Float(count)
        let magnitude = sqrt(avgDx * avgDx + avgDy * avgDy)

        return OpticalFlowResult(
            averageDx: avgDx,
            averageDy: avgDy,
            magnitude: magnitude,
            isMoving: magnitude > movementThreshold
        )
    }

    /// 정규화 ROI(0~1) → 픽셀 인덱스 범위
    private func pixelRange(
        for roi: CGRect?,
        width: Int,
        height: Int
    ) -> (xStart: Int, yStart: Int, xEnd: Int, yEnd: Int) {

        guard let roi = roi else {
            return (0, 0, width, height)
        }

        let xStart = max(0, Int(roi.minX * CGFloat(width)))
        let yStart = max(0, Int(roi.minY * CGFloat(height)))
        let xEnd = min(width, Int(roi.maxX * CGFloat(width)))
        let yEnd = min(height, Int(roi.maxY * CGFloat(height)))

        guard xStart < xEnd, yStart < yEnd else {
            return (0, 0, width, height)
        }

        return (xStart, yStart, xEnd, yEnd)
    }
}
