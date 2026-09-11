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
import com.example.safewalknav.ml.MotionGate
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
import java.util.concurrent.TimeUnit

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
 *
 * 2026-09 — 사용자 동행 테스트에서 두 가지가 드러났다.
 *   (1) 신호등이 어디 있는지 몰라 폰을 빠르게 휘두른다 → 블러 + 안정성 스트릭 리셋으로
 *       오히려 더 안 잡힌다.  → [MotionGate] 로 그 동안 추론을 건너뛴다.
 *   (2) "앱에서 소리가 너무 많이 난다".  → 조준 안내를 음성에서 [AimingFeedback] 진동으로
 *       옮기고, 남은 발화의 간격과 길이를 줄였다.
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
    private lateinit var vibrator: Vibrator
    private var introSpoken = false

    /** 모델 로드 실패 — TTS 준비 전에 났을 수 있어, 준비되면 인트로 대신 이걸 말한다. */
    private var modelLoadFailed = false

    /**
     * 카메라 권한 거부 — 모델 실패와 같은 이유로 플래그가 필요하다.
     * 권한 다이얼로그는 앱을 켜자마자 뜨는데 TTS 초기화는 수백 ms 걸린다. 그래서
     * 거부 시점에는 [ttsReady] 가 대개 false 고, 그대로 두면 안내가 통째로 증발한 뒤
     * onInit 이 태연하게 "신호등을 카메라에 비춰 주세요" 라고 말한다.
     * 카메라도 분석기도 없으니 그 뒤로는 **영원히 아무 소리도 나지 않는다.**
     */
    private var permissionDenied = false

    // ===== 조준 보조 (2026-09) =====
    /** 대상이 중앙에 가까울수록 빨라지는 진동. 음성 조준 안내를 대체한다. */
    private lateinit var aiming: AimingFeedback

    /**
     * 빠르게 휘두르는 동안 추론을 건너뛰는 게이트.
     * 분석 스레드가 읽으므로 @Volatile. 소멸 시에도 null 로 만들지 않고 stop() 만 한다
     * (분석 스레드가 들고 있던 참조로 접근해도 안전한 상태가 되도록).
     */
    @Volatile
    private var motionGate: MotionGate? = null

    /**
     * 카메라가 살아 있고 분석 결과를 받아도 되는가.
     *
     * ⚠️ 이 플래그가 없으면 진동이 영영 안 꺼지는 경로가 생긴다.
     * 분석 스레드가 `detect()` 안에 있는 동안 사용자가 앱을 백그라운드로 보내면,
     * `onPause → stopCamera()` 가 먼저 끝나고 **그 뒤에** 늦은 결과가
     * `runOnUiThread` 로 도착한다. 그게 [AimingFeedback.update] 를 불러 repeat 파형을
     * 다시 걸면, 그걸 끌 주체가 아무도 남아 있지 않다 — 재부팅 전까지 계속 울린다.
     * 시각장애인 사용자에게는 "지금 잘 겨누고 있다"는 거짓 신호이기도 하다.
     */
    @Volatile
    private var analysisEnabled = false

    /** 마지막으로 검출이 1건이라도 있었던 시각. [updateAiming] 이 "아직 보고 있나" 판단에 쓴다. */
    private var lastDetectionAtMs = 0L

    /** 엔진이 마지막으로 **유효 판정**(Announce·Repeat·Flicker)을 낸 시각. 박스 개수와 무관. */
    private var lastValidatedAtMs = 0L

    /** startCamera 가 비동기 바인딩을 기다리는 중인지. 중복 시작 방지. */
    private var cameraStarting = false

    /**
     * 카메라 시작 세대 번호. startCamera 마다 +1, stopCamera 에서도 +1.
     * 비동기 리스너는 자기 세대가 아직 유효할 때만 바인딩한다 —
     * pause→resume 이 빠르게 일어나면 리스너가 두 개 떠서, 먼저 뜬(이미 화면에서 떼어낸
     * PreviewView 를 들고 있는) 쪽이 나중 것을 덮어쓸 수 있다.
     */
    private var cameraGeneration = 0

    // ===== 미탐지 안내 (카메라 켜고 6초 대기 → 30초 간격 반복 → 20초 넘으면 소리주의 1회) =====
    private var noDetStartedAt = 0L
    private var noDetLastSpeechAt = 0L
    private var noDetSafetyDone = false
    private val NO_DET_FIRST_MS = 6_000L

    // 2026-09: 12초 → 30초. 조준 정보는 이제 진동이 실시간으로 주므로, 음성은
    // "아직도 못 찾고 있다"는 사실만 가끔 확인해 주면 된다.
    private val NO_DET_REPEAT_MS = 30_000L
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
        aiming = AimingFeedback(vibrator)
        motionGate = MotionGate(
            context = this,
            // 휘두르기 시작 → 조준 진동을 즉시 끈다. 추론이 멈춰 콜백이 안 오므로
            // 여기서 끄지 않으면 마지막 박자가 그대로 반복된다(거짓 정보).
            onFastMotionStart = { aiming.stop() },
            // 1.5초 넘게 계속 휘두르면 한 번만 짚어 준다 (쿨다운은 MotionGate 가 관리).
            onSustainedFastMotion = { speakTrafficLight("천천히 돌려 주세요", interrupt = false) },
        )

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
                permissionDenied = false
                startCamera()
            } else {
                overlayStatusText.text = "카메라 권한이 필요합니다"
                permissionDenied = true
                // ttsReady 가 false 면 여기서는 묻히고, onInit 이 대신 말한다.
                speakTts(PERMISSION_DENIED_MESSAGE)
            }
        }
    }

    // ==================== 카메라 ====================

    private fun startCamera() {
        if (cameraProvider != null) return   // 이미 작동 중
        // cameraProvider 는 비동기 리스너 안에서야 채워진다. 그 사이에 onCreate→onResume 이
        // 연달아 startCamera 를 부르면 PreviewView 와 바인딩이 이중으로 생긴다.
        if (cameraStarting) return
        if (!hasCameraPermission()) return
        cameraStarting = true
        val generation = ++cameraGeneration

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
                // 화면 글씨만 바꾸면 시각장애인에게는 '아무 일도 안 일어남'과 구별되지 않는다.
                // 분석기가 아예 안 붙으므로 미탐지 안내조차 돌지 않아 영영 침묵한다.
                // onResume 마다 재시도하며 같은 말을 반복하지 않도록 한 번만.
                if (!modelLoadFailed) {
                    modelLoadFailed = true
                    speakTts(MODEL_LOAD_FAILED_MESSAGE)
                }
            }
        }
        if (analysisExecutor == null) analysisExecutor = Executors.newSingleThreadExecutor()

        // 신호 판정 상태 초기화 + 겨눌 시간(3초)은 미탐지 안내 억제.
        signalDecisionEngine.reset()
        resetNoDetection()
        resetAimingState()
        detectStartHoldUntil = System.currentTimeMillis() + DETECT_START_HOLD_MS

        // 모션 게이트 가동. 센서가 없는 기기면 start() 가 조용히 아무것도 안 한다.
        motionGate?.reset()
        motionGate?.start()
        aiming.stop()
        analysisEnabled = true

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                // provider 획득은 비동기다. 그 사이에 stopCamera 나 더 나중의 startCamera 가
                // 지나갔다면 이 리스너는 한물간 것이다. 그대로 바인딩하면 이미 컨테이너에서
                // 떼어낸 PreviewView 에 붙어 검은 화면 + 분석만 도는 좀비 상태가 된다.
                // 한물간 리스너는 **아무것도 바인딩한 적이 없으므로** unbindAll 하지 않는다.
                // 여기서 unbind 하면, 만에 하나 리스너 실행 순서가 뒤집혔을 때 살아 있는
                // 바인딩을 뜯어내 검은 화면으로 만든다. 그냥 빠져나가는 게 안전하다.
                if (generation != cameraGeneration || !analysisEnabled) {
                    Log.d(TAG, "stale camera listener (gen=$generation, now=$cameraGeneration) — skip")
                    return@addListener
                }
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
                        // 원거리 신호를 위해 분석 프레임 해상도를 높인다(기본 저해상도면 640 리사이즈에서 뭉갬).
                        .setTargetResolution(android.util.Size(1280, 720))
                        .build()
                    analysis.setAnalyzer(
                        executor,
                        // 빠르게 휘두르는 동안은 추론 자체를 건너뛴다 — 블러 프레임이
                        // 안정성 스트릭을 계속 0 으로 되돌리는 악순환을 끊는다.
                        TrafficLightAnalyzer(
                            detector = detector,
                            isActive = { motionGate?.isFastMotion != true },
                        ) { detections ->
                            runOnUiThread { onTrafficLightDetected(detections) }
                        }
                    )
                    useCases += analysis
                }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()
                )
                val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                camera?.cameraControl?.setZoomRatio(minOf(2.0f, maxZoom))
                Log.d(TAG, "Camera bound (use cases: ${useCases.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                cameraProvider = null
                camera = null
            } finally {
                // 한물간 리스너가 현재 진행 중인 시작의 플래그를 내리면 안 된다.
                if (generation == cameraGeneration) cameraStarting = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        // 가장 먼저 끈다 — 이 뒤에 도착하는 늦은 분석 콜백을 전부 무시하기 위해.
        analysisEnabled = false
        // 진행 중이던 비동기 시작을 무효화하고, 다음 startCamera 가 막히지 않게 푼다.
        cameraGeneration++
        cameraStarting = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        camera = null
        if (::cameraPreviewContainer.isInitialized) cameraPreviewContainer.removeAllViews()
        signalDecisionEngine.reset()
        resetNoDetection()
        resetAimingState()
        // 화면을 떠나는데 진동이 계속 뛰면 안 된다.
        motionGate?.stop()
        if (::aiming.isInitialized) aiming.stop()
    }

    // ==================== 신호 검출 처리 ====================

    private fun onTrafficLightDetected(detections: List<TrafficLightDetection>) {
        // stopCamera 이후 도착한 늦은 콜백 — 진동·발화를 되살리면 안 된다.
        if (!analysisEnabled) return

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
            updateAiming()
            // 약한 후보(임계 근처)가 보이면 미탐지 안내를 억제 — 곧 잡힐 수 있음.
            if (stats != null && stats.peakConfidence >= NO_DET_SUPPRESS_PEAK_CONFIDENCE) {
                resetNoDetection()
                return
            }
            handleNoDetection()
            return
        }

        val now = System.currentTimeMillis()
        lastDetectionAtMs = now

        val rawSignals = detections.map {
            RawSignalDetection(it.classId, it.confidence, it.bbox.width, it.bbox.height)
        }
        val decision = signalDecisionEngine.decide(rawSignals, now)
        updateOverlay(decision)

        // 조준 진동을 언제 끄는가 — 검출 유무가 아니라 **엔진이 확정했는가**로 가른다.
        // 박스는 있는데 Silent 인 상태(너무 작다 / 확신 부족 / 안정성 프레임 대기)는
        // 사용자가 거의 다 맞춘 순간이다. 여기서 진동을 끊으면 가장 필요한 때 안내가 사라진다.
        when (decision) {
            is SignalDecision.Silent -> {
                resetNoDetection()
                updateAiming()
            }

            is SignalDecision.Repeat -> {
                resetNoDetection()
                lastValidatedAtMs = now
                aiming.stop()
                speakTrafficLight(repeatMessage(decision.color), interrupt = false)
            }

            is SignalDecision.Flicker -> {
                resetNoDetection()
                lastValidatedAtMs = now
                // 두 가지를 동시에 막는다.
                //  (1) 650ms 경고 패턴이 조준 진동에 덮어씌워지는 것.
                //  (2) 점멸 락아웃(엔진 6초) 동안 조준 진동이 되살아나는 것 —
                //      엔진은 점멸 시 확정색을 COLOR_NONE 으로 비우므로 updateAiming 의
                //      holdingSignal 이 false 가 되어, 막지 않으면 "멈춰서 기다리세요"를
                //      듣고 서 있는 6초 내내 손이 계속 울린다.
                aiming.holdOff(FLICKER_AIM_HOLD_MS)
                speakTrafficLight("신호가 깜빡입니다. 멈춰서 다음 신호를 기다리세요.", interrupt = true)
                vibrateWarning()
            }

            is SignalDecision.Announce -> {
                resetNoDetection()
                lastValidatedAtMs = now
                aiming.holdOff(VIBRATE_SHORT_HOLD_MS)   // 판정 진동이 잘리지 않도록
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

    /**
     * 조준 진동을 갱신할지 끌지 **한 곳에서** 결정한다.
     *
     * 기준은 [SignalDecision.Silent] 의 이유 코드가 아니라 **엔진이 색을 확정하고 있는가**다.
     * 이유 코드로 거르면 반드시 샌다 — 엔진은 확정 이후에도 프레임 품질에 따라
     * ALL_LOW_CONFIDENCE / ALL_TOO_SMALL 을 돌려주고, 검출이 0건인 프레임은 아예
     * 이 분기까지 오지도 않는다. 그 틈마다 조준 진동이 되살아나면
     * "빨간불입니다" 를 듣고 서 있는 내내 손이 계속 울린다.
     *
     * 다만 확정색만 보면 반대 문제가 생긴다 — 신호를 다 건넌 뒤 카메라를 돌려도
     * 확정색이 남아 있어 조준 도움이 영영 안 돌아온다. 그래서 "최근에 실제로 검출이
     * 있었는가"([AIM_RESUME_AFTER_MS])를 함께 본다.
     */
    private fun updateAiming() {
        val now = System.currentTimeMillis()
        val holdingSignal =
            signalDecisionEngine.confirmedColor != SignalDecisionEngine.COLOR_NONE &&
                // 검출이 아예 끊겼나 (카메라를 돌렸다)
                now - lastDetectionAtMs < AIM_RESUME_AFTER_MS &&
                // 박스는 계속 오는데 전부 엔진에 걸러지는 상태(약한 오탐이 흘러드는 경우)에
                // 확정색이 남아 조준 도움이 영영 안 돌아오는 것을 막는 상한.
                now - lastValidatedAtMs < AIM_HOLD_MAX_MS
        if (holdingSignal) {
            aiming.stop()
        } else {
            aiming.update(trafficLightDetector?.lastAimTargetCenterX)
        }
    }

    private fun resetNoDetection() {
        noDetStartedAt = 0L
        noDetLastSpeechAt = 0L
        noDetSafetyDone = false
    }

    /** 카메라 시작·정지 시 조준 판단용 타임스탬프를 비운다. */
    private fun resetAimingState() {
        lastDetectionAtMs = 0L
        lastValidatedAtMs = 0L
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
     *
     * 2026-09 — 좌/우/위만 남기고 전부 잘라냈다. "정렬이 얼마나 맞았는가"는 이제
     * [AimingFeedback] 진동이 실시간으로 알려주므로, 음성은 진동이 못 담는 **방향**
     * 한 단어만 담당한다. 진동 모터가 하나뿐이라 좌/우를 촉각으로는 구분할 수 없다.
     */
    private fun crosswalkGuidanceMessage(): String {
        // lastAimTargetCenterX 가 이미 (신호등 후보 → 횡단보도) 폴백을 품고 있다.
        val cx = trafficLightDetector?.lastAimTargetCenterX ?: -1f
        return when {
            cx < 0f -> "천천히 좌우로 움직여 주세요"
            cx < 0.40f -> "왼쪽으로"
            cx > 0.60f -> "오른쪽으로"
            else -> "조금 위로"
        }
    }

    /**
     * heartbeat(같은 색 12초 지속) 재안내 문구.
     * 첫 [SignalDecision.Announce] 는 "정지하세요" 같은 행동까지 온전히 말하지만,
     * 반복은 이미 들은 말이므로 색 한 단어로 줄인다. — 발화량 절감의 핵심.
     */
    private fun repeatMessage(classId: Int): String = when (classId) {
        0 -> "빨간불"
        1 -> "초록불"
        else -> "신호 확인"
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
            // 발화 진행 리스너는 제거했다 — 유일하던 소비자 ttsSpeaking 을 아무도 읽지 않았고,
            // onPause 의 tts.stop() 은 onDone 을 부르지 않아 true 로 굳는 함정이 있었다.
            // "말하는 중이면 끼어들지 않기" 를 넣을 때 다시 붙이되, stop() 경로를 함께 처리할 것.
            if (!introSpoken) {
                introSpoken = true
                // TTS 준비 전에 이미 실패했다면, 밝은 인트로 대신 그 사실을 말한다.
                // 이 분기가 없으면 카메라가 안 켜진 상태로 "비춰 주세요"만 듣고 영원히 침묵한다.
                when {
                    permissionDenied -> speakTts(PERMISSION_DENIED_MESSAGE)
                    modelLoadFailed -> speakTts(MODEL_LOAD_FAILED_MESSAGE)
                    else -> speakTts("SafeWalk입니다. 신호등을 카메라에 비춰 주세요.")
                }
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
        // 설정에서 권한을 켜고 돌아오는 경로는 onRequestPermissionsResult 를 거치지 않는다.
        if (hasCameraPermission()) permissionDenied = false
        if (cameraProvider == null && hasCameraPermission()) startCamera()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
        // 큐에 남은 발화를 비운다. 그러지 않으면 사용자가 폰을 주머니에 넣은 뒤에
        // "빨간불입니다. 정지하세요." 가 뒤늦게 나갈 수 있다 — 맥락 없는 안전 지시는
        // 아무 안내도 없는 것보다 위험하다.
        if (::tts.isInitialized) tts.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()          // 여기서 motionGate.stop() / aiming.stop() 이 함께 불린다

        // ⚠️ shutdown() 은 진행 중인 작업을 기다리지 않는다. 분석 스레드가
        // interpreter.run() 안에 있는데 close() 로 네이티브 메모리를 해제하면
        // SIGSEGV 로 죽는다 (Kotlin catch 로는 못 잡는 네이티브 크래시).
        var analysisDrained = true
        analysisExecutor?.let { ex ->
            ex.shutdown()
            analysisDrained = try {
                ex.awaitTermination(EXECUTOR_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
        analysisExecutor = null

        // 타임아웃이면 추론이 아직 돌고 있다는 뜻이다. 그 상태에서 close() 하면 죽는다.
        // 누수(인터프리터 하나)가 네이티브 크래시보다 낫다 — 어차피 프로세스 종료 중이다.
        if (analysisDrained) {
            trafficLightDetector?.close()
        } else {
            Log.w(TAG, "분석 스레드가 ${EXECUTOR_SHUTDOWN_WAIT_MS}ms 안에 안 끝남 — interpreter.close() 생략")
        }
        trafficLightDetector = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    companion object {
        private const val TAG = "SafeWalkNav"

        // 실패 안내는 발화 지점이 두 곳(즉시 / onInit 지연)이라 상수로 묶는다.
        private const val PERMISSION_DENIED_MESSAGE =
            "카메라 권한이 필요합니다. 설정에서 허용해 주세요."
        private const val MODEL_LOAD_FAILED_MESSAGE =
            "신호등 인식을 시작할 수 없습니다. 앱을 다시 실행해 주세요."

        /** vibrateShort(50ms) 보호 구간. */
        private const val VIBRATE_SHORT_HOLD_MS = 400L

        /**
         * 점멸 감지 후 조준 진동 차단 구간.
         * 경고 패턴(650ms) 보호 + 엔진의 점멸 락아웃(6초) + 재확정 몇 프레임을 모두 덮는다.
         */
        private const val FLICKER_AIM_HOLD_MS = 7_000L

        /** 확정색을 붙들고 있어도 이만큼 유효 판정이 없으면 조준 도움을 되살린다. */
        private const val AIM_HOLD_MAX_MS = 20_000L

        /** 분석 스레드가 추론 중일 수 있어 종료를 기다린다 (interpreter.close() 전). */
        private const val EXECUTOR_SHUTDOWN_WAIT_MS = 1_500L

        /**
         * 검출이 이만큼 끊기면 "더 이상 신호를 보고 있지 않다" 로 보고 조준 진동을 재개한다.
         * 추론이 3fps(≈333ms) 이므로 2초는 6프레임 — 순간 놓침으로는 발동하지 않는다.
         */
        private const val AIM_RESUME_AFTER_MS = 2_000L
    }
}
