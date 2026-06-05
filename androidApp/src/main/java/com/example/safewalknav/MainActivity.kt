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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.safewalknav.location.LocationTracker
import com.example.safewalknav.compass.CompassView
import com.example.safewalknav.ml.BoundingBoxOverlay
import com.example.safewalknav.ml.TrafficLightAnalyzer
import com.example.safewalknav.ml.TrafficLightDetection
import com.example.safewalknav.ml.TrafficLightDetector
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.logEvent
import com.google.firebase.ktx.Firebase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.example.safewalknav.navigation.AndroidHeadingLogger
import com.example.safewalknav.navigation.tmap.ArrivalState
import com.example.safewalknav.navigation.NavigationManager
import com.example.safewalknav.navigation.tmap.POIResult
import com.example.safewalknav.navigation.signal.SignalApiClient
import com.example.safewalknav.navigation.tmap.TMapApiClient
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
import com.example.safewalknav.navigation.signal.TrafficSignalLocation
import com.example.safewalknav.traffic.TrafficSignalDatabase
import com.example.safewalknav.traffic.TrafficSignalRepository
import com.example.safewalknav.navigation.signal.SeoulTrafficSignalLocationApiClient


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
    private lateinit var compassContainer: ViewGroup
    private lateinit var compassView: CompassView
    private lateinit var tvCompassGuidance: TextView
    private lateinit var tvCompassSubInfo: TextView
    private lateinit var debugContainer: ViewGroup
    private lateinit var tvDebugStatus: TextView
    private lateinit var tvDebugGuidance: TextView
    private lateinit var tvDebugAiResult: TextView

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

    // ==================== 외출 디버깅 파일 로깅 ====================
    // logcat ring buffer 가 외출 동안 시스템 로그로 덮어써져서 우리 진단 로그가 사라지는 문제 회피.
    // NAVIGATING 시작 시 파일 열고, isInCrosswalkZone / guidance / TL 검출 / 경로 dump 모두 기록.
    // 외장 저장소: /sdcard/Android/data/com.example.safewalknav/files/walk_logs/walk_<ts>.log
    private var navLogFile: File? = null
    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS")

    // ==================== Firebase Analytics 클라우드 집계 (2026-05-29) ====================
    // 외출별 walk_log 는 디바이스 로컬에 저장되지만, Firebase 는 다중 사용자/다중 외출을
    // 한 곳에 통합 집계해서 대시보드 그래프로 보여준다. capstone 발표 슬라이드에 그래프 캡처
    // 인용 가능. 백엔드 진로 측면에서도 BaaS 통합 사례.
    //
    // 커스텀 이벤트 5종:
    //   navigation_start      — 외출 시작 (목적지/거리/waypoint 수/횡단보도 수)
    //   crosswalk_zone_enter  — 횡단보도 zone 진입
    //   traffic_light_announced — ML 신호등 안내 (color/transition_type/confidence)
    //   flicker_detected      — 점멸 phase 감지 (gap_ms)
    //   navigation_arrival    — 도착 (소요시간/거리/lean 카운트/ML 카운트)
    private val firebaseAnalytics: FirebaseAnalytics by lazy { Firebase.analytics }

    // ==================== 외출 정량 평가 지표 (실 테스트용, 2026-05-29) ====================
    // startNavLog 시점에 모두 reset, closeNavLog 시점에 walk_log 끝에 SUMMARY 블록으로 출력.
    // Capstone 발표 / 시각장애인 사용자 테스트 분석 / 슬라이드 정량 인용용.
    private var metricStartMs: Long = 0L
    private var metricLeanCount = 0              // "치우쳤습니다"
    private var metricCurveCount = 0             // "휘어집니다" / "꺾습니다"
    private var metricCrosswalkAnnounceCount = 0 // "횡단보도가 있습니다" / "휴대폰을 세로로"
    private var metricMlRedCount = 0             // ANNOUNCED_RED 발화
    private var metricMlGreenStaticCount = 0     // ANNOUNCED_STATIC_GREEN 발화
    private var metricMlTransitionCount = 0      // ANNOUNCED_TRANSITION_R_TO_G 발화
    private var metricMlGreenToRedCount = 0      // ANNOUNCED_TRANSITION_G_TO_R 발화
    private var metricFlickerCount = 0           // FLICKER_DETECTED
    private var metricRerouteCount = 0           // "경로를 다시 탐색" 류
    private var metricZoneEnterCount = 0         // 횡단보도 zone 진입
    private var metricDistanceM = 0.0            // 누적 GPS 거리 (m)
    private var metricSpeedSum = 0.0             // 평균 속도용 누적
    private var metricSpeedSamples = 0
    private var metricLastLat = 0.0
    private var metricLastLon = 0.0
    private var metricHasLastGps = false

    private fun resetMetrics() {
        metricStartMs = System.currentTimeMillis()
        metricLeanCount = 0
        metricCurveCount = 0
        metricCrosswalkAnnounceCount = 0
        metricMlRedCount = 0
        metricMlGreenStaticCount = 0
        metricMlTransitionCount = 0
        metricMlGreenToRedCount = 0
        metricFlickerCount = 0
        metricRerouteCount = 0
        metricZoneEnterCount = 0
        metricDistanceM = 0.0
        metricSpeedSum = 0.0
        metricSpeedSamples = 0
        metricHasLastGps = false
    }

    private fun writeWalkSummary() {
        val elapsedMs = System.currentTimeMillis() - metricStartMs
        val elapsedMin = elapsedMs / 60_000
        val elapsedSec = (elapsedMs % 60_000) / 1000
        val avgSpeed = if (metricSpeedSamples > 0) metricSpeedSum / metricSpeedSamples else 0.0
        val totalMlAnnounces = metricMlRedCount + metricMlGreenStaticCount +
                metricMlTransitionCount + metricMlGreenToRedCount
        val mlSuccessRate = if (metricZoneEnterCount > 0)
            (totalMlAnnounces * 100) / metricZoneEnterCount else 0
        appendNavLog("===================== WALK SUMMARY =====================")
        appendNavLog("총 시간: ${elapsedMin}분 ${elapsedSec}초 (${elapsedMs / 1000}초)")
        appendNavLog("이동 거리(GPS 누적): ${metricDistanceM.toInt()}m")
        appendNavLog("평균 속도: ${"%.2f".format(avgSpeed)} m/s")
        appendNavLog("--- 보행 안내 ---")
        appendNavLog("lean 보정 발화: ${metricLeanCount}회")
        appendNavLog("곡선/회전 안내: ${metricCurveCount}회")
        appendNavLog("횡단보도 진입 안내: ${metricCrosswalkAnnounceCount}회")
        appendNavLog("재라우팅: ${metricRerouteCount}회")
        appendNavLog("--- ML 신호등 ---")
        appendNavLog("횡단보도 zone 진입: ${metricZoneEnterCount}회")
        appendNavLog("ML 안내 총합: ${totalMlAnnounces}회 (zone 대비 ${mlSuccessRate}%)")
        appendNavLog("  · 빨간불(RED): ${metricMlRedCount}")
        appendNavLog("  · 정적 초록(STATIC_GREEN): ${metricMlGreenStaticCount}")
        appendNavLog("  · R→G 전환: ${metricMlTransitionCount}")
        appendNavLog("  · G→R 전환: ${metricMlGreenToRedCount}")
        appendNavLog("  · Flicker 감지: ${metricFlickerCount}")
        appendNavLog("=======================================================")
    }

    private fun startNavLog() {
        try {
            val dir = getExternalFilesDir("walk_logs")
            dir?.mkdirs()
            @Suppress("SpellCheckingInspection")
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            navLogFile = File(dir, "walk_$ts.log").apply {
                writeText("=== SafeWalkNav 외출 로그 시작 ${Date()} ===\n")
            }
            Log.d("SafeWalkNav", "Nav log file: ${navLogFile?.absolutePath}")
            // 정량 지표 카운터 reset — 매 외출마다 깨끗한 상태로 시작.
            resetMetrics()
        } catch (e: Exception) {
            Log.e("SafeWalkNav", "Nav log file create failed", e)
        }
    }

    private fun appendNavLog(msg: String) {
        try {
            navLogFile?.appendText("[${tsFormat.format(Date())}] $msg\n")
        } catch (_: Exception) {
        }
    }

    private fun closeNavLog() {
        if (navLogFile == null) return   // 이미 닫혔으면 no-op (idempotent)
        // 종료 직전 SUMMARY 블록 자동 출력 — 실 사용자 테스트 후 walk_log 마지막에
        // 그대로 슬라이드/리포트에 인용할 수 있는 정량 데이터.
        writeWalkSummary()
        appendNavLog("=== 종료 ===")
        navLogFile = null
    }

    // ==================== 카메라 (CameraX) ====================

    // NAVIGATING 진입 시 후방 카메라 PreviewView 를 cameraPreviewContainer 에 attach.
    // PR-UX2: 미리보기 use case
    // PR-AI: ImageAnalysis use case 추가 — TrafficLightDetector 로 보행자 신호등 색 검출
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var hasNearbyTrafficSignalForCamera = false
    private val TRAFFIC_SIGNAL_CAMERA_ZOOM_RATIO = 3f

    // ==================== 신호등 검출 (PR-AI) ====================

    private var trafficLightDetector: TrafficLightDetector? = null
    private var analysisExecutor: ExecutorService? = null

    // ==================== 발표/시연용 바운딩박스 오버레이 (DEBUG only) ====================
    // PreviewView 위에 한 겹 add 해서 모델이 검출한 신호등 위치를 빨강/초록 사각형으로 표시.
    // Release 빌드에서는 add 자체를 안 한다 — 실 사용자(시각장애인)에겐 의미 없으므로.
    private var boundingBoxOverlay: BoundingBoxOverlay? = null

    // ⚠️ 시연/테스트용 토글 — true 면 횡단보도 zone gate 를 우회해서 ML 추론 + 안내가 항상 작동.
    // 강의실/카페에서 신호등 사진/영상 비추면서 인식 정확도/박스 시각화/TTS 발화를 종합 점검할 때 사용.
    //
    // ❗ 외출 운영 빌드 전에 반드시 false 로 되돌릴 것. true 인 채로 외출하면:
    //   - GPS 없는 실내/지하/터널에서도 카메라 영상에 신호등 비슷한 게 잡히면 TTS 발화 (혼란)
    //   - 배터리/CPU 사용량 ↑ (333ms 마다 매 프레임 추론)
    //   - walk_*.log 에 TL_DIAG 가 zone 무관하게 폭증
    //
    // 안전망:
    //   - 안정성 필터(3 frame) + 색 변경 시에만 TTS 정책이 살아있으므로 정적인 신호등 사진에 대고 TTS 가 폭주하진 않음
    //   - bbox 6% 미만 필터도 그대로 적용 — 진짜 작은 noise 는 그래도 걸러짐
    //   - DEBUG 빌드 전제 (release 빌드는 디버그 박스 안 보이므로 사실상 영향 적음)
    //
    // 2026-05-29 — 화면 모드 전환(zone 진입 시 카메라 ON / 이탈 시 카메라 OFF) 도입 후
    //   실 외출에서도 zone 밖에선 카메라 자체가 꺼져 있으므로 ML 동작 안 함.
    //   따라서 false 로 두는 게 실 외출의 정확한 의도(zone 안에서만 ML).
    //   시연/강의실 신호등 사진 인식 테스트 시에만 true 로 켜고, 외출 전 다시 false 로 되돌릴 것.
    private val TEST_MODE_FORCE_ML_ON = false

    // 시연용 박스 표시 임계값 — 이 값 이상의 confidence 만 BoundingBoxOverlay 에 그린다.
    // DEBUG 빌드에서 진단 모드(threshold 0.3)로 추론 중이라 noise 박스도 검출 결과에 섞이는데,
    // 그걸 화면에 다 그리면 신호등 외 빨간 점/표지판/간판 같은 false positive 도 박스로 보임.
    // 0.6 정도면 진짜 신호등만 시연 화면에 깔끔하게 보임. 진단 로그(TL_DIAG)는 그대로 모든 추론 기록.
    // 시연 환경에 따라 0.5 (관대) ~ 0.7 (엄격) 사이로 조정 가능.
    private val OVERLAY_MIN_CONFIDENCE = 0.6f

    // 신호등 안전 정책 state machine — PR-SAFETY (2026-05-29)
    //
    // 정책:
    //   1. 빨간불 → "정지하세요" (안전 critical)
    //   2. 정적 초록불 (전환 못 본 상태) → "일단 멈춰서 다음 신호를 기다리세요"
    //      ❗ "건너세요" 절대 안 함. 사용자가 신호 시작점 타이밍 모르므로 다음 주기 대기.
    //   3. 빨강→초록 전환 (직접 인식) → "방금 초록불로 바뀌었습니다. 안전을 확인하고 건너세요"
    //      ✅ 유일하게 건너기를 안내하는 케이스. 강한 진동 동반.
    //
    // 안정성 필터:
    //   - 3 frame 연속 같은 색이 validated 검출돼야 confirm (오분류 흔들림 방지, 약 1초 지연)
    //
    // 발화 빈도:
    //   - 색 변경 시에만 TTS (반복 X)
    //   - HEARTBEAT_INTERVAL_MS 마다 같은 색상 신호를 TTS 반복
    //
    // 검출 타임아웃:
    //   - DETECTION_TIMEOUT_MS 이상 validated 검출 없으면 state reset.
    //     이유: 사용자가 잠시 카메라 돌렸다가 다시 신호등 향하면 그 사이 신호가 바뀌었을 수 있음 →
    //     이전 lastConfirmedColor 를 신뢰하지 않음.
    private var currentColorCandidate: Int = -1   // 현재 안정성 필터에서 추적 중인 색
    private var colorStreak: Int = 0              // 연속 검출 카운트
    private var lastConfirmedColor: Int = -1      // 3-frame 확정 통과한 마지막 색
    private var lastHeartbeatAt: Long = 0L        // 마지막 heartbeat 톤 시각
    private var lastValidatedAt: Long = 0L        // 마지막 validated 검출 시각 (timeout 판정용)
    private val RED_STABILITY_FRAMES = 2
    private val GREEN_STABILITY_FRAMES = 3
    private val GREEN_TRANSITION_STABILITY_FRAMES = 2
    private val HEARTBEAT_INTERVAL_MS = 10_000L
    private val DETECTION_TIMEOUT_MS = 10_000L

    // iOS TrafficLightDetector 와 동일한 미탐지 단계 안내.
    // AI가 켜진 상태에서 신호등을 계속 못 잡으면 3/6/9초에 한 번씩 카메라 조작 안내를 낸다.
    private var noDetectionStage: Int = 0
    private var noDetectionStartedAt: Long = 0L
    private val NO_DET_STAGE1_MS = 3_000L
    private val NO_DET_STAGE2_MS = 6_000L
    private val NO_DET_STAGE3_MS = 9_000L
    private val NO_DET_SUPPRESS_PEAK_CONFIDENCE = 0.25f
    private val GREEN_TRANSITION_MIN_CONFIDENCE = 0.55f
    private val GREEN_OVER_RED_CONFIDENCE_MARGIN = 0.05f
    private val NO_DET_SPEECH_HOLD_AFTER_CAMERA_ENTRY_MS = 5_000L
    private var noDetectionSpeechHoldUntil: Long = 0L
    private val CROSSWALK_ENTRY_SPEECH_HOLD_MS = 4_500L
    private var crosswalkEntrySpeechHoldUntil: Long = 0L
    private var deferredSignalDirectionJob: Job? = null

    // Flicker(점멸) 감지 — 한국 보행 신호 종료 직전 약 5~15초간 깜빡이는 phase 대응.
    // 깜빡임 중에 "방금 초록불로 바뀌었습니다, 건너세요" 발화는 안전상 매우 위험 — 사용자가 건너기
    // 시작하면 곧 빨강으로 바뀌어 위험. 그래서 transition 직후 또 transition 이 빠르게 일어나면
    // "신호 깜빡입니다, 멈추세요" 로 전환하고 일정 시간 안내 락아웃.
    private var lastTransitionAt: Long = 0L
    private var flickerLockoutUntil: Long = 0L
    private val MIN_PHASE_DURATION_MS = 4_000L   // 정상 신호 phase 는 최소 이 시간 지속
    // 한국 보행 신호 정상 phase 는 보통 5초 이상이므로 4초 임계가 안전.
    // 2026-05-29 walk_20260529_125126.log 분석에서 gap 3.1~3.08초 transition 이
    // 정상으로 처리되어 점멸 phase 일부를 놓치는 케이스 발견 → 3000 → 4000 으로 상향.
    private val FLICKER_LOCKOUT_MS = 6_000L      // flicker 감지 후 추가 안내 차단 기간

    // 횡단보도 zone 게이팅 — NavigationManager.isInCrosswalkZone (TMap waypoint 기반) 정확히 추적.
    // GPS update 마다 NavigationManager 가 isOnCrosswalkSegment() 로 판정 → state flow emit.
    // observeGuidance 의 collectLatest 로 갱신.
    private var inCrosswalkZone: Boolean = false

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
                speakTTS("SafeWalk입니다. GPS가 꺼져 있어 위치를 확인할 수 없습니다. 설정에서 GPS를 켜주세요.")
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
    @Suppress("unused")
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
                val now = System.currentTimeMillis()
                val r = FloatArray(9)
                if (SensorManager.getRotationMatrix(r, null, accelValues, magValues)) {
                    val orient = FloatArray(3)
                    SensorManager.getOrientation(r, orient)
                    var az = Math.toDegrees(orient[0].toDouble()).toFloat()
                    if (az < 0) az += 360f
                    val delta = ((az - currentAzimuth + 540f) % 360f) - 180f
                    currentAzimuth = (currentAzimuth + 0.15f * delta + 360f) % 360f
                    // Step 1 (2026-05-29) — IMU heading 보정 안내 복원.
                    // NavigationManager 가 currentTargetBearing 을 매 GPS tick 마다 도로 방향으로
                    // 갱신하므로, 이 호출이 walkingDiagnostic.analyzeLeanStatus 를 거쳐 25° 이상
                    // 벌어지면 LEFT/RIGHT_LEAN 누적 → 3회 도달 시 음성 보정 안내.
                    // 휴대폰 자세 가정: 평평하게 눕혀서 들고 있음 (Step 2 에서 자세 토글 추가 예정).
                    if (::navigationManager.isInitialized) {
                        navigationManager.updateCompassHeading(currentAzimuth, now)
                        // 나침반 UI 갱신 — 사용자 방향(흰 화살표) + 도로 방향(초록 화살표) 동시 push.
                        // 나침반 컨테이너가 GONE 일 때도 invalidate 비용은 미미.
                        // 시각 표시는 onboarding 중에도 유지 (사용자 회전 확인용).
                        if (::compassView.isInitialized) {
                            val target = navigationManager.targetBearing.value
                            compassView.setHeading(currentAzimuth, target)
                        }
                    }
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
                tMapApiClient = TMapApiClient(BuildConfig.TMAP_APP_KEY),
                signalApiClient = SignalApiClient(BuildConfig.T_DATA_API_KEY),
                headingLogger = headingLogger,
                trafficSignals = emptyList()
            )

        lifecycleScope.launch {

            navigationManager.updateTrafficSignals(
                loadTrafficSignalLocations()
            )
        }

        /*lifecycleScope.launch {
            navigationManager.fetchTrafficSignalData(
                signalLat = 37.5547454,
                signalLon = 127.1364893
            )
        }*/

        observeGuidance()

        lifecycleScope.launch {
            val trafficSignals = loadTrafficSignalLocations()
            Log.d(
                "TrafficSignalAPI",
                "loaded to MainActivity: ${trafficSignals.size}"
            )
            navigationManager.updateTrafficSignals(trafficSignals)
        }



        // View 참조
        rootLayout = findViewById(R.id.rootLayout)
        cameraPreviewContainer = findViewById(R.id.cameraPreviewContainer)
        beforeContainer = findViewById(R.id.beforeContainer)
        tvBeforeHint = findViewById(R.id.tvBeforeHint)
        resultsContainer = findViewById(R.id.resultsContainer)
        arrivedContainer = findViewById(R.id.arrivedContainer)
        tvArrivedName = findViewById(R.id.tvArrivedName)
        compassContainer = findViewById(R.id.compassContainer)
        compassView = findViewById(R.id.compassView)
        tvCompassGuidance = findViewById(R.id.tvCompassGuidance)
        tvCompassSubInfo = findViewById(R.id.tvCompassSubInfo)
        debugContainer = findViewById(R.id.debugContainer)
        tvDebugStatus = findViewById(R.id.tvDebugStatus)
        tvDebugGuidance = findViewById(R.id.tvDebugGuidance)
        tvDebugAiResult = findViewById(R.id.tvDebugAiResult)

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
        //observeGuidance()
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
        trafficLightDetector?.close()
        trafficLightDetector = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        tts.shutdown()
        toneGenerator?.release()
        releaseStereoTrack()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // walk_log 가 NAVIGATING 중에 열려있는 상태에서 OS 가 Activity 를 강제 종료할 수 있음.
        // 그 경우에도 SUMMARY 블록이 walk_log 끝에 들어가도록 닫는다.
        // navLogFile=null 이면 closeNavLog 가 no-op 이라 안전.
        closeNavLog()
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

        // 컨테이너 visibility (FrameLayout 위에 쌓인 5개 컨테이너 중 하나만 보이게)
        when (state) {
            AppState.IDLE, AppState.LISTENING, AppState.SEARCHING -> {
                beforeContainer.visibility = View.VISIBLE
                resultsContainer.visibility = View.GONE
                arrivedContainer.visibility = View.GONE
                cameraPreviewContainer.visibility = View.GONE
                compassContainer.visibility = View.GONE
            }

            AppState.RESULTS -> {
                beforeContainer.visibility = View.GONE
                resultsContainer.visibility = View.VISIBLE
                arrivedContainer.visibility = View.GONE
                cameraPreviewContainer.visibility = View.GONE
                compassContainer.visibility = View.GONE
            }

            AppState.NAVIGATING -> {
                // 2026-05-29 — NAVIGATING 진입 시 기본은 나침반 모드.
                // 횡단보도 zone + 10m 이내 신호등이면 카메라 모드로 전환.
                beforeContainer.visibility = View.GONE
                resultsContainer.visibility = View.GONE
                arrivedContainer.visibility = View.GONE
                applyNavigatingMode(inCrosswalkZone)
            }

            AppState.ARRIVED -> {
                beforeContainer.visibility = View.GONE
                resultsContainer.visibility = View.GONE
                arrivedContainer.visibility = View.VISIBLE
                cameraPreviewContainer.visibility = View.GONE
                compassContainer.visibility = View.GONE
            }
        }

        // IDLE 진입 시 검색 결과 컨테이너 정리 (이전 버튼들 제거)
        if (state == AppState.IDLE) {
            resultsContainer.removeAllViews()
        }

        // 카메라 lifecycle — 이제 NAVIGATING 자체가 아니라 inCrosswalkZone 변화에 따라 토글.
        // NAVIGATING 진입 시점엔 zone=false 가정이라 나침반만 표시, 카메라는 zone 진입 시 startCamera().
        // NAVIGATING 이탈 시 카메라가 켜져 있으면 정리.
        if (previous == AppState.NAVIGATING && state != AppState.NAVIGATING) {
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

    /**
     * NAVIGATING 상태일 때 inZone 에 따라 카메라/나침반 화면을 토글한다.
     *
     * inZone=false → 나침반 모드 (compassContainer VISIBLE, 카메라 OFF)
     * inZone=true  → 카메라 모드 (cameraPreviewContainer VISIBLE, startCamera 호출)
     *
     * NAVIGATING 진입 시 showState 가 inCrosswalkZone 값으로 1회 호출 (초기 false 가정).
     * 이후 inCrosswalkZone StateFlow collect 가 transition 마다 다시 호출.
     */
    private fun applyNavigatingMode(inZone: Boolean) {
        if (appState != AppState.NAVIGATING) return
        val shouldUseCamera = inZone && hasNearbyTrafficSignalForCamera
        if (shouldUseCamera) {
            compassContainer.visibility = View.GONE
            cameraPreviewContainer.visibility = View.VISIBLE
            startCamera()
        } else {
            cameraPreviewContainer.visibility = View.GONE
            compassContainer.visibility = View.VISIBLE
            stopCamera()
        }
    }

    private fun applyTrafficSignalCameraZoom() {
        val camera = camera ?: return
        val targetZoom = if (hasNearbyTrafficSignalForCamera) TRAFFIC_SIGNAL_CAMERA_ZOOM_RATIO else 1f
        val zoomState = camera.cameraInfo.zoomState.value
        val clampedZoom = if (zoomState != null) {
            targetZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        } else {
            targetZoom
        }
        camera.cameraControl.setZoomRatio(clampedZoom)
        appendNavLog("Camera zoom=${"%.1f".format(clampedZoom)} signalNearby=$hasNearbyTrafficSignalForCamera")
    }

    private fun updateDebugInfo() {
        if (!BuildConfig.DEBUG) return
        if (!::tvDebugStatus.isInitialized) return
        val talkback = if (isTalkBackEnabled()) "ON" else "OFF"
        val gps = if (gpsReady) "OK" else "?"
        val last = lastSearchKeyword.ifEmpty { "-" }
        val ai = if (inCrosswalkZone && hasNearbyTrafficSignalForCamera) "ON" else "OFF"
        tvDebugStatus.text = "STATE=${appState.name} | GPS=$gps | AI=$ai | TalkBack=$talkback | last=$last"
    }

    private fun updateAiDebugResult(message: String) {
        if (!BuildConfig.DEBUG) return
        if (!::tvDebugAiResult.isInitialized) return
        tvDebugAiResult.text = summarizeAiDebug(message)
        updateCompactDebugGuidance()
    }

    private fun summarizeAiDebug(message: String): String {
        val action = Regex("action=([A-Z_]+)").find(message)?.groupValues?.getOrNull(1)
        val label = Regex("label=([^\\s|]+)").find(message)?.groupValues?.getOrNull(1)
        val confPct = Regex("conf=(\\d+)%").find(message)?.groupValues?.getOrNull(1)
        val peakPct = Regex("peak=([0-9.]+)").find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { (it * 100).toInt().toString() }
        val color = when {
            label?.contains("red", ignoreCase = true) == true -> "RED"
            label?.contains("green", ignoreCase = true) == true -> "GREEN"
            else -> null
        }
        val aiLine = when {
            message.contains("AI OFF") -> "AI=OFF"
            color != null -> "AI=$color ${(confPct ?: peakPct ?: "").let { if (it.isEmpty()) "" else "$it%" }}".trim()
            message.contains("AI ON") -> "AI=ON"
            else -> "AI=OFF"
        }
        return if (action != null && action != "CHECKING") {
            "$aiLine\naction=$action"
        } else {
            aiLine
        }
    }

    private fun updateCompactDebugGuidance() {
        if (!BuildConfig.DEBUG) return
        if (!::tvDebugGuidance.isInitialized) return

        val debug = navigationManager.debugMessage.value
        val crosswalkDist = Regex("crosswalkDist=(-?\\d+)m").find(debug)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeUnless { it == "-1" }
        val state = Regex("crosswalkState=([^\\n|]+)").find(debug)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "NONE" }
        val aiLines = if (::tvDebugAiResult.isInitialized) {
            tvDebugAiResult.text.toString().lines().filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        val lines = mutableListOf<String>()
        lines.add("횡단보도=$inCrosswalkZone")
        lines.add("crosswalkDist=${crosswalkDist?.let { "${it}m" } ?: "-"}")
        if (state != null) lines.add("crosswalkState=$state")
        lines.add("signal10m=$hasNearbyTrafficSignalForCamera")
        if (aiLines.isNotEmpty()) {
            lines.addAll(aiLines)
        } else {
            lines.add("AI=${if (inCrosswalkZone && hasNearbyTrafficSignalForCamera) "ON" else "OFF"}")
        }
        tvDebugGuidance.text = lines.joinToString("\n")
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
                appState == AppState.SEARCHING
            ) {
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

            else -> { /* RESULTS/ARRIVED/SEARCHING 은 setupTouchArea 에서 이미 차단 */
            }
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
        } catch (_: Exception) {
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
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                speakTTS("소리를 키웠습니다.")
            }

            text.contains("작게") || text.contains("볼륨 내려") -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
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
            val msg = "주변 1킬로미터 이내에 $keyword 검색 결과가 없습니다. 화면을 길게 눌러 다시 시도하세요."
            speakTTS(msg)
            showState(AppState.IDLE)
        } else {
            val msg = navigationManager.lastError
                ?: "주변 1킬로미터 이내에 $keyword 검색 결과가 없습니다"
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
                frontLon = selected.frontLon,
                // MainActivity 가 요약을 직접 말하므로 NavigationManager 의 자동 요약 TTS 중복은 차단.
                suppressInitialSummary = true,
            )

            if (success) {
                showState(AppState.NAVIGATING)
                playToneSuccess()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // 파일 로깅 시작 + 경로 정보 dump
                startNavLog()
                val route = navigationManager.currentRoute
                if (route != null) {
                    val crosswalks = route.waypoints.count {
                        it.pointType == "CROSSWALK" || it.turnType in 211..217
                    }
                    appendNavLog("경로 로드: ${route.waypoints.size}개 waypoint (CROSSWALK ${crosswalks}개, 총 ${route.totalDistance}m)")
                    route.waypoints.forEachIndexed { i, wp ->
                        val mark = if (wp.pointType == "CROSSWALK" || wp.turnType in 211..217) "🚦" else "  "
                        appendNavLog("$mark [$i] type=${wp.pointType} turn=${wp.turnType} road=${wp.roadType} dist=${wp.distance} desc=${wp.description.take(80)}")
                    }
                    // Firebase Analytics — 외출 시작 이벤트
                    firebaseAnalytics.logEvent("navigation_start") {
                        param("destination_name", selected.name)
                        param("total_distance_m", route.totalDistance.toLong())
                        param("waypoint_count", route.waypoints.size.toLong())
                        param("crosswalk_count", crosswalks.toLong())
                    }
                }

                val summary = navigationManager.buildInitialSummary().ifEmpty { getRouteSummary() }
                appendNavLog("Starting walking guidance immediately")
                if (summary.isNotEmpty()) speakTTS(summary)
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
            "${"%.1f".format(meters / 1000.0)}킬로"
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
        appendNavLog("finishNavigation: 도착 — $arrivedName")
        // Firebase Analytics — 도착 이벤트 (closeNavLog 전에 호출, 그래야 metric 카운터가 살아있음)
        val durationSec = if (metricStartMs > 0) (System.currentTimeMillis() - metricStartMs) / 1000 else 0L
        val totalMlAnnounces = metricMlRedCount + metricMlGreenStaticCount +
                metricMlTransitionCount + metricMlGreenToRedCount
        firebaseAnalytics.logEvent("navigation_arrival") {
            param("destination_name", arrivedName)
            param("duration_sec", durationSec)
            param("distance_m", metricDistanceM.toLong())
            param("lean_count", metricLeanCount.toLong())
            param("curve_count", metricCurveCount.toLong())
            param("crosswalk_announce_count", metricCrosswalkAnnounceCount.toLong())
            param("reroute_count", metricRerouteCount.toLong())
            param("zone_enter_count", metricZoneEnterCount.toLong())
            param("ml_announce_total", totalMlAnnounces.toLong())
            param("flicker_count", metricFlickerCount.toLong())
        }
        closeNavLog()
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
        appendNavLog("stopNavigationFull (사용자 중단 또는 음성 명령)")
        closeNavLog()
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
            locationTracker.getLocationUpdates(500L).collectLatest { location ->
                // 극단적 오염(터널 출구 GPS 점프 등)만 사전 차단 — 세밀한 gating은 KalmanHeading에 위임
                if (location.hasAccuracy() && location.accuracy > 50f) {
                    return@collectLatest
                }

                // 자력계 없는 기기 fallback: GPS bearing(이동 중일 때만 신뢰 가능) → azimuth
                if (!magnetometerAvailable && location.hasBearing() && location.hasSpeed() && location.speed > 0.5f) {
                    val gpsBearing = location.bearing
                    val delta = ((gpsBearing - currentAzimuth + 540f) % 360f) - 180f
                    currentAzimuth = (currentAzimuth + 0.3f * delta + 360f) % 360f
                    // Step 1 (2026-05-29) — 자력계 없는 디바이스에서 GPS bearing 으로 azimuth 추정 후
                    // 같은 lean 보정 채널로 전달. magnetometerAvailable=true 인 경우는
                    // orientationListener 가 매 sensor tick 마다 별도로 호출하고 있으므로 중복 없음.
                    if (::navigationManager.isInitialized) {
                        navigationManager.updateCompassHeading(currentAzimuth, System.currentTimeMillis())
                    }
                }

                navigationManager.updateLocation(location.toGpsLocation())

                // 정량 지표 — 매 GPS tick 마다 거리 누적 + 속도 평균 샘플 추가.
                if (metricHasLastGps) {
                    val seg = LocationTracker.distanceBetween(
                        metricLastLat, metricLastLon, location.latitude, location.longitude
                    )
                    // 30m 넘는 점프는 GPS jitter 로 보고 제외 (실제 보행 속도 한계 vs 2초 tick).
                    if (seg < 30f) metricDistanceM += seg.toDouble()
                }
                metricLastLat = location.latitude
                metricLastLon = location.longitude
                metricHasLastGps = true
                if (location.hasSpeed()) {
                    metricSpeedSum += location.speed
                    metricSpeedSamples++
                }

                // 디버그 박스 갱신 (DEBUG 빌드만) + 파일 로그 (전체 빌드)
                val dist = LocationTracker.distanceBetween(
                    location.latitude, location.longitude,
                    navigationManager.destinationLat, navigationManager.destinationLon
                )
                val accuracyText =
                    if (location.hasAccuracy()) "±${location.accuracy.toInt()}m" else ""
                val speedText =
                    if (location.hasSpeed()) "${"%.1f".format(location.speed)}m/s" else "?m/s"

                // 횡단보도 zone 디버깅 — 매 GPS tick 마다 파일 로그.
                // 외출 후 walk_logs/*.log 파일을 받아서 분석할 수 있도록 모든 핵심 필드 dump.
                // 형식: GPS_TICK | gps좌표 | acc | spd | dest거리 | <debugMessage 전체 (zone/idx/wp/turnType/desc/nearestXW)>
                val debugSnapshot = navigationManager.debugMessage.value.replace("\n", " | ")
                appendNavLog(
                    "GPS_TICK lat=${"%.5f".format(location.latitude)} lon=${"%.5f".format(location.longitude)} " +
                            "$accuracyText spd=$speedText dest=${dist.toInt()}m | $debugSnapshot"
                )

                // IMU + GPS Kalman heading 융합 진단 — 2026-05-31 추가.
                // NavigationManager.fuseImuAndGps 가 매 sensor tick (~16Hz) 호출되며
                // latestFusionDebug 를 갱신하지만, walk_log 에는 GPS tick 주기(500ms~2s)로만 기록해
                // 파일 크기 폭주 방지. 외출 후 walk_log 에서 HEADING_FUSION 라인 grep 으로 융합 동작 검증.
                val fusionDebug = navigationManager.latestFusionDebug
                if (fusionDebug.isNotEmpty()) {
                    appendNavLog("HEADING_FUSION $fusionDebug")
                }

                if (BuildConfig.DEBUG) {
                    updateCompactDebugGuidance()
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

                if (dist > 15f) {
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
            } catch (_: Exception) {
            }
            track.write(stereoBuffer, 0, stereoBuffer.size)
            track.play()
        } catch (_: Exception) {
        }
    }

    private fun releaseStereoTrack() {
        try {
            stereoTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            stereoTrack?.release()
        } catch (_: Exception) {
        }
        stereoTrack = null
    }

    // ==================== Guidance Observer ====================

    private fun observeGuidance() {
        lifecycleScope.launch {
            navigationManager.guidanceMessage.collectLatest { message ->
                if (message.isNotEmpty()) {
                    speakTTS(message)
                    Log.d("SafeWalkNav", "Guidance: $message")
                    appendNavLog("Guidance: $message")

                    // 정량 지표 — guidanceMessage 패턴으로 안내 종류 카운트.
                    when {
                        message.contains("치우쳤습니다") -> metricLeanCount++
                        message.contains("휘어집니다") || message.contains("꺾습니다") -> metricCurveCount++
                        message.contains("횡단보도가 있습니다") ||
                                message.contains("휴대폰을 세로로") -> metricCrosswalkAnnounceCount++
                        message.contains("재탐색") ||
                                message.contains("다시 탐색") -> metricRerouteCount++
                    }

                    // 나침반 화면의 메인 텍스트도 같이 갱신 — 사용자가 시각으로도 확인 가능.
                    if (::tvCompassGuidance.isInitialized) {
                        tvCompassGuidance.text = message
                    }

                    if (BuildConfig.DEBUG) {
                        updateCompactDebugGuidance()
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

        // 횡단보도 zone 상태 추적 — TMap waypoint 의 pointType=CROSSWALK + GPS 위치 기반.
        // NavigationManager 가 매 GPS update 마다 갱신. ML 안내 게이팅에 사용.
        lifecycleScope.launch {
            navigationManager.navEvents.collectLatest { event ->
                val message = event.message
                if (message.isEmpty()) return@collectLatest

                val now = System.currentTimeMillis()
                val isSignalDirection = message.contains("신호등") && message.contains("방향")
                val isSignalPresenceOnly = message == "신호등이 있습니다."
                val isCrosswalkEvent = message.contains("횡단보도")

                if (isSignalPresenceOnly) {
                    appendNavLog("NavEvent suppressed: signal presence only")
                    return@collectLatest
                }

                if (now < crosswalkEntrySpeechHoldUntil && (isCrosswalkEvent || message.contains("신호등"))) {
                    if (isSignalDirection) {
                        val delayMs = crosswalkEntrySpeechHoldUntil - now
                        deferredSignalDirectionJob?.cancel()
                        deferredSignalDirectionJob = lifecycleScope.launch {
                            delay(delayMs)
                            speakTTS(message)
                            appendNavLog("NavEvent deferred after posture: $message")
                            if (::tvCompassGuidance.isInitialized) {
                                tvCompassGuidance.text = message
                            }
                            if (BuildConfig.DEBUG) updateCompactDebugGuidance()
                        }
                    } else {
                        appendNavLog("NavEvent suppressed during posture guidance: $message")
                    }
                    return@collectLatest
                }

                if (event.interrupt) {
                    tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, message.hashCode().toString())
                } else {
                    speakTTS(message)
                }
                Log.d("SafeWalkNav", "NavEvent: $message")
                appendNavLog("NavEvent: $message")

                if (::tvCompassGuidance.isInitialized) {
                    tvCompassGuidance.text = message
                }
                if (BuildConfig.DEBUG) {
                    updateCompactDebugGuidance()
                }
                if (message.contains("신호등") || message.contains("횡단보도")) {
                    vibrateMedium()
                    playToneAlert()
                }
            }
        }

        lifecycleScope.launch {
            navigationManager.isInCrosswalkZone.collectLatest { inZone ->
                val wasIn = inCrosswalkZone
                inCrosswalkZone = inZone
                if (inZone && !wasIn) {
                    // 진입 시점 — 신호등 안전 state machine 리셋해서 깨끗한 상태로 시작.
                    // 안정성 streak, 마지막 confirmed 색, heartbeat 시각, flicker 락아웃 모두 초기화.
                    currentColorCandidate = -1
                    colorStreak = 0
                    lastConfirmedColor = -1
                    lastHeartbeatAt = 0L
                    lastValidatedAt = 0L
                    lastTransitionAt = 0L
                    flickerLockoutUntil = 0L
                    resetTrafficLightNoDetectionEscalation()
                    noDetectionSpeechHoldUntil =
                        System.currentTimeMillis() + NO_DET_SPEECH_HOLD_AFTER_CAMERA_ENTRY_MS
                    crosswalkEntrySpeechHoldUntil =
                        System.currentTimeMillis() + CROSSWALK_ENTRY_SPEECH_HOLD_MS
                    metricZoneEnterCount++   // 정량 지표 — zone 진입 카운트
                    Log.d("SafeWalkNav", "Crosswalk zone ENTER | TrafficLight AI waits for signal<=10m")
                    appendNavLog("Crosswalk zone ENTER | TrafficLight AI waits for signal<=10m")
                    updateAiDebugResult("AI ON | waiting frame")
                    // Firebase Analytics — 횡단보도 zone 진입 이벤트
                    firebaseAnalytics.logEvent("crosswalk_zone_enter") {
                        param("session_start_ms", metricStartMs)
                        param("zone_enter_count_in_session", metricZoneEnterCount.toLong())
                    }
                    // 화면 모드 전환 — 나침반 → 카메라 (NAVIGATING 중일 때만)
                    if (appState == AppState.NAVIGATING) {
                        speakTTS("횡단보도가 가까워졌습니다. 휴대폰을 세로로 들어주세요.")
                        applyNavigatingMode(inZone = true)
                    }
                } else if (!inZone && wasIn) {
                    Log.d("SafeWalkNav", "Crosswalk zone EXIT | TrafficLight AI=OFF")
                    appendNavLog("Crosswalk zone EXIT | TrafficLight AI=OFF")
                    updateAiDebugResult("AI OFF")
                    deferredSignalDirectionJob?.cancel()
                    deferredSignalDirectionJob = null
                    crosswalkEntrySpeechHoldUntil = 0L
                    // 화면 모드 전환 — 카메라 → 나침반 (NAVIGATING 중일 때만)
                    if (appState == AppState.NAVIGATING) {
                        speakTTS("횡단보도를 지나갔습니다. 휴대폰을 평평하게 들고 계속 진행하세요.")
                        applyNavigatingMode(inZone = false)
                    }
                }
                updateDebugInfo()
                updateCompactDebugGuidance()
            }
        }

        lifecycleScope.launch {
            navigationManager.hasNearbyTrafficSignal.collectLatest { hasSignal ->
                hasNearbyTrafficSignalForCamera = hasSignal
                applyNavigatingMode(inCrosswalkZone)
                applyTrafficSignalCameraZoom()
                updateCompactDebugGuidance()
            }
        }

        // 도로 진행 방향 (targetBearing) StateFlow — 2초 GPS tick 주기로 갱신됨.
        // 나침반 화면의 초록 화살표가 이 값을 따라가게 한다.
        // (사용자 방향 흰 화살표는 orientationListener 가 더 자주 갱신하므로
        //  여기선 도로 방향이 바뀔 때만 추가 invalidate.)
        lifecycleScope.launch {
            navigationManager.targetBearing.collectLatest { roadBearing ->
                if (::compassView.isInitialized) {
                    compassView.setHeading(currentAzimuth, roadBearing)
                }
            }
        }

        // 목적지까지 남은 거리 — 나침반 화면 하단 보조 정보로 표시.
        lifecycleScope.launch {
            navigationManager.distanceToDestination.collectLatest { dist ->
                if (!::tvCompassSubInfo.isInitialized) return@collectLatest
                tvCompassSubInfo.text = if (dist == Float.MAX_VALUE) ""
                else "목적지까지 ${dist.toInt()}m"
            }
        }

        // NavigationManager 의 debugMessage 도 파일에 기록 (sparse 하게 — 매 GPS update 마다라 양 많을 수 있음)
        lifecycleScope.launch {
            navigationManager.debugMessage.collectLatest { msg ->
                if (msg.isNotEmpty()) {
                    appendNavLog("DBG: ${msg.replace("\n", " | ")}")
                }
            }
        }

        // DEBUG 빌드: NavigationManager.debugMessage 를 화면 하단에 실시간 표시.
        // 외출 중 횡단보도 zone 판정 디버깅 용도 — `횡단보도=`, `wp=`, `roadType=`, `idx=` 값 추적.
        if (BuildConfig.DEBUG) {
            lifecycleScope.launch {
                navigationManager.debugMessage.collectLatest { msg ->
                    if (msg.isNotEmpty()) {
                        updateCompactDebugGuidance()
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
        //신호등 디버그 화면 표시
        lifecycleScope.launch {
            navigationManager.debugMessage.collectLatest { message ->
                if (BuildConfig.DEBUG && message.isNotEmpty()) {
                    updateCompactDebugGuidance()
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
        if (cameraProvider != null) {
            applyTrafficSignalCameraZoom()
            return
        }   // 이미 작동 중

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

        // 발표/시연용 바운딩박스 오버레이 — DEBUG 빌드에서만 PreviewView 위에 한 겹 add.
        // onTrafficLightDetected 가 setDetections() 로 검출 결과를 푸시 → 카메라 영상 위에 빨강/초록 박스.
        // Release 빌드에서는 add 안 함 — 실 사용자(시각장애인)에게 의미 없으므로 GPU/메모리 절약.
        if (BuildConfig.DEBUG) {
            val overlay = BoundingBoxOverlay(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            cameraPreviewContainer.addView(overlay)
            boundingBoxOverlay = overlay
            appendNavLog("BoundingBoxOverlay attached (DEBUG 빌드 시연용)")
        }

        // 검출기/executor 초기화 (재사용)
        if (trafficLightDetector == null) {
            try {
                trafficLightDetector = TrafficLightDetector(this).apply {
                    // 실측 중 TTS 판정은 운영 임계값(0.5)을 사용한다.
                    // diagnosticMode=true 는 0.3 후보까지 TTS state machine 에 들어와 오발화 위험이 크다.
                    diagnosticMode = false
                }
                Log.d("SafeWalkNav", "TrafficLightDetector loaded (diagnosticMode=false)")
                appendNavLog("TrafficLightDetector loaded (diagnosticMode=false)")
            } catch (e: Exception) {
                Log.e("SafeWalkNav", "Failed to load TrafficLightDetector", e)
                appendNavLog("Failed to load TrafficLightDetector: ${e.message}")
            }
        }
        if (analysisExecutor == null) {
            analysisExecutor = Executors.newSingleThreadExecutor()
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }

                // ImageAnalysis use case — 검출기 로드 됐을 때만 추가
                val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
                val detector = trafficLightDetector
                val executor = analysisExecutor
                if (detector != null && executor != null) {
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(
                        executor,
                        TrafficLightAnalyzer(
                            detector = detector,
                            // 횡단보도 zone + 10m 이내 신호등일 때만 ML 추론. 테스트 모드는 예외.
                            isActive = {
                                (inCrosswalkZone && hasNearbyTrafficSignalForCamera) || TEST_MODE_FORCE_ML_ON
                            },
                        ) { detections ->
                            runOnUiThread { onTrafficLightDetected(detections) }
                        }
                    )
                    useCases += analysis
                    Log.d("SafeWalkNav", "ImageAnalysis bound — TrafficLight detection ON")
                }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases.toTypedArray()
                )
                applyTrafficSignalCameraZoom()
                Log.d("SafeWalkNav", "Camera bound (use cases: ${useCases.size})")
            } catch (e: Exception) {
                Log.e("SafeWalkNav", "Camera bind failed", e)
                cameraProvider = null
                camera = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 신호등 검출 결과 처리 (PR-SAFETY 2026-05-29 — state machine 기반).
     *
     * 단계:
     *   (0) raw detection 들 → BoundingBoxOverlay (시연용, OVERLAY_MIN_CONFIDENCE 필터)
     *   (1) detections.isEmpty / zone gate
     *   (2) 6% bbox 필터 → validated
     *   (3) Detection 타임아웃 체크 → state reset 여부
     *   (4) 안정성 필터: 3 frame 연속 같은 색이어야 confirm
     *   (5) confirmed 색 처리:
     *       · 이전 confirmed 와 같음 → HEARTBEAT_INTERVAL_MS 마다 같은 색상 TTS 반복
     *       · 다름 → 색 변경 또는 첫 confirm → TTS 발화
     *           - 빨강: "빨간불입니다. 정지하세요."
     *           - 초록 (이전이 빨강): "방금 초록불로 바뀌었습니다. 안전을 확인하고 건너세요." ← 유일 "건너세요"
     *           - 초록 (그 외): "초록불입니다. 일단 멈춰서 다음 신호를 기다리세요."
     *
     * 진단: 매 inference 의 단계별 통과 / 차단 사유를 walk_*.log 에 기록.
     *   TL_DIAG zone=… raw=N aboveTh=K peakConf=X.XX (R=Y G=Z) th=… inferMs=…
     *   TL_DIAG  └─ <REASON> …
     *     where REASON ∈ {ALL_TOO_SMALL, STATE_RESET, STABILITY_PENDING, SAME_COLOR_QUIET,
     *                     HEARTBEAT, ANNOUNCED_RED, ANNOUNCED_STATIC_GREEN,
     *                     ANNOUNCED_TRANSITION_R_TO_G, ANNOUNCED_TRANSITION_G_TO_R}
     */
    private fun onTrafficLightDetected(detections: List<TrafficLightDetection>) {
        val stats = trafficLightDetector?.lastStats

        // ──── 발표/시연용 바운딩박스 오버레이 갱신 ────
        // OVERLAY_MIN_CONFIDENCE 이상의 검출만 그린다 — 진단 임계(0.3)에 잡힌 noise 박스
        // (빨간 점/표지판/간판 등) 가 화면을 덮는 걸 방지. 진단 로그(TL_DIAG)는 그대로 모두 기록.
        // 6% bbox 필터 / cooldown 같은 TTS 후처리는 의도적으로 적용 안 함 — "모델이 신뢰도 높게
        // 잡은 객체는 무엇인가" 를 시연에 정직하게 보여주기 위함.
        // empty 리스트가 들어가도 setDetections 가 알아서 박스를 지운다.
        // boundingBoxOverlay 자체가 DEBUG 빌드에서만 attach 되므로 release 빌드에서는 자동으로 no-op.
        boundingBoxOverlay?.setDetections(
            detections.filter { it.confidence >= OVERLAY_MIN_CONFIDENCE }
        )

        // ──── 진단 통계 헤더 (zone 진입 후 매 inference 기록) ────
        // zone=false 일 땐 spam 방지 위해 기록 안 함. zone=true 인데 detections=0 면 모델이 신호등을
        // 못 보고 있다는 강력한 신호.
        val statsLine = if (stats != null) {
            "TL_DIAG zone=$inCrosswalkZone signal10m=$hasNearbyTrafficSignalForCamera " +
                    "raw=${detections.size} " +
                    "aboveTh=${stats.rawCandidatesAboveThreshold} " +
                    "peakConf=${"%.2f".format(stats.peakConfidence)} " +
                    "(R=${"%.2f".format(stats.peakConfRed)} G=${"%.2f".format(stats.peakConfGreen)}) " +
                    "th=${"%.2f".format(stats.confidenceThresholdUsed)}${if (stats.diagnosticMode) "[DIAG]" else ""} " +
                    "inferMs=${stats.inferenceMs}"
        } else {
            "TL_DIAG zone=$inCrosswalkZone signal10m=$hasNearbyTrafficSignalForCamera raw=${detections.size} stats=null"
        }
        val trafficLightAiActive = inCrosswalkZone && hasNearbyTrafficSignalForCamera
        val aiResultLine = if (stats != null) {
            "AI ${if (trafficLightAiActive) "ON" else "OFF"} | raw=${detections.size} " +
                    "above=${stats.rawCandidatesAboveThreshold} " +
                    "peak=${"%.2f".format(stats.peakConfidence)} " +
                    "R=${"%.2f".format(stats.peakConfRed)} " +
                    "G=${"%.2f".format(stats.peakConfGreen)} " +
                    "th=${"%.2f".format(stats.confidenceThresholdUsed)} " +
                    "ms=${stats.inferenceMs}"
        } else {
            "AI ${if (trafficLightAiActive) "ON" else "OFF"} | raw=${detections.size} stats=null"
        }
        updateAiDebugResult("$aiResultLine | action=CHECKING")

        // zone 안에서만 파일 로깅 — 평소 보행 중엔 spam 방지.
        // 테스트 모드면 zone 무관하게 로깅 (인식 정확도 검증을 위해 모든 추론 라인 필요).
        if (trafficLightAiActive || TEST_MODE_FORCE_ML_ON) {
            appendNavLog(statsLine)
        }

        // ──── 안내 흐름 (기존 로직, 단 reason 명시) ────

        if (detections.isEmpty()) {
            // 모델이 신호등을 못 봄 (또는 threshold 미달).
            // iOS TrafficLightDetector 와 동일하게 AI 활성 상태에서는 단계형 카메라 조작 안내를 낸다.
            if (stats != null && stats.peakConfidence >= NO_DET_SUPPRESS_PEAK_CONFIDENCE) {
                resetTrafficLightNoDetectionEscalation()
                appendNavLog(
                    "TL_DIAG  └─ WEAK_CANDIDATE_SUPPRESS_NO_DET peak=${"%.2f".format(stats.peakConfidence)} " +
                            "(R=${"%.2f".format(stats.peakConfRed)} G=${"%.2f".format(stats.peakConfGreen)})"
                )
                if (BuildConfig.DEBUG) {
                    updateCompactDebugGuidance()
                    updateAiDebugResult("$aiResultLine | action=WEAK_CANDIDATE")
                }
                return
            }
            handleTrafficLightNoDetection(
                aiResultLine = aiResultLine,
                reason = "NO_DET",
                allowSpeech = trafficLightAiActive || TEST_MODE_FORCE_ML_ON,
            )
            if (BuildConfig.DEBUG) {
                updateCompactDebugGuidance()
                updateAiDebugResult("$aiResultLine | action=NO_DET")
            }
            return
        }

        // 0차 필터: 횡단보도 zone 진입했을 때만 안내.
        // NavigationManager.isInCrosswalkZone (TMap waypoint pointType=CROSSWALK + GPS 위치 판정) 기반.
        // ML 추론은 백그라운드에서 계속 돌지만 zone 밖에선 결과 무시.
        // 단 TEST_MODE_FORCE_ML_ON=true 면 zone 무관하게 TTS 발화 + 디바운스/박스필터 진행 (인식 정확도 테스트용).
        if (!trafficLightAiActive && !TEST_MODE_FORCE_ML_ON) return

        // 1차 필터: 너무 작은 박스 제외.
        // 빨간불은 놓치는 비용이 크므로 초록불보다 작은 박스도 통과시킨다.
        val minRedBoxDimension = 0.03f
        val minGreenBoxDimension = if (lastConfirmedColor == 0) 0.03f else 0.06f
        val validated = detections.filter { d ->
            val minBoxDimension = if (d.classId == 0) minRedBoxDimension else minGreenBoxDimension
            d.bbox.width >= minBoxDimension && d.bbox.height >= minBoxDimension
        }
        if (validated.isEmpty()) {
            // 1차 필터에서 다 떨어짐 — 진단 라인에 reason 추가
            val small = detections.first()
            appendNavLog(
                "TL_DIAG  └─ ALL_TOO_SMALL nearest=${small.label} ${(small.confidence * 100).toInt()}% " +
                        "box=${(small.bbox.width * 100).toInt()}x${(small.bbox.height * 100).toInt()}% " +
                        "(minR=3%, minG=6%)"
            )
            if (BuildConfig.DEBUG) {
                updateCompactDebugGuidance()
                updateAiDebugResult(
                    "$aiResultLine | action=SMALL label=${small.label} " +
                            "conf=${(small.confidence * 100).toInt()}% " +
                            "box=${(small.bbox.width * 100).toInt()}x${(small.bbox.height * 100).toInt()}%"
                )
            }
            resetTrafficLightNoDetectionEscalation()
            return
        }

        resetTrafficLightNoDetectionEscalation()

        val nearest = selectTrafficLightForSpeech(validated, lastConfirmedColor) ?: return
        val detectedColor = nearest.classId

        val now = System.currentTimeMillis()

        // ──── (a0) Flicker 락아웃 — 깜빡임 감지된 이후 안내 자체를 차단 ────
        // 한국 보행 신호 종료 직전 점멸 phase 에서 transition 이 1~3초 간격으로 반복되는 패턴을
        // 잡아 "건너세요" 류 발화를 막는다. lockout 동안 state 도 동결 (streak 갱신 X).
        if (flickerLockoutUntil > 0L && now < flickerLockoutUntil) {
            val remainingMs = flickerLockoutUntil - now
            appendNavLog(
                "TL_DIAG  └─ FLICKER_LOCKOUT remaining=${remainingMs}ms current=${nearest.label}"
            )
            if (BuildConfig.DEBUG) {
                updateCompactDebugGuidance()
                updateAiDebugResult(
                    "$aiResultLine | action=FLICKER_LOCKOUT remaining=${remainingMs}ms"
                )
            }
            return
        }
        if (flickerLockoutUntil > 0L && now >= flickerLockoutUntil) {
            // 락아웃 종료 — state 깨끗이 reset 해서 다음 frame 부터 보수적으로 재인식.
            // lastConfirmedColor 도 -1 로 reset 해야 락아웃 직후 GREEN 이 "transition" 으로 잘못
            // 잡혀 "건너세요" 발화되는 일을 막을 수 있음.
            appendNavLog("TL_DIAG  └─ FLICKER_LOCKOUT_END (state reset)")
            currentColorCandidate = -1
            colorStreak = 0
            lastConfirmedColor = -1
            flickerLockoutUntil = 0L
        }

        // ──── (a) Detection 타임아웃 → state reset ────
        // validated 검출이 DETECTION_TIMEOUT_MS 이상 끊겼다가 다시 들어오면 이전 lastConfirmedColor 신뢰 X.
        // 사용자가 잠시 카메라를 다른 데 돌렸다가 다시 신호등 비추는 사이 신호가 바뀌었을 가능성.
        if (lastValidatedAt > 0 && now - lastValidatedAt > DETECTION_TIMEOUT_MS) {
            appendNavLog("TL_DIAG  └─ STATE_RESET (gap=${(now - lastValidatedAt) / 1000}s, prev=${classLabel(lastConfirmedColor)})")
            currentColorCandidate = -1
            colorStreak = 0
            lastConfirmedColor = -1
        }
        lastValidatedAt = now

        // ──── (b) 안정성 필터 — 빨간불은 더 빠르게, 초록불은 더 보수적으로 confirm ────
        if (detectedColor == currentColorCandidate) {
            colorStreak++
        } else {
            currentColorCandidate = detectedColor
            colorStreak = 1
        }

        val requiredStabilityFrames = when {
            detectedColor == 0 -> RED_STABILITY_FRAMES
            lastConfirmedColor == 0 -> GREEN_TRANSITION_STABILITY_FRAMES
            else -> GREEN_STABILITY_FRAMES
        }
        if (colorStreak < requiredStabilityFrames) {
            appendNavLog(
                "TL_DIAG  └─ STABILITY_PENDING candidate=${nearest.label} streak=$colorStreak/$requiredStabilityFrames " +
                        "conf=${(nearest.confidence * 100).toInt()}% " +
                        "box=${(nearest.bbox.width * 100).toInt()}x${(nearest.bbox.height * 100).toInt()}%"
            )
            if (BuildConfig.DEBUG) {
                updateCompactDebugGuidance()
                updateAiDebugResult(
                    "$aiResultLine | action=STABILITY_PENDING label=${nearest.label} " +
                            "streak=$colorStreak/$requiredStabilityFrames"
                )
            }
            return
        }

        // ──── (c) 안정성 통과 — confirmed ────
        val confirmedColor = currentColorCandidate
        val previousColor = lastConfirmedColor

        // (c-1) 같은 색 지속 → HEARTBEAT_INTERVAL_MS 마다 같은 색상 TTS 반복
        if (confirmedColor == previousColor) {
            val heartbeatDue = now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS
            if (heartbeatDue) {
                val repeatMessage = repeatTrafficLightMessage(confirmedColor)
                speakTrafficLightTTS(repeatMessage, interrupt = confirmedColor == 0)
                lastHeartbeatAt = now
                appendNavLog(
                    "TL_DIAG  └─ REPEAT_TTS color=${classLabel(confirmedColor)} interval=${HEARTBEAT_INTERVAL_MS}ms"
                )
                if (BuildConfig.DEBUG) {
                    updateAiDebugResult("$aiResultLine | action=REPEAT_TTS label=${nearest.label}")
                }
            } else {
                val nextIn = (HEARTBEAT_INTERVAL_MS - (now - lastHeartbeatAt)) / 1000
                appendNavLog(
                    "TL_DIAG  └─ SAME_COLOR_QUIET (next repeat in ${nextIn}s)"
                )
                if (BuildConfig.DEBUG) {
                    updateAiDebugResult(
                        "$aiResultLine | action=QUIET label=${nearest.label} next_repeat=${nextIn}s"
                    )
                }
            }
            return
        }

        // (c-2) 색이 변경됨 (또는 첫 confirm)

        // ── (c-2a) Flicker 감지 — 직전 transition 후 MIN_PHASE_DURATION_MS 미만이면 점멸로 간주 ──
        // previousColor != -1 조건은 첫 confirm 제외 (첫 confirm 은 항상 정상 안내).
        // 점멸 phase 에서 "건너세요" 발화 막는 핵심 안전 로직.
        val isRedToGreenTransition = previousColor == 0 && confirmedColor == 1
        if (!isRedToGreenTransition &&
            previousColor != -1 &&
            lastTransitionAt > 0L &&
            now - lastTransitionAt < MIN_PHASE_DURATION_MS
        ) {
            val gapMs = now - lastTransitionAt
            val flickerMsg = "신호가 깜빡입니다. 멈춰서 다음 신호를 기다리세요."
            speakTrafficLightTTS(flickerMsg, interrupt = true)
            vibrateWarning()                    // 강한 staccato 진동
            flickerLockoutUntil = now + FLICKER_LOCKOUT_MS
            metricFlickerCount++                // 정량 지표
            // Firebase Analytics — Flicker(점멸) 감지 이벤트
            firebaseAnalytics.logEvent("flicker_detected") {
                param("gap_ms", gapMs)
                param("prev_color", classLabel(previousColor))
                param("new_color", classLabel(confirmedColor))
            }

            // state 정리 — 락아웃 동안 streak / candidate 갱신 안 되게.
            // lastConfirmedColor 는 일단 -1 로 — 락아웃 종료 후 보수적 재시작.
            lastConfirmedColor = -1
            currentColorCandidate = -1
            colorStreak = 0
            lastTransitionAt = now
            lastHeartbeatAt = now

            Log.d("SafeWalkNav", "TL FLICKER detected — gap=${gapMs}ms, lockout ${FLICKER_LOCKOUT_MS}ms")
            appendNavLog(
                "TL_DIAG  └─ FLICKER_DETECTED prev=${classLabel(previousColor)} new=${classLabel(confirmedColor)} " +
                        "gap=${gapMs}ms (min=${MIN_PHASE_DURATION_MS}ms) lockout=${FLICKER_LOCKOUT_MS}ms"
            )

            if (BuildConfig.DEBUG) {
                updateCompactDebugGuidance()
                updateAiDebugResult(
                    "$aiResultLine | action=FLICKER gap=${gapMs}ms lockout=${FLICKER_LOCKOUT_MS}ms"
                )
            }
            return
        }

        // (c-2b) 정상 transition (또는 첫 confirm) → TTS 발화
        lastConfirmedColor = confirmedColor
        lastHeartbeatAt = now
        lastTransitionAt = now

        val message: String
        val withVibrate: Boolean
        val action: String

        when {
            confirmedColor == 0 -> {
                // 빨강 — 신규 또는 초록→빨강 전환
                message = "빨간불입니다. 정지하세요."
                withVibrate = previousColor == 1   // 초록→빨강 전환 시 진동 추가
                action = if (previousColor == 1) "ANNOUNCED_TRANSITION_G_TO_R" else "ANNOUNCED_RED"
                if (previousColor == 1) metricMlGreenToRedCount++ else metricMlRedCount++
            }
            confirmedColor == 1 && previousColor == 0 -> {
                // 빨강 → 초록 직접 전환 인식! 유일하게 "건너세요" 안내하는 케이스.
                message = "방금 초록불로 바뀌었습니다. 안전을 확인하고 건너세요."
                withVibrate = true   // 강한 주의 환기
                action = "ANNOUNCED_TRANSITION_R_TO_G"
                metricMlTransitionCount++
            }
            confirmedColor == 1 -> {
                // 정적 초록불 (첫 인식 / timeout 후 재인식 — 전환 못 봄).
                // ❗ "건너세요" 절대 안 함. 다음 주기 대기 안내.
                message = "초록불입니다. 일단 멈춰서 다음 신호를 기다리세요."
                withVibrate = false
                action = "ANNOUNCED_STATIC_GREEN"
                metricMlGreenStaticCount++
            }
            else -> return
        }

        val shouldInterrupt = when (action) {
            "ANNOUNCED_RED",
            "ANNOUNCED_TRANSITION_G_TO_R",
            "ANNOUNCED_TRANSITION_R_TO_G" -> true
            else -> false
        }
        speakTrafficLightTTS(message, interrupt = shouldInterrupt)
        if (withVibrate) vibrateShort()

        // Firebase Analytics — ML 신호등 안내 이벤트 (color + transition_type + confidence)
        firebaseAnalytics.logEvent("traffic_light_announced") {
            param("color", classLabel(confirmedColor))
            param("transition_type", action)
            param("confidence_pct", (nearest.confidence * 100).toLong())
            param("box_width_pct", (nearest.bbox.width * 100).toLong())
            param("box_height_pct", (nearest.bbox.height * 100).toLong())
        }

        Log.d("SafeWalkNav", "TL: $action — $message (conf=${nearest.confidence}, prev=$previousColor)")
        appendNavLog(
            "TL_DIAG  └─ $action prev=${classLabel(previousColor)} new=${classLabel(confirmedColor)} " +
                    "conf=${"%.2f".format(nearest.confidence)} " +
                    "box=${"%.2f".format(nearest.bbox.width)}x${"%.2f".format(nearest.bbox.height)} " +
                    "validated=${validated.size}/${detections.size}"
        )

        if (BuildConfig.DEBUG) {
            updateCompactDebugGuidance()
            updateAiDebugResult(
                "$aiResultLine | action=$action label=${nearest.label} " +
                        "conf=${(nearest.confidence * 100).toInt()}% " +
                        "box=${(nearest.bbox.width * 100).toInt()}x${(nearest.bbox.height * 100).toInt()}%"
            )
        }
    }

    private fun resetTrafficLightNoDetectionEscalation() {
        noDetectionStage = 0
        noDetectionStartedAt = 0L
    }

    private fun handleTrafficLightNoDetection(
        aiResultLine: String,
        reason: String,
        allowSpeech: Boolean,
    ) {
        if (!allowSpeech) {
            resetTrafficLightNoDetectionEscalation()
            return
        }

        val now = System.currentTimeMillis()
        if (now < noDetectionSpeechHoldUntil) {
            resetTrafficLightNoDetectionEscalation()
            appendNavLog(
                "TL_DIAG  └─ NO_DET_HOLD_AFTER_CAMERA_ENTRY remaining=${(noDetectionSpeechHoldUntil - now) / 1000}s reason=$reason"
            )
            if (BuildConfig.DEBUG) {
                updateAiDebugResult("$aiResultLine | action=NO_DET_HOLD reason=$reason")
            }
            return
        }

        if (noDetectionStartedAt == 0L) {
            noDetectionStartedAt = now
        }

        val elapsed = now - noDetectionStartedAt
        val stageMessage = when {
            elapsed >= NO_DET_STAGE3_MS && noDetectionStage < 3 -> {
                noDetectionStage = 3
                "신호등이 감지되지 않습니다. 주변의 소리에 주의하세요."
            }
            elapsed >= NO_DET_STAGE2_MS && noDetectionStage < 2 -> {
                noDetectionStage = 2
                "각도를 바꿔서 다시 왼쪽에서 오른쪽으로 카메라를 이동해 주세요."
            }
            elapsed >= NO_DET_STAGE1_MS && noDetectionStage < 1 -> {
                noDetectionStage = 1
                "신호등이 보이지 않습니다. 왼쪽에서 오른쪽으로 천천히 카메라를 이동해 주세요."
            }
            else -> null
        }

        if (stageMessage != null) {
            speakTrafficLightTTS(stageMessage, interrupt = false)
            appendNavLog(
                "TL_DIAG  └─ NO_DET_STAGE stage=$noDetectionStage reason=$reason elapsed=${elapsed / 1000}s"
            )
            if (BuildConfig.DEBUG) {
                updateAiDebugResult(
                    "$aiResultLine | action=NO_DET_STAGE stage=$noDetectionStage reason=$reason"
                )
            }
        }
    }

    /** TTS 판정용 대표 검출 선택. 안전상 빨간불 후보를 초록불보다 우선한다. */
    private fun selectTrafficLightForSpeech(
        detections: List<TrafficLightDetection>,
        previousColor: Int,
    ): TrafficLightDetection? {
        val red = detections
            .filter { it.classId == 0 }
            .maxWithOrNull(compareBy<TrafficLightDetection> { it.confidence }.thenBy { it.bbox.area })
        val green = detections
            .filter { it.classId == 1 }
            .maxWithOrNull(compareBy<TrafficLightDetection> { it.confidence }.thenBy { it.bbox.area })

        if (previousColor == 0 && green != null) {
            if (red == null) return green

            val greenIsStrongEnough = green.confidence >= GREEN_TRANSITION_MIN_CONFIDENCE
            val greenBeatsRed = green.confidence >= red.confidence + GREEN_OVER_RED_CONFIDENCE_MARGIN
            if (greenIsStrongEnough && greenBeatsRed) return green
        }

        if (red != null) return red

        return detections.maxWithOrNull(
            compareBy<TrafficLightDetection> { it.confidence }.thenBy { it.bbox.area }
        )
    }

    /** classId → 사람이 읽기 쉬운 라벨 (로그용). */
    private fun classLabel(classId: Int): String = when (classId) {
        0 -> "RED"
        1 -> "GREEN"
        -1 -> "NONE"
        else -> "?($classId)"
    }

    private fun repeatTrafficLightMessage(classId: Int): String = when (classId) {
        0 -> "빨간불입니다. 정지하세요."
        1 -> "초록불입니다."
        else -> "신호를 확인하세요."
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        camera = null
        cameraPreviewContainer.removeAllViews()
        // removeAllViews() 가 오버레이 View 자체는 제거하지만 reference 는 명시적으로 비워 GC 친화적으로.
        boundingBoxOverlay = null

        // 신호등 안전 state machine 리셋 — 다음 NAVIGATING 진입 시 깨끗한 상태로 시작
        currentColorCandidate = -1
        colorStreak = 0
        lastConfirmedColor = -1
        lastHeartbeatAt = 0L
        lastValidatedAt = 0L
        lastTransitionAt = 0L
        flickerLockoutUntil = 0L
        noDetectionSpeechHoldUntil = 0L

        // 횡단보도 zone 은 NavigationManager.isInCrosswalkZone state flow 가 자동 관리 —
        // 여기서 명시적 reset 불필요. NAVIGATING 종료 시 navigationManager.stopNavigation() 호출되며
        // route 가 cleared → state flow 도 자연스럽게 false 로 emit.

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
                speakTTS("SafeWalk입니다. 내비게이션을 실행하시려면 화면을 2초간 길게 눌러주세요.")
            }
        }
    }

    private fun speakTTS(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, message.hashCode().toString())
    }

    private fun speakTrafficLightTTS(message: String, interrupt: Boolean = true) {
        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = if (interrupt) "traffic_light_urgent" else "traffic_light"
        tts.speak(message, queueMode, null, utteranceId)
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

    private suspend fun loadTrafficSignalLocations(): List<TrafficSignalLocation> {
        val db = TrafficSignalDatabase.getInstance(this)

        val repository = TrafficSignalRepository(
            dao = db.trafficSignalDao(),
            apiClient = SeoulTrafficSignalLocationApiClient(
                apiKey = BuildConfig.SEOUL_API_KEY
            )
        )

        return repository.getTrafficSignals()
    }
}
