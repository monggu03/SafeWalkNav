package com.example.safewalknav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.safewalknav.ml.TrafficLightAnalyzer
import com.example.safewalknav.ml.TrafficLightDetection
import com.example.safewalknav.ml.TrafficLightDetector
import com.example.safewalknav.navigation.signal.RawSignalDetection
import com.example.safewalknav.navigation.signal.SignalDecision
import com.example.safewalknav.navigation.signal.SignalDecisionEngine
import com.example.safewalknav.navigation.signal.SignalTransition
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SafeWalk — 시각장애인용 보행 신호등 인식 앱 (카메라 단일 기능).
 *
 * 흐름: 앱 실행 → (SafetyNoticeActivity 고지 게이트) → MainActivity 진입 즉시
 *       후방 카메라 + 신호등 인식 시작 → 색/전환을 음성·진동으로 안내.
 *
 * 특별한 점: 한국 보행 신호등에 맞춘 모델(kairess, `crosswalk_kairess.tflite`)을 온디바이스로 사용.
 * 판정 로직은 shared 모듈의 [SignalDecisionEngine] 에 있어 Android/iOS 동작이 일치한다.
 *
 * 2026-07 — 도보 내비게이션(TMap 경로·GPS·목적지 입력·상태기계)을 전면 제거하고
 *           "열면 바로 신호 인식" 단일 기능으로 재구성. (OKO 식)
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ===== 뷰 =====
    private lateinit var cameraPreviewContainer: FrameLayout
    private lateinit var overlayColorView: View
    private lateinit var overlayStatusText: TextView

    // ===== 카메라 / 검출 =====
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysisExecutor: ExecutorService? = null
    private var trafficLightDetector: TrafficLightDetector? = null

    // ===== 신호 판정 (shared 공용 엔진) =====
    private val signalDecisionEngine = SignalDecisionEngine()

    // ===== TTS / 진동 =====
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var ttsSpeaking = false
    private lateinit var vibrator: Vibrator
    private var introSpoken = false

    // ===== 미탐지 안내 (카메라 켜고 6초 대기 → 12초 간격 반복 → 20초 넘으면 소리주의 1회) =====
    private var noDetStartedAt = 0L
    private var noDetLastSpeechAt = 0L
    private var noDetSafetyDone = false
    private val NO_DET_FIRST_MS = 6_000L
    private val NO_DET_REPEAT_MS = 12_000L
    private val NO_DET_SAFETY_MS = 20_000L
    private val NO_DET_SUPPRESS_PEAK_CONFIDENCE = 0.25f
    private var detectStartHoldUntil = 0L
    private val DETECT_START_HOLD_MS = 3_000L   // 카메라 켠 직후 겨눌 시간

    private val CAMERA_PERMISSION_CODE = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreviewContainer = findViewById(R.id.cameraPreviewContainer)
        overlayColorView = findViewById(R.id.overlayColorView)
        overlayStatusText = findViewById(R.id.overlayStatusText)

        tts = TextToSpeech(this, this)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setIdleVisual()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }
    }

    private fun hasCameraPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (hasCameraPermission()) {
                startCamera()
            } else {
                overlayStatusText.text = "카메라 권한이 필요합니다"
                if (ttsReady) speakTts("카메라 권한이 필요합니다. 설정에서 허용해 주세요.")
            }
        }
    }

    // ==================== 카메라 ====================

    private fun startCamera() {
        if (cameraProvider != null) return   // 이미 작동 중
        if (!hasCameraPermission()) return

        val pv = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraPreviewContainer.removeAllViews()
        cameraPreviewContainer.addView(pv)

        if (trafficLightDetector == null) {
            try {
                trafficLightDetector = TrafficLightDetector(this).apply { diagnosticMode = false }
                Log.d(TAG, "TrafficLightDetector loaded")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load TrafficLightDetector", e)
                overlayStatusText.text = "모델 로드 실패"
            }
        }
        if (analysisExecutor == null) analysisExecutor = Executors.newSingleThreadExecutor()

        // 신호 판정 상태 초기화 + 겨눌 시간(3초)은 미탐지 안내 억제.
        signalDecisionEngine.reset()
        resetNoDetection()
        detectStartHoldUntil = System.currentTimeMillis() + DETECT_START_HOLD_MS

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }
                val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)

                val detector = trafficLightDetector
                val executor = analysisExecutor
                if (detector != null && executor != null) {
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(
                        executor,
                        TrafficLightAnalyzer(detector = detector, isActive = { true }) { detections ->
                            runOnUiThread { onTrafficLightDetected(detections) }
                        }
                    )
                    useCases += analysis
                }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()
                )
                Log.d(TAG, "Camera bound (use cases: ${useCases.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                cameraProvider = null
                camera = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        camera = null
        cameraPreviewContainer.removeAllViews()
        signalDecisionEngine.reset()
        resetNoDetection()
    }

    // ==================== 신호 검출 처리 ====================

    private fun onTrafficLightDetected(detections: List<TrafficLightDetection>) {
        val stats = trafficLightDetector?.lastStats

        // 필드 디버깅용 — 매 추론의 peak 신뢰도(R/G)를 logcat 에 남긴다.
        if (BuildConfig.DEBUG && stats != null) {
            Log.d(
                "TL_DIAG",
                "raw=${detections.size} above=${stats.rawCandidatesAboveThreshold} " +
                    "peak=${"%.2f".format(stats.peakConfidence)} " +
                    "(R=${"%.2f".format(stats.peakConfRed)} G=${"%.2f".format(stats.peakConfGreen)}) " +
                    "th=${"%.2f".format(stats.confidenceThresholdUsed)} ms=${stats.inferenceMs}"
            )
        }

        if (detections.isEmpty()) {
            setIdleVisual()
            // 약한 후보(임계 근처)가 보이면 미탐지 안내를 억제 — 곧 잡힐 수 있음.
            if (stats != null && stats.peakConfidence >= NO_DET_SUPPRESS_PEAK_CONFIDENCE) {
                resetNoDetection()
                return
            }
            handleNoDetection()
            return
        }

        val rawSignals = detections.map {
            RawSignalDetection(it.classId, it.confidence, it.bbox.width, it.bbox.height)
        }
        val decision = signalDecisionEngine.decide(rawSignals, System.currentTimeMillis())
        updateOverlay(decision)

        when (decision) {
            is SignalDecision.Silent -> {
                resetNoDetection()
            }

            is SignalDecision.Repeat -> {
                resetNoDetection()
                speakTrafficLight(repeatMessage(decision.color), interrupt = false)
            }

            is SignalDecision.Flicker -> {
                resetNoDetection()
                speakTrafficLight("신호가 깜빡입니다. 멈춰서 다음 신호를 기다리세요.", interrupt = true)
                vibrateWarning()
            }

            is SignalDecision.Announce -> {
                resetNoDetection()
                val message = when (decision.transition) {
                    SignalTransition.RED_NEW -> "빨간불입니다. 정지하세요."
                    SignalTransition.GREEN_TO_RED -> "빨간불입니다. 정지하세요."
                    SignalTransition.RED_TO_GREEN -> "방금 초록불로 바뀌었습니다. 안전을 확인하고 건너세요."
                    SignalTransition.STATIC_GREEN -> "초록불입니다. 일단 멈춰서 다음 신호를 기다리세요."
                }
                speakTrafficLight(message, interrupt = decision.interrupt)
                if (decision.vibrate) vibrateShort()
                Log.d(TAG, "TL announce: $message (conf=${decision.confidence})")
            }
        }
    }

    private fun resetNoDetection() {
        noDetStartedAt = 0L
        noDetLastSpeechAt = 0L
        noDetSafetyDone = false
    }

    /** 신호등을 못 잡을 때 — 조용히 대기했다가 같은 문장을 간격 두고 반복. */
    private fun handleNoDetection() {
        val now = System.currentTimeMillis()
        if (now < detectStartHoldUntil) {
            resetNoDetection()
            return
        }
        if (noDetStartedAt == 0L) noDetStartedAt = now
        val elapsed = now - noDetStartedAt
        if (elapsed < NO_DET_FIRST_MS) return

        if (elapsed >= NO_DET_SAFETY_MS && !noDetSafetyDone) {
            noDetSafetyDone = true
            noDetLastSpeechAt = now
            speakTrafficLight("신호등이 잘 잡히지 않습니다. 주변 소리에 주의하세요.", interrupt = false)
            return
        }
        if (noDetLastSpeechAt == 0L || now - noDetLastSpeechAt >= NO_DET_REPEAT_MS) {
            noDetLastSpeechAt = now
            speakTrafficLight(crosswalkGuidanceMessage(), interrupt = false)
        }
    }

    /**
     * 신호등이 안 잡힐 때의 방향 안내.
     * 모델이 횡단보도(Zebra_Cross)를 검출했으면 그 화면상 위치로 "어느 쪽으로 돌릴지" 짚어주고,
     * 아무것도 없으면 막연한 좌우 스윕 안내로 폴백한다. (kairess 모델의 횡단보도 클래스 활용)
     */
    private fun crosswalkGuidanceMessage(): String {
        val cx = trafficLightDetector?.lastCrosswalkCenterX ?: -1f
        return when {
            cx < 0f -> "신호등을 찾고 있습니다. 카메라를 천천히 좌우로 움직여 주세요."
            cx < 0.40f -> "횡단보도가 왼쪽에 보입니다. 카메라를 왼쪽으로 조금 돌려 주세요."
            cx > 0.60f -> "횡단보도가 오른쪽에 보입니다. 카메라를 오른쪽으로 조금 돌려 주세요."
            else -> "횡단보도가 정면에 있습니다. 카메라를 조금 위로 올려 신호등을 비춰 주세요."
        }
    }

    private fun repeatMessage(classId: Int): String = when (classId) {
        0 -> "빨간불입니다. 정지하세요."
        1 -> "초록불입니다."
        else -> "신호를 확인하세요."
    }

    // ==================== 화면 오버레이 ====================

    private fun setIdleVisual() {
        overlayColorView.setBackgroundColor(0x00000000)
        overlayStatusText.text = "신호등을\n비춰주세요"
    }

    private fun updateOverlay(decision: SignalDecision) {
        val red = 0x66D50000.toInt()
        val green = 0x6600C853.toInt()
        // 화면 글씨는 "초록불"/"빨간불" 두 단어만. 나머지 안내(건너세요 등)는 음성이 담당.
        when (decision) {
            is SignalDecision.Announce -> when (decision.transition) {
                SignalTransition.RED_TO_GREEN -> { overlayColorView.setBackgroundColor(green); overlayStatusText.text = "초록불" }
                SignalTransition.STATIC_GREEN -> { overlayColorView.setBackgroundColor(green); overlayStatusText.text = "초록불" }
                else -> { overlayColorView.setBackgroundColor(red); overlayStatusText.text = "빨간불" }
            }
            is SignalDecision.Repeat ->
                if (decision.color == 0) { overlayColorView.setBackgroundColor(red); overlayStatusText.text = "빨간불" }
                else { overlayColorView.setBackgroundColor(green); overlayStatusText.text = "초록불" }
            is SignalDecision.Flicker -> { overlayColorView.setBackgroundColor(red); overlayStatusText.text = "빨간불" }
            is SignalDecision.Silent -> { /* 확신 부족/안정성 대기 — 현재 화면 유지 */ }
        }
    }

    // ==================== TTS / 진동 ====================

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            ttsReady = true
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { ttsSpeaking = true }
                override fun onDone(utteranceId: String?) { ttsSpeaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { ttsSpeaking = false }
            })
            if (!introSpoken) {
                introSpoken = true
                speakTts("SafeWalk입니다. 신호등을 카메라에 비춰 주세요.")
            }
        }
    }

    private fun speakTts(message: String) {
        if (!ttsReady) return
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, message.hashCode().toString())
    }

    private fun speakTrafficLight(message: String, interrupt: Boolean) {
        if (!ttsReady) return
        tts.speak(
            message,
            if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            if (interrupt) "tl_urgent" else "tl",
        )
    }

    private fun vibrateShort() {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateWarning() {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150, 100, 150), -1))
    }

    // ==================== 라이프사이클 ====================

    override fun onResume() {
        super.onResume()
        if (cameraProvider == null && hasCameraPermission()) startCamera()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        trafficLightDetector?.close()
        trafficLightDetector = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    companion object {
        private const val TAG = "SafeWalkNav"
    }
}
