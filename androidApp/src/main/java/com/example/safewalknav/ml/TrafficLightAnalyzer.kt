package com.example.safewalknav.ml

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX ImageAnalysis 콜백 → TrafficLightDetector 추론.
 *
 * Frame skipping 적용 — 매 [frameIntervalMs] ms 마다 1프레임만 추론 (배터리/CPU 절약).
 * 카메라 30 FPS 기준 333 ms = 약 3 FPS 추론. 충분히 빠른 반응속도.
 *
 * @property detector       TFLite 검출기
 * @property onDetection    검출 결과 콜백 (메인 스레드 아님 주의 — UI 갱신은 runOnUiThread)
 * @property frameIntervalMs 추론 간격 (기본 333 ms)
 */
class TrafficLightAnalyzer(
    private val detector: TrafficLightDetector,
    private val frameIntervalMs: Long = 333L,
    private val isActive: () -> Boolean = { true },
    /** 추론이 [FAILURE_CALLBACK_THRESHOLD] 번 이상 연속 실패할 때마다 (연속 실패 횟수). 분석 스레드에서 불린다. */
    private val onFailure: (Int) -> Unit = {},
    private val onDetection: (List<TrafficLightDetection>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val lastFrameAt = AtomicLong(0L)

    /** 연속 추론 실패 횟수. 단일 분석 스레드에서만 접근하므로 동기화 불필요. */
    private var consecutiveFailures = 0

    override fun analyze(image: ImageProxy) {
        // 비활성 (예: 횡단보도 zone 밖) — 추론 자체 안 돌림. CPU/배터리 0 비용.
        if (!isActive()) {
            image.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt.get() < frameIntervalMs) {
            image.close()   // skip
            return
        }
        lastFrameAt.set(now)

        try {
            val bitmap = imageProxyToBitmap(image)
            val detections = detector.detect(bitmap)
            consecutiveFailures = 0
            onDetection(detections)
        } catch (e: Throwable) {
            // Throwable — 비트맵 OOM 은 Exception 이 아니라서 예전 catch 로는 분석 스레드가 죽었다.
            Log.e(TAG, "Analyze frame failed", e)
            detector.clearLastResult()
            // ⚠️ 예외를 삼키고 끝내면 호출자는 "조용한 정상"과 구별할 수 없다.
            //    (출력 shape 불일치·NNAPI 위임 실패·비트맵 OOM 등이 실제로 이 경로다)
            // ⚠️ 2026-10: 예전에는 빈 결과(검출 0건)로 위장해 넘겼다. 그러면 미탐지 안내가 나가며
            //    생존 신호로 세어져 워치독이 영영 안 울렸다. 이제 실패는 실패로 따로 알린다.
            consecutiveFailures++
            if (consecutiveFailures == FAILURE_CALLBACK_THRESHOLD) {
                // NNAPI 드라이버가 run() 에서 터지는 기기 — CPU 로 바꿔 한 번 살려 본다.
                if (detector.fallbackToCpu()) consecutiveFailures = 0
            }
            if (consecutiveFailures >= FAILURE_CALLBACK_THRESHOLD) {
                onFailure(consecutiveFailures)
            }
        } finally {
            image.close()
        }
    }

    /**
     * ImageProxy → Bitmap 변환.
     * `image.toBitmap()` 은 camera-core 1.3.0+ 에서 제공 (우리 의존성 1.3.1).
     * 카메라 회전 (rotationDegrees) 을 보정해서 똑바로 세움.
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val degrees = image.imageInfo.rotationDegrees
        return if (degrees == 0) raw else rotate(raw, degrees.toFloat())
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        private const val TAG = "TrafficLightAnalyzer"

        /**
         * 이만큼 연속 실패하면 빈 결과로 콜백해 미탐지 안내 사다리를 깨운다.
         * 일시적인 한두 프레임 실패로 "신호등을 못 찾는다"고 말하지는 않도록 여유를 둔다.
         */
        private const val FAILURE_CALLBACK_THRESHOLD = 3
    }
}
