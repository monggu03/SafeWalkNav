package com.example.safewalknav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.safewalknav.location.LocationTracker
import com.example.safewalknav.navigation.AndroidHeadingLogger
import com.example.safewalknav.navigation.ArrivalState
import com.example.safewalknav.navigation.NavigationManager
import com.example.safewalknav.navigation.POIResult
import com.example.safewalknav.navigation.TMapApiClient
import com.example.safewalknav.navigation.toGpsLocation
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 시각장애인 사용자 흐름 — PR-UX1 (사용자 합의안)
 *
 * 상태 머신:
 *
 *   IDLE  ─ long press 2s ─►  LISTENING (STT)
 *    ▲                              │
 *    │                              ▼
 *    │                         SEARCHING
 *    │                              │
 *    │                              ▼
 *    │      (0건, 3회 미만)    RESULTS  (1~5개 풀스크린 버튼, TalkBack 더블탭으로 선택)
 *    │      └── 자동 STT 재시도       │
 *    │                              │ 더블탭
 *    │                              ▼
 *    │                         NAVIGATING (카메라 풀스크린)
 *    │                              │
 *    │                              ▼
 *    │                          ARRIVED ── 3초 후 자동 ──┐
 *    │                                                  │
 *    └──────────────────────────────────────────────────┘
 *
 * 화면:
 *   - IDLE/LISTENING/SEARCHING: 빈 화면 (DEBUG 빌드만 하단에 디버그 정보)
 *   - RESULTS: resultsContainer 에 동적으로 1~5개 버튼 (LinearLayout, weight=1 균등 분배)
 *   - NAVIGATING: cameraPreviewContainer 풀스크린 (PR-3 가 PreviewView 추가)
 *   - ARRIVED: 짧게 도착 안내 → 자동으로 IDLE 로 복귀
 *
 * 트리거:
 *   - 흔들기 폐기 (가방/주머니 실수 트리거 위험). shakeListener 코드는 보존하되 등록 안 함.
 *   - long press 2초 = 모든 상태에서 STT 활성화 (IDLE: 목적지 입력, NAVIGATING: 음성 명령)
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ==================== 상태 ====================

    private enum class AppState {
        IDLE,         // 빈 화면, long press 대기
        LISTENING,    // STT 진행 중
        SEARCHING,    // TMap API 호출 중
        RESULTS,      // 검색 결과 풀스크린 버튼
        NAVIGATING,   // 카메라 풀스크린 + 안내
        ARRIVED       // 도착 후 짧은 안내 (3초 → IDLE)
    }

    private var appState: AppState = AppState.IDLE

    // ==================== 매니저 ====================

    private lateinit var locationTracker: LocationTracker
    private lateinit var navigationManager: NavigationManager
    private lateinit var tts: TextToSpeech

    // ==================== UI 참조 ====================

    private lateinit var rootLayout: View
    private lateinit var cameraPreviewContainer: FrameLayout
    private lateinit var beforeContainer: ViewGroup
    private lateinit var tvBeforeHint: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var arrivedContainer: ViewGroup
    private lateinit var tvArrivedName: TextView
    private lateinit var debugContainer: ViewGroup
    private lateinit var tvDebugStatus: TextView
    private lateinit var tvDebugGuidance: TextView

    // ==================== 흐름 ====================

    private val LOCATION_PERMISSION_CODE = 1001
    private var trackingJob: Job? = null
    private var ttsReady = false
    private var gpsReady = false
    private var welcomePlayed = false
    private var gpsDialogDeniedTime = 0L
    private var gpsCheckInProgress = false

    // long press 2초 — 화면 어디든 터치하고 2초 유지하면 STT
    private var longPressJob: Job? = null
    private val LONG_PRESS_MS = 2000L

    // STT 연속 실패 카운터 — 0건 결과 시 자동 재시도, 3회 누적 시 IDLE 로 복귀
    private var sttFailureCount = 0
    private val STT_FAILURE_LIMIT = 3

    // 도착 후 자동 복귀 (3초)
    private var arrivedReturnJob: Job? = null
    private val ARRIVED_RETURN_MS = 3000L

    // 마지막 검색어 (디버그 표시 + 0건 시 재시도 안내)
    private var lastSearchKeyword: String = ""

    // ==================== 카메라 (CameraX) ====================

    // NAVIGATING 진입 시 후방 카메라 PreviewView 를 cameraPreviewContainer 에 attach.
    // 1차 (PR-UX2): 미리보기만 → 시연/사용자에게 "동작 중" 시각 피드백.
    // 2차 (PR-3, 김수영): ImageAnalysis use case 추가 → 신호등 detection + 횡단보도 줄무늬.
    private var cameraProvider: ProcessCameraProvider? = null

    // ==================== 진동 / 효과음 ====================

    private lateinit var vibrator: Vibrator
    private var toneGenerator: ToneGenerator? = null

    // 스테레오 비프 재사용 AudioTrack
    private val stereoSampleRate = 44100
    private val stereoDurationMs = 120
    private val stereoNumSamples = stereoSampleRate * stereoDurationMs / 1000
    private val stereoBuffer = ShortArray(stereoNumSamples * 2)
    private var stereoTrack: AudioTrack? = null

    // ==================== 안내 비콘 ====================

    private var autoRepeatJob: Job? = null
    private var beaconJob: Job? = null

    // 방향성 비콘 (NEAR 이후 입구 방향 유도)
    private var directionalBeaconJob: Job? = null
    private var lastBehindAnnounceTime = 0L

    // ==================== 센서 (방위각 / 가속도) ====================

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private var currentAzimuth = 0f
    private val magnetometerAvailable: Boolean
        get() = magnetometer != null

    // ==================== TTS 상태 ====================

    private var ttsSpeaking = false
    private var ttsSpeed = 1.0f

    // ==================== ActivityResultLaunchers ====================

    /** GPS 켜기 다이얼로그 결과 */
    private val gpsEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        gpsCheckInProgress = false
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "GPS가 켜졌습니다", Toast.LENGTH_SHORT).show()
            onGPSEnabled()
        } else {
            gpsDialogDeniedTime = System.currentTimeMillis()
            if (ttsReady && !welcomePlayed) {
                welcomePlayed = true
                speakTTS("SafeWalkNav입니다. GPS가 꺼져 있어 위치를 확인할 수 없습니다. 설정에서 GPS를 켜주세요.")
            }
        }
    }

    /** STT 결과 — 성공/실패 모두 처리 */
    private val sttLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                handleVoiceInput(text)
            } else {
                onSTTNoMatch()
            }
        } else {
            // 사용자 취소 또는 타임아웃 — 재시도 카운터 영향 없음, 안내만
            speakTTS("음성 입력이 취소되었습니다. 화면을 길게 눌러 다시 시도하세요.")
            showState(AppState.IDLE)
        }
    }

    // ==================== 센서 리스너 ====================

    /**
     * 흔들기 리스너 — PR-UX1 에서 등록 보류 (실수 트리거 위험).
     * 코드는 보존 — 향후 NAVIGATING 중 음성 명령 트리거로 재도입 가능성.
     */
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            // intentionally unused — see onResume (registration disabled)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** 가속도계 + 자력계 → 방위각 (저역 통과 필터). 보행쏠림 보정용 — NavigationManager 로 전달. */
    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    event.values.copyInto(accelValues, 0, 0, 3)
                    hasAccel = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    event.values.copyInto(magValues, 0, 0, 3)
                    hasMag = true
                }
            }
            if (hasAccel && hasMag) {
                val r = FloatArray(9)
                val i = FloatArray(9)
                if (SensorManager.getRotationMatrix(r, i, accelValues, magValues)) {
                    val orient = FloatArray(3)
                    SensorManager.getOrientation(r, orient)
                    var az = Math.toDegrees(orient[0].toDouble()).toFloat()
                    if (az < 0) az += 360f
                    val delta = ((az - currentAzimuth + 540f) % 360f) - 180f
                    currentAzimuth = (currentAzimuth + 0.15f * delta + 360f) % 360f
                    navigationManager.updateCompassHeading(currentAzimuth)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ==================== Activity 라이프사이클 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 매니저 초기화
        tts = TextToSpeech(this, this)
        locationTracker = LocationTracker(this)
        val headingLogger = AndroidHeadingLogger(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        )
        navigationManager = NavigationManager(
            TMapApiClient(BuildConfig.TMAP_APP_KEY),
            headingLogger,
        )

        // View 참조
        rootLayout = findViewById(R.id.rootLayout)
        cameraPreviewContainer = findViewById(R.id.cameraPreviewContainer)
        beforeContainer = findViewById(R.id.beforeContainer)
        tvBeforeHint = findViewById(R.id.tvBeforeHint)
        resultsContainer = findViewById(R.id.resultsContainer)
        arrivedContainer = findViewById(R.id.arrivedContainer)
        tvArrivedName = findViewById(R.id.tvArrivedName)
        debugContainer = findViewById(R.id.debugContainer)
        tvDebugStatus = findViewById(R.id.tvDebugStatus)
        tvDebugGuidance = findViewById(R.id.tvDebugGuidance)

        // DEBUG 빌드만 디버그 박스 표시 + 시각 힌트 텍스트 표시
        if (BuildConfig.DEBUG) {
            debugContainer.visibility = View.VISIBLE
            tvBeforeHint.visibility = View.VISIBLE
            tvBeforeHint.text = "화면을 2초간 길게 눌러주세요"
        }

        // 센서 / 진동 / 효과음
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (_: Exception) {
        }

        // 권한 + GPS + UI 초기화
        requestLocationPermission()
        checkAndEnableGPS()
        setupTouchArea()
        observeGuidance()
        showState(AppState.IDLE)
    }

    override fun onResume() {
        super.onResume()
        checkAndEnableGPS()
        // 흔들기 리스너 등록 보류 (PR-UX1: 흔들기 폐기)
        // 향후 NAVIGATING 중 음성 명령 트리거로 재도입 시 해제 — 그땐 NAVIGATING 상태에서만 등록.
        // accelerometer?.let {
        //     sensorManager.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI)
        // }

        // 방위각 (보행쏠림 보정) — 항상 등록
        accelerometer?.let {
            sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(orientationListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        trackingJob?.cancel()
        autoRepeatJob?.cancel()
        beaconJob?.cancel()
        directionalBeaconJob?.cancel()
        longPressJob?.cancel()
        arrivedReturnJob?.cancel()
        stopCamera()
        tts.shutdown()
        toneGenerator?.release()
        releaseStereoTrack()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ==================== 상태 전환 ====================

    /**
     * 화면 컨테이너 visibility 토글 + 디버그 정보 갱신 + 부수 효과 처리.
     * 모든 상태 전환은 이 함수를 거쳐야 함 — 화면/내부 상태 동기화 보장.
     */
    private fun showState(state: AppState) {
        val previous = appState
        appState = state
        Log.d("SafeWalkNav", "AppState: $previous -> $state")

        // 컨테이너 visibility (FrameLayout 위에 쌓인 4개 컨테이너 중 하나만 보이게)
        when (state) {
            AppState.IDLE, AppState.LISTENING, AppState.SEARCHING -> {
                beforeContainer.visibility = View.VISIBLE
                resultsContainer.visibility = View.GONE
                arrivedContainer.visibility = View.GONE
                cameraPreviewContainer.visibility = View.GONE
            }
            AppState.RESULTS -> {
                beforeContainer.visibility = View.GONE
                resultsContainer.visibility = View.VISIBLE
                arrivedContainer.visibility = View.GONE
                cameraPreviewContainer.visibility = View.GONE
            }
            AppState.NAVIGATING -> {
                beforeContainer.visibility = View.GONE
                resultsContainer.visibility = View.GONE
                arrivedContainer.visibility = View.GONE
                cameraPreviewContainer.visibility = View.VISIBLE
            }
            AppState.ARRIVED -> {
                beforeContainer.visibility = View.GONE
                resultsContainer.visibility = View.GONE
                arrivedContainer.visibility = View.VISIBLE
                cameraPreviewContainer.visibility = View.GONE
            }
        }

        // IDLE 진입 시 검색 결과 컨테이너 정리 (이전 버튼들 제거)
        if (state == AppState.IDLE) {
            resultsContainer.removeAllViews()
        }

        // 카메라 lifecycle — NAVIGATING 진입/이탈 시 토글
        if (state == AppState.NAVIGATING && previous != AppState.NAVIGATING) {
            startCamera()
        } else if (previous == AppState.NAVIGATING && state != AppState.NAVIGATING) {
            stopCamera()
        }

        // TalkBack accessibility — root 의 announce 대상 여부 토글.
        //   IDLE/LISTENING: root 가 announce 대상 (사용자가 long press 가능 영역).
        //   RESULTS/NAVIGATING/ARRIVED: root 를 accessibility tree 에서 제외.
        //     자식 컨테이너 (버튼들 / 카메라 / 도착 화면) 가 자체 contentDescription 가지므로
        //     root 까지 announce 되면 화면 변화 마다 "화면을 2초간 길게 눌러..." 가 반복 발화됨.
        when (state) {
            AppState.IDLE, AppState.LISTENING -> {
                rootLayout.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                rootLayout.contentDescription = "화면을 2초간 길게 눌러 음성으로 목적지를 입력하세요"
            }
            else -> {
                rootLayout.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                rootLayout.contentDescription = null
            }
        }

        updateDebugInfo()
    }

    private fun updateDebugInfo() {
        if (!BuildConfig.DEBUG) return
        val talkback = if (isTalkBackEnabled()) "ON" else "OFF"
        val gps = if (gpsReady) "OK" else "?"
        val last = if (lastSearchKeyword.isEmpty()) "-" else lastSearchKeyword
        tvDebugStatus.text = "STATE=${appState.name} | GPS=$gps | TalkBack=$talkback | last=$last"
    }

    private fun isTalkBackEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.isEnabled && am.isTouchExplorationEnabled
        } catch (_: Exception) {
            false
        }
    }

    // ==================== 사용자 인터랙션 (long press) ====================

    /**
     * 화면 전체 long press 2초 → STT 트리거.
     *
     * 주의: TalkBack ON 환경에서는 단일 탭이 accessibility focus 로 가로채져서 onTouch 가
     * 우리 앱에 도달하지 않을 수 있음. TalkBack 사용자는 화면 전체에 부여된
     * contentDescription 을 듣고 더블탭-홀드로 long press 발화시켜야 함.
     * 1차 구현: setOnTouchListener (TalkBack OFF 시 가장 단순).
     * TalkBack 실측 후 호환성 보강 필요하면 setOnLongClickListener 도 병행 등록.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchArea() {
        rootLayout.setOnTouchListener { _, event ->
            // long press 활성 상태:
            //   IDLE / LISTENING → STT 시작
            //   NAVIGATING → 안내 종료 (사용자 요구: "한 번 더 길게 누르면 종료")
            // 비활성 상태:
            //   RESULTS → 각 버튼이 자체 탭/더블탭 받음
            //   ARRIVED → 3초 후 자동 IDLE 복귀 중
            //   SEARCHING → API 호출 진행 중
            if (appState == AppState.RESULTS ||
                appState == AppState.ARRIVED ||
                appState == AppState.SEARCHING) {
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressJob?.cancel()
                    longPressJob = lifecycleScope.launch {
                        delay(LONG_PRESS_MS)
                        vibrateMedium()
                        onLongPressTriggered()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressJob?.cancel()
                    longPressJob = null
                    true
                }
                else -> false
            }
        }
    }

    /** 2초 long press 트리거 — 현재 상태에 따라 다른 동작. */
    private fun onLongPressTriggered() {
        when (appState) {
            AppState.NAVIGATING -> {
                // 이동 중 안내 종료 — 카메라 화면에서 화면 길게 눌러서 빠져나옴
                stopNavigationFull()
            }
            AppState.IDLE, AppState.LISTENING -> {
                startSTT()
            }
            else -> { /* RESULTS/ARRIVED/SEARCHING 은 setupTouchArea 에서 이미 차단 */ }
        }
    }

    private fun startSTT() {
        if (!ttsReady) return
        tts.stop()
        showState(AppState.LISTENING)

        val prompt = when (appState) {
            AppState.NAVIGATING -> "명령을 말씀하세요"
            else -> "목적지를 말씀하세요"
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        try {
            sttLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "음성 인식을 사용할 수 없습니다", Toast.LENGTH_SHORT).show()
            showState(AppState.IDLE)
        }
    }

    private fun handleVoiceInput(text: String) {
        Log.d("SafeWalkNav", "Voice: '$text' (state: $appState)")

        // NAVIGATING 중이면 음성 명령 처리. (현재는 long press 기반이라 NAVIGATING 진입 안 됨,
        // 향후 NAVIGATING 음성 명령 활성화 시 사용 — 흔들기 또는 별도 트리거.)
        if (appState == AppState.NAVIGATING) {
            handleNavigationCommand(text)
            return
        }

        // 그 외엔 검색 키워드로 처리
        sttFailureCount = 0   // 입력 성공 시 재시도 카운터 리셋
        if (text.contains("도움") || text.contains("사용법")) {
            speakAndListenIdle("화면을 2초간 길게 눌러 목적지를 말씀하시면, 검색 결과 중에서 선택할 수 있습니다.")
            return
        }
        lastSearchKeyword = text
        performSearch(text)
    }

    /** STT 결과는 성공이지만 빈 문자열 — 음성은 들렸으나 인식 실패 */
    private fun onSTTNoMatch() {
        sttFailureCount++
        if (sttFailureCount >= STT_FAILURE_LIMIT) {
            sttFailureCount = 0
            speakTTS("음성 인식에 실패했습니다. 화면을 길게 눌러 다시 시도하세요.")
            showState(AppState.IDLE)
        } else {
            // 자동 재시도 (3회 미만)
            speakAndListenIdle("다시 말씀해주세요.")
        }
    }

    private fun handleNavigationCommand(text: String) {
        when {
            text.contains("종료") || text.contains("그만") || text.contains("멈춰") -> {
                stopNavigationFull()
            }
            text.contains("어디") || text.contains("현재") || text.contains("위치") ||
            text.contains("다시") || text.contains("반복") -> {
                val msg = navigationManager.guidanceMessage.value
                if (msg.isNotEmpty()) speakTTS(msg)
            }
            text.contains("빠르게") || text.contains("빨리") -> {
                ttsSpeed = (ttsSpeed + 0.25f).coerceAtMost(2.0f)
                tts.setSpeechRate(ttsSpeed)
                speakTTS("음성 속도를 높였습니다.")
            }
            text.contains("느리게") || text.contains("천천히") -> {
                ttsSpeed = (ttsSpeed - 0.25f).coerceAtLeast(0.5f)
                tts.setSpeechRate(ttsSpeed)
                speakTTS("음성 속도를 낮췄습니다.")
            }
            text.contains("크게") || text.contains("볼륨 올려") -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                speakTTS("소리를 키웠습니다.")
            }
            text.contains("작게") || text.contains("볼륨 내려") -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                speakTTS("소리를 줄였습니다.")
            }
            text.contains("도움") || text.contains("도움말") -> {
                speakTTS("종료, 현재위치, 반복, 빠르게, 느리게, 크게, 작게를 사용할 수 있습니다.")
            }
            else -> {
                speakTTS("다시 말씀해주세요.")
            }
        }
    }

    // ==================== 검색 ====================

    private fun performSearch(keyword: String) {
        showState(AppState.SEARCHING)
        speakTTS("검색 중입니다.")

        lifecycleScope.launch {
            val currentLocation = locationTracker.getCurrentLocation()
            val results = navigationManager.searchDestination(
                keyword = keyword,
                currentLat = currentLocation?.latitude,
                currentLon = currentLocation?.longitude,
            )

            if (results.isEmpty()) {
                handleEmptyResults(keyword)
                return@launch
            }

            // 거리 계산 (현재 위치 있을 때만)
            val distances: List<Int>? = currentLocation?.let { loc ->
                results.map { poi ->
                    LocationTracker.distanceBetween(
                        loc.latitude, loc.longitude, poi.lat, poi.lon
                    ).toInt()
                }
            }

            // 1개여도 풀스크린 버튼 (사용자 합의안: 일관성)
            showResultsScreen(results, distances)
        }
    }

    /**
     * 결과 0건 — 자동 STT 재시도 (3회 누적 시 IDLE 로 복귀).
     */
    private fun handleEmptyResults(keyword: String) {
        sttFailureCount++
        playToneError()
        if (sttFailureCount >= STT_FAILURE_LIMIT) {
            sttFailureCount = 0
            val msg = "주변 1킬로미터 이내에 ${keyword} 검색 결과가 없습니다. 화면을 길게 눌러 다시 시도하세요."
            speakTTS(msg)
            showState(AppState.IDLE)
        } else {
            val msg = navigationManager.lastError
                ?: "주변 1킬로미터 이내에 ${keyword} 검색 결과가 없습니다"
            speakAndListenIdle("$msg. 다른 목적지를 말씀해주세요.")
        }
    }

    /**
     * 검색 결과 풀스크린 — resultsContainer 에 1~5개 버튼 동적 추가.
     *
     * TalkBack 인터랙션:
     *   - 단일 탭 (TalkBack ON) = 버튼 contentDescription 읽기
     *   - 더블탭 (TalkBack ON) = 선택
     *   - TalkBack OFF 시 단일 탭으로도 선택 가능 (시연/시각자용)
     *
     * 음성 안내: "검색 결과 N개입니다. 위에서부터 하나씩 읽어보세요."
     * → 사용자가 각 버튼 탭하면 TalkBack 이 가게명 + 거리 + 주소 읽음.
     */
    private fun showResultsScreen(results: List<POIResult>, distances: List<Int>?) {
        showState(AppState.RESULTS)
        resultsContainer.removeAllViews()

        // TalkBack 분기 전략:
        //   ON  — 우리 TTS 안 발화. 첫 버튼 contentDescription 에 "검색 결과 N개 중 1번째" 인트로 박아서
        //         TalkBack 이 첫 focus 잡을 때 한 번에 발화. 우리 TTS 와 시간 겹침 0.
        //   OFF — 우리 TTS 가 흐름 안내. 각 버튼은 단순 contentDescription.
        val talkbackOn = isTalkBackEnabled()

        results.forEachIndexed { i, poi ->
            val distText = distances?.get(i)?.let { formatDistance(it) } ?: ""
            val button = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f   // weight=1 균등 분배
                ).apply {
                    setMargins(8, 8, 8, 8)
                }
                text = if (distText.isNotEmpty()) "${poi.name}\n$distText" else poi.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                setTextColor(0xFF000000.toInt())
                setBackgroundColor(0xFFFFD700.toInt())   // 노랑
                isAllCaps = false

                // TalkBack 이 읽을 풍부한 설명
                // 첫 버튼 (i==0) + TalkBack ON 시 검색 결과 전체 안내를 인트로로 포함
                val intro = when {
                    talkbackOn && i == 0 -> "검색 결과 ${results.size}개 중 ${i + 1}번째, "
                    talkbackOn -> "${i + 1}번째, "
                    else -> ""
                }
                val parts = mutableListOf("$intro${poi.name}")
                if (distText.isNotEmpty()) parts.add("거리 $distText")
                if (poi.address.isNotEmpty()) parts.add(poi.address)
                contentDescription = parts.joinToString(", ")

                setOnClickListener {
                    vibrateShort()
                    selectDestination(poi)
                }
            }
            resultsContainer.addView(button)
        }

        // TalkBack OFF 시에만 우리 TTS 발화. ON 일 땐 첫 버튼 focus 때 자동 announce.
        if (!talkbackOn) {
            val msg = if (results.size == 1) {
                "검색 결과 1개입니다. 화면 가운데를 눌러 선택하세요."
            } else {
                "검색 결과 ${results.size}개입니다. 위에서부터 하나씩 읽어보세요."
            }
            speakTTS(msg)
        }
    }

    private fun selectDestination(selected: POIResult) {
        lifecycleScope.launch {
            speakTTS("${selected.name}으로 경로를 탐색합니다.")

            val currentLocation = locationTracker.getCurrentLocation()
            if (currentLocation == null) {
                playToneError()
                speakAndListenIdle("위치를 확인할 수 없습니다. GPS 확인 후 다시 시도하세요.")
                return@launch
            }

            val success = navigationManager.startNavigation(
                startLat = currentLocation.latitude,
                startLon = currentLocation.longitude,
                endLat = selected.lat,
                endLon = selected.lon,
                endName = selected.name,
                frontLat = selected.frontLat,
                frontLon = selected.frontLon
            )

            if (success) {
                showState(AppState.NAVIGATING)
                playToneSuccess()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                val summary = getRouteSummary()
                if (summary.isNotEmpty()) {
                    speakTTS(summary)
                }
                startLocationTracking()
                startAutoRepeat()
            } else {
                playToneError()
                speakAndListenIdle("경로를 찾을 수 없습니다. 다른 목적지를 말씀해주세요.")
            }
        }
    }

    /** 거리를 읽기 좋게 포맷 (1200m → "1.2킬로", 300m → "300미터") */
    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) {
            "${String.format("%.1f", meters / 1000.0)}킬로"
        } else {
            "${meters}미터"
        }
    }

    /** 경로 요약 ("총 800미터, 약 10분, 횡단보도 2개") */
    private fun getRouteSummary(): String {
        val route = navigationManager.currentRoute ?: return ""
        val totalMin = route.totalTime / 60
        val crosswalks = route.waypoints.count { it.pointType == "CROSSWALK" }
        val turns = route.waypoints.count { it.pointType == "TURN" }

        val parts = mutableListOf(formatDistance(route.totalDistance), "약 ${totalMin}분")
        if (crosswalks > 0) parts.add("횡단보도 ${crosswalks}개")
        if (turns > 0) parts.add("회전 ${turns}회")

        return parts.joinToString(", ")
    }

    // ==================== 도착 / 종료 ====================

    /**
     * NAVIGATING 종료 — ARRIVED 상태로 전환 후 3초 뒤 자동으로 IDLE 로.
     */
    private fun finishNavigation(arrivedName: String) {
        trackingJob?.cancel()
        stopAutoRepeat()
        stopBeacon()
        navigationManager.stopNavigation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvArrivedName.text = "${arrivedName}에 도착했습니다"
        showState(AppState.ARRIVED)

        // 3초 후 자동으로 IDLE — 사용자가 화면 안 봐도 다음 검색 흐름 시작 가능
        arrivedReturnJob?.cancel()
        arrivedReturnJob = lifecycleScope.launch {
            delay(ARRIVED_RETURN_MS)
            stopDirectionalBeacon()  // 방향비콘도 종료
            speakTTS("다음 목적지를 검색하시려면 화면을 길게 눌러주세요.")
            showState(AppState.IDLE)
        }
    }

    /** 음성 명령 "종료" 또는 사용자가 도중 중단 — ARRIVED 화면 거치지 않고 곧장 IDLE. */
    private fun stopNavigationFull() {
        trackingJob?.cancel()
        stopAutoRepeat()
        stopBeacon()
        stopDirectionalBeacon()
        arrivedReturnJob?.cancel()
        // navigationManager.stopNavigation() 가 자체적으로 "안내를 종료합니다" 를
        // guidanceMessage 에 emit 함 → observeGuidance 가 그걸 받아 TTS 재생.
        // 우리가 여기서 또 speakTTS 호출하면 중복 발화 → 호출 안 함.
        navigationManager.stopNavigation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showState(AppState.IDLE)
    }

    // ==================== GPS 위치 추적 ====================

    private fun startLocationTracking() {
        trackingJob?.cancel()
        trackingJob = lifecycleScope.launch {
            locationTracker.getLocationUpdates(2000L).collectLatest { location ->
                if (location.hasAccuracy() && location.accuracy > 25f) {
                    return@collectLatest
                }

                // 자력계 없는 기기 fallback: GPS bearing(이동 중일 때만 신뢰 가능) → azimuth
                if (!magnetometerAvailable && location.hasBearing() && location.hasSpeed() && location.speed > 0.5f) {
                    val gpsBearing = location.bearing
                    val delta = ((gpsBearing - currentAzimuth + 540f) % 360f) - 180f
                    currentAzimuth = (currentAzimuth + 0.3f * delta + 360f) % 360f
                    navigationManager.updateCompassHeading(currentAzimuth)
                }

                navigationManager.updateLocation(location.toGpsLocation())

                // 디버그 박스 갱신 (DEBUG 빌드만)
                if (BuildConfig.DEBUG) {
                    val dist = LocationTracker.distanceBetween(
                        location.latitude, location.longitude,
                        navigationManager.destinationLat, navigationManager.destinationLon
                    )
                    val accuracyText = if (location.hasAccuracy()) "±${location.accuracy.toInt()}m" else ""
                    tvDebugGuidance.text = "GPS ${String.format("%.5f", location.latitude)}, ${
                        String.format("%.5f", location.longitude)
                    } $accuracyText | dest=${dist.toInt()}m"
                }
            }
        }
    }

    // ==================== 안내 자동 반복 ====================

    private fun startAutoRepeat() {
        autoRepeatJob?.cancel()
        autoRepeatJob = lifecycleScope.launch {
            while (true) {
                delay(45_000)
                if (!navigationManager.isNavigating.value) break
                val msg = navigationManager.guidanceMessage.value
                if (msg.isNotEmpty()) speakTTS(msg)
            }
        }
    }

    private fun stopAutoRepeat() {
        autoRepeatJob?.cancel()
        autoRepeatJob = null
    }

    // ==================== 거리 비콘 ====================

    /**
     * 거리 기반 비프음 시작
     * >10m: 3초 간격, 5~10m: 1.5초, 3~5m: 0.8초, <3m: 0.4초
     */
    private fun startBeacon() {
        beaconJob?.cancel()
        beaconJob = lifecycleScope.launch {
            while (true) {
                val dist = navigationManager.distanceToDestination.value

                if (dist > 15f || dist == Float.MAX_VALUE) {
                    delay(1000)
                    continue
                }

                val interval = when {
                    dist <= 3f -> 400L
                    dist <= 5f -> 800L
                    dist <= 10f -> 1500L
                    else -> 3000L
                }

                if (ttsSpeaking) {
                    delay(interval)
                    continue
                }

                try {
                    val tone = if (dist <= 5f)
                        ToneGenerator.TONE_PROP_BEEP2
                    else
                        ToneGenerator.TONE_PROP_BEEP
                    toneGenerator?.startTone(tone, 80)
                } catch (_: Exception) {
                }

                if (dist <= 10f) {
                    val intensity = when {
                        dist <= 3f -> 255
                        dist <= 5f -> 180
                        else -> 100
                    }
                    vibrator.vibrate(VibrationEffect.createOneShot(60, intensity))
                }

                delay(interval)
            }
        }
    }

    private fun stopBeacon() {
        beaconJob?.cancel()
        beaconJob = null
    }

    // ==================== 방향성 비콘 (NEAR 이후 입구 찾기) ====================

    private fun startDirectionalBeacon() {
        directionalBeaconJob?.cancel()
        directionalBeaconJob = lifecycleScope.launch {
            while (true) {
                val loc = locationTracker.getCurrentLocation()
                if (loc == null) {
                    delay(500)
                    continue
                }

                val targetLat = navigationManager.destinationFrontLat
                    ?: navigationManager.destinationLat
                val targetLon = navigationManager.destinationFrontLon
                    ?: navigationManager.destinationLon

                if (targetLat == 0.0 && targetLon == 0.0) {
                    delay(500)
                    continue
                }

                val target = Location("t").apply {
                    latitude = targetLat
                    longitude = targetLon
                }
                val bearing = loc.bearingTo(target)
                var angleDiff = bearing - currentAzimuth
                while (angleDiff > 180f) angleDiff -= 360f
                while (angleDiff < -180f) angleDiff += 360f

                if (ttsSpeaking) {
                    delay(400)
                    continue
                }

                val (leftVol, rightVol, highPitch) = computeStereoPan(angleDiff)
                playStereoBeep(leftVol, rightVol, highPitch)

                if (abs(angleDiff) > 135f) {
                    val now = System.currentTimeMillis()
                    if (now - lastBehindAnnounceTime > 4000L) {
                        lastBehindAnnounceTime = now
                        runOnUiThread { speakTTS("목적지는 뒤쪽입니다. 몸을 돌려주세요.") }
                    }
                }

                val interval = when {
                    abs(angleDiff) < 15f -> 300L
                    abs(angleDiff) < 45f -> 500L
                    else -> 700L
                }
                delay(interval)
            }
        }
    }

    private fun stopDirectionalBeacon() {
        directionalBeaconJob?.cancel()
        directionalBeaconJob = null
    }

    private fun computeStereoPan(angleDiff: Float): Triple<Float, Float, Boolean> {
        val clamped = angleDiff.coerceIn(-90f, 90f)
        val pan = clamped / 90f
        val angle = ((pan + 1f) / 2f) * (PI.toFloat() / 2f)
        val left = cos(angle)
        val right = sin(angle)
        val facing = abs(angleDiff) < 15f
        val scale = if (abs(angleDiff) > 90f) 0.3f else 1f
        return Triple(left * scale, right * scale, facing)
    }

    private fun playStereoBeep(leftVol: Float, rightVol: Float, highPitch: Boolean) {
        try {
            val freq = if (highPitch) 1320.0 else 880.0
            val amp = (Short.MAX_VALUE * 0.6).toInt()
            val attack = 200
            val release = 500
            for (i in 0 until stereoNumSamples) {
                val env = when {
                    i < attack -> i / attack.toFloat()
                    stereoNumSamples - i < release -> (stereoNumSamples - i) / release.toFloat()
                    else -> 1f
                }
                val s = (amp * env * sin(2 * PI * freq * i / stereoSampleRate)).toInt()
                stereoBuffer[i * 2] = (s * leftVol).toInt().coerceIn(-32768, 32767).toShort()
                stereoBuffer[i * 2 + 1] = (s * rightVol).toInt().coerceIn(-32768, 32767).toShort()
            }

            val track = stereoTrack ?: AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(stereoSampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(stereoBuffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { stereoTrack = it }

            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                track.flush()
            } catch (_: Exception) {}
            track.write(stereoBuffer, 0, stereoBuffer.size)
            track.play()
        } catch (_: Exception) {
        }
    }

    private fun releaseStereoTrack() {
        try { stereoTrack?.stop() } catch (_: Exception) {}
        try { stereoTrack?.release() } catch (_: Exception) {}
        stereoTrack = null
    }

    // ==================== Guidance Observer ====================

    private fun observeGuidance() {
        lifecycleScope.launch {
            navigationManager.guidanceMessage.collectLatest { message ->
                if (message.isNotEmpty()) {
                    speakTTS(message)
                    Log.d("SafeWalkNav", "Guidance: $message")

                    if (BuildConfig.DEBUG) {
                        tvDebugGuidance.text = "guidance=$message"
                    }

                    if (message.contains("이탈")) {
                        vibrateWarning()
                        playToneWarning()
                    } else if (message.contains("횡단보도") || message.contains("계단")) {
                        vibrateMedium()
                        playToneAlert()
                    }
                }
            }
        }

        lifecycleScope.launch {
            navigationManager.arrivalState.collectLatest { state ->
                when (state) {
                    ArrivalState.FAR -> {
                        stopDirectionalBeacon()
                    }

                    ArrivalState.APPROACHING -> {
                        vibrateMedium()
                        startBeacon()
                    }

                    ArrivalState.NEAR -> {
                        vibrateMedium()
                        stopBeacon()
                        startDirectionalBeacon()
                    }

                    ArrivalState.ARRIVED -> {
                        stopBeacon()
                        vibrateArrival()
                        playToneSuccess()
                        // 방향비콘은 계속 유지 — 입구 찾는 동안. finishNavigation 의 3초 후 종료.
                        if (directionalBeaconJob == null) {
                            startDirectionalBeacon()
                        }
                        // ARRIVED 화면 + 3초 후 자동 IDLE 복귀
                        val name = navigationManager.destinationName.ifEmpty { "목적지" }
                        finishNavigation(name)
                    }
                }
            }
        }
    }

    // ==================== 카메라 ON/OFF ====================

    /**
     * 후방 카메라 PreviewView 를 cameraPreviewContainer 에 attach + bindToLifecycle.
     * 권한 없으면 silent skip — NAVIGATING 자체는 음성/진동/비콘으로 정상 동작.
     */
    private fun startCamera() {
        if (cameraProvider != null) return   // 이미 작동 중

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("SafeWalkNav", "Camera permission denied — skip preview")
            return
        }

        val pv = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // PreviewView 자체엔 contentDescription 안 부여 (시각장애인은 카메라 영상 안 봄)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraPreviewContainer.removeAllViews()
        cameraPreviewContainer.addView(pv)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
                Log.d("SafeWalkNav", "Camera bound (preview only)")
            } catch (e: Exception) {
                Log.e("SafeWalkNav", "Camera bind failed", e)
                cameraProvider = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        cameraPreviewContainer.removeAllViews()
        Log.d("SafeWalkNav", "Camera unbound")
    }

    // ==================== 진동 패턴 ====================

    private fun vibrateShort() {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateMedium() {
        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateWarning() {
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150, 100, 150), -1)
        )
    }

    private fun vibrateArrival() {
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 500), -1)
        )
    }

    // ==================== 효과음 ====================

    private fun playToneSuccess() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        } catch (_: Exception) {
        }
    }

    private fun playToneWarning() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        } catch (_: Exception) {
        }
    }

    private fun playToneAlert() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {
        }
    }

    private fun playToneError() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300)
        } catch (_: Exception) {
        }
    }

    // ==================== TTS ====================

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(ttsSpeed)
            ttsReady = true

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    ttsSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    ttsSpeaking = false
                    if (utteranceId == "auto_listen") {
                        runOnUiThread { startSTT() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    ttsSpeaking = false
                }
            })

            tryPlayWelcome()
        }
    }

    private fun onGPSEnabled() {
        gpsReady = true
        updateDebugInfo()
        tryPlayWelcome()
    }

    private fun tryPlayWelcome() {
        if (ttsReady && gpsReady && !welcomePlayed) {
            welcomePlayed = true
            // TalkBack ON 일 땐 우리 TTS 발화 안 함 — TalkBack 이 자동으로 root layout 의
            // contentDescription ("화면을 2초간 길게 눌러 음성으로 목적지를 입력하세요") 을
            // 화면 진입 시 읽어주고, 그 끝에 "두 번 탭하여 활성화" hint 가 자동 추가됨.
            // 우리 TTS 가 동시에 나오면 두 음성이 겹쳐서 혼란.
            if (!isTalkBackEnabled()) {
                speakTTS("SafeWalkNav입니다. 내비게이션을 실행하시려면 화면을 2초간 길게 눌러주세요.")
            }
        }
    }

    private fun speakTTS(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, message.hashCode().toString())
    }

    /**
     * 안내 TTS 끝나면 자동으로 STT 시작 (IDLE 상태로 전환).
     * 0건/실패 후 자동 재시도 흐름에 사용.
     */
    private fun speakAndListenIdle(message: String) {
        showState(AppState.IDLE)
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, "auto_listen")
    }

    // ==================== GPS ====================

    private fun checkAndEnableGPS() {
        if (gpsReady) return
        if (gpsCheckInProgress) return
        if (System.currentTimeMillis() - gpsDialogDeniedTime < 30000L) return

        gpsCheckInProgress = true

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 2000L
        ).build()

        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                gpsCheckInProgress = false
                onGPSEnabled()
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    val request = IntentSenderRequest.Builder(exception.resolution).build()
                    gpsEnableLauncher.launch(request)
                } else {
                    gpsCheckInProgress = false
                }
            }
    }

    // ==================== 권한 ====================

    private fun requestLocationPermission() {
        val perms = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.CAMERA)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), LOCATION_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            // 이번 요청 batch 가 아니라 "현재 권한 상태"로 판단.
            // 이전 실행에서 위치는 이미 허용됐고 이번엔 카메라/마이크만 새로 요청한 케이스
            // → permissions 배열에 위치가 없어 zip.any 로 체크 시 항상 false 가 되는 버그 회피.
            val locationGranted = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            if (locationGranted) {
                checkAndEnableGPS()
            } else {
                speakTTS("위치 권한이 필요합니다. 설정에서 허용해주세요.")
            }
        }
    }
}
