package com.example.safewalknav.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * 보행자 신호등 색깔 검출기.
 *
 * 모델: YOLOv8n, 자체 학습 (Roboflow cible/pedestrian-traffic-light-3p4dd, 939장)
 *   - mAP50 = 0.938
 *   - 빨강 ↔ 초록 오분류 0% (안전 핵심)
 *   - 클래스: 0=red_pedestrian (정지), 1=green_pedestrian (보행)
 *
 * 입력: Bitmap (어떤 크기든 OK — 내부에서 640x640 으로 리사이즈)
 * 출력: List<TrafficLightDetection> (NMS 적용 후, confidence threshold 통과한 것만)
 *
 * 사용법:
 *   val detector = TrafficLightDetector(context)
 *   val detections = detector.detect(bitmap)
 *   // ... 사용 후
 *   detector.close()
 */
class TrafficLightDetector(context: Context) {

    private val interpreter: Interpreter

    /**
     * Confidence threshold — 이 값 이상의 점수만 유효 검출로 인정.
     * 0.7 로 비교적 엄격하게 — 시각장애인 안전 시나리오에선 false positive 가 false negative 보다 더 치명적.
     * 학습 시 mAP50 0.938, 진짜 신호등은 0.7+ 로 잡힘. 0.5~0.7 대는 noise 가능성 높아 제외.
     */
    var confidenceThreshold: Float = 0.7f

    /** NMS IoU threshold — 같은 클래스 박스가 이 값 이상 겹치면 중복으로 제거. */
    var iouThreshold: Float = 0.45f

    init {
        // CPU 추론 (4 threads). GPU delegate 는 tensorflow-lite-gpu-delegate-plugin 의존성이
        // tflite 2.14 와 API 호환 안 돼서 PR-1 에서 제거됨. 직접 GpuDelegate() 호출 시
        // NoClassDefFoundError (GpuDelegateFactory$Options) 발생 → CPU 만 사용.
        // YOLOv8n 모델이 작아 (약 6 MB) CPU + 4 threads 로도 5~10ms 추론, 모바일에 충분.
        val options = Interpreter.Options()
        options.setNumThreads(4)

        val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILENAME)
        interpreter = Interpreter(modelBuffer, options)

        Log.d(TAG, "Model loaded: $MODEL_FILENAME (CPU, 4 threads)")
        Log.d(TAG, "Input shape: ${interpreter.getInputTensor(0).shape().toList()}")
        Log.d(TAG, "Output shape: ${interpreter.getOutputTensor(0).shape().toList()}")
    }

    /**
     * 이미지에서 보행자 신호등 검출.
     * 매 호출마다 GPU/CPU 추론 (호출자가 frame skipping 책임 — Analyzer 참고).
     */
    fun detect(bitmap: Bitmap): List<TrafficLightDetection> {
        val input = preprocess(bitmap)

        // YOLOv8 출력 shape: [1, 4 + numClasses, numAnchors] = [1, 6, 8400]
        // [0..3] = bbox (cx, cy, w, h, 입력 크기 단위), [4..5] = class scores
        val output = Array(1) { Array(NUM_OUTPUT_CHANNELS) { FloatArray(NUM_ANCHORS) } }
        interpreter.run(input, output)

        return postprocess(output[0])
    }

    /**
     * Bitmap → 정규화된 ByteBuffer (RGB float32, 0.0~1.0).
     * 입력 크기 INPUT_SIZE x INPUT_SIZE 로 강제 리사이즈 (학습 시 그대로 stretch 사용).
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        buffer.rewind()
        return buffer
    }

    /**
     * YOLOv8 raw output → 필터링/NMS 거친 최종 검출 리스트.
     *
     * 입력 형태: [6][8400]
     *   - output[0..3][i] = anchor i 의 bbox (cx, cy, w, h, 단위는 INPUT_SIZE 기준)
     *   - output[4..5][i] = anchor i 의 class score (sigmoid 적용된 값)
     */
    private fun postprocess(output: Array<FloatArray>): List<TrafficLightDetection> {
        val candidates = ArrayList<TrafficLightDetection>(64)

        for (i in 0 until NUM_ANCHORS) {
            // 가장 높은 class score 와 그 클래스 ID 찾기
            var maxScore = output[4][i]
            var maxClass = 0
            for (c in 1 until NUM_CLASSES) {
                val s = output[4 + c][i]
                if (s > maxScore) {
                    maxScore = s
                    maxClass = c
                }
            }

            if (maxScore < confidenceThreshold) continue

            // bbox 정규화 (INPUT_SIZE → 0.0~1.0)
            val cx = output[0][i] / INPUT_SIZE
            val cy = output[1][i] / INPUT_SIZE
            val w = output[2][i] / INPUT_SIZE
            val h = output[3][i] / INPUT_SIZE

            candidates += TrafficLightDetection(
                classId = maxClass,
                label = CLASS_NAMES[maxClass],
                confidence = maxScore,
                bbox = BoundingBox(cx, cy, w, h),
            )
        }

        return nonMaxSuppression(candidates)
    }

    /** 같은 클래스 내에서 IoU 가 threshold 넘는 박스는 confidence 가장 높은 것만 남김. */
    private fun nonMaxSuppression(detections: List<TrafficLightDetection>): List<TrafficLightDetection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<TrafficLightDetection>(sorted.size)

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best

            // 같은 클래스의 박스 중 IoU 큰 것 제거
            sorted.removeAll { other ->
                other.classId == best.classId && iou(best.bbox, other.bbox) > iouThreshold
            }
        }
        return keep
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.area + b.area - inter
        return if (union > 0f) inter / union else 0f
    }

    fun close() {
        try { interpreter.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "TrafficLightDetector"
        private const val MODEL_FILENAME = "pedestrian_tl.tflite"

        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 2
        private const val NUM_OUTPUT_CHANNELS = 4 + NUM_CLASSES   // 6
        private const val NUM_ANCHORS = 8400                      // 80*80 + 40*40 + 20*20

        // data.yaml 의 names 와 정확히 일치 (학습 시점 클래스 매핑)
        private val CLASS_NAMES = listOf("red_pedestrian", "green_pedestrian")
    }
}
