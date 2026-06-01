package com.example.safewalknav.ml

/**
 * YOLOv8 보행자 신호등 검출 결과 1건.
 *
 * @property classId   0 = red_pedestrian (정지), 1 = green_pedestrian (보행)
 * @property label     클래스 이름 (TTS 안내용)
 * @property confidence 0.0 ~ 1.0 신뢰도
 * @property bbox      정규화된 bounding box (0.0~1.0 비율)
 */
data class TrafficLightDetection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val bbox: BoundingBox,
)

/**
 * 정규화된 bounding box (입력 이미지 크기 기준 0.0~1.0).
 * cx, cy = 박스 중심, w, h = 너비/높이 (모두 정규화).
 */
data class BoundingBox(
    val xCenter: Float,
    val yCenter: Float,
    val width: Float,
    val height: Float,
) {
    val left: Float get() = xCenter - width / 2
    val top: Float get() = yCenter - height / 2
    val right: Float get() = xCenter + width / 2
    val bottom: Float get() = yCenter + height / 2

    val area: Float get() = width * height
}

/**
 * TrafficLightDetector 의 진단 통계 — 매 inference 마다 갱신.
 *
 * "ML 모델이 신호등을 보고 있는가, 보고 있다면 어디서 막혔는가" 를 파이프라인 단계별로
 * 추적하기 위한 데이터.
 *
 * 해석 가이드:
 *   - peakConfidence 가 항상 낮음 (예: < 0.2) → 카메라가 신호등을 안 향하고 있을 가능성
 *   - peakConfidence 가 높음 (≥ 0.5) 인데 finalDetections=0 → NMS 또는 후처리 버그
 *   - rawCandidatesAboveThreshold 가 많은데 finalDetections=0 → NMS 가 너무 공격적
 *   - peakConfRed/Green 한 쪽만 높음 → 색상 편향 가능성
 *
 * @property inferenceMs 추론 시간 (ms). 30 ms 넘으면 frame skip 간격 (333ms) 더 늘릴지 검토.
 * @property rawCandidatesAboveThreshold confidence threshold 통과한 anchor 개수 (NMS 전).
 * @property finalDetections NMS 적용 후 최종 검출 개수 (호출자가 받는 리스트 크기).
 * @property peakConfidence 모든 anchor 중 클래스 무관 최고 confidence (threshold 무시).
 * @property peakConfRed class 0 (red_pedestrian) 의 최고 점수 — threshold 무시.
 * @property peakConfGreen class 1 (green_pedestrian) 의 최고 점수 — threshold 무시.
 * @property confidenceThresholdUsed 이번 inference 에 실제로 적용된 threshold (진단 모드 반영).
 * @property diagnosticMode 진단 모드가 활성화되어 있는지 (낮은 threshold 사용 중).
 */
data class DetectionStats(
    val inferenceMs: Long,
    val rawCandidatesAboveThreshold: Int,
    val finalDetections: Int,
    val peakConfidence: Float,
    val peakConfRed: Float,
    val peakConfGreen: Float,
    val confidenceThresholdUsed: Float,
    val diagnosticMode: Boolean,
)
