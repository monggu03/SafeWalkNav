package com.example.safewalknav

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var locationTracker: LocationTracker
    private lateinit var navigationManager: NavigationManager
    private lateinit var tts: TextToSpeech

    // UI
    private lateinit var etDestination: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnVoice: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvGuidance: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvArrivalState: TextView

    // 카메라 프리뷰 컨테이너 (DEBUG 빌드에서만 표시 — PR-2/PR-3 가 이 안에 추가)
    private var cameraPreviewContainer: FrameLayout? = null

    private val LOCATION_PERMISSION_CODE = 1001
    private var trackingJob: Job? = null
    private var ttsReady = false
    private var gpsReady = false
    private var welcomePlayed = false
    private var gpsDialogDeniedTime = 0L
    private var gpsCheckInProgress = false

    // 음성 제어
    private enum class VoiceMode { IDLE, SELECTING, NAVIGATING }

    private var voiceMode = VoiceMode.IDLE
    private var currentSearchResults: List<POIResult> = emptyList()
    private var searchResultDialog: AlertDialog? = null

    // 흔들기 감지
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTime = 0L
    private val SHAKE_THRESHOLD = 13f
    private val SHAKE_COOLDOWN = 2000L

    // 진동 피드백
    private lateinit var vibrator: Vibrator

    // 효과음
    private var toneGenerator: ToneGenerator? = null

    // 스테레오 비프 재사용 AudioTrack (방향성 비콘에서 700ms마다 재생 → 매 호출 생성 시 GC 압박)
    private val stereoSampleRate = 44100
    private val stereoDurationMs = 120
    private val stereoNumSamples = stereoSampleRate * stereoDurationMs / 1000
    private val stereoBuffer = ShortArray(stereoNumSamples * 2)
    private var stereoTrack: AudioTrack? = null

    // 안내 자동 반복
    private var autoRepeatJob: Job? = null

    // 오디오 비콘 (거리 기반 비프 — 가까울수록 빨라짐)
    private var beaconJob: Job? = null

    // 방향성 비콘 (나침반 기반 스테레오 패닝 — NEAR 이후 입구 방향 유도)
    private var directionalBeaconJob: Job? = null
    private var magnetometer: Sensor? = null
    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false
    private var currentAzimuth = 0f
    // 자력계 없는 기기 fallback: GPS bearing을 azimuth로 사용
    private val magnetometerAvailable: Boolean
        get() = magnetometer != null
    private var lastBehindAnnounceTime = 0L

    // TTS 재생 중 플래그 (비콘 일시정지용)
    private var ttsSpeaking = false

    // TTS 속도
    private var ttsSpeed = 1.0f

    // GPS 켜기 다이얼로그
    private val gpsEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        gpsCheckInProgress = false
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "GPS가 켜졌습니다", Toast.LENGTH_SHORT).show()
            onGPSEnabled()
        } else {
            gpsDialogDeniedTime = System.currentTimeMillis()
            // GPS 거부 시에도 앱이 켜졌음을 알리고 안내
            if (ttsReady && !welcomePlayed) {
                welcomePlayed = true
                speakTTS("SafeWalkNav입니다. GPS가 꺼져 있어 위치를 확인할 수 없습니다. 설정에서 GPS를 켜주세요.")
            }
        }
    }

    // 음성 인식(STT) 결과
    private val sttLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                handleVoiceInput(text)
            }
        } else {
            // STT 취소/타임아웃 시 안내
            val hint = when (voiceMode) {
                VoiceMode.IDLE -> "기기를 흔들어 다시 시도하세요."
                VoiceMode.SELECTING -> "번호를 선택하려면 기기를 흔들어주세요."
                VoiceMode.NAVIGATING -> "음성 명령은 기기를 흔들어 시작합니다."
            }
            speakTTS(hint)
        }
    }

    // 흔들기 리스너
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val acceleration = abs(magnitude - SensorManager.GRAVITY_EARTH)

            if (acceleration > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > SHAKE_COOLDOWN) {
                    lastShakeTime = now
                    runOnUiThread {
                        vibrateShort()
                        startSTT()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // 방위각 리스너 (가속도계 + 자력계 → 방위각 계산)
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
                    // 저역 통과 필터 (±180° 경계 처리)
                    val delta = ((az - currentAzimuth + 540f) % 360f) - 180f
                    currentAzimuth = (currentAzimuth + 0.15f * delta + 360f) % 360f
                    // CSV 로그용 센서 퓨전 heading을 NavigationManager에 전달
                    navigationManager.updateCompassHeading(currentAzimuth)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)
        locationTracker = LocationTracker(this)
        // Heading 분석용 CSV 로그 — app-scoped 경로(권한 불필요)에 저장.
        // NavigationManager 가 KMM commonMain 에 있으므로 File 의존은 AndroidHeadingLogger 가 흡수.
        val headingLogger = AndroidHeadingLogger(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        )
        navigationManager = NavigationManager(
            TMapApiClient(BuildConfig.TMAP_APP_KEY),
            headingLogger,
        )

        etDestination = findViewById(R.id.etDestination)
        btnSearch = findViewById(R.id.btnSearch)
        btnVoice = findViewById(R.id.btnVoice)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvGuidance = findViewById(R.id.tvGuidance)
        tvDistance = findViewById(R.id.tvDistance)
        tvArrivalState = findViewById(R.id.tvArrivalState)

        // 흔들기 감지 초기화
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // 진동 초기화
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // 효과음 초기화
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (_: Exception) {
        }

        // 카메라 프리뷰 컨테이너 — DEBUG 빌드에서만 표시 (개발/시연 시 인식 결과 시각화용).
        // 실 사용 시점(릴리스 빌드)엔 시각장애인 사용자에게 보이지 않음.
        cameraPreviewContainer = findViewById(R.id.cameraPreviewContainer)
        if (BuildConfig.DEBUG) {
            cameraPreviewContainer?.visibility = android.view.View.VISIBLE
        }

        requestLocationPermission()
        checkAndEnableGPS()
        setupButtons()
        observeGuidance()
    }

    // ========== 지도 관련 코드는 PR-UI(시각장애인 풀스크린) 마이그레이션으로 제거됨 ==========
    // 시각장애인 사용자는 지도 화면을 사용하지 않으므로 TMap SDK 의존 코드를 모두 제거.
    // 시각 정보는 음성(TTS) + 진동 + 오디오 비콘으로 전달.
    // 시연/디버그용 카메라 프리뷰는 cameraPreviewContainer (DEBUG 빌드 한정) 에 PR-2/PR-3 가 추가.

    // ========== 음성 제어 핵심 ==========

    private fun handleVoiceInput(text: String) {
        Log.d("SafeWalkNav", "Voice: '$text' (mode: $voiceMode)")

        when (voiceMode) {
            VoiceMode.IDLE -> {
                if (text.contains("도움") || text.contains("사용법")) {
                    speakAndListen(
                        "목적지를 말씀하면 검색하고, 번호로 선택합니다. 목적지를 말씀해주세요.",
                        VoiceMode.IDLE
                    )
                } else {
                    etDestination.setText(text)
                    performSearch(text)
                }
            }

            VoiceMode.SELECTING -> {
                val number = parseKoreanNumber(text)
                if (number != null && number in 1..currentSearchResults.size) {
                    searchResultDialog?.dismiss()
                    selectDestination(currentSearchResults[number - 1])
                } else if (text.contains("다시") || text.contains("취소")) {
                    searchResultDialog?.dismiss()
                    speakAndListen("목적지를 다시 말씀해주세요.", VoiceMode.IDLE)
                } else {
                    speakAndListen(
                        "${currentSearchResults.size}번까지 있습니다. 번호를 말씀해주세요.",
                        VoiceMode.SELECTING
                    )
                }
            }

            VoiceMode.NAVIGATING -> {
                when {
                    text.contains("종료") || text.contains("그만") || text.contains("멈춰") -> {
                        stopNavigationFull()
                    }

                    text.contains("어디") || text.contains("현재") || text.contains("위치") -> {
                        val msg = navigationManager.guidanceMessage.value
                        if (msg.isNotEmpty()) speakTTS(msg)
                    }

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
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                        speakTTS("소리를 키웠습니다.")
                    }

                    text.contains("작게") || text.contains("볼륨 내려") -> {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audioManager.adjustStreamVolume(
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
        }
    }

    private fun parseKoreanNumber(text: String): Int? {
        Regex("(\\d+)").find(text)?.value?.toIntOrNull()?.let { return it }

        return when {
            text.contains("일번") || text.contains("첫") || text.contains("하나") -> 1
            text.contains("두번") || text.contains("둘") -> 2
            text.contains("삼번") || text.contains("세번") || text.contains("셋") -> 3
            text.contains("사번") || text.contains("네번") || text.contains("넷") -> 4
            text.contains("오번") || text.contains("다섯") -> 5
            else -> null
        }
    }

    private fun speakAndListen(message: String, mode: VoiceMode) {
        voiceMode = mode
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, "auto_listen")
    }

    private fun startSTT() {
        tts.stop()

        val prompt = when (voiceMode) {
            VoiceMode.IDLE -> "목적지를 말씀하세요"
            VoiceMode.SELECTING -> "번호를 말씀하세요"
            VoiceMode.NAVIGATING -> "명령을 말씀하세요"
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
        }
    }

    // ========== 검색 ==========

    /**
     * 목적지 검색 — Option C 흐름 (시각장애인 UX 합의안)
     *
     *   1) 현재 위치를 TMap searchPOI 에 전달 → 서버가 1km 반경 + 거리순 정렬 (PR-A).
     *   2) 결과 0개면 안내 후 재입력 유도.
     *   3) 결과 ≥1개 + GPS 신호 OK → 가장 가까운 결과 자동 선택 (사용자 추가 선택 불필요).
     *   4) GPS 신호 없음 (locationTracker 가 null 반환) → 거리 정렬 불가 → 다이얼로그 fallback
     *      (시각장애인 시나리오에서는 거의 발생하지 않음 — 시작 시점에 GPS 확인 필수).
     */
    private fun performSearch(keyword: String) {
        lifecycleScope.launch {
            tvStatus.text = "검색 중..."
            speakTTS("검색 중입니다.")

            // 현재 위치 — TMapApiClient 가 이 좌표를 중심으로 1km 반경 검색 + 거리순 정렬
            val currentLocation = locationTracker.getCurrentLocation()
            val results = navigationManager.searchDestination(
                keyword = keyword,
                currentLat = currentLocation?.latitude,
                currentLon = currentLocation?.longitude,
            )

            if (results.isEmpty()) {
                // network 에러면 lastError, 아니면 1km 이내 결과 없음
                val errorMsg = navigationManager.lastError
                    ?: "주변 1킬로미터 이내에 ${keyword} 검색 결과가 없습니다"
                tvStatus.text = errorMsg
                playToneError()
                speakAndListen("$errorMsg. 다른 목적지를 말씀해주세요.", VoiceMode.IDLE)
                return@launch
            }

            currentSearchResults = results

            // GPS 없으면 거리 정렬이 무의미 → 다이얼로그 fallback (시각장애인 시나리오에선 드문 경로)
            if (currentLocation == null) {
                if (results.size == 1) {
                    selectDestination(results.first())
                } else {
                    showSearchResults(results, null)
                }
                return@launch
            }

            // Option C: 가장 가까운 결과 자동 선택. results 는 서버에서 이미 거리순.
            val nearest = results.first()
            val nearestDistMeters = LocationTracker.distanceBetween(
                currentLocation.latitude, currentLocation.longitude,
                nearest.lat, nearest.lon
            ).toInt()
            val distText = formatDistance(nearestDistMeters)

            val msg = if (results.size == 1) {
                "${nearest.name}, ${distText} 선택합니다."
            } else {
                "검색 결과 ${results.size}개 중 가장 가까운 ${nearest.name}, ${distText} 선택합니다."
            }
            speakTTS(msg)
            selectDestination(nearest)
        }
    }

    // ========== 버튼 ==========

    private fun setupButtons() {
        btnSearch.setOnClickListener {
            vibrateShort()
            val keyword = etDestination.text.toString().trim()
            if (keyword.isEmpty()) {
                speakAndListen("목적지를 말씀해주세요.", VoiceMode.IDLE)
                return@setOnClickListener
            }
            performSearch(keyword)
        }

        btnVoice.setOnClickListener {
            vibrateShort()
            startSTT()
        }

        btnStart.setOnClickListener {
            vibrateShort()
            if (navigationManager.isNavigating.value) {
                startLocationTracking()
                speakTTS("위치 추적을 시작합니다.")
            } else {
                speakAndListen("먼저 목적지를 말씀해주세요.", VoiceMode.IDLE)
            }
        }

        btnStop.setOnClickListener {
            vibrateShort()
            stopNavigationFull()
        }
    }

    private fun stopNavigationFull() {
        trackingJob?.cancel()
        stopAutoRepeat()
        stopBeacon()
        stopDirectionalBeacon()
        navigationManager.stopNavigation()
        // 지도 제거됨 (PR-UI: 시각장애인 풀스크린 UI)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 풀스크린 UI: 메시지 영역만 초기화. 배경색은 layout XML 의 검정 유지.
        tvStatus.text = "대기 중"
        tvGuidance.text = "기기를 흔들어 목적지를 음성으로 입력하세요"
        tvDistance.text = ""
        tvArrivalState.text = ""

        voiceMode = VoiceMode.IDLE
        speakTTS("안내를 종료합니다.")
    }

    /** 검색 결과 표시 (거리 포함) */
    private fun showSearchResults(results: List<POIResult>, distances: List<Int>?) {
        // 음성: "1번 스타벅스 300미터, 2번 ..."
        val listText = results.mapIndexed { i, poi ->
            val dist = distances?.get(i)?.let { formatDistance(it) } ?: ""
            "${i + 1}번, ${poi.name} $dist"
        }.joinToString(". ")

        // 다이얼로그: 이름 + 주소 + 거리
        val names = results.mapIndexed { i, poi ->
            val dist = distances?.get(i)?.let { formatDistance(it) } ?: ""
            "${i + 1}. ${poi.name} $dist (${poi.address})"
        }.toTypedArray()

        searchResultDialog = AlertDialog.Builder(this)
            .setTitle("목적지 선택")
            .setItems(names) { _, which ->
                selectDestination(results[which])
            }
            .setCancelable(true)
            .setOnCancelListener {
                voiceMode = VoiceMode.IDLE
                speakTTS("목적지를 다시 검색하려면 기기를 흔들어주세요.")
            }
            .show()

        // 자동 STT 안 띄움 — TalkBack으로 다이얼로그 항목을 다시 들을 수 있도록
        // 사용자가 준비되면 기기를 흔들어 번호를 말함
        voiceMode = VoiceMode.SELECTING
        speakTTS("검색 결과 ${results.size}개입니다. $listText. 기기를 흔들고 번호를 말씀해주세요.")
    }

    /** 거리를 읽기 좋게 포맷 (1200m → "1.2킬로", 300m → "300미터") */
    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) {
            "${String.format("%.1f", meters / 1000.0)}킬로"
        } else {
            "${meters}미터"
        }
    }

    /** 경로 요약 생성 ("총 800미터, 약 10분, 횡단보도 2개") */
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

    private fun selectDestination(selected: POIResult) {
        lifecycleScope.launch {
            tvStatus.text = "목적지: ${selected.name}"
            speakTTS("${selected.name}으로 경로를 탐색합니다.")

            val currentLocation = locationTracker.getCurrentLocation()
            if (currentLocation == null) {
                tvStatus.text = "현재 위치를 가져올 수 없습니다"
                playToneError()
                speakAndListen(
                    "위치를 확인할 수 없습니다. GPS 확인 후 다시 말씀해주세요.",
                    VoiceMode.IDLE
                )
                return@launch
            }

            tvStatus.text = "경로 탐색 중..."

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
                tvStatus.text = "안내 중: ${selected.name}"
                voiceMode = VoiceMode.NAVIGATING
                playToneSuccess()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // 지도 그리기 제거됨 (PR-UI). 시각 정보는 음성/진동/비콘으로 전달.
                // 경로 요약 음성 안내
                val summary = getRouteSummary()
                if (summary.isNotEmpty()) {
                    speakTTS(summary)
                }
                startLocationTracking()
                startAutoRepeat()
            } else {
                tvStatus.text = "경로 탐색 실패"
                playToneError()
                speakAndListen(
                    "경로를 찾을 수 없습니다. 다른 목적지를 말씀해주세요.",
                    VoiceMode.IDLE
                )
            }
        }
    }

    // ========== GPS 위치 추적 ==========

    private fun startLocationTracking() {
        trackingJob?.cancel()
        trackingJob = lifecycleScope.launch {
            locationTracker.getLocationUpdates(2000L).collectLatest { location ->
                // GPS 정확도 낮으면 무시 (25m 이상)
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

                val dist = LocationTracker.distanceBetween(
                    location.latitude, location.longitude,
                    navigationManager.destinationLat, navigationManager.destinationLon
                )

                // 시각장애인 UI: GPS 좌표는 디버그용 정보. 큰 글씨로 거리만 강조.
                tvDistance.text = "목적지까지 ${dist.toInt()}m"
                if (BuildConfig.DEBUG) {
                    val accuracyText = if (location.hasAccuracy()) "±${location.accuracy.toInt()}m" else ""
                    val gpsDebug = "GPS ${String.format("%.5f", location.latitude)}, ${
                        String.format("%.5f", location.longitude)
                    } $accuracyText"
                    tvStatus.text = gpsDebug
                }

                // 지도 위치 갱신 제거됨 (PR-UI). 거리 변화는 tvDistance + 비콘으로 전달.
            }
        }
    }

    // ========== 안내 자동 반복 ==========

    private fun startAutoRepeat() {
        autoRepeatJob?.cancel()
        autoRepeatJob = lifecycleScope.launch {
            while (true) {
                delay(45_000)
                if (!navigationManager.isNavigating.value) break

                val distText = tvDistance.text.toString()
                val meters = Regex("목적지까지: (\\d+)m").find(distText)
                    ?.groupValues?.get(1)
                if (!meters.isNullOrEmpty()) {
                    speakTTS("목적지까지 ${meters}미터")
                }
            }
        }
    }

    private fun stopAutoRepeat() {
        autoRepeatJob?.cancel()
        autoRepeatJob = null
    }

    // ========== 오디오 비콘 (목적지 접근 시 비프) ==========

    /**
     * 거리 기반 비프음 시작
     * >10m: 3초 간격, 5~10m: 1.5초, 3~5m: 0.8초, <3m: 0.4초
     * 가까울수록 빨라져서 방향감각 제공
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

                // TTS 재생 중이면 비프 스킵 (음성 안내 방해 방지)
                if (ttsSpeaking) {
                    delay(interval)
                    continue
                }

                // 짧은 비프 + 진동 펄스
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
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(60, intensity)
                    )
                }

                delay(interval)
            }
        }
    }

    private fun stopBeacon() {
        beaconJob?.cancel()
        beaconJob = null
    }

    /**
     * 방향성 비콘 — NEAR 이후 활성
     * 사용자 헤딩과 목적지 방위 차이를 스테레오 패닝으로 표현.
     * 왼쪽 소리 → 왼쪽으로 몸 돌리라는 뜻. 소리 균등 = 정면 = 목적지 방향.
     * 뒤쪽(|각도| > 135°)이면 TTS로 알림.
     */
    private fun startDirectionalBeacon() {
        directionalBeaconJob?.cancel()
        directionalBeaconJob = lifecycleScope.launch {
            while (true) {
                val loc = locationTracker.getCurrentLocation()
                if (loc == null) {
                    delay(500)
                    continue
                }

                // 입구 좌표 우선, 없으면 POI 좌표
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
                val bearing = loc.bearingTo(target)  // 북 기준 0~360
                var angleDiff = bearing - currentAzimuth
                while (angleDiff > 180f) angleDiff -= 360f
                while (angleDiff < -180f) angleDiff += 360f
                // angleDiff: 음수=왼쪽, 양수=오른쪽, 0=정면, ±180=뒤

                if (ttsSpeaking) {
                    delay(400)
                    continue
                }

                val (leftVol, rightVol, highPitch) = computeStereoPan(angleDiff)
                playStereoBeep(leftVol, rightVol, highPitch)

                // 뒤쪽이면 음성 안내 (4초 간격)
                if (abs(angleDiff) > 135f) {
                    val now = System.currentTimeMillis()
                    if (now - lastBehindAnnounceTime > 4000L) {
                        lastBehindAnnounceTime = now
                        runOnUiThread { speakTTS("목적지는 뒤쪽입니다. 몸을 돌려주세요.") }
                    }
                }

                // 정면 근처일수록 빠른 비트
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

    /**
     * 각도 차이 → (왼쪽 볼륨, 오른쪽 볼륨, 고음 여부) 매핑.
     * Equal-power pan law. 정면(|각도|<15°)일 때는 고음으로 구분.
     */
    private fun computeStereoPan(angleDiff: Float): Triple<Float, Float, Boolean> {
        val clamped = angleDiff.coerceIn(-90f, 90f)
        val pan = clamped / 90f  // -1..+1
        val angle = ((pan + 1f) / 2f) * (PI.toFloat() / 2f)
        val left = cos(angle)
        val right = sin(angle)
        val facing = abs(angleDiff) < 15f
        // 뒤쪽이면 전체 볼륨 낮춤 (시끄럽지 않게, 대신 TTS로 알림)
        val scale = if (abs(angleDiff) > 90f) 0.3f else 1f
        return Triple(left * scale, right * scale, facing)
    }

    /**
     * 스테레오 PCM 비프 재생.
     * AudioTrack을 한 번만 STREAM 모드로 만들어 재사용한다 (방향성 비콘이 ~500ms마다 호출 → 매번 생성 시 GC 압박).
     * leftVol/rightVol: 0~1 개별 채널 진폭 배율.
     */
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

            // 이전 재생이 끝났든 아니든 새 PCM 데이터를 흘려보내기 전에 멈춤+flush
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

    // ========== 안내 메시지 수신 ==========

    private fun observeGuidance() {
        lifecycleScope.launch {
            navigationManager.guidanceMessage.collectLatest { message ->
                if (message.isNotEmpty()) {
                    tvGuidance.text = message
                    speakTTS(message)
                    Log.d("SafeWalkNav", "Guidance: $message")

                    if (message.contains("이탈")) {
                        vibrateWarning()
                        playToneWarning()
                    } else if (message.contains("횡단보도") || message.contains("계단")) {
                        vibrateMedium()
                        playToneAlert()
                    }

                    // 경로 재탐색 완료 시 (지도 갱신 제거됨, PR-UI)
                    // 음성 안내(message)만으로 사용자에게 새 경로 알림이 충분히 전달됨
                }
            }
        }

        lifecycleScope.launch {
            navigationManager.arrivalState.collectLatest { state ->
                tvArrivalState.text = when (state) {
                    ArrivalState.FAR -> "이동 중"
                    ArrivalState.APPROACHING -> "목적지 근처 (15m 이내)"
                    ArrivalState.NEAR -> "거의 도착 (5m 이내)"
                    ArrivalState.ARRIVED -> "도착!"
                }

                // 상태별 색상 (시각장애인 풀스크린 UI: 검정 배경 위에서 잘 보이는 톤만 사용)
                // 지도 줌 제거됨 — 거리 단계는 비콘 빠르기 + 진동으로 표현.
                when (state) {
                    ArrivalState.FAR -> {
                        tvArrivalState.setTextColor(Color.parseColor("#888888"))
                        stopDirectionalBeacon()
                    }

                    ArrivalState.APPROACHING -> {
                        tvArrivalState.setTextColor(Color.parseColor("#FFAA33"))
                        vibrateMedium()
                        // 오디오 비콘 시작 (거리 가까울수록 빠름)
                        startBeacon()
                    }

                    ArrivalState.NEAR -> {
                        tvArrivalState.setTextColor(Color.parseColor("#FF4444"))
                        vibrateMedium()
                        // 거리비콘 → 방향비콘 전환 (입구 찾기)
                        stopBeacon()
                        startDirectionalBeacon()
                    }

                    ArrivalState.ARRIVED -> {
                        tvArrivalState.setTextColor(Color.parseColor("#00CC66"))
                        stopBeacon()
                        vibrateArrival()
                        playToneSuccess()
                        stopAutoRepeat()
                        // 지도 클리어 제거됨 (PR-UI)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        voiceMode = VoiceMode.IDLE
                        // 방향비콘은 계속 유지 (입구 찾기 단계). 종료 버튼 누르면 중지.
                        if (directionalBeaconJob == null) {
                            startDirectionalBeacon()
                        }
                    }
                }
            }
        }
    }

    // ========== 진동 패턴 ==========

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

    // ========== 효과음 ==========

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

    // ========== TTS ==========

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
        tryPlayWelcome()
    }

    private fun tryPlayWelcome() {
        if (ttsReady && gpsReady && !welcomePlayed) {
            welcomePlayed = true
            speakAndListen("SafeWalkNav입니다. 목적지를 말씀해주세요.", VoiceMode.IDLE)
        }
    }

    private fun speakTTS(message: String) {
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, message.hashCode().toString())
    }

    // ========== GPS ==========

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

    // ========== 권한 ==========

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkAndEnableGPS()
            } else {
                speakTTS("위치 권한이 필요합니다. 설정에서 허용해주세요.")
            }
        }
    }

    // ========== 라이프사이클 ==========

    override fun onResume() {
        super.onResume()
        checkAndEnableGPS()
        accelerometer?.let {
            sensorManager.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(orientationListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(shakeListener)
        sensorManager.unregisterListener(orientationListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        trackingJob?.cancel()
        autoRepeatJob?.cancel()
        beaconJob?.cancel()
        directionalBeaconJob?.cancel()
        tts.shutdown()
        toneGenerator?.release()
        releaseStereoTrack()
        // tMapView 제거됨 (PR-UI)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
