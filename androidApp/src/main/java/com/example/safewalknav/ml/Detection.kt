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
