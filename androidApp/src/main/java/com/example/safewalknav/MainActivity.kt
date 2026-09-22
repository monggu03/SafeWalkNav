package com.example.safewalknav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
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

    /** TTS 를 아예 쓸 수 없는 상태(엔진 초기화 실패·한국어 음성 없음). 진동으로만 안내한다. */
    private var ttsUnavailable = false

    /** 카메라 바인딩 실패 — TTS 준비 전에 날 수 있어, 준비되면 인트로 대신 이걸 말한다. */
    private var cameraBindFailed = false

    /** 볼륨 0 경고를 이미 했는가. 볼륨이 올라가면 다시 false 로 돌아가 재경고가 가능해진다. */
    private var volumeWarned = false

    // ===== 볼륨 키 조작 (2026-09) =====

    /** 사용자가 직접 일시정지했는가. onPause 로 인한 정지와 구분해야 onResume 이 멋대로 켜지 않는다. */
    private var userPaused = false

    /** 볼륨 다운 롱프레스를 이미 처리했는가. 키를 뗄 때 짧은 누름으로 중복 처리하지 않기 위함. */
    private var volumeLongPressHandled = false

    /** 일시정지 재알림을 몇 번 했는가. 일부러 멈춘 사람에게 영원히 잔소리하지 않기 위한 상한. */
    private var pausedReminderCount = 0

    /** 마지막으로 안내한 신호 문구·색·시각. 다시 듣기용. */
    private var lastAnnouncement: String? = null
    private var lastAnnouncedColor: Int = SignalDecisionEngine.COLOR_NONE
    private var lastAnnouncementAtMs = 0L

    /**
     * 접근성 용도 오디오 속성. **TTS 와 진동 양쪽**에 쓴다.
     *
     * TTS: 기본값(USAGE_MEDIA)은 미디어 볼륨을 타는데, 시각장애인 사용자는 미디어를 0 으로
     *      두고 접근성 볼륨만 쓰는 경우가 흔하다. 그러면 앱이 통째로 무음이 된다.
     * 진동: 속성이 없으면 USAGE_UNKNOWN 이라 절전 모드·시스템 진동 설정에서 억제된다.
     *      접근성 용도는 그 억제에서 면제된다.
     */
    private val a11yAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /**
     * 마지막으로 **발화를 큐에 넣은** 시각(단조 시계).
     * [livenessTick] 이 "살아 있는데 조용한 것"과 "죽어서 조용한 것"을 구분하는 근거.
     *
     * ⚠️ 진동 경로에서는 갱신하지 않는다. 조준 소나가 이걸 갱신하면
     * "계속 울리기만 하고 아무 판정도 안 나오는" 바로 그 상태에서 워치독이 영영 안 깨어난다.
     * 색 확정·경고 진동은 언제나 발화와 짝지어 나가므로 발화 쪽만 찍으면 충분하다.
     */
    private var lastAudibleAtMs = 0L

    /** 파이프라인이 멎었는지 감시하는 워치독 핸들러. */
    private val livenessHandler = Handler(Looper.getMainLooper())

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
    // ⚠️ 판정 임계(0.25)보다 반드시 낮아야 한다. 같으면 "임계 미만 후보" 억제가 영영 발동 안 함.
    private val NO_DET_SUPPRESS_PEAK_CONFIDENCE = 0.18f

    /** '곧 잡힐 듯' 억제의 시작 시각. 0 이면 비활성. */
    private var noDetSuppressStartedAt = 0L

    /** '곧 잡힐 듯' 억제의 최대 지속 시간 — 이걸 넘기면 약한 오탐으로 보고 안내를 돌린다. */
    private val NO_DET_SUPPRESS_MAX_MS = 10_000L
    private var detectStartHoldUntil = 0L
    private val DETECT_START_HOLD_MS = 3_000L   // 카메라 켠 직후 겨눌 시간

    private val CAMERA_PERMISSION_CODE = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreviewContainer = findViewById(R.id.cameraPreviewContainer)
        overlayColorView = findViewById(R.id.overlayColorView)
        overlayStatusText = findViewById(R.id.overlayStatusText)

        // ⚠️ 진동·조준을 **TTS 보다 먼저** 만든다.
        //    TextToSpeech 생성자는 엔진 바인딩에 실패하면 onInit(ERROR) 를 **동기로**,
        //    즉 생성자가 반환하기도 전에 호출한다. 그 분기가 진동으로 실패를 알리는데,
        //    순서가 반대면 그 순간 vibrator 가 아직 lateinit 미초기화라
        //    UninitializedPropertyAccessException 으로 onCreate 에서 죽는다.
        //    하필 "TTS 엔진이 없는 기기" — 그 분기가 도우려던 바로 그 사용자다.
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        aiming = AimingFeedback(vibrator, a11yAudioAttributes)

        tts = TextToSpeech(this, this)

        motionGate = MotionGate(
            context = this,
            // 휘두르기 시작 → 조준 진동을 즉시 끈다. 추론이 멈춰 콜백이 안 오므로
            // 여기서 끄지 않으면 마지막 박자가 그대로 반복된다(거짓 정보).
            onFastMotionStart = { aiming.stop() },
            // 1.5초 넘게 계속 휘두르면 한 번만 짚어 준다 (쿨다운은 MotionGate 가 관리).
            onSustainedFastMotion = {
                speakTrafficLight("천천히 돌려 주세요", interrupt = false, countsAsLiveness = false)
            },
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setIdleVisual()

        // 회전·복원으로 다시 만들어졌다면 일시정지 상태를 되살린다.
        userPaused = savedInstanceState?.getBoolean(STATE_USER_PAUSED, false) ?: false
        if (userPaused) {
            // ⚠️ 남은 알림 횟수도 함께 복원한다. startPausedReminder() 는 횟수를 0 으로
            //    되돌리므로, 다크모드 전환·글꼴 변경 한 번마다 잔소리 예산이 리필돼
            //    "3번만 알린다"는 약속이 깨진다 (onResume 쪽은 이미 지키고 있는 약속).
            pausedReminderCount = savedInstanceState?.getInt(STATE_PAUSED_REMINDER_COUNT, 0) ?: 0
            resumePausedReminder()
            return   // 카메라도 권한 요청도 하지 않는다 — 멈춰 있던 상태 그대로
        }

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
        startLivenessWatchdog()

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
                // 한물간 리스너의 실패는 무시한다 — 안 그러면 그 사이에 시작된
                // 일시정지 알림이나 새 카메라 세션을 엉뚱하게 뒤엎는다.
                if (generation != cameraGeneration) return@addListener
                // ⚠️ 예전에는 로그만 찍고 끝이었다. 그러면 분석기가 아예 안 붙어서
                //    onTrafficLightDetected 가 한 번도 안 불리고, 미탐지 안내 타이머조차
                //    돌지 않는다. 사용자는 인트로 한 마디를 듣고 **영원히 침묵**을 겪는다.
                //    다른 앱이 카메라를 쥐고 있거나 HAL 오류일 때 실제로 발생한다.
                Log.e(TAG, "Camera bind failed", e)
                cameraProvider = null
                camera = null
                analysisEnabled = false
                stopLivenessWatchdog()   // 살아날 수 없는 상태 — 45초마다 울려도 의미 없다
                motionGate?.stop()
                cameraBindFailed = true
                overlayStatusText.text = "카메라를 열 수 없음"
                // ⚠️ vibrateWarning 을 쓰면 안 된다 — 점멸 경고와 **같은 패턴**이라
                //    촉각만 남은 사용자가 '카메라 고장'을 '신호 점멸'로 읽는다.
                //    바인딩 실패는 TTS 초기화보다 먼저 끝날 수 있어 ttsUnavailable 이 아직
                //    false 일 수 있으므로, 여기서는 ttsReady 로 판단한다.
                if (!ttsReady) {
                    vibrateFatal()          // 자체적으로 holdOff 를 건다
                } else {
                    aiming.holdOff(VIBRATE_WARNING_HOLD_MS)
                    vibrateWarning()
                }
                speakTts(CAMERA_FAILED_MESSAGE)
            } finally {
                // 한물간 리스너가 현재 진행 중인 시작의 플래그를 내리면 안 된다.
                if (generation == cameraGeneration) cameraStarting = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        // 가장 먼저 끈다 — 이 뒤에 도착하는 늦은 분석 콜백을 전부 무시하기 위해.
        analysisEnabled = false
        stopLivenessWatchdog()
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
            // 약한 후보(임계 근처)가 보이면 미탐지 안내를 잠시 억제 — 곧 잡힐 수 있음.
            //
            // ⚠️ 두 가지 함정을 막아야 한다.
            //  (1) 이 임계가 판정 임계(0.25)와 같으면 "임계 미만의 약한 후보"는 조건을
            //      영영 못 넘어서, 실제로는 횡단보도 무늬 바닥을 비출 때만 발동했다 —
            //      정확히 잘못된 경우에만 억제하고 있었던 셈이다. 그래서 판정 임계보다
            //      확실히 낮게 둔다.
            //  (2) 무기한 억제 금지. 약한 오탐(간판·차양)이 계속 흘러들면 "곧 잡힐 듯"만
            //      반복하며 음성 사다리가 영영 침묵한다. 상한을 넘기면 억제를 풀고
            //      사다리를 돌린다.
            if (stats != null && stats.peakConfidence >= NO_DET_SUPPRESS_PEAK_CONFIDENCE) {
                val nowMs = System.currentTimeMillis()
                if (noDetSuppressStartedAt == 0L) noDetSuppressStartedAt = nowMs
                if (nowMs - noDetSuppressStartedAt < NO_DET_SUPPRESS_MAX_MS) {
                    resetNoDetection()
                    return
                }
                // 상한 초과 — 억제를 풀고 아래의 handleNoDetection 으로 떨어진다.
            } else {
                noDetSuppressStartedAt = 0L
            }
            handleNoDetection()
            return
        }

        val now = System.currentTimeMillis()
        lastDetectionAtMs = now
        noDetSuppressStartedAt = 0L   // 진짜 검출이 나왔으니 억제 창을 새로 시작

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
                val repeatMsg = repeatMessage(decision.color)
                rememberAnnouncement(repeatMsg, decision.color)
                speakTrafficLight(repeatMsg, interrupt = false)
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
                rememberAnnouncement(message, decision.color)
                speakTrafficLight(message, interrupt = decision.interrupt)
                // ⚠️ 전환(decision.vibrate)일 때만이 아니라 **모든 확정에서** 진동한다.
                //    예전에는 RED_NEW(횡단보도 앞에서 처음 만나는 빨간불)에 진동이 없었는데,
                //    음성이 안 들리는 상황에서는 그게 곧 "아무 일도 없었음"과 같다.
                //    색을 촉각으로 구분할 수 있게 패턴을 나눠 둔 이유이기도 하다.
                vibrateColor(decision.color)
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
        noDetSuppressStartedAt = 0L
    }

    // ==================== 생존 신호 (워치독) ====================

    /**
     * **이 앱의 가장 큰 결함은 생존 신호가 없다는 것이었다.**
     *
     * 시각장애인 사용자는 "정상인데 신호등이 안 보여서 조용한 것"과 "죽어서 조용한 것"을
     * 구분할 방법이 전혀 없었다. 그리고 죽는 경로가 생각보다 많다:
     *
     *   · 카메라 바인딩 실패 — catch 에서 로그만 찍고 끝 (분석기가 아예 안 붙는다)
     *   · 추론이 매 프레임 예외 — Analyzer 가 삼키고 onDetection 을 안 부른다
     *     → 미탐지 안내 타이머 자체가 돌지 않는다
     *   · 엔진이 계속 Silent — resetNoDetection() 이 불려서 미탐지 사다리가 0 으로 유지된다
     *     (박스가 하나라도 잡히는 한 영원히)
     *   · 발열로 추론이 1초를 넘김 — maxObservationGapMs 가 매 프레임 스트릭을 리셋해
     *     확정이 구조적으로 불가능해진다
     *
     * 전부 증상이 같다: **조용하고, 조준 진동은 계속 울린다.** 사용자는 "잘 겨누고 있으니
     * 곧 알려주겠지" 하고 횡단보도 앞에서 몇 분을 기다린다.
     *
     * 그래서 원인을 하나하나 막는 대신 **결과를 감시한다.** 실행 중인데
     * [LIVENESS_TIMEOUT_MS] 동안 들리거나 느껴지는 출력이 하나도 없었다면, 그 사실 자체를 알린다.
     */
    private fun livenessTick() {
        if (!analysisEnabled) return
        checkAudibleVolume()   // 사용자가 걷는 중에 볼륨을 내릴 수도 있다 — 매번 본다
        val now = SystemClock.elapsedRealtime()
        if (lastAudibleAtMs > 0L && now - lastAudibleAtMs >= LIVENESS_TIMEOUT_MS) {
            lastAudibleAtMs = now
            aiming.holdOff(VIBRATE_WARNING_HOLD_MS)
            // ⚠️ 음성이 없는 상태에서 vibrateWarning 을 쓰면 안 된다 —
            //    그건 "신호가 깜빡입니다. 멈추세요" 와 **똑같은 패턴**이라,
            //    촉각만 남은 사용자는 '앱이 멈췄다'를 '점멸 신호다'로 읽는다.
            //    최악의 경우 건너는 중에 그 진동을 받고 멈춰 선다.
            if (ttsUnavailable) vibrateFatal() else vibrateWarning()
            speakTrafficLight(
                "신호를 확인하지 못하고 있습니다. 주변 소리에 주의하시고, 필요하면 앱을 다시 실행해 주세요.",
                interrupt = false,
            )
        }
        livenessHandler.postDelayed(::livenessTick, LIVENESS_CHECK_INTERVAL_MS)
    }

    /**
     * 접근성 볼륨이 0 이면 알린다 — **음성으로는 알릴 수 없는 문제**라서 진동으로.
     *
     * `tts.speak()` 는 볼륨이 0 이어도 SUCCESS 를 돌려주고 onDone 도 정상적으로 온다.
     * 앱 입장에서는 완벽히 말한 것처럼 보이는데 사용자는 한 마디도 못 듣는다.
     * 그런데 조준 진동과 색 확정 진동은 멀쩡히 동작하므로, 사용자는
     * "인식은 되고 있다"는 신호만 받고 정작 **색은 영영 모른다.**
     *
     * 볼륨 조절도 함정이다 — TalkBack 이 켜져 있으면 볼륨 키가 접근성 스트림을 향하지만,
     * 미디어가 재생 중일 때만 미디어 스트림으로 바뀐다. 우리 발화는 안 들리니
     * 그 창이 언제 열리는지도 알 수 없다. 그래서 "볼륨을 올려 달라"는 말 자체를
     * 들리는 채널로 전달할 방법이 없고, 남는 건 진동뿐이다.
     */
    private fun checkAudibleVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val stream = android.media.AudioManager.STREAM_ACCESSIBILITY
        val level = try {
            am.getStreamVolume(stream)
        } catch (_: Exception) {
            return   // 일부 기기에서 접근성 스트림을 노출하지 않는다 — 판단 불가면 조용히 넘어간다
        }
        val muted = level <= 0
        if (muted && !volumeWarned) {
            volumeWarned = true
            Log.w(TAG, "접근성 볼륨 0 — 음성 안내가 들리지 않는다")
            vibrateFatal()
            // 혹시 다른 스트림으로라도 나갈 수 있으면 나가라고 시도는 해 본다.
            speakTts("소리가 꺼져 있어 음성 안내를 들을 수 없습니다. 볼륨을 올려 주세요.")
        } else if (!muted) {
            volumeWarned = false
        }
    }

    private fun startLivenessWatchdog() {
        lastAudibleAtMs = SystemClock.elapsedRealtime()
        livenessHandler.removeCallbacksAndMessages(null)
        livenessHandler.postDelayed(::livenessTick, LIVENESS_CHECK_INTERVAL_MS)
    }

    private fun stopLivenessWatchdog() {
        livenessHandler.removeCallbacksAndMessages(null)
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
        if (status != TextToSpeech.SUCCESS) {
            // ⚠️ 예전에는 이 분기가 아예 없었다. TTS 엔진 초기화가 실패하면 ttsReady 가
            //    영원히 false 라서 speakTts/speakTrafficLight 가 매번 조기 반환하고,
            //    사용자는 **처음부터 끝까지 아무 소리도 못 듣는다.** 게다가 실패했다는
            //    사실조차 음성으로 알릴 수 없으니 진동으로 알려야 한다.
            Log.e(TAG, "TTS init 실패 (status=$status)")
            ttsUnavailable = true
            vibrateFatal()
            return
        }

        // ⚠️ setLanguage 의 반환값을 버리면 안 된다. 한국어 음성 데이터가 없는 기기에서는
        //    LANG_MISSING_DATA 를 돌려주고 언어는 바뀌지 않는다. 그 상태로 speak() 를 부르면
        //    한글은 대부분 **소리 없이 지나간다** — 예외도, 오류도 없다.
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "한국어 음성 없음 (setLanguage=$lang)")
            ttsUnavailable = true
            vibrateFatal()
            return
        }

        // ⭐ 이 앱의 음성을 **접근성 채널**로 보낸다.
        //
        // 기본값은 STREAM_MUSIC(USAGE_MEDIA) 인데, 시각장애인 사용자는 미디어 볼륨을 0 으로
        // 두고 TalkBack 이 쓰는 접근성 볼륨만 올려 두는 경우가 흔하다. 그러면 이 앱은
        // **완전히 무음인데 speak() 는 SUCCESS 를 돌려주고 진동은 정상 작동한다.**
        // 사용자는 "인식됐다"는 진동만 받고 색은 영영 못 듣는다. 가장 위험한 실패다.
        // 접근성 채널로 보내면, 사용자가 확실히 켜 둔 볼륨을 타게 된다.
        if (tts.setAudioAttributes(a11yAudioAttributes) != TextToSpeech.SUCCESS) {
            // 실패해도 말은 나온다 — 다만 미디어 볼륨을 타므로 볼륨 0 함정이 되살아난다.
            Log.w(TAG, "TTS 오디오 속성 적용 실패 — 미디어 볼륨을 탈 수 있음")
        }
        ttsReady = true

        if (!introSpoken) {
            introSpoken = true
            // TTS 준비 전에 이미 실패했다면, 밝은 인트로 대신 그 사실을 말한다.
            // 이 분기가 없으면 카메라가 안 켜진 상태로 "비춰 주세요"만 듣고 영원히 침묵한다.
            // ⚠️ 카메라 바인딩 실패도 반드시 여기 들어가야 한다. 빠지면 TTS 가 준비된 순간
            //    "신호등을 카메라에 비춰 주세요" 라고 말하고 — 카메라는 죽어 있고 워치독도
            //    멈춰 있으니 **그게 그 세션의 마지막 출력**이 된다. 사용자는 잘 되고 있다고 믿는다.
            when {
                // ⚠️ 일시정지 복원(회전·다크모드·프로세스 복원)이 맨 앞이어야 한다.
                //    빠지면 카메라도 엔진도 꺼진 상태에서 "신호등을 카메라에 비춰 주세요"를 말하고,
                //    정정은 30초 뒤에야 온다.
                userPaused -> speakTts("일시정지 상태입니다. 볼륨 아래 버튼을 길게 누르면 다시 시작합니다.")
                permissionDenied -> speakTts(PERMISSION_DENIED_MESSAGE)
                cameraBindFailed -> speakTts(CAMERA_FAILED_MESSAGE)
                modelLoadFailed -> speakTts(MODEL_LOAD_FAILED_MESSAGE)
                // 조작법은 여기서 한 번만 알린다 — 물리 키는 알려주지 않으면 발견할 방법이 없다.
                else -> speakTts(
                    "SafeWalk입니다. 신호등을 카메라에 비춰 주세요. " +
                        "볼륨 아래 버튼을 누르면 다시 듣기, 길게 누르면 일시정지입니다."
                )
            }
        }
    }

    private fun speakTts(message: String) {
        // ⚠️ 스탬프는 ttsReady 가드 **앞**에 찍는다.
        //    워치독이 재는 건 "파이프라인이 살아 있는가"이지 "소리가 났는가"가 아니다.
        //    가드 뒤에 찍으면, TTS 가 없는 기기에서는 인식이 완벽히 돌아가도
        //    스탬프가 영영 갱신되지 않아 45초마다 치명 진동이 울린다 — 멀쩡한데 고장 신호를 준다.
        lastAudibleAtMs = SystemClock.elapsedRealtime()
        if (!ttsReady) return
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, message.hashCode().toString())
    }

    /** 신호 안내를 기록해 둔다 — 볼륨 다운 짧게 누르면 이걸 다시 들려준다. */
    private fun rememberAnnouncement(message: String, color: Int) {
        lastAnnouncement = message
        lastAnnouncedColor = color
        lastAnnouncementAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * @param countsAsLiveness 이 발화가 "인식 파이프라인이 살아 있다"는 증거인가.
     *   기본 true. **센서만으로 나오는 발화는 false 여야 한다** — 예: "천천히 돌려 주세요"는
     *   자이로만 있으면 나오는 말이라, 이걸로 워치독 스탬프를 갱신하면 계속 휘두르는 사용자는
     *   (모션 쿨다운 15초 < 워치독 45초) 스탬프가 15초마다 리필돼 인식이 완전히 죽어 있어도
     *   워치독이 영영 안 운다. 정확히 워치독이 지켜야 할 사용자가 빠져나간다.
     */
    private fun speakTrafficLight(message: String, interrupt: Boolean, countsAsLiveness: Boolean = true) {
        if (countsAsLiveness) lastAudibleAtMs = SystemClock.elapsedRealtime()
        if (!ttsReady) return
        tts.speak(
            message,
            if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            if (interrupt) "tl_urgent" else "tl",
        )
    }

    // ──────────────── 진동 ────────────────
    //
    // ⚠️ 모든 진동에 접근성 용도를 붙인다. 속성이 없으면 USAGE_UNKNOWN 으로 취급돼
    //    **절전 모드와 시스템 진동 설정에서 통째로 억제된다.** 위 TTS 문제와 겹치면
    //    사용자에게 남는 채널이 하나도 없다. 접근성 용도는 그 억제에서 면제된다.

    private fun vibrate(effect: VibrationEffect) {
        // ::vibrator 가드 — onInit 이 생성자에서 동기로 불리는 경로를 이중으로 막는다.
        if (!::vibrator.isInitialized || !vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(effect, a11yAudioAttributes)
        } catch (e: Exception) {
            Log.w(TAG, "진동 실패", e)
        }
    }

    /**
     * 색 확정 진동. **빨강과 초록이 촉각으로 달라야 한다.**
     *
     * 예전에는 둘 다 50ms 단발이라 촉각만으로는 색을 구분할 수 없었다. 위의 음성 실패
     * 경로(볼륨 0·한국어 음성 없음·엔진 초기화 실패)에서는 진동이 **유일한 채널**인데,
     * 그때 "뭔가 확정됐다"만 전달되고 정작 빨강인지 초록인지는 사라졌다.
     *
     * 빨강 = 길게 한 번(멈춤을 뜻하는 묵직한 느낌).
     * 초록 = 짧게 세 번(움직임을 뜻하는 가벼운 느낌).
     * 두 번이 아니라 세 번인 이유: 2연타는 AimingFeedback 의 "찾았다"(45·70·45)와 닮아서
     * 장갑 낀 손으로는 구분이 안 된다. 아래 waveform 과 이 문장은 항상 함께 고칠 것.
     */
    private fun vibrateColor(color: Int) {
        // ⚠️ else 로 뭉뚱그리지 않는다. 모르는 색을 초록으로 진동하면,
        //    음성이 "신호 확인"(불확실)이라고 말하는 동안 촉각은 "초록"이라고 단언하게 된다.
        if (color != SignalDecisionEngine.COLOR_RED && color != SignalDecisionEngine.COLOR_GREEN) {
            vibrateWarning()
            return
        }
        val effect = if (color == SignalDecisionEngine.COLOR_RED) {
            VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            // 초록은 **세 번**. 두 번이면 AimingFeedback 의 "찾았다"(45·70·45)와 너무 닮는다 —
            // 장갑 끼고 걸으면서 한 손으로 쥔 상태에서는 구분이 안 되고,
            // 그 둘은 "여기 겨눠라"와 "초록불이니 다음 신호를 기다려라"로 뜻이 전혀 다르다.
            VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70, 90, 70), -1)
        }
        vibrate(effect)
    }

    private fun vibrateWarning() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150, 100, 150), -1))
    }

    /**
     * 음성을 아예 쓸 수 없을 때의 치명 오류 신호. 길게 세 번.
     * 1.9초짜리라 소나에 덮이기 쉬워서 보호 구간을 스스로 건다.
     */
    private fun vibrateFatal() {
        if (::aiming.isInitialized) aiming.holdOff(VIBRATE_FATAL_HOLD_MS)
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
    }

    // ==================== 볼륨 키 조작 ====================
    //
    // 이 앱에는 조작 수단이 하나도 없었다. 방금 안내를 지나가는 버스 소리에 놓치면
    // heartbeat(12초)를 기다리는 수밖에 없었고, 잠시 멈추려면 전원 버튼을 눌러야 했는데
    // 그건 화면이 꺼지는 것 말고는 아무 확인도 주지 않아서 앱이 죽은 것과 구별되지 않았다.
    //
    // ⚠️ **볼륨 업은 건드리지 않는다.** 접근성 볼륨이 0 이면 앱 음성이 하나도 안 들리는데,
    //    두 키를 다 명령으로 먹으면 그 상태에서 빠져나갈 방법이 사라진다.
    //    볼륨을 못 내리는 것보다 못 올리는 것이 비교할 수 없이 위험하므로,
    //    명령은 볼륨 **다운**에만 싣는다.
    //
    // 터치 대신 물리 키를 쓰는 이유: 화면을 못 보는 사람도 손끝으로 찾을 수 있고,
    // 폰을 어떻게 쥐든 위치가 같으며, TalkBack 이 터치 제스처를 가로채는 것과 무관하다.
    // (TalkBack 은 볼륨 업+다운 **동시** 누름만 자기 단축키로 쓰고 단독 누름은 통과시킨다)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // startTracking() 을 해야 onKeyLongPress 가 불린다.
            if (event.repeatCount == 0) {
                volumeLongPressHandled = false
                event.startTracking()
            }
            return true   // 소비 — 볼륨은 안 내려간다
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeLongPressHandled = true
            togglePause()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!volumeLongPressHandled) repeatLastAnnouncement()
            volumeLongPressHandled = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** 일시정지 ↔ 재개. 음성이 죽어 있어도 알 수 있도록 진동 모양을 다르게 준다. */
    private fun togglePause() {
        if (!userPaused) {
            userPaused = true
            stopCamera()          // analysisEnabled=false, 워치독·모션게이트·조준진동 모두 정지
            setIdleVisual()       // 마지막 빨강/초록 화면이 남아 동행자를 오도하지 않게
            // ⚠️ 끼어들어 말한다. QUEUE_ADD 로 붙이면, "건너세요" 가 재생 중일 때
            //    멈췄는데도 건너라는 안내가 몇 초 더 이어진다.
            speakTrafficLight("일시정지했습니다. 볼륨 아래 버튼을 길게 누르면 다시 시작합니다.", interrupt = true)
            vibratePauseAck(paused = true)
            startPausedReminder()
            return
        }

        // 재개 — 권한이 그 사이 취소됐을 수 있다.
        // 확인 없이 "다시 시작합니다" 라고 말하면 startCamera 가 조용히 빠져나가
        // **약속만 하고 아무것도 안 하는** 상태가 된다.
        if (!hasCameraPermission()) {
            // ⚠️ userPaused 는 건드리지 않는다. 여기서 풀어 버리면 알림도 카메라도 없는
            //    무주공산 상태가 되고, 다음 롱프레스가 "일시정지" 로 잘못 해석된다.
            //    권한 요청까지 실제로 띄워 사용자가 빠져나갈 길을 만든다.
            permissionDenied = true
            vibrateFatal()
            speakTrafficLight(PERMISSION_DENIED_MESSAGE, interrupt = true)
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
            return
        }
        userPaused = false
        stopPausedReminder()
        speakTrafficLight("다시 시작합니다.", interrupt = true)
        aiming.holdOff(VIBRATE_SHORT_HOLD_MS)
        vibratePauseAck(paused = false)
        startCamera()
    }

    /**
     * 일시정지 중임을 몇 번 다시 알린다.
     *
     * ⚠️ 롱프레스 문턱이 0.5초라, 손으로 더듬다가 **의도치 않게** 멈추기 쉽다.
     * 그런데 일시정지 상태에서는 워치독도 볼륨 점검도 멈추므로, 사고로 멈춘 줄 모르면
     * 그대로 영구 침묵이다 — 이 앱이 없애려던 바로 그 상태.
     *
     * 다만 일부러 멈춘 사람에게 영원히 잔소리하면 안 되므로 [PAUSED_REMINDER_MAX] 번만 한다.
     */
    private fun pausedTick() {
        if (!userPaused) return
        if (pausedReminderCount >= PAUSED_REMINDER_MAX) return
        pausedReminderCount++
        vibratePauseAck(paused = true)
        speakTts("일시정지 중입니다. 볼륨 아래 버튼을 길게 누르면 다시 시작합니다.")
        livenessHandler.postDelayed(::pausedTick, PAUSED_REMINDER_MS)
    }

    /** 새 일시정지 — 알림 횟수를 초기화하고 시작. */
    private fun startPausedReminder() {
        pausedReminderCount = 0
        resumePausedReminder()
    }

    /** 이미 세던 횟수를 **유지한 채** 알림만 다시 건다 (백그라운드 왕복 복구용). */
    private fun resumePausedReminder() {
        livenessHandler.removeCallbacksAndMessages(null)
        if (pausedReminderCount >= PAUSED_REMINDER_MAX) return
        livenessHandler.postDelayed(::pausedTick, PAUSED_REMINDER_MS)
    }

    private fun stopPausedReminder() {
        livenessHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 마지막 안내를 다시 들려준다.
     *
     * ⚠️ 오래된 안내를 그대로 반복하면 위험하다 — 그 사이에 신호가 바뀌었을 수 있다.
     * 그래서 [STALE_ANNOUNCEMENT_MS] 가 지났으면 "조금 전 안내"임을 먼저 밝힌다.
     * 색 진동도 함께 재생해, 음성이 안 들리는 상황에서도 색은 전달되게 한다.
     */
    private fun repeatLastAnnouncement() {
        val last = lastAnnouncement
        if (last == null) {
            // ⚠️ holdOff 없이 울리면 진행 중인 소나 파형을 덮어쓰는데,
            //    AimingFeedback 의 currentTier 는 그대로라 티어가 바뀌기 전까지 다시 안 걸린다.
            //    조준 중에만 닿는 분기라 하필 제일 아쉬운 순간에 소나가 죽는다.
            // ⚠️ 일시정지 확인음을 재사용하면 안 된다 — 첫 확정 전에는 이 분기가 흔한데,
            //    사용자는 그 진동을 "멈췄다"로 배웠기 때문에 멀쩡히 도는 앱을 멈춘 줄 알고
            //    되살리려다 **진짜로** 멈추게 된다.
            aiming.holdOff(VIBRATE_SHORT_HOLD_MS)
            vibrateNothingYet()
            speakTts("아직 안내한 신호가 없습니다.")
            return
        }

        // ⚠️ 일시정지 중이라는 사실을 반드시 먼저 말한다.
        //    안 그러면 카메라도 엔진도 멈춘 상태에서 "초록불입니다" 를 확신에 차서 재생한다 —
        //    혼란스러운 사용자가 제일 먼저 누르는 버튼이, 앱이 살아 있다고 가장 그럴듯하게
        //    거짓말하는 버튼이 되어 버린다.
        val ageMs = SystemClock.elapsedRealtime() - lastAnnouncementAtMs
        val stale = userPaused || ageMs >= STALE_ANNOUNCEMENT_MS
        val prefix = when {
            // 일시정지 중이면 나이와 무관하게 언제나 오래된 정보다 — 둘 다 붙인다.
            userPaused -> "일시정지 중입니다. 조금 전 안내는 "
            stale -> "조금 전 안내입니다. "
            else -> ""
        }
        aiming.holdOff(VIBRATE_WARNING_HOLD_MS)
        // ⚠️ 오래된 안내에는 **색 진동을 쓰지 않는다.**
        //    음성이 죽은 사용자에게 색 진동은 "지금 막 확정됐다"는 뜻이다. 재생에 그대로 쓰면
        //    카메라가 꺼진 상태에서 "방금 초록불" 과 촉각적으로 완전히 같은 신호가 나간다.
        if (stale) vibrateWarning() else vibrateColor(lastAnnouncedColor)
        speakTrafficLight(prefix + last, interrupt = true)
    }

    /**
     * 일시정지·재개 확인 진동.
     *
     * ⚠️ 펄스 **개수**로 구분하면 안 된다 — 2연타는 AimingFeedback 의 "찾았다"(45·70·45)와
     * 너무 닮아서, 장갑 끼고 한 손으로 쥔 상태에서는 "재개했다"와 "여기 겨눠라"가 구별되지 않는다.
     * 그래서 길이가 확실히 다른 **한 번**짜리로 나눈다.
     */
    private fun vibratePauseAck(paused: Boolean) {
        val effect = if (paused) {
            // 긴 펄스 두 번. 긴 펄스 계열은 **개수로** 구분한다 —
            // 1번=빨간불, 2번=일시정지, 3번=치명 오류. 앞부분이 같아도 세면 갈린다.
            VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400), -1)
        } else {
            // 재개는 짧게 한 번. "멈춤=길다 / 감=짧다" 로, 빨강·초록 규칙과 결이 같다.
            VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrate(effect)
    }

    /** "다시 들려줄 게 아직 없다" — 이 어휘에서 가장 가벼운 신호. 다른 어떤 것과도 안 겹친다. */
    private fun vibrateNothingYet() {
        vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ==================== 라이프사이클 ====================

    override fun onResume() {
        super.onResume()
        // 설정에서 권한을 켜고 돌아오는 경로는 onRequestPermissionsResult 를 거치지 않는다.
        if (hasCameraPermission()) permissionDenied = false
        // ⚠️ 사용자가 직접 멈춰 둔 것이면 되살리지 않는다. 그러지 않으면 알림창을 내렸다
        //    올리는 것만으로 일시정지가 풀려서, 멈춰 둔 줄 알고 주머니에 넣은 폰이
        //    다시 말하기 시작한다.
        if (userPaused) {
            // ⚠️ 여기서 알림을 되살리지 않으면, 일시정지 중 **백그라운드에 한 번만 다녀와도**
            //    안전망이 통째로 사라진다. onPause → stopCamera → stopLivenessWatchdog 가
            //    같은 핸들러를 비우기 때문이다. 하필 영문 모르고 멈춘 사용자가 제일 먼저 하는 행동이
            //    "화면을 확인한다 / 앱을 전환한다" 라서, 정확히 그때 그물이 찢어졌다.
            //    남은 횟수는 유지한다 — 백그라운드 왕복으로 무한 잔소리를 재충전하면 안 된다.
            resumePausedReminder()
            return
        }
        if (cameraProvider == null && hasCameraPermission()) startCamera()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // ⚠️ 이게 없으면 다크모드 전환·글꼴 크기 변경·프로세스 복원으로 Activity 가 다시 만들어질 때
        //    일시정지가 풀려서, 멈춰 둔 줄 알고 주머니에 넣은 폰이 혼자 말하기 시작한다.
        outState.putBoolean(STATE_USER_PAUSED, userPaused)
        outState.putInt(STATE_PAUSED_REMINDER_COUNT, pausedReminderCount)
    }

    override fun onPause() {
        super.onPause()
        // 키를 뗀 이벤트를 못 받고 백그라운드로 갈 수 있다. 플래그가 남으면 다음 짧은 누름이 먹힌다.
        volumeLongPressHandled = false
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
        private const val CAMERA_FAILED_MESSAGE =
            "카메라를 열 수 없습니다. 다른 앱이 카메라를 쓰고 있는지 확인하고 앱을 다시 실행해 주세요."
        private const val MODEL_LOAD_FAILED_MESSAGE =
            "신호등 인식을 시작할 수 없습니다. 앱을 다시 실행해 주세요."

        /** 색 확정 진동(빨강 400ms / 초록 70·90·70) 보호 구간. */
        private const val VIBRATE_SHORT_HOLD_MS = 600L

        /** vibrateWarning(650ms) 보호 구간. */
        private const val VIBRATE_WARNING_HOLD_MS = 1_200L

        /**
         * vibrateFatal(500·200·500·200·500 = 1,900ms) 보호 구간.
         * 이 앱에서 가장 중요한 신호("폰이 말을 못 한다")인데 가장 길어서,
         * 보호 구간이 짧으면 소나가 되살아나 끝을 잘라 먹는다.
         */
        private const val VIBRATE_FATAL_HOLD_MS = 2_200L

        /**
         * 실행 중인데 이만큼 아무 출력도 없으면 "멈췄다"고 본다.
         *
         * 정상 동작에서는 미탐지 안내가 최대 30초 간격([NO_DET_REPEAT_MS])으로 나가므로,
         * 그보다 넉넉히 길게 잡아야 정상 상태를 오탐하지 않는다.
         */
        private const val LIVENESS_TIMEOUT_MS = 45_000L

        /** 워치독 점검 주기. */
        private const val LIVENESS_CHECK_INTERVAL_MS = 5_000L

        /**
         * 다시 듣기에서 "조금 전 안내입니다"를 붙이는 기준.
         * 보행 신호 한 주기가 보통 15~30초라, 10초를 넘겼으면 이미 바뀌었을 수 있다.
         */
        private const val STALE_ANNOUNCEMENT_MS = 10_000L

        /** 일시정지 재알림 간격과 횟수. 사고로 멈춘 경우를 잡되, 일부러 멈춘 사람은 곧 놓아준다. */
        private const val PAUSED_REMINDER_MS = 30_000L
        private const val PAUSED_REMINDER_MAX = 3

        /** 사용자가 직접 멈춘 상태인지 — 화면 회전·프로세스 복원 후에도 유지한다. */
        private const val STATE_USER_PAUSED = "userPaused"
        private const val STATE_PAUSED_REMINDER_COUNT = "pausedReminderCount"

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
