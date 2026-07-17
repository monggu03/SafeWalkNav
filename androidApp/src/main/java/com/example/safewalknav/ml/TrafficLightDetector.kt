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
 * 보행자 신호등 색깔 검출기. ACTIVE_MODEL 상수 하나로 모델을 전환한다.
 *
 * KAIRESS_YOLOV5 : kairess/crosswalk-traffic-light-detection-yolov5 (GPL-3.0)
 *   한국 6,593장 학습, YOLOv5s6(P6) @640, 3 classes, 출력 [1,25500,8] (objectness 있음)
 * HYBRID_P2      : 자체 YOLOv11n + P2 head, 939장, 2 classes, 출력 [1,6,34000] (objectness 없음)
 *
 * 앱 계약: detect() 의 classId 는 항상 0=빨강, 1=초록. 모델이 무엇이든 불변.
 */
class TrafficLightDetector(context: Context) {

    enum class ModelKind { KAIRESS_YOLOV5, HYBRID_P2 }

    private val interpreter: Interpreter
    private val spec: ModelSpec

    var confidenceThreshold: Float
    var iouThreshold: Float = 0.45f
    var diagnosticMode: Boolean = false

    @Volatile
    var lastStats: DetectionStats? = null
        private set

    /** 최근 프레임에 횡단보도(Zebra_Cross)가 보였는지. kairess 모델만 제공. */
    @Volatile
    var lastCrosswalkVisible: Boolean = false
        private set

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            @Suppress("DEPRECATION")
            setUseNNAPI(true)
        }

        // ACTIVE_MODEL 의 tflite 가 없으면 검증된 기존 모델로 폴백 (크래시 방지).
        var loadedSpec = SPECS.getValue(ACTIVE_MODEL)
        val buffer = try {
            FileUtil.loadMappedFile(context, loadedSpec.assetFilename)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ $ACTIVE_MODEL 모델(assets/${loadedSpec.assetFilename}) 없음: ${e.message}")
            Log.w(TAG, "   → $FALLBACK_MODEL 로 폴백. export 후 assets/ 에 넣으십시오.")
            loadedSpec = SPECS.getValue(FALLBACK_MODEL)
            FileUtil.loadMappedFile(context, loadedSpec.assetFilename)
        }

        spec = loadedSpec
        confidenceThreshold = spec.defaultConfidence
        interpreter = Interpreter(buffer, options)

        val outShape = interpreter.getOutputTensor(0).shape().toList()
        Log.d(TAG, "Model=${spec.kind} in=${interpreter.getInputTensor(0).shape().toList()} out=$outShape conf=$confidenceThreshold")
        verifyOutputShape(outShape)
    }

    /** 코드 상수와 실제 텐서가 어긋나면 조용히 오작동한다 (빨강↔초록 뒤바뀜). 크게 경고. */
    private fun verifyOutputShape(outShape: List<Int>) {
        val expected = when (spec.kind) {
            ModelKind.KAIRESS_YOLOV5 -> listOf(spec.numAnchors, spec.numOutputChannels)
            ModelKind.HYBRID_P2 -> listOf(spec.numOutputChannels, spec.numAnchors)
        }
        val actual = listOf(outShape.getOrNull(1) ?: -1, outShape.getOrNull(2) ?: -1)
        if (actual != expected) {
            Log.e(TAG, "⚠️ 출력 shape 불일치! 기대=[1,${expected[0]},${expected[1]}] 실제=$outShape")
            Log.e(TAG, "   → numAnchors / numClasses 를 실제 모델에 맞추십시오.")
        } else {
            Log.d(TAG, "✅ 출력 shape 일치")
        }
    }

    fun detect(bitmap: Bitmap): List<TrafficLightDetection> {
        val t0 = System.nanoTime()
        val input = preprocess(bitmap)
        val th = if (diagnosticMode) DIAGNOSTIC_CONFIDENCE_THRESHOLD else confidenceThreshold

        val r = when (spec.kind) {
            ModelKind.KAIRESS_YOLOV5 -> runYolov5(input, th)
            ModelKind.HYBRID_P2 -> runYolov11(input, th)
        }

        lastStats = DetectionStats(
            inferenceMs = (System.nanoTime() - t0) / 1_000_000,
            rawCandidatesAboveThreshold = r.rawAboveThreshold,
            finalDetections = r.detections.size,
            peakConfidence = r.peakAll,
            peakConfRed = r.peakRed,
            peakConfGreen = r.peakGreen,
            confidenceThresholdUsed = th,
            diagnosticMode = diagnosticMode,
        )
        return r.detections
    }

    /**
     * YOLOv5: [1, 25500, 8] anchor-major. row = [cx,cy,w,h,obj, zebra,red,green]
     * confidence = objectness × class_score  (objectness 를 빼먹으면 배경도 고득점 → 오탐 폭증)
     * 리매핑: R_Signal(1)→앱0(red), G_Signal(2)→앱1(green), Zebra_Cross(0)→제외
     */
    private fun runYolov5(input: ByteBuffer, threshold: Float): PostprocessResult {
        val output = Array(1) { Array(spec.numAnchors) { FloatArray(spec.numOutputChannels) } }
        interpreter.run(input, output)
        val rows = output[0]

        val candidates = ArrayList<TrafficLightDetection>(64)
        var peakAll = 0f; var peakRed = 0f; var peakGreen = 0f
        var rawAboveThreshold = 0
        var crosswalkSeen = false

        for (i in 0 until spec.numAnchors) {
            val row = rows[i]
            val obj = row[4]
            val confCrosswalk = obj * row[5 + CLS_ZEBRA]
            val confRed = obj * row[5 + CLS_R_SIGNAL]
            val confGreen = obj * row[5 + CLS_G_SIGNAL]

            // peak 는 threshold 무관하게 전 anchor 집계 — "임계 아래라도 보고는 있는가" 진단용.
            if (confRed > peakRed) peakRed = confRed
            if (confGreen > peakGreen) peakGreen = confGreen
            if (confCrosswalk >= threshold) crosswalkSeen = true

            val appClassId: Int
            val score: Float
            if (confRed >= confGreen) { appClassId = APP_CLASS_RED; score = confRed }
            else { appClassId = APP_CLASS_GREEN; score = confGreen }
            if (score > peakAll) peakAll = score

            if (score < threshold) continue
            if (confCrosswalk > score) continue   // 신호등보다 횡단보도에 가까운 anchor
            rawAboveThreshold++

            candidates += TrafficLightDetection(
                classId = appClassId,
                label = APP_CLASS_NAMES[appClassId],
                confidence = score,
                bbox = decodeBbox(row[0], row[1], row[2], row[3]),
            )
        }

        lastCrosswalkVisible = crosswalkSeen
        return PostprocessResult(nonMaxSuppression(candidates), rawAboveThreshold, peakAll, peakRed, peakGreen)
    }

    /** YOLOv11: [1, 6, 34000] channel-major. objectness 없음 (anchor-free). */
    private fun runYolov11(input: ByteBuffer, threshold: Float): PostprocessResult {
        val output = Array(1) { Array(spec.numOutputChannels) { FloatArray(spec.numAnchors) } }
        interpreter.run(input, output)
        val ch = output[0]

        val candidates = ArrayList<TrafficLightDetection>(64)
        var peakAll = 0f; var peakRed = 0f; var peakGreen = 0f
        var rawAboveThreshold = 0

        for (i in 0 until spec.numAnchors) {
            val scoreRed = ch[4][i]
            val scoreGreen = ch[5][i]
            if (scoreRed > peakRed) peakRed = scoreRed
            if (scoreGreen > peakGreen) peakGreen = scoreGreen

            val maxScore: Float
            val maxClass: Int
            if (scoreRed >= scoreGreen) { maxScore = scoreRed; maxClass = APP_CLASS_RED }
            else { maxScore = scoreGreen; maxClass = APP_CLASS_GREEN }
            if (maxScore > peakAll) peakAll = maxScore

            if (maxScore < threshold) continue
            rawAboveThreshold++

            candidates += TrafficLightDetection(
                classId = maxClass,
                label = APP_CLASS_NAMES[maxClass],
                confidence = maxScore,
                bbox = decodeBbox(ch[0][i], ch[1][i], ch[2][i], ch[3][i]),
            )
        }

        lastCrosswalkVisible = false
        return PostprocessResult(nonMaxSuppression(candidates), rawAboveThreshold, peakAll, peakRed, peakGreen)
    }

    /**
     * bbox 스케일 자동 감지 — export 옵션에 따라 픽셀 좌표일 수도, 이미 0~1 정규화일 수도 있다.
     * raw 최댓값이 1.5 초과면 픽셀 단위로 간주 (정규화 값은 1.0 을 넘지 않는다).
     */
    private fun decodeBbox(cx: Float, cy: Float, w: Float, h: Float): BoundingBox {
        val maxRaw = maxOf(maxOf(cx, cy), maxOf(w, h))
        val s = if (maxRaw > 1.5f) 1f / spec.inputSize else 1f
        return BoundingBox(cx * s, cy * s, w * s, h * s)
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val size = spec.inputSize
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((p and 0xFF) / 255.0f)
        }
        buffer.rewind()
        return buffer
    }

    private fun nonMaxSuppression(detections: List<TrafficLightDetection>): List<TrafficLightDetection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<TrafficLightDetection>(sorted.size)
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best
            sorted.removeAll { o -> o.classId == best.classId && iou(best.bbox, o.bbox) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val x1 = max(a.left, b.left); val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right); val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.area + b.area - inter
        return if (union > 0f) inter / union else 0f
    }

    fun close() {
        try { interpreter.close() } catch (_: Exception) {}
    }

    private data class PostprocessResult(
        val detections: List<TrafficLightDetection>,
        val rawAboveThreshold: Int,
        val peakAll: Float,
        val peakRed: Float,
        val peakGreen: Float,
    )

    private data class ModelSpec(
        val kind: ModelKind,
        val assetFilename: String,
        val inputSize: Int,
        val numClasses: Int,
        val numAnchors: Int,
        val defaultConfidence: Float,
    ) {
        /** YOLOv5 는 objectness 채널이 하나 더 있다. */
        val numOutputChannels: Int
            get() = when (kind) {
                ModelKind.KAIRESS_YOLOV5 -> 4 + 1 + numClasses   // 8
                ModelKind.HYBRID_P2 -> 4 + numClasses            // 6
            }
    }

    companion object {
        private const val TAG = "TrafficLightDetector"

        /** ⭐ 사용할 모델. 이 한 줄만 바꾸면 전환된다. */
        private val ACTIVE_MODEL = ModelKind.KAIRESS_YOLOV5

        /** ACTIVE_MODEL 의 tflite 가 assets 에 없을 때 쓸, 항상 커밋된 검증 모델. */
        private val FALLBACK_MODEL = ModelKind.HYBRID_P2

        private val SPECS = mapOf(
            ModelKind.KAIRESS_YOLOV5 to ModelSpec(
                kind = ModelKind.KAIRESS_YOLOV5,
                assetFilename = "crosswalk_kairess.tflite",
                inputSize = 640,
                numClasses = 3,
                // P6 @640: 3 × (80² + 40² + 20² + 10²) = 3 × 8,500 = 25,500
                numAnchors = 25_500,
                defaultConfidence = 0.25f,
            ),
            ModelKind.HYBRID_P2 to ModelSpec(
                kind = ModelKind.HYBRID_P2,
                assetFilename = "safewalknav_tl.tflite",
                inputSize = 640,
                numClasses = 2,
                // P2 head: 160² + 80² + 40² + 20² = 34,000
                numAnchors = 34_000,
                defaultConfidence = 0.2f,
            ),
        )

        const val DIAGNOSTIC_CONFIDENCE_THRESHOLD: Float = 0.15f

        // kairess data/crosswalk.yaml → names: ['Zebra_Cross', 'R_Signal', 'G_Signal']
        // ⚠️ 이 순서가 틀리면 빨간불을 초록불로 안내한다. export 시 반드시 검증할 것.
        private const val CLS_ZEBRA = 0
        private const val CLS_R_SIGNAL = 1
        private const val CLS_G_SIGNAL = 2

        // 앱 계약 (모델 무관 고정)
        const val APP_CLASS_RED = 0
        const val APP_CLASS_GREEN = 1
        private val APP_CLASS_NAMES = listOf("red_pedestrian", "green_pedestrian")
    }
}
