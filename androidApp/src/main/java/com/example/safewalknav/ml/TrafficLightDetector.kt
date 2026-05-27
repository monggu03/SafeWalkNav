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
 * 모델: Hybrid v2.1 (YOLOv11n backbone + P2 head 확장), 자체 학습, float16 양자화
 *   - 데이터셋: Roboflow pedestrian-traffic-light--1 (train 822 / val 78 / test 39 = 939장)
 *   - mAP50 = 0.9476  (5종 비교 실험 중 ★ 채택 모델)
 *   - 빨강 ↔ 초록 오분류 0건 (안전 핵심)
 *   - 클래스: 0=red_pedestrian (정지), 1=green_pedestrian (보행)
 *   - 파일 크기: 5.57 MB (float16, float32 대비 절반)
 *
 * 아키텍처 핵심:
 *   - Ultralytics 가 제공하지 않는 yolo11-p2.yaml 을 자체 설계
 *   - YOLOv11 backbone + YOLOv8-P2 head 패턴 결합, 레이어 인덱스 +1 재매핑, C2f → C3k2 치환
 *   - 사전학습 가중치 88% 전이 성공 → 939장의 적은 데이터로도 안정 수렴
 *   - P2 head 추가로 anchor 수 8,400 → 34,000 으로 확장 (160² + 80² + 40² + 20²)
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
     * 학습 시 mAP50 0.9476, 진짜 신호등은 0.7+ 로 잡힘. 0.5~0.7 대는 noise 가능성 높아 제외.
     *
     * 진단 모드 (diagnosticMode=true) 일 때는 [DIAGNOSTIC_CONFIDENCE_THRESHOLD] 가 대신 적용됨.
     */
    var confidenceThreshold: Float = 0.7f

    /** NMS IoU threshold — 같은 클래스 박스가 이 값 이상 겹치면 중복으로 제거. */
    var iouThreshold: Float = 0.45f

    /**
     * 진단 모드 — 실기기에서 ML 파이프라인을 점검할 때만 ON.
     *
     * ON 이면:
     *   - confidence threshold 가 [DIAGNOSTIC_CONFIDENCE_THRESHOLD] (0.3) 로 떨어짐 → 모델이 신호등을
     *     보고는 있는지(낮은 점수라도) 확인 가능. 운영용으로 켜면 false positive 위험.
     *   - lastStats 가 더 상세한 통계를 채움.
     *
     * 평상시엔 false 유지.
     */
    var diagnosticMode: Boolean = false

    /**
     * 가장 최근 inference 의 진단 통계. ML 파이프라인 어느 단계에서 막혔는지 추적용.
     * 매 detect() 호출 시 갱신.
     */
    @Volatile
    var lastStats: DetectionStats? = null
        private set

    init {
        // CPU 추론 (4 threads) + NNAPI delegate (가능하면) fallback CPU.
        // GPU delegate 는 tensorflow-lite-gpu-delegate-plugin 의존성이 tflite 2.14 와 API 호환 안 돼서
        // 제거됐고, 직접 GpuDelegate() 호출 시 NoClassDefFoundError 발생.
        // 대신 NNAPI (Android 8.1+) 를 우선 사용 — 디바이스 NPU/DSP/GPU 를 자동 활용해 추론 가속.
        // float16 양자화 모델 (5.6 MB) 이라 NNAPI 와 궁합 좋음 — float32 대비 1.5~2배 추론 가속 기대.
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            // useNNAPI 는 deprecated 됐지만 NnApiDelegate 가 일부 디바이스에서 crash 유발하므로
            // 안전한 형태로 옵션만 켜둠. 실패 시 자동 CPU fallback.
            @Suppress("DEPRECATION")
            setUseNNAPI(true)
        }

        val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILENAME)
        interpreter = Interpreter(modelBuffer, options)

        Log.d(TAG, "Model loaded: $MODEL_FILENAME (NNAPI on, 4 threads CPU fallback)")
        Log.d(TAG, "Input shape: ${interpreter.getInputTensor(0).shape().toList()}")
        Log.d(TAG, "Output shape: ${interpreter.getOutputTensor(0).shape().toList()}")
    }

    /**
     * 이미지에서 보행자 신호등 검출.
     * 매 호출마다 GPU/CPU 추론 (호출자가 frame skipping 책임 — Analyzer 참고).
     *
     * 진단 통계 ([lastStats]) 를 매 호출마다 갱신한다 — 호출자가 ML 파이프라인 어느 단계에서
     * 떨어졌는지 추적 가능.
     */
    fun detect(bitmap: Bitmap): List<TrafficLightDetection> {
        val t0 = System.nanoTime()

        val input = preprocess(bitmap)

        // YOLO 출력 shape: [1, 4 + numClasses, numAnchors] = [1, 6, 34000]
        // P2 head 포함 anchor 수: 160² + 80² + 40² + 20² = 25600 + 6400 + 1600 + 400 = 34000.
        // [0..3] = bbox (cx, cy, w, h, 입력 크기 단위), [4..5] = class scores
        val output = Array(1) { Array(NUM_OUTPUT_CHANNELS) { FloatArray(NUM_ANCHORS) } }
        interpreter.run(input, output)

        val activeThreshold = if (diagnosticMode) DIAGNOSTIC_CONFIDENCE_THRESHOLD else confidenceThreshold
        val (detections, rawCount, peakAll, peakRed, peakGreen) = postprocessWithStats(output[0], activeThreshold)

        val inferenceMs = (System.nanoTime() - t0) / 1_000_000
        lastStats = DetectionStats(
            inferenceMs = inferenceMs,
            rawCandidatesAboveThreshold = rawCount,
            finalDetections = detections.size,
            peakConfidence = peakAll,
            peakConfRed = peakRed,
            peakConfGreen = peakGreen,
            confidenceThresholdUsed = activeThreshold,
            diagnosticMode = diagnosticMode,
        )
        return detections
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
     * YOLO raw output → 필터링/NMS 거친 최종 검출 리스트 + 진단 통계.
     *
     * 입력 형태: [6][34000]  (Hybrid v2.1, P2 head 포함)
     *   - output[0..3][i] = anchor i 의 bbox (cx, cy, w, h, 단위는 INPUT_SIZE 기준)
     *   - output[4..5][i] = anchor i 의 class score (sigmoid 적용된 값)
     *
     * @return (NMS 통과 최종 detection 리스트, threshold 통과 anchor 개수 NMS 전,
     *          모든 anchor 중 최고 confidence (임계값 무관), class 0 최고, class 1 최고)
     */
    private fun postprocessWithStats(
        output: Array<FloatArray>,
        threshold: Float,
    ): PostprocessResult {
        val candidates = ArrayList<TrafficLightDetection>(64)
        var peakAll = 0f
        var peakRed = 0f
        var peakGreen = 0f
        var rawAboveThreshold = 0

        for (i in 0 until NUM_ANCHORS) {
            // 클래스별 점수 (NUM_CLASSES=2 가정)
            val scoreRed = output[4][i]
            val scoreGreen = output[5][i]
            if (scoreRed > peakRed) peakRed = scoreRed
            if (scoreGreen > peakGreen) peakGreen = scoreGreen

            val maxScore: Float
            val maxClass: Int
            if (scoreRed >= scoreGreen) {
                maxScore = scoreRed
                maxClass = 0
            } else {
                maxScore = scoreGreen
                maxClass = 1
            }
            if (maxScore > peakAll) peakAll = maxScore

            if (maxScore < threshold) continue
            rawAboveThreshold++

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

        return PostprocessResult(
            detections = nonMaxSuppression(candidates),
            rawAboveThreshold = rawAboveThreshold,
            peakAll = peakAll,
            peakRed = peakRed,
            peakGreen = peakGreen,
        )
    }

    /** detect() 내부에서 destructuring 으로 받기 위한 묶음 (data class). */
    private data class PostprocessResult(
        val detections: List<TrafficLightDetection>,
        val rawAboveThreshold: Int,
        val peakAll: Float,
        val peakRed: Float,
        val peakGreen: Float,
    )

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

        // Hybrid v2.1 (YOLOv11n + P2 head, float16 양자화), mAP50 0.9476, 939장 학습.
        // 이전 YOLOv8n 모델 (pedestrian_tl.tflite, mAP 0.938) 은 APK 용량 절감 위해 제거됨.
        // 재현 필요 시 models/best.pt 에서 재export 또는 Colab 학습 결과에서 복구.
        private const val MODEL_FILENAME = "safewalknav_tl.tflite"

        /**
         * 진단 모드일 때 적용되는 confidence threshold (0.3). 운영 임계 0.7 대비 충분히 낮춰
         * 모델이 신호등을 보고는 있지만 점수가 낮아 떨어진 케이스를 식별하기 위함.
         * 운영용으로 켜면 false positive 위험 — 진단 후 반드시 끌 것.
         */
        const val DIAGNOSTIC_CONFIDENCE_THRESHOLD: Float = 0.3f

        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 2
        private const val NUM_OUTPUT_CHANNELS = 4 + NUM_CLASSES   // 6
        // P2 head 포함 anchor 수: 160² + 80² + 40² + 20² = 25,600 + 6,400 + 1,600 + 400 = 34,000.
        // P2 head 가 없던 이전 모델 (8,400 anchors) 과 다름 — 모델 교체 시 반드시 같이 갱신할 것.
        private const val NUM_ANCHORS = 34000

        // data.yaml 의 names 와 정확히 일치 (학습 시점 클래스 매핑)
        private val CLASS_NAMES = listOf("red_pedestrian", "green_pedestrian")
    }
}
