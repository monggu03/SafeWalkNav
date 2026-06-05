package com.example.safewalknav.navigation

import com.example.safewalknav.audio.BeepTone
import com.example.safewalknav.audio.SpatialBeeper
import com.example.safewalknav.navigation.geo.KalmanHeading
import com.example.safewalknav.navigation.geo.RouteBearingProfile
import com.example.safewalknav.navigation.geo.alongTrackMeters
import com.example.safewalknav.navigation.geo.angleDiff
import com.example.safewalknav.navigation.geo.bearing
import com.example.safewalknav.navigation.geo.computeCumulativeDistances
import com.example.safewalknav.navigation.geo.computeSignedCrossTrack
import com.example.safewalknav.navigation.geo.cumulativeAlongRoute
import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.geo.getClockDirection
import com.example.safewalknav.navigation.platform.GpsLocation
import com.example.safewalknav.navigation.platform.currentTimeMillis
import com.example.safewalknav.navigation.signal.SignalApiClient
import com.example.safewalknav.navigation.signal.SignalRemainingInfo
import com.example.safewalknav.navigation.signal.TrafficIntersectionParser
import com.example.safewalknav.navigation.signal.TrafficSignalLocation
import com.example.safewalknav.navigation.signal.TrafficSignalMatcher
import com.example.safewalknav.navigation.signal.TrafficSignalRemainingTimeParser
import com.example.safewalknav.navigation.tbfw.AnnouncementStage
import com.example.safewalknav.navigation.tbfw.MessageBuilder
import com.example.safewalknav.navigation.tbfw.NavigatorConfig
import com.example.safewalknav.navigation.tbfw.PathAnnotation
import com.example.safewalknav.navigation.tbfw.RouteAnnotationLogger
import com.example.safewalknav.navigation.tbfw.RouteAnnotator
import com.example.safewalknav.navigation.tbfw.selectAnnouncementCandidate
import com.example.safewalknav.navigation.tmap.ArrivalState
import com.example.safewalknav.navigation.tmap.LatLng
import com.example.safewalknav.navigation.tmap.POIResult
import com.example.safewalknav.navigation.tmap.RiskLevel
import com.example.safewalknav.navigation.tmap.RouteSegment
import com.example.safewalknav.navigation.tmap.TMapApiClient
import com.example.safewalknav.navigation.tmap.TMapRoute
import com.example.safewalknav.navigation.tmap.Waypoint
import com.example.safewalknav.navigation.walking.HeadingLogger
import com.example.safewalknav.navigation.walking.LeanStatus
import com.example.safewalknav.navigation.walking.NoopHeadingLogger
import com.example.safewalknav.navigation.walking.WalkingDiagnostic
import com.example.safewalknav.navigation.walking.CrosswalkZoneInfo
import com.example.safewalknav.navigation.walking.findCrosswalkZoneInfo
import com.example.safewalknav.navigation.walking.isCrosswalkWaypoint
import com.example.safewalknav.navigation.walking.isOnCrosswalkSegment
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A-3 일회성 안내 이벤트.
 * 연속 안내(_guidanceMessage StateFlow)와 분리된 채널로 흐른다.
 *
 * @property interrupt true 면 iOS TTS 가 현재 발화·큐를 끊고 즉시 발화(선점).
 *   회전 직전(IMMINENT) 발화 같은 타이밍 생명선 안내에서만 true. 기본값을 두지 않아
 *   호출부가 의도를 명시하도록 강제(Swift interop 도 default 인자 인식 안 함).
 */
data class NavAnnouncement(
    val message: String,
    val forceRepeat: Boolean,
    val interrupt: Boolean,
)

/**
 * A-3 이벤트 구독 핸들. Swift 가 직접 collect 할 수 없는 SharedFlow 대신 콜백 등록을
 * 사용하므로 stop 시 cancel() 로 해제한다.
 *
 * 이름이 Combine.Cancellable 과 겹칠 수 있어 Swift 측에서는 `shared.Cancellable` 로
 * 명시 또는 모호하지 않으면 그대로 사용한다.
 */
interface Cancellable {
    fun cancel()
}

/**
 * 내비게이션 매니저
 * 경로 탐색 → 경로 추종 → 도착 안내 전체 흐름 관리
 *
 * 도착 안내 단계:
 * 1. FAR (15m+): 일반 경로 안내
 * 2. APPROACHING (15m): 시계 방향 + 거리, 5초 간격
 * 3. NEAR (5m): 시계 방향 + 거리 + 랜드마크, 2초 간격 (정밀 유도)
 * 4. ARRIVED (2m): 도착 + 주변 랜드마크 확인
 */
class NavigationManager(
    private val tMapApiClient: TMapApiClient,
    private val signalApiClient: SignalApiClient,
    private val headingLogger: HeadingLogger = NoopHeadingLogger,
    private var trafficSignals: List<TrafficSignalLocation> = emptyList(), //횡단보도 주변 신호등 데이터
) {
    private val walkingDiagnostic = WalkingDiagnostic()
    // 사용자 휴대폰 자력계+가속도 fusion azimuth (MainActivity 가 매 sensor tick 마다 push)
    private val _compassHeading = MutableStateFlow(0f)
    val compassHeading: StateFlow<Float> = _compassHeading.asStateFlow()

    // 현재 도로 진행 방향 (computeRouteBearingAhead 결과, 매 GPS tick 갱신).
    // CompassView 가 이 값과 compassHeading 두 화살표를 한 원에 그려 방향 비교를 시각화.
    private val _targetBearing = MutableStateFlow(0f)
    val targetBearing: StateFlow<Float> = _targetBearing.asStateFlow()
    private var currentTargetBearing: Float
        get() = _targetBearing.value
        set(value) { _targetBearing.value = value }

    // 첫 GPS tick 으로 도로 방향이 최초로 갱신됐는지. false 면 updateCompassHeading 의
    // lean 안내 분기를 건너뛴다 — 도로 방향 없는 상태에서 0f 와 비교하면
    // 사용자 azimuth 와 항상 큰 차이가 나서 즉시 잘못된 발화가 일어남.
    private var hasRoadBearing = false

    // ========== 폴리라인 평행 나침반 ==========
    // 경로 폴리라인을 SEGMENT_INTERVAL_M 간격으로 나눈 구간별 방위각 프로파일.
    // targetBearing(도로 방향 화살표)을 사용자가 현재 밟고 있는 구간에 평행하게 맞추는 데 사용.
    // startNavigation 에서 채우고, computeParallelRoadBearing 이 읽는다.
    private var routeBearingProfile: RouteBearingProfile? = null
    // routePoints 각 점까지의 누적 거리 (m) — currentRoutePointIndex → distanceAlong 매핑용.
    private var routePointCumulative: List<Double> = emptyList()

    // 최근 GPS 속도 (m/s) — 정지 상태에선 IMU heading 비교 의미 없으므로
    // updateCompassHeading 에서 MIN_WALKING_SPEED_MPS 미만이면 walkingDiagnostic 건너뛴다.
    private var latestSpeed: Float = 0f
    private val MIN_WALKING_SPEED_MPS = 0.3f

    // 최근 GPS 정확도 (m). IMU+GPS heading 융합 가중치 계산에 사용.
    // 작을수록(정확할수록) GPS 가중치 ↑. 30m 초과면 GPS bearing 신뢰 불가.
    private var latestGpsAccuracy: Float = 999f
    private val FUSION_MIN_SPEED = 0.5f       // 이 미만이면 IMU만 사용 (GPS bearing 잡음)
    private val FUSION_FULL_SPEED = 2.0f      // 이 이상이면 GPS 가중치 최대
    private val FUSION_MAX_ACCURACY = 30f     // 이 초과면 IMU만 사용 (GPS 신뢰 X)
    private val FUSION_GPS_WEIGHT_MIN = 0.2f  // GPS 가중치 하한
    private val FUSION_GPS_WEIGHT_MAX = 0.8f  // GPS 가중치 상한 (IMU 신호 최소 보존)

    // 최근 fuseImuAndGps 호출 결과 진단 문자열. MainActivity 가 GPS tick 마다 읽어
    // walk_log 에 HEADING_FUSION 라인으로 기록. 외출 후 외부에서 융합 가중치 추적 가능.
    var latestFusionDebug: String = ""
        private set

    // Settling — 첫 lean 발화 후 사용자가 보정해서 STRAIGHT 한 번 거치기 전엔
    // 재누적 안 함. 발화 직후 과보정 → 반대 LEAN → 즉시 반대 발화 사이클 방지.
    private var requireStraightBeforeNextLean: Boolean = false

    // 외부에서 관찰할 수 있는 StateFlow (필요시)

    fun updateTrafficSignals(signals: List<TrafficSignalLocation>) {
        trafficSignals = signals
        _debugMessage.value = "signals=${signals.size}"
    }

    var currentRoute: TMapRoute? = null
        private set

    private var currentWaypointIndex = 0

    // skip-ahead fallback 상태 — wp[N] 을 우회 advance 할 후보로 들어간 시각
    // 0L = 후보 아님. 조건이 깨지면 0L 로 리셋.
    private var skipCandidateStartMs: Long = 0L
    private var skipCandidateForIndex: Int = -1

    var destinationLat = 0.0
        private set
    var destinationLon = 0.0
        private set
    var destinationName = ""
        private set

    // 도착 상태
    private val _arrivalState = MutableStateFlow(ArrivalState.FAR)
    val arrivalState: StateFlow<ArrivalState> = _arrivalState

    // 횡단보도 zone 진입 여부 — TMap waypoint 기반 판정 (isOnCrosswalkSegment).
    // 안드: TrafficLightDetector 의 ML 안내 게이팅에 사용 (PR-AI).
    // iOS: 동일 로직 적용 가능 (이지민 협업).
    private val _isInCrosswalkZone = MutableStateFlow(false)
    val isInCrosswalkZone: StateFlow<Boolean> = _isInCrosswalkZone

    private val _hasNearbyTrafficSignal = MutableStateFlow(false)
    val hasNearbyTrafficSignal: StateFlow<Boolean> = _hasNearbyTrafficSignal

    // 안내 메시지
    private val _guidanceMessage = MutableStateFlow("")
    val guidanceMessage: StateFlow<String> = _guidanceMessage.asStateFlow()

    // --- A-3: 일회성 안내 전용 이벤트 채널 ---
    // 매 GPS tick 마다 _guidanceMessage 가 "약 N미터 직진" 으로 덮어써져 회전/굽은길/횡단보도
    // 등 일회성 안내가 iOS 200ms 폴링에 잡히지 않던 문제를 분리 채널로 해결한다.
    // replay=0: 새 구독자는 과거 이벤트 받지 않음, 16 버퍼 + DROP_OLDEST 로 emit 손실 방지.
    private val _navEvents = MutableSharedFlow<NavAnnouncement>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navEvents: SharedFlow<NavAnnouncement> = _navEvents.asSharedFlow()
    // Swift 가 SharedFlow.collect 를 직접 호출할 수 없으므로 콜백 패턴으로 브리지.
    // CoroutineScope 가 NavigationManager 에 없어 collect 대신 직접 invoke 한다.
    private var navEventListener: ((NavAnnouncement) -> Unit)? = null

    // 디버그 메시지
    private val _debugMessage = MutableStateFlow("")
    val debugMessage: StateFlow<String> = _debugMessage

    // 내비게이션 활성 여부
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    // 목적지까지 실시간 거리 (오디오 비콘용)
    private val _distanceToDestination = MutableStateFlow(Float.MAX_VALUE)
    val distanceToDestination: StateFlow<Float> = _distanceToDestination

    // RouteAnnotator 사전 분석 결과 — startNavigation() 에서 채움.
    private var pathAnnotations: List<PathAnnotation> = emptyList()

    // waypoint 별 누적 거리 (m). pathAnnotations 와 동시에 채워진다.
    // 사용자 진행 거리 vs annotation 거리 비교로 사전 안내 시점을 잡는 데 사용.
    private var cumulativeDistances: List<Double> = emptyList()

    // 이미 발화한 (startWaypointIndex, stage) 키 집합. 중복 발화 방지.
    // stage 별로 dedup 하므로 같은 회전이라도 APPROACH 1회 + IMMINENT 1회 가능.
    private val announcedKeys = mutableSetOf<Pair<Int, AnnouncementStage>>()

    // RouteAnnotator/announceDistance 등 TBFW 튜닝 상수 묶음 — 매번 생성하지 않고 인스턴스 보관.
    private val navigatorConfig = NavigatorConfig()

    /**
     * 가상 waypoint 통과 시 스테레오 비프 안내를 담당.
     *
     * 플랫폼별 동작:
     *   - iOS: 생성만 해두고 Swift SpatialBeeperImpl 가 iosImpl 콜백을 채워야 실제 소리가 남.
     *     (AppDependencies 가 책임)
     *   - Android: actual 자체가 AudioTrack 으로 사인파를 합성하므로 즉시 작동.
     *
     * 외부에서 콜백을 주입해야 하는 iOS 를 위해 public 으로 노출한다.
     */
    val spatialBeeper: SpatialBeeper = SpatialBeeper()

    // 가상 waypoint 통과 카운터 — "잘 가고 있을 때" 무음 vs 가벼운 확인음 토글에 사용.
    private var virtualPassCount = 0

    // 곡선 방향 리마인더 — 마지막 발화 시각(시간 쿨다운용).
    private var lastCurveReminderTime = 0L
    // 곡선 방향 리마인더 — 마지막에 발화한 방향("LEFT"/"RIGHT"). 곡선이 바뀌면 카운터 주기와 무관하게 즉시 발화.
    private var lastCurveReminderDirection: String? = null

    // 곡선당 "○○ 방향" 발화 횟수 카운터 (곡선 진입 시 0으로 리셋).
    private var curveAnnounceCount = 0
    // 직전 가상 waypoint 통과 시점의 currentWaypointIndex (곡선 경계 감지용).
    private var lastVirtualWpIndex = -1

    // 디버그용 — iOS 에서 관찰 가능하게 StateFlow 로 노출
    private val _annotations = MutableStateFlow<List<PathAnnotation>>(emptyList())
    val annotations: StateFlow<List<PathAnnotation>> = _annotations.asStateFlow()

    // 발화 로그 — 최근 20개만 유지
    private val _announcementLog = MutableStateFlow<List<String>>(emptyList())
    val announcementLog: StateFlow<List<String>> = _announcementLog.asStateFlow()

    val lastError: String? get() = tMapApiClient.lastError

    private var lastSpokenMessage = ""
    private var lastGuidanceTime: Long = 0L
    private val guidanceCooldownMs: Long = 5000L
    private var lastRerouteTime = 0L
    private var lastPreAnnouncedIndex = -1
    private var consecutiveRerouteCount = 0  // 연속 재탐색 횟수 (쿨다운 점진 증가)
    private var lastStraightGuidanceTime = 0L // 직진 안내 전용 타이머 (provideDirectionalGuidance 의 straight 분기)
    private var lastRoadType = -1 // 이전 구간 도로 유형 (전환 안내용)
    // 횡단보도 진입 시점에 "X시 방향으로 횡단보도를 건너세요" 1회 안내용 상태.
    // 시각장애인이 어느 방향에 횡단보도가 있는지 모르므로 zone 진입 transition 에서 발화.
    private var wasInCrosswalkZone = false
    private var lastCrosswalkAnnouncedWpIdx = -1
    private var lastSignalPresenceAnnouncedWpIdx = -1
    private var lastSignalDirectionAnnouncedWpIdx = -1
    private val SIGNAL_CAMERA_MATCH_RADIUS_M = 10f
    private val SIGNAL_DIRECTION_MATCH_RADIUS_M = 35f
    private val ARRIVAL_DISTANCE_M = 5f
    private val NEAR_DISTANCE_M = 10f
    private val APPROACHING_DISTANCE_M = 20f

    //클래스 변수 추가
    private var lastSignalApiCallTime = 0L
    private var lastSignalItstId: String? = null
    private val signalApiCooldownMs = 60_000L

    // ========== Heading Smoothing (Circular Kalman Filter) ==========
    // 알고리즘 본체는 shared/commonMain/.../navigation/KalmanHeading.kt 에 분리됨.
    // 자세한 설명/파라미터는 그쪽 docstring 참조.
    private val kalmanHeading = KalmanHeading(stationarySpeed = STATIONARY_SPEED)

    // ========== CSV 로그 (Heading 분석용) ==========
    // 실제 저장은 생성자에서 주입받은 headingLogger 가 담당. 기본값은 NoopHeadingLogger.
    // Android 실 사용 시 MainActivity 가 AndroidHeadingLogger 를 주입.
    // MainActivity 센서 퓨전(가속도+자력계) azimuth. 미갱신이면 -1.
    private var latestCompassHeading: Float = -1f

    // 도착지 주변 정보 캐시 (API 반복 호출 방지)
    private var cachedNearbyPOIs: List<POIResult> = emptyList()
    private var cachedAddress: String? = null
    private var arrivalInfoLoaded = false

    // 목적지 입구 좌표 (frontLat/frontLon)
    var destinationFrontLat: Double? = null
        private set
    var destinationFrontLon: Double? = null
        private set

    // ========== 외부 주입 (센서) ==========

    /**
     * MainActivity의 센서 퓨전(가속도+자력계) azimuth를 최신값으로 받는다.
     * CSV `rotation_vector_heading` 필드 기록용. heading 판정 로직 자체는 GPS bearing 기반 그대로 유지.
     */
    private var leanAccumulator = 0 // 쏠림 누적 카운트

    fun updateCompassHeading(azimuth: Float, currentTime: Long) {
        latestCompassHeading = azimuth
        _compassHeading.value = azimuth   // CompassView 흰 화살표 계속 작동

        // ──────────────────────────────────────────────────────────────────────
        // 2026-05-31 설계 결정 — IMU heading 기반 lean 안내 시스템 비활성화.
        //
        // 문제: IMU heading vs 도로 방향 비교 방식은 다음 본질적 한계로 폭주 발화 유발:
        //   1. IMU azimuth 는 "휴대폰 향한 방향" 이지 "이동 방향" 이 아님 — 휴대폰 자세 변화/
        //      자력계 노이즈(건물 근처)에 매우 민감.
        //   2. 비교 기준인 도로 방향(폴리라인) 도 GPS 정확도 한계로 흔들림.
        //   3. 시각장애인 흰지팡이 좌우 탐지 보행은 본질적으로 큰 좌우 흔들림 동반 — 이게
        //      *정상* 보행 방식인데 lean 으로 잘못 판정.
        //
        // 대안: 굽은 길 보정은 handleVirtualWaypointPassed (가상 waypoint cross-track 기반)
        //   이 담당. 사용자 *위치* 가 가상점 라인에서 5m+ 벗어나면 "이탈하셨습니다" 발화.
        //   - 위치 기반 → IMU 노이즈 회피
        //   - 5m 간격 가상점 (RouteAnnotator.expandWithVirtualWaypoints) → 곡선 추적 정밀
        //   - 시각장애인 흰지팡이 흔들림 무시 (위치만 보므로)
        //
        // CompassView 의 흰 화살표(사용자 방향)는 _compassHeading 으로 그대로 갱신되므로
        // 시각 표시는 영향 없음. 음성 발화만 차단.
        //
        // 향후 IMU heading 신뢰도가 높은 환경(실내/터널)에서 부분 재활성화 검토 가능 —
        // 그래서 아래 walkingDiagnostic 로직은 제거하지 않고 dead branch 로 보존.
        // ──────────────────────────────────────────────────────────────────────
        return

        @Suppress("UNREACHABLE_CODE")
        if (_isNavigating.value && hasRoadBearing) {
            // 정지/거의 정지 상태에선 IMU heading 비교 자체가 의미 없음.
            // 사용자가 멈춰 있으면 휴대폰을 두리번거리거나 자세 바꾸는 게 흔하고, 그게 진행 방향과 무관.
            // 보행 중(speed >= 0.3 m/s) 일 때만 walkingDiagnostic 진입.
            if (latestSpeed < MIN_WALKING_SPEED_MPS) return

            // ──── IMU + GPS Kalman heading 융합 (2026-05-31) ────
            // 기존: IMU(자력계 fusion azimuth) 만으로 도로 방향과 비교 → 자기장 노이즈/휴대폰 자세에 민감
            // 변경: GPS Kalman heading(실제 이동 방향, 민성님 KalmanHeading) 과 융합해 정확도 향상
            //   - 속도 빠르고 GPS 정확도 좋으면 GPS 가중치 ↑ (실제 진행 방향 신뢰)
            //   - 속도 느리거나 GPS 정확도 나쁘면 IMU 가중치 ↑ (현재 향한 방향 신뢰)
            val fusedHeading = fuseImuAndGps(
                imuAzimuth = azimuth,
                gpsKalmanHeading = kalmanHeading.current,
                speed = latestSpeed,
                gpsAccuracy = latestGpsAccuracy,
            )

            val targetBearing = currentTargetBearing
            val status = walkingDiagnostic.analyzeLeanStatus(fusedHeading, targetBearing)

            // Settling — 직전 발화 후 사용자가 보정해서 STRAIGHT 한 번 거치기 전엔 재누적 안 함.
            // 발화 직후 사용자가 과보정하면 반대 방향 LEAN 이 5초 후 또 발화되는 핑퐁 사이클 방지.
            if (requireStraightBeforeNextLean) {
                if (status == LeanStatus.STRAIGHT) {
                    requireStraightBeforeNextLean = false
                    leanAccumulator = 0
                }
                // STRAIGHT 든 LEAN 이든 일단 누적/발화는 안 함.
                return
            }

            // 5초 쿨타임 체크
            if (currentTime - lastGuidanceTime >= guidanceCooldownMs) {
                when (status) {
                    LeanStatus.LEFT_LEAN -> leanAccumulator--
                    LeanStatus.RIGHT_LEAN -> leanAccumulator++
                    LeanStatus.STRAIGHT -> {
                        // 잔상효과(정상 보행이어도 누적된 메시지 알림 방지)
                        leanAccumulator = 0
                    }
                }

                // 누적 카운트가 임계값(5)에 도달하면 안내 메시지 발화.
                // 2026-05-31 외출 피드백 — 3 회 누적은 너무 빨라 잘못된 발화 폭주.
                // 5 회 (≈ 0.3 초 지속 LEAN) 로 완화해 일시적 흔들림 무시.
                if (kotlin.math.abs(leanAccumulator) >= 5) {
                    val message = if (leanAccumulator <= -3) {
                        "왼쪽으로 치우쳤습니다. 오른쪽으로 오세요."
                    } else {
                        "오른쪽으로 치우쳤습니다. 왼쪽으로 오세요."
                    }

                    emitGuidance(message)
                    lastGuidanceTime = currentTime
                    requireStraightBeforeNextLean = true   // 다음 발화는 STRAIGHT 한 번 거친 후

                    // 발화 후에는 다시 0부터 쌓이도록 초기화
                    leanAccumulator = 0
                }
            }
        }
    }

    /**
     * 보행 중 lean 안내용 heading 결정.
     *
     * 2026-05-31 설계 변경 — IMU(자력계+가속도 fusion)는 *움직일 때* 자기장 노이즈와
     * 휴대폰 자세 변화로 오류가 큰 것이 외출 테스트에서 확인됨. 따라서 보행 중 lean 보정에는
     * **GPS bearing 의 Kalman smoothed heading 만 사용**. IMU 는 fallback 으로만 유지.
     *
     * 이 함수는 updateCompassHeading 에서 latestSpeed >= MIN_WALKING_SPEED_MPS(0.3) 조건
     * 통과 후 호출되므로 호출 시점에 이미 *보행 중*. 정지 상태에선 호출 자체가 안 됨.
     *
     * 동작:
     *   - 정상 (GPS Kalman 초기화 + GPS accuracy 양호) → GPS Kalman heading 사용 (IMU 무시)
     *   - GPS Kalman 미초기화 (KalmanHeading 초기 1~2 frame) → IMU fallback
     *   - GPS accuracy > 30m (도심 빌딩 밀집 등) → IMU fallback
     *
     * Note: 함수 이름은 호환성 위해 fuseImuAndGps 유지. 내부 동작은 GPS 우선.
     * 가중 평균 버전의 상수 (FUSION_MIN_SPEED, FUSION_FULL_SPEED, FUSION_GPS_WEIGHT_MIN/MAX)
     * 및 import (PI, sin, cos, atan2) 는 향후 재시도 가능성 + 보고서 narrative 유지를 위해 보존.
     */
    private fun fuseImuAndGps(
        imuAzimuth: Float,
        gpsKalmanHeading: Float,
        speed: Float,
        gpsAccuracy: Float,
    ): Float {
        // K/N commonMain 은 String.format 미지원 — math.round 로 직접 2자리 반올림.
        val speedR2 = kotlin.math.round(speed * 100.0) / 100.0

        // GPS Kalman 미초기화 → IMU fallback (KalmanHeading 초기 1~2 frame)
        if (gpsKalmanHeading < 0f) {
            val debug = "imu=${imuAzimuth.toInt()}° gps=N/A " +
                    "speed=${speedR2} acc=${gpsAccuracy.toInt()}m USED=IMU(fallback)"
            println("[HEADING_FUSION] $debug")
            latestFusionDebug = debug
            return imuAzimuth
        }
        // GPS 정확도 너무 나쁨 → IMU fallback (빌딩 밀집/지하 출구 등)
        if (gpsAccuracy > FUSION_MAX_ACCURACY) {
            val debug = "imu=${imuAzimuth.toInt()}° gps=${gpsKalmanHeading.toInt()}° " +
                    "speed=${speedR2} acc=${gpsAccuracy.toInt()}m USED=IMU(gps_bad)"
            println("[HEADING_FUSION] $debug")
            latestFusionDebug = debug
            return imuAzimuth
        }
        // 정상 보행 중 + GPS 신뢰 가능 → GPS Kalman heading 만 사용 (IMU 무시).
        // IMU 움직임 노이즈가 굽은 길에서 잘못된 lean 안내를 유발하던 문제 해결.
        val debug = "imu=${imuAzimuth.toInt()}° gps=${gpsKalmanHeading.toInt()}° " +
                "speed=${speedR2} acc=${gpsAccuracy.toInt()}m USED=GPS"
        println("[HEADING_FUSION] $debug")
        latestFusionDebug = debug
        return gpsKalmanHeading
    }

    // ========== 경로 탐색 ==========

    /**
     * 목적지 검색 — 사용자 현재 위치 기준 가까운 순으로 정렬 + 1km 이내 필터.
     *
     * @param keyword 검색 키워드
     * @param currentLat 사용자 현재 위도 (null 가능 — 호환성, 다만 현재 위치를 넘기는 게 표준)
     * @param currentLon 사용자 현재 경도
     * @param radiusKm 검색 반경 (기본 1km). 이 거리 초과는 결과에서 제외
     * @return 가까운 순 정렬된 POI 목록 (최대 5개, 1km 안에 결과 없으면 빈 리스트)
     */
    suspend fun searchDestination(
        keyword: String,
        currentLat: Double? = null,
        currentLon: Double? = null,
        radiusKm: Float = 1.0f,
    ): List<POIResult> {
        return tMapApiClient.searchPOI(
            keyword = keyword,
            currentLat = currentLat,
            currentLon = currentLon,
            radiusKm = radiusKm,
        )
    }

    suspend fun startNavigation(
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double,
        endName: String,
        frontLat: Double? = null,
        frontLon: Double? = null,
        // iOS AutoOnboardingCoordinator 같은 외부가 요약 멘트를 직접 발화할 때 true.
        // 본 매니저는 _guidanceMessage 갱신을 건너뛰어 중복 발화를 막는다.
        suppressInitialSummary: Boolean = false,
    ): Boolean {
        destinationLat = endLat
        destinationLon = endLon
        destinationName = endName
        destinationFrontLat = frontLat
        destinationFrontLon = frontLon

        // 실제 보행 가능 좌표(입구)로 라우팅, 도착 판정은 실제 POI 좌표 기준
        val routeEndLat = frontLat ?: endLat
        val routeEndLon = frontLon ?: endLon

        val route = tMapApiClient.searchPedestrianRoute(
            startLat, startLon, routeEndLat, routeEndLon,
            startName = "현재 위치",
            endName = endName
        )

        if (route == null) {
            _guidanceMessage.value = tMapApiClient.lastError ?: "경로를 찾을 수 없습니다"
            return false
        }

        currentRoute = route
        currentWaypointIndex = 0
        currentRoutePointIndex = 0
        lastPreAnnouncedIndex = -1
        wasInCrosswalkZone = false
        lastCrosswalkAnnouncedWpIdx = -1
        lastSignalPresenceAnnouncedWpIdx = -1
        lastSignalDirectionAnnouncedWpIdx = -1
        _isNavigating.value = true
        _arrivalState.value = ArrivalState.FAR
        _distanceToDestination.value = Float.MAX_VALUE
        _isInCrosswalkZone.value = false
        _hasNearbyTrafficSignal.value = false
        // 새 경로 시작 — 도로 방향은 첫 GPS tick 들어올 때까지 미정.
        hasRoadBearing = false
        currentTargetBearing = 0f
        latestSpeed = 0f
        latestGpsAccuracy = 999f
        requireStraightBeforeNextLean = false
        leanAccumulator = 0

        // === 진단: TMap 응답이 횡단보도를 별도 waypoint 로 만들었는지 검증 ===
        // 시각장애인 안내의 핵심 — 만약 CROSSWALK 0 개면 TMap API 가 sparse 응답한 것.
        val crosswalkCount = route.waypoints.count { isCrosswalkWaypoint(it) }
        val typeBreakdown = route.waypoints.groupingBy { it.pointType }.eachCount()
        val riskBreakdown = route.segments.groupingBy { it.riskLevel }.eachCount()
        println("══════════ [NavManager] 경로 로드 완료 ══════════")
        println("총 거리: ${route.totalDistance}m, 예상 시간: ${route.totalTime}초 (~${route.totalTime / 60}분)")
        println("Waypoint: ${route.waypoints.size}개 (CROSSWALK ${crosswalkCount}개)")
        println("Point type 분포: $typeBreakdown")
        println("Segment: ${route.segments.size}개, 위험도 분포: $riskBreakdown")
        println("RoutePoint(폴리라인 좌표): ${route.routePoints.size}개")
        println("──────────── waypoint 전체 (untruncated) ────────────")
        route.waypoints.forEachIndexed { i, wp ->
            val mark = if (isCrosswalkWaypoint(wp)) "🚦" else "  "
            println("$mark [$i] type=${wp.pointType} turn=${wp.turnType} dist=${wp.distance}m " +
                    "lat=${wp.lat} lon=${wp.lon}")
            println("       desc=${wp.description}")
        }
        println("──────────── segment 전체 (LineString) ────────────")
        route.segments.forEachIndexed { i, seg ->
            val riskMark = when (seg.riskLevel) {
                RiskLevel.CAUTION -> "🟠"
                RiskLevel.NORMAL  -> "🟡"
                RiskLevel.SAFE    -> "🟢"
            }
            println("$riskMark [$i] wp[${seg.fromWaypointIndex}→${seg.toWaypointIndex}] " +
                    "dist=${seg.distance}m time=${seg.time}s " +
                    "road=${seg.roadType} facility=${seg.facilityType} " +
                    "name='${seg.name}' risk=${seg.riskLevel}")
        }
        println("════════════════════════════════════════════════")

        // RouteAnnotator 사전 분석 — 실제 안내에 사용.
        // annotateHybrid: waypoint 1차 + 직진 구간 내부 routePoints 곡률 보조 검사.
        println("──────────── waypoint dump (Annotator 입력 검증) ────────────")
        println("[Waypoints] count=${route.waypoints.size}")
        if (route.waypoints.isNotEmpty()) {
            val first = route.waypoints.first()
            val last = route.waypoints.last()
            println("[Waypoints] first=(${first.lat},${first.lon}) desc='${first.description}' " +
                    "pointType=${first.pointType} turnType=${first.turnType}")
            println("[Waypoints] last=(${last.lat},${last.lon}) desc='${last.description}' " +
                    "pointType=${last.pointType} turnType=${last.turnType}")
        }
        route.waypoints.forEachIndexed { idx, wp ->
            if (idx < 5 || idx >= route.waypoints.size - 2) {
                println("[Waypoints] [$idx] lat=${wp.lat} lon=${wp.lon} " +
                        "turnType=${wp.turnType} pointType=${wp.pointType} desc='${wp.description}'")
            }
        }
        println("[Waypoints] routePoints.size=${route.routePoints.size}")
        if (route.routePoints.isNotEmpty()) {
            val rpFirst = route.routePoints.first()
            val rpLast = route.routePoints.last()
            println("[Waypoints] routePoints first=(${rpFirst.lat},${rpFirst.lon}) " +
                    "last=(${rpLast.lat},${rpLast.lon})")
        }
        println("══════════════════════════════════════════════════════════════")

        val annotator = RouteAnnotator(navigatorConfig)
        val annotatedResult = runCatching {
            annotator.annotateHybrid(route.waypoints, route.routePoints)
        }.onFailure { e ->
            println("[NavManager] RouteAnnotator 분석 실패: ${e.message}")
        }.getOrNull()

        pathAnnotations = annotatedResult?.annotations ?: emptyList()

        // 곡선 구간에 가상 waypoint(5m 간격) 삽입 — 통과 시점에 비프로 방향 안내.
        // 가상 점은 routePoints 가 아닌 waypoints 에만 들어가므로 폴리라인 그리기엔 영향 없음.
        currentRoute = if (annotatedResult != null) {
            val expandedWaypoints = annotator.expandWithVirtualWaypoints(
                annotatedResult,
                route.routePoints,
            )
            val virtualCount = expandedWaypoints.count { it.isVirtual }
            println("[NavManager] 가상 waypoint 삽입 — 원본 ${route.waypoints.size}개 → 확장 ${expandedWaypoints.size}개 (가상 ${virtualCount}개)")
            route.copy(waypoints = expandedWaypoints)
        } else {
            route
        }

        // 폴리라인 평행 나침반 프로파일 — 원본 routePoints(가상 waypoint 미포함) 기준으로 구축.
        // 구간 분할은 폴리라인 좌표만 쓰므로 expandWithVirtualWaypoints 영향 없음.
        routeBearingProfile = RouteBearingProfile.build(route.routePoints, SEGMENT_INTERVAL_M)
        routePointCumulative = cumulativeAlongRoute(route.routePoints)

        // 누적 거리 / annotation 발화 추적은 확장된 waypoint 리스트 기준으로 다시 계산.
        cumulativeDistances = computeCumulativeDistances(currentRoute!!.waypoints)
        announcedKeys.clear()
        virtualPassCount = 0
        skipCandidateStartMs = 0L
        skipCandidateForIndex = -1

        // ─── G0: distanceFromStartM 좌표계 정렬 ───
        // RouteAnnotator 는 expand 이전 원본 waypoint chord합 으로 distanceFromStartM 을 채운다.
        // 반면 cumulativeDistances 는 expand 이후 확장 chord합(≈호 길이) 기준이라,
        // 곡선당 (arc − chord) 만큼의 시프트가 모든 annotation 의 gap 계산에 누적된다.
        // 옵션 B: expand 직후 annotation 의 distanceFromStartM 만 확장 cumulative 로 재매핑.
        //   인덱스(startWaypointIndex)·메시지·의미는 그대로. 거리만 정렬.
        val originalWaypointCount = route.waypoints.size
        val expandedForRemap = currentRoute!!.waypoints
        val originalToExpandedIndex = IntArray(originalWaypointCount) { -1 }
        var origCounter = 0
        for (exp in expandedForRemap.indices) {
            if (!expandedForRemap[exp].isVirtual) {
                if (origCounter < originalWaypointCount) {
                    originalToExpandedIndex[origCounter] = exp
                }
                origCounter++
            }
        }
        require(origCounter == originalWaypointCount) {
            "[G0] expand 후 비가상 waypoint 수 불일치: 비가상=$origCounter expected=$originalWaypointCount"
        }
        val beforeDistances = pathAnnotations.map { it.distanceFromStartM }
        pathAnnotations = pathAnnotations.mapIndexed { i, ann ->
            val expIdx = if (ann.startWaypointIndex in 0 until originalWaypointCount) {
                originalToExpandedIndex[ann.startWaypointIndex]
            } else -1
            if (expIdx < 0 || expIdx >= cumulativeDistances.size) {
                println("[G0] remap 실패 ann#$i startIdx=${ann.startWaypointIndex} → 원거리 유지")
                ann
            } else {
                val newDist = cumulativeDistances[expIdx]
                val before = beforeDistances[i]
                val delta = newDist - before
                // 부동소수 잡음 무시용 1자리 반올림 (commonMain 에 String.format 없음 → math.round 직접)
                val beforeR = kotlin.math.round(before * 10.0) / 10.0
                val afterR = kotlin.math.round(newDist * 10.0) / 10.0
                val deltaR = kotlin.math.round(delta * 10.0) / 10.0
                println(
                    "[G0-CHECK] ann#$i type=${ann.type} startIdx=${ann.startWaypointIndex} " +
                        "before=${beforeR}m after=${afterR}m Δ=+${deltaR}m"
                )
                ann.copy(distanceFromStartM = newDist)
            }
        }
        // ──────────────────────────────────────────

        _annotations.value = pathAnnotations
        _announcementLog.value = emptyList()

        // 사람이 읽기 좋은 포맷으로도 한 번 더 출력 — 임계값 튜닝/검증용.
        annotatedResult?.let {
            runCatching {
                RouteAnnotationLogger.log(
                    annotated = it,
                    routeName = "현재 위치 → ${endName}",
                    totalDistanceM = route.totalDistance,
                )
            }.onFailure { e ->
                println("[NavManager] RouteAnnotator 로그 실패: ${e.message}")
            }
        }

        cachedNearbyPOIs = emptyList()
        cachedAddress = null
        arrivalInfoLoaded = false
        consecutiveDeviationCount = 0
        lastRoadType = if (route.waypoints.isNotEmpty()) route.waypoints[0].roadType else -1

        // Heading Kalman 상태 리셋
        kalmanHeading.reset()

        // CSV 로그 시작 (logDirectory 미지정이면 no-op)
        openLogWriter()

        if (!suppressInitialSummary) {
            _guidanceMessage.value = buildInitialSummary()
        }

        return true
    }

    /**
     * 출발 시 안내 멘트 — "○○까지 N미터, 약 N분 소요됩니다. 횡단보도 K개. 안내를 시작합니다."
     *
     * iOS AutoOnboardingCoordinator 처럼 외부에서 직접 발화하고 싶을 때 호출한다.
     * 경로가 없으면 빈 문자열.
     */
    fun buildInitialSummary(): String {
        val route = currentRoute ?: return ""
        val totalMin = route.totalTime / 60
        val totalM = route.totalDistance
        val crosswalkCount = route.waypoints.count { isCrosswalkWaypoint(it) }
        return buildString {
            append("${destinationName}까지 ${totalM}미터, 약 ${totalMin}분 소요됩니다.")
            if (crosswalkCount > 0) append(" 횡단보도 ${crosswalkCount}개.")
            append(" 안내를 시작합니다.")
        }
    }

    private fun emitGuidance(message: String) {
        _guidanceMessage.value = message // 안내 메시지를 업데이트하는 역할
    }

    /**
     * 횡단보도 zone 진입 시 1회 호출 — 시각장애인이 어느 방향에 횡단보도가 있는지 알 수 있게
     * "X시 방향으로 횡단보도를 건너세요" 형식으로 발화한다.
     *
     * 방향 산출:
     *   - 횡단보도 waypoint 다음의 waypoint(=건넌 후 도달할 점)를 타겟으로 잡고
     *     사용자 heading 기준 시계 방향을 구한다.
     *   - 다음 waypoint 가 없거나 (정지 상태로 heading 부정확하면) "전방" 으로 폴백.
     *
     * 한 횡단보도당 한 번만 발화 — `lastCrosswalkAnnouncedWpIdx` 로 중복 방지.
     */
    private fun announceCrosswalkDirection(
        route: TMapRoute,
        currentLat: Double, currentLon: Double,
        userBearing: Float, speed: Float,
    ) {
        // currentWaypointIndex 부근(현재 ± 직전/직후 2개)에서 CROSSWALK 를 찾는다.
        // zone 판정은 segment 기반이라 currentWaypointIndex 가 정확히 CROSSWALK 가 아닐 수 있음.
        val searchStart = maxOf(0, currentWaypointIndex - 1)
        val searchEnd = minOf(currentWaypointIndex + 3, route.waypoints.size)
        var crosswalkIdx = -1
        for (i in searchStart until searchEnd) {
            if (isCrosswalkWaypoint(route.waypoints[i])) {
                crosswalkIdx = i
                break
            }
        }
        if (crosswalkIdx == -1) return  // CROSSWALK waypoint 못 찾음 — zone 오판정 가능성
        if (crosswalkIdx == lastCrosswalkAnnouncedWpIdx) return
        lastCrosswalkAnnouncedWpIdx = crosswalkIdx

        // 횡단보도 waypoint 자체의 좌표 — 사용자가 알아야 할 건 "횡단보도가 어느 쪽에 있는지".
        val crosswalkWp = route.waypoints.getOrNull(crosswalkIdx)
        val baseMessage = if (crosswalkWp == null) {
            "전방에 횡단보도가 있습니다. 신호를 확인하고 건너세요."
        } else {
            val side = getLeftRightDirection(
                currentLat, currentLon,
                crosswalkWp.lat, crosswalkWp.lon,
                userBearing, speed,
            )
            if (side == "전방") {
                "전방에 횡단보도가 있습니다. 신호를 확인하고 건너세요."
            } else {
                "${side}에 횡단보도가 있습니다. 신호를 확인하고 건너세요."
            }
        }
        val message = if (findSignalForCrosswalkIndex(route, crosswalkIdx, currentLat, currentLon, userBearing) != null) {
            lastSignalPresenceAnnouncedWpIdx = crosswalkIdx
            "$baseMessage 신호등이 있습니다."
        } else {
            baseMessage
        }

        // A-3: 일회성 이벤트 채널로 발화. _guidanceMessage 덮어쓰기에 영향받지 않음.
        announceEvent(message, forceRepeat = true, interrupt = false)
    }

    private fun announceSignalPresenceIfNeeded(
        crosswalkZoneInfo: CrosswalkZoneInfo,
        currentLat: Double,
        currentLon: Double,
        userBearing: Float,
    ) {
        val route = currentRoute ?: return
        val crosswalkIdx = crosswalkZoneInfo.crosswalkIndex
            ?: findNearbyCrosswalkWaypointIndex(route)
            ?: return
        if (crosswalkIdx == lastSignalPresenceAnnouncedWpIdx) return
        if (findSignalForCrosswalkIndex(route, crosswalkIdx, currentLat, currentLon, userBearing) == null) return

        lastSignalPresenceAnnouncedWpIdx = crosswalkIdx
        // A-3: 일회성 이벤트 채널.
        announceEvent("신호등이 있습니다.", forceRepeat = true, interrupt = false)
    }

    private fun announceSignalDirectionIfNeeded(
        crosswalkZoneInfo: CrosswalkZoneInfo,
        currentLat: Double,
        currentLon: Double,
        userBearing: Float,
    ) {
        val route = currentRoute ?: return
        val crosswalkIdx = crosswalkZoneInfo.crosswalkIndex
            ?: findNearbyCrosswalkWaypointIndex(route)
            ?: return
        if (crosswalkIdx == lastSignalDirectionAnnouncedWpIdx) return

        val signal = findSignalForCrosswalkIndex(
            route,
            crosswalkIdx,
            currentLat,
            currentLon,
            userBearing,
            matchRadiusMeters = SIGNAL_DIRECTION_MATCH_RADIUS_M,
        )
            ?: return

        lastSignalDirectionAnnouncedWpIdx = crosswalkIdx
        val directionReferenceBearing = if (latestCompassHeading >= 0f) {
            latestCompassHeading
        } else {
            userBearing
        }
        val clockDirection = getClockDirection(
            currentLat = currentLat,
            currentLon = currentLon,
            targetLat = signal.lat,
            targetLon = signal.lon,
            userBearing = directionReferenceBearing,
        )
        // A-3: 일회성 이벤트 채널.
        announceEvent("신호등은 ${clockDirection} 방향으로 추정됩니다. 휴대폰을 해당 방향으로 향해 주세요.", forceRepeat = true, interrupt = false)
    }

    private fun findSignalForCrosswalkZone(
        crosswalkZoneInfo: CrosswalkZoneInfo,
        currentLat: Double,
        currentLon: Double,
        userBearing: Float,
    ): TrafficSignalLocation? {
        val route = currentRoute ?: return null
        val crosswalkIdx = crosswalkZoneInfo.crosswalkIndex
            ?: findNearbyCrosswalkWaypointIndex(route)
            ?: return null
        return findSignalForCrosswalkIndex(route, crosswalkIdx, currentLat, currentLon, userBearing)
    }

    private fun findSignalForCrosswalkIndex(
        route: TMapRoute,
        crosswalkIdx: Int,
        currentLat: Double,
        currentLon: Double,
        userBearing: Float,
        matchRadiusMeters: Float = SIGNAL_CAMERA_MATCH_RADIUS_M,
    ): TrafficSignalLocation? {
        val crosswalkWp = route.waypoints.getOrNull(crosswalkIdx) ?: return null
        val routeBearing = computeRouteBearingAhead(15f) ?: userBearing
        return TrafficSignalMatcher.findBestSignalForCrosswalk(
            currentLat = currentLat,
            currentLon = currentLon,
            crosswalkLat = crosswalkWp.lat,
            crosswalkLon = crosswalkWp.lon,
            routeBearing = routeBearing,
            signals = trafficSignals,
            crosswalkRadiusMeters = matchRadiusMeters,
            currentRadiusMeters = matchRadiusMeters,
        )
    }

    private fun findNearbyCrosswalkWaypointIndex(route: TMapRoute): Int? {
        val searchStart = maxOf(0, currentWaypointIndex - 1)
        val searchEnd = minOf(currentWaypointIndex + 4, route.waypoints.size)
        for (i in searchStart until searchEnd) {
            if (isCrosswalkWaypoint(route.waypoints[i])) return i
        }
        return null
    }

    fun stopNavigation() {
        _isNavigating.value = false
        currentRoute = null
        currentWaypointIndex = 0
        currentRoutePointIndex = 0
        _arrivalState.value = ArrivalState.FAR
        _distanceToDestination.value = Float.MAX_VALUE
        _isInCrosswalkZone.value = false
        _hasNearbyTrafficSignal.value = false
        _guidanceMessage.value = "안내를 종료합니다"
        lastSpokenMessage = ""
        lastGuidanceTime = 0L
        lastRerouteTime = 0L
        lastPreAnnouncedIndex = -1
        lastStraightGuidanceTime = 0L
        lastRoadType = -1
        wasInCrosswalkZone = false
        lastCrosswalkAnnouncedWpIdx = -1
        lastSignalPresenceAnnouncedWpIdx = -1
        lastSignalDirectionAnnouncedWpIdx = -1
        cachedNearbyPOIs = emptyList()
        cachedAddress = null
        arrivalInfoLoaded = false
        consecutiveDeviationCount = 0
        consecutiveRerouteCount = 0
        pathAnnotations = emptyList()
        cumulativeDistances = emptyList()
        announcedKeys.clear()
        virtualPassCount = 0
        lastCurveReminderTime = 0L
        lastCurveReminderDirection = null
        curveAnnounceCount = 0
        lastVirtualWpIndex = -1
        spatialBeeper.stop()
        _annotations.value = emptyList()
        _announcementLog.value = emptyList()

        // 도로 방향 상태 리셋 — 다음 경로 시작 시 첫 GPS tick 까지 lean 안내 차단.
        hasRoadBearing = false
        currentTargetBearing = 0f
        latestSpeed = 0f
        latestGpsAccuracy = 999f
        requireStraightBeforeNextLean = false
        leanAccumulator = 0

        // 폴리라인 평행 나침반 상태 리셋
        routeBearingProfile = null
        routePointCumulative = emptyList()

        // Heading Kalman 상태 리셋
        kalmanHeading.reset()

        // CSV 로그 종료
        closeLogWriter()
    }

    private fun onArrived() {
        _isNavigating.value = false
        currentRoute = null
        currentWaypointIndex = 0
        currentRoutePointIndex = 0
        _distanceToDestination.value = 0f
        _isInCrosswalkZone.value = false
        _hasNearbyTrafficSignal.value = false
        lastSpokenMessage = ""
        wasInCrosswalkZone = false
        lastCrosswalkAnnouncedWpIdx = -1
        lastSignalPresenceAnnouncedWpIdx = -1
        lastSignalDirectionAnnouncedWpIdx = -1
        consecutiveDeviationCount = 0
        consecutiveRerouteCount = 0
        pathAnnotations = emptyList()
        cumulativeDistances = emptyList()
        announcedKeys.clear()
        virtualPassCount = 0
        lastCurveReminderTime = 0L
        lastCurveReminderDirection = null
        curveAnnounceCount = 0
        lastVirtualWpIndex = -1
        spatialBeeper.stop()
        _annotations.value = emptyList()

        // 도로 방향 상태 리셋
        hasRoadBearing = false
        currentTargetBearing = 0f
        leanAccumulator = 0

        // 폴리라인 평행 나침반 상태 리셋
        routeBearingProfile = null
        routePointCumulative = emptyList()

        // Heading Kalman 상태 리셋
        kalmanHeading.reset()

        // CSV 로그 종료
        closeLogWriter()
    }

    // ========== 경로 추종 ==========

    suspend fun updateLocation(location: GpsLocation) {
        if (!_isNavigating.value) return
        val route = currentRoute ?: return

        val currentLat = location.latitude
        val currentLon = location.longitude
        val speed = location.speed  // m/s
        val rawBearing = location.bearing
        // GPS accuracy 는 GpsLocation 변환 시점에 fallback(10f) 적용되므로 여기선 그대로 사용.
        val accuracy = location.accuracy
        val userBearing = updateSmoothedHeading(rawBearing, speed, accuracy)

        // updateCompassHeading 에서 정지 상태 판정용으로 노출 — sensor tick 이 GPS 보다 자주 들어옴.
        latestSpeed = speed
        latestGpsAccuracy = accuracy

        // CSV 로그 기록 (기존 로직에 영향 없음, writer 미초기화 시 no-op)
        writeLogRow(rawBearing, speed, accuracy, currentLat, currentLon)

        // 도착 판정은 실제 POI 또는 입구(frontLat) 중 더 가까운 쪽 기준
        val distToDest = distanceBetween(
            currentLat, currentLon, destinationLat, destinationLon
        )
        val fLat = destinationFrontLat
        val fLon = destinationFrontLon
        val distToDestination = if (fLat != null && fLon != null) {
            val distToFront = distanceBetween(
                currentLat, currentLon, fLat, fLon
            )
            minOf(distToDest, distToFront)
        } else {
            distToDest
        }

        // 실시간 거리 업데이트 (오디오 비콘용)
        _distanceToDestination.value = distToDestination

        // 도착 판정
        updateArrivalState(currentLat, currentLon, distToDestination, userBearing, speed)

        if (_arrivalState.value == ArrivalState.ARRIVED) return

        // 경로 이탈 체크 (GPS 정확도 + 속도 정보 활용)
        if (checkRouteDeviation(currentLat, currentLon, accuracy, speed)) {
            reroute(currentLat, currentLon)
            return
        }

        // 경로 위에 있으면 연속 재탐색 카운트 리셋
        consecutiveRerouteCount = 0

        // Forward-Only Waypoint 동기화 (지나간 waypoint를 다시 잡는 문제 방지)
        // userBearing 를 함께 전달 — 가상 waypoint 통과 시점에 비프 안내에 사용.
        currentRoute?.let {
            syncWaypointIndexForwardOnly(it, currentLat, currentLon, userBearing)
        }

        // ──── 도로 진행 방향 갱신 — IMU heading 보정 안내용 (Step 1, 2026-05-29) ────
        // computeRouteBearingAhead 는 폴리라인 기반이라 굽은 길에서도 자동으로 변한다.
        // 사용자 휴대폰 자세(자력계+가속도 fusion azimuth) vs 이 도로 방향을 walkingDiagnostic 가
        // 비교해 25° 이상 벌어지면 LEFT/RIGHT_LEAN 누적 → 3회 도달 시 음성 보정 안내.
        // MainActivity.orientationListener → navigationManager.updateCompassHeading 경로가 이 값을
        // 읽어가므로 매 GPS tick 마다 최신 도로 방향으로 갱신해 둔다.
        // 폴리라인 평행 우선 — 사용자가 현재 밟고 있는 구간의 접선 방위각.
        // 프로파일이 없거나(짧은 경로 등) 계산 불가 시 기존 lookahead chord 로 폴백.
        val roadBearingAhead = computeParallelRoadBearing(currentLat, currentLon)
            ?: computeRouteBearingAhead(15f)
        if (roadBearingAhead != null) {
            currentTargetBearing = roadBearingAhead
            hasRoadBearing = true
        }

        //현재 추척중인 waypoint 정보
        val currentWp = route.waypoints.getOrNull(currentWaypointIndex)

        // 현재 위치가 횡단보도인지 판정
        val crosswalkZoneInfo = findCrosswalkZoneInfo(
            currentLat,
            currentLon,
            route.waypoints,
            currentWaypointIndex
        )
        val isInCrossWalkZone = crosswalkZoneInfo.isInZone
        val hasNearbySignal = isInCrossWalkZone &&
                findSignalForCrosswalkZone(crosswalkZoneInfo, currentLat, currentLon, userBearing) != null
        // 외부 (안드 ML 검출 게이팅 등) 가 collect 할 수 있게 state flow 갱신
        _isInCrosswalkZone.value = isInCrossWalkZone
        _hasNearbyTrafficSignal.value = hasNearbySignal

        // zone 진입(false→true) 시점에 횡단 방향을 1회 안내 — 시각장애인이 어느 쪽에
        // 횡단보도가 있는지 모르기 때문. 매 update 마다 호출되므로 transition 만 잡는다.
        if (isInCrossWalkZone && !wasInCrosswalkZone) {
            announceCrosswalkDirection(route, currentLat, currentLon, userBearing, speed)
            announceSignalDirectionIfNeeded(crosswalkZoneInfo, currentLat, currentLon, userBearing)
        }
        if (isInCrossWalkZone) {
            announceSignalDirectionIfNeeded(crosswalkZoneInfo, currentLat, currentLon, userBearing)
            announceSignalPresenceIfNeeded(crosswalkZoneInfo, currentLat, currentLon, userBearing)
        }
        wasInCrosswalkZone = isInCrossWalkZone

        // 현재 위치가 속한 segment 찾기 (currentWaypointIndex 직전에 진입한 segment)
        //   진행 방향: waypoints[currentWaypointIndex-1] → waypoints[currentWaypointIndex]
        //   해당 segment 는 toWaypointIndex == currentWaypointIndex 인 것.
        val currentSegment = route.segments.firstOrNull {
            it.fromWaypointIndex == currentWaypointIndex
                    || it.toWaypointIndex == currentWaypointIndex
        }

        // 횡단보도 zone 디버그 — 가장 가까운 CROSSWALK waypoint 거리도 같이 표시.
        // (zone false 일 때 "그럼 가장 가까운 횡단보도가 얼마나 떨어져있냐" 를 즉시 알 수 있어 디버깅 용이)
        val nearestCrosswalk = route.waypoints
            .withIndex()
            .filter { isCrosswalkWaypoint(it.value) }
            .map { (idx, wp) ->
                Triple(idx, wp, distanceBetween(currentLat, currentLon, wp.lat, wp.lon))
            }
            .minByOrNull { it.third }
        val totalCrosswalkCount = route.waypoints.count { isCrosswalkWaypoint(it) }
        val nearestCrosswalkInfo = nearestCrosswalk?.let { (idx, _, dist) ->
            "nearestXW=idx${idx} dist=${dist.toInt()}m (total ${totalCrosswalkCount}개)"
        } ?: "nearestXW=없음 (route 에 CROSSWALK 0개 — TMap sparse response 의심)"

        _debugMessage.value =
            "횡단보도=$isInCrossWalkZone\n" +
                    "idx=$currentWaypointIndex/${route.waypoints.size}\n" +
                    "crosswalkState=${crosswalkZoneInfo.state}\n" +
                    "crosswalkIdx=${crosswalkZoneInfo.crosswalkIndex ?: -1}\n" +
                    "crosswalkDist=${crosswalkZoneInfo.distanceMeters?.toInt() ?: -1}m\n" +
                    "wp=${currentWp?.pointType}\n" +
                    "seg='${currentSegment?.name}' road=${currentSegment?.roadType} " +
                    "risk=${currentSegment?.riskLevel}\n" +
                    "turnType=${currentWp?.turnType}\n" +
                    "desc=${currentWp?.description}\n" +
                    nearestCrosswalkInfo
        val crosswalkDebugBase =
            "scenario=${if (isInCrossWalkZone) "CROSSWALK_ZONE" else "NO_CROSSWALK_ZONE"}\n" +
                    _debugMessage.value
        _debugMessage.value = crosswalkDebugBase
        if (isInCrossWalkZone) {
            val nearest = trafficSignals.minByOrNull {
                distanceBetween(currentLat, currentLon, it.lat, it.lon)
            }

            val nearestDist = nearest?.let {
                distanceBetween(currentLat, currentLon, it.lat, it.lon)
            }

            val nearestSignal = TrafficSignalMatcher.findNearestSignal(
                currentLat = currentLat,
                currentLon = currentLon,
                signals = trafficSignals,
                radiusMeters = 10f
            )

            if (nearestSignal != null) {
                _debugMessage.value =
                    "횡단보도 감지됨\n" +
                            "signals=${trafficSignals.size}\n" +
                            "nearestId=${nearest?.itstId ?: "없음"}\n" +
                            "nearestDist=${nearestDist?.toInt() ?: -1}m\n" +
                            "nearestSignalLat=${nearestSignal.lat}\n" +
                            "nearestSignalLon=${nearestSignal.lon}\n" +
                            "교차로 매칭 시도"

                _debugMessage.value =
                    crosswalkDebugBase + "\n" +
                            "scenario=CROSSWALK_SIGNAL_NEARBY\n" +
                            "signals=${trafficSignals.size}\n" +
                            "nearestId=${nearestSignal.itstId}\n" +
                            "nearestDist=${nearestDist?.toInt() ?: -1}m\n" +
                            "nearestSignalLat=${nearestSignal.lat}\n" +
                            "nearestSignalLon=${nearestSignal.lon}\n" +
                            "intersectionLookup=pending"

                fetchTrafficSignalData(
                    signalLat = nearestSignal.lat,
                    signalLon = nearestSignal.lon,
                    crosswalkZoneInfo = crosswalkZoneInfo,
                    nearestSignalId = nearestSignal.itstId,
                    nearestSignalDistance = nearestDist
                )
            } else {
                _debugMessage.value =
                    "횡단보도 감지됨\n" +
                            "signals=${trafficSignals.size}\n" +
                            "nearestId=${nearest?.itstId ?: "없음"}\n" +
                            "nearestDist=${nearestDist?.toInt() ?: -1}m\n" +
                            "10m 이내 신호등 없음"
            }
        }

        // waypoint 안내
        if (isInCrossWalkZone) {
            val nearestSignalForScenario = TrafficSignalMatcher.findNearestSignal(
                currentLat = currentLat,
                currentLon = currentLon,
                signals = trafficSignals,
                radiusMeters = 10f
            )
            if (nearestSignalForScenario == null) {
                val nearestForScenario = trafficSignals.minByOrNull {
                    distanceBetween(currentLat, currentLon, it.lat, it.lon)
                }
                val nearestDistForScenario = nearestForScenario?.let {
                    distanceBetween(currentLat, currentLon, it.lat, it.lon)
                }
                _debugMessage.value =
                    crosswalkDebugBase + "\n" +
                            "scenario=CROSSWALK_NO_SIGNAL\n" +
                            "signalPresent=false\n" +
                            "remainingTimeAvailable=false\n" +
                            "reason=NO_NEARBY_SIGNAL\n" +
                            "signals=${trafficSignals.size}\n" +
                            "nearestId=${nearestForScenario?.itstId ?: "?놁쓬"}\n" +
                            "nearestDist=${nearestDistForScenario?.toInt() ?: -1}m\n" +
                            "signalRadius=10m\n" +
                            "nearbySignal=false"
            }
        }

        updateWaypointGuidance(currentLat, currentLon, userBearing, speed)

        // RouteAnnotator 사전 안내 발화 — 폴리라인 기반 코너 즉석 감지를 대체함.
        if (_arrivalState.value == ArrivalState.FAR) {
            announceUpcomingAnnotation(currentLat, currentLon, speed)
        }

        // 직진 구간 무음 방지 안내 (좌우 보정은 RouteAnnotator 사전 안내로 대체됨)
        if (_arrivalState.value == ArrivalState.FAR) {
            provideDirectionalGuidance(currentLat, currentLon, distToDestination)
        }
    }

    // ========== 도착 안내 (핵심) ==========

    /**
     * 도착 상태 판정 + 안내
     *
     * 히스테리시스: GPS 흔들림 방지
     * - NEAR 진입 후 7m까지 유지
     * - APPROACHING 진입 후 18m까지 유지
     *
     * 도착 판정: 2m (GPS 한계 고려해 3m→2m 축소, 대신 정밀 유도로 보완)
     *
     * TTS 간격:
     * - APPROACHING: 5초마다
     * - NEAR: 2초마다 (정밀 유도 모드)
     */
    private suspend fun updateArrivalState(
        currentLat: Double, currentLon: Double,
        distToDestination: Float, userBearing: Float, speed: Float
    ) {
        val previousState = _arrivalState.value

        val newState = when {
            distToDestination <= ARRIVAL_DISTANCE_M -> ArrivalState.ARRIVED
            distToDestination <= NEAR_DISTANCE_M -> ArrivalState.NEAR
            distToDestination <= APPROACHING_DISTANCE_M -> {
                if (previousState == ArrivalState.NEAR && distToDestination <= NEAR_DISTANCE_M + 2f) {
                    ArrivalState.NEAR
                } else {
                    ArrivalState.APPROACHING
                }
            }
            else -> {
                if (previousState == ArrivalState.APPROACHING && distToDestination <= APPROACHING_DISTANCE_M + 3f) {
                    ArrivalState.APPROACHING
                } else {
                    ArrivalState.FAR
                }
            }
        }

        _arrivalState.value = newState

        // 상태 전환 시 안내
        if (newState != previousState) {
            // APPROACHING 진입 시 주변 정보 미리 로드 (1회만)
            if (newState != ArrivalState.FAR && !arrivalInfoLoaded) {
                loadArrivalInfo()
            }

            val message = when (newState) {
                ArrivalState.APPROACHING -> {
                    // 15m: 방향 + 주변 맥락 (건물 찾기 단서)
                    val clockDir = getClockDirSafe(
                        currentLat, currentLon, userBearing, speed
                    )
                    val nearbyContext = buildNearbyContext()
                    buildString {
                        append("${clockDir} ${distToDestination.toInt()}미터, ${destinationName} 근처입니다.")
                        if (nearbyContext.isNotEmpty()) {
                            append(" $nearbyContext")
                        }
                    }
                }

                ArrivalState.NEAR -> {
                    // 5m: 입구 방향 + 정밀 유도
                    val clockDir = getClockDirSafe(
                        currentLat, currentLon, userBearing, speed
                    )
                    val entranceDir = getEntranceDirection(currentLat, currentLon, userBearing, speed)
                    buildString {
                        append("${clockDir} ${distToDestination.toInt()}미터.")
                        if (entranceDir.isNotEmpty()) {
                            append(" $entranceDir")
                        }
                        append(" 계속 걸어오세요.")
                    }
                }

                ArrivalState.ARRIVED -> {
                    // 2m: 최종 확인 (랜드마크 상대위치 + 입구 방향 + 주소)
                    onArrived()
                    buildArrivalMessage(currentLat, currentLon, userBearing, speed)
                }

                ArrivalState.FAR -> ""
            }

            if (message.isNotEmpty()) {
                speak(message)
            }
        } else if (newState == ArrivalState.APPROACHING || newState == ArrivalState.NEAR) {
            // 같은 상태 유지 시: NEAR=2초, APPROACHING=5초 간격 업데이트
            val now = currentTimeMillis()
            val interval = if (newState == ArrivalState.NEAR) 2000L else 5000L
            if (now - lastGuidanceTime < interval) return
            lastGuidanceTime = now

            val clockDir = getClockDirSafe(
                currentLat, currentLon, userBearing, speed
            )
            val message = "${clockDir} ${distToDestination.toInt()}미터"
            speak(message, forceRepeat = true)
        }
    }


    suspend fun fetchTrafficSignalData(
        signalLat: Double,
        signalLon: Double,
        crosswalkZoneInfo: CrosswalkZoneInfo? = null,
        nearestSignalId: String? = null,
        nearestSignalDistance: Float? = null
    ) {
        val signalDebugBase =
            "crosswalkState=${crosswalkZoneInfo?.state ?: "?놁쓬"}\n" +
                    "crosswalkIdx=${crosswalkZoneInfo?.crosswalkIndex ?: -1}\n" +
                    "crosswalkDist=${crosswalkZoneInfo?.distanceMeters?.toInt() ?: -1}m\n" +
                    "nearestSignalId=${nearestSignalId ?: "?놁쓬"}\n" +
                    "nearestSignalDist=${nearestSignalDistance?.toInt() ?: -1}m\n" +
                    "signalLat=$signalLat\n" +
                    "signalLon=$signalLon"
        _debugMessage.value = "fetchTrafficSignalData 진입"//위치 확인용 임시
        _debugMessage.value =
            signalDebugBase + "\n" +
                    "scenario=CROSSWALK_SIGNAL_LOOKUP_PENDING\n" +
                    "signalPresent=true\n" +
                    "remainingTimeAvailable=unknown\n" +
                    "intersectionLookup=pending"
        val crossroadJson = signalApiClient.fetchIntersectionData()

        if (crossroadJson == null) {
            _debugMessage.value =
                signalDebugBase + "\n" +
                        "scenario=CROSSWALK_INTERSECTION_API_FAILED\n" +
                        "signalPresent=true\n" +
                        "remainingTimeAvailable=false\n" +
                        "reason=INTERSECTION_API_NULL\n" +
                        "intersectionApi=null"
            return
        }

        if (crossroadJson.startsWith("ERROR")) {
            _debugMessage.value =
                signalDebugBase + "\n" +
                        "scenario=CROSSWALK_INTERSECTION_API_FAILED\n" +
                        "signalPresent=true\n" +
                        "remainingTimeAvailable=false\n" +
                        "reason=INTERSECTION_API_ERROR\n" +
                        "intersectionApi=$crossroadJson"
            return
        }

        val intersections = TrafficIntersectionParser.parse(crossroadJson)

        val nearestIntersection = TrafficIntersectionParser.findNearest(
            intersections = intersections,
            lat = signalLat,
            lon = signalLon,
            radiusMeters = 10f
        )

        if (nearestIntersection == null) {
            _debugMessage.value =
                signalDebugBase + "\n" +
                        "scenario=CROSSWALK_SIGNAL_NON_INTERSECTION\n" +
                        "signalPresent=true\n" +
                        "remainingTimeAvailable=false\n" +
                        "reason=NO_INTERSECTION_MATCH\n" +
                        "intersectionMatched=false\n" +
                        "intersections=${intersections.size}"
            return
        }

        val now = currentTimeMillis()

        val isSameIntersection =
            nearestIntersection.itstId == lastSignalItstId

        val isCooldownActive =
            now - lastSignalApiCallTime < signalApiCooldownMs

        if (isSameIntersection && isCooldownActive) {
            _debugMessage.value = "잔여시간 API 쿨다운 중"
            return
        }

        lastSignalItstId = nearestIntersection.itstId
        lastSignalApiCallTime = now

        val remainJson = signalApiClient.fetchSignalRemainingData(
            itstId = nearestIntersection.itstId
        )

        val parsedSignals = remainJson?.let {
            TrafficSignalRemainingTimeParser.parse(it)

        } ?: emptyList()

        val routeBearing = computeRouteBearingAhead(15f)

        val selectedSignal = if (routeBearing != null) {
            selectSignalForRouteDirection(
                routeBearing = routeBearing,
                signals = parsedSignals
            )
        } else {
            parsedSignals.firstOrNull {
                it.remainingSeconds != null
            }
        }
        val allPedestrianSignals = buildPedestrianSignalDebug(parsedSignals)
        val targetDirection = routeBearing?.let { bearingToSignalDirection(it) }
        val signalScenario = when {
            remainJson == null || remainJson.startsWith("ERROR") ->
                "CROSSWALK_SIGNAL_REMAINING_API_FAILED"
            parsedSignals.isEmpty() ->
                "CROSSWALK_SIGNAL_NO_PEDESTRIAN_DATA"
            selectedSignal == null ->
                "CROSSWALK_SIGNAL_DIRECTION_MISSING"
            selectedSignal.remainingSeconds == null ->
                "CROSSWALK_SIGNAL_SELECTED_NO_REMAINING"
            else ->
                "CROSSWALK_SIGNAL_SELECTED"
        }
        val signalReason = when (signalScenario) {
            "CROSSWALK_SIGNAL_SELECTED_NO_REMAINING" ->
                "NO_REMAINING_TIME_FOR_SELECTED_SIGNAL"
            else -> signalScenario
        }

        _debugMessage.value =
            "교차로 매칭 성공\n" +
                    "itstId=${nearestIntersection.itstId}\n" +
                    "name=${nearestIntersection.itstNm}\n" +
                    "routeBearing=${routeBearing?.toInt() ?: -1}\n" +
                    "targetDirection=${routeBearing?.let { bearingToSignalDirection(it) } ?: "없음"}\n" +
                    "selectedDirection=${selectedSignal?.direction ?: "없음"}\n" +
                    "보행신호=${selectedSignal?.stateName ?: "없음"}\n" +
                    "raw=${selectedSignal?.remainingRaw ?: -1}\n" +
                    "남은시간=${selectedSignal?.remainingSeconds ?: -1}초\n" +
                    "parsedSignals=${parsedSignals.size}\n" +
                    "allPdsg=$allPedestrianSignals"

        _debugMessage.value =
            signalDebugBase + "\n" +
                    "scenario=$signalScenario\n" +
                    "signalPresent=true\n" +
                    "remainingTimeAvailable=${selectedSignal?.remainingSeconds != null}\n" +
                    "reason=$signalReason\n" +
                    "intersectionMatched=true\n" +
                    "itstId=${nearestIntersection.itstId}\n" +
                    "name=${nearestIntersection.itstNm ?: "?놁쓬"}\n" +
                    "routeBearing=${routeBearing?.toInt() ?: -1}\n" +
                    "targetDirection=${targetDirection ?: "?놁쓬"}\n" +
                    "selectedDirection=${selectedSignal?.direction ?: "?놁쓬"}\n" +
                    "walkSignal=${selectedSignal?.stateName ?: "?놁쓬"}\n" +
                    "remainingRaw=${selectedSignal?.remainingRaw ?: -1}\n" +
                    "remainingSeconds=${selectedSignal?.remainingSeconds ?: -1}\n" +
                    "parsedSignals=${parsedSignals.size}\n" +
                    "allPdsg=$allPedestrianSignals"
    }

    private fun buildPedestrianSignalDebug(
        signals: List<SignalRemainingInfo>
    ): String {
        val byDirection = signals.associateBy { it.direction }
        return listOf("nt", "ne", "et", "se", "st", "sw", "wt", "nw")
            .joinToString(" | ") { direction ->
                val signal = byDirection[direction]
                if (signal == null) {
                    "$direction=-"
                } else {
                    val raw = signal.remainingRaw?.toString() ?: "-"
                    val seconds = signal.remainingSeconds?.toString() ?: "-"
                    "$direction=${signal.stateName},raw=$raw,sec=$seconds"
                }
            }
    }

    private fun selectSignalForRouteDirection(
        routeBearing: Float,
        signals: List<SignalRemainingInfo>
    ): SignalRemainingInfo? {
        val targetDirection = bearingToSignalDirection(routeBearing)

        val exact = signals.firstOrNull { signal ->
            signal.direction == targetDirection
        }

        if (exact != null) {
            return exact
        }

        val fallbackDirections = adjacentSignalDirections(targetDirection)

        return fallbackDirections
            .asSequence()
            .mapNotNull { fallbackDirection ->
                signals.firstOrNull { signal ->
                    signal.direction == fallbackDirection
                }
            }
            .firstOrNull()
    }

    private fun bearingToSignalDirection(bearing: Float): String {
        val normalized = ((bearing % 360f) + 360f) % 360f

        return when {
            normalized >= 337.5f || normalized < 22.5f -> "nt"
            normalized < 67.5f -> "ne"
            normalized < 112.5f -> "et"
            normalized < 157.5f -> "se"
            normalized < 202.5f -> "st"
            normalized < 247.5f -> "sw"
            normalized < 292.5f -> "wt"
            else -> "nw"
        }
    }

    private fun adjacentSignalDirections(directionCode: String): List<String> {
        return when (directionCode) {
            "nt" -> listOf("nw", "ne")
            "ne" -> listOf("nt", "et")
            "et" -> listOf("ne", "se")
            "se" -> listOf("et", "st")
            "st" -> listOf("se", "sw")
            "sw" -> listOf("st", "wt")
            "wt" -> listOf("sw", "nw")
            "nw" -> listOf("wt", "nt")
            else -> emptyList()
        }
    }

    /**
     * 안전한 시계 방향 계산
     * 속도가 너무 낮으면(정지 상태) bearing이 부정확하므로 "전방" 으로 대체
     */
    private fun getClockDirSafe(
        currentLat: Double, currentLon: Double,
        userBearing: Float, speed: Float
    ): String = clockDirectionToward(
        currentLat, currentLon, destinationLat, destinationLon, userBearing, speed
    )

    /**
     * 임의의 타겟 좌표에 대한 시계 방향 안내("3시 방향"). 정지 상태(speed<0.3)면 bearing 부정확 → "전방".
     */
    private fun clockDirectionToward(
        currentLat: Double, currentLon: Double,
        targetLat: Double, targetLon: Double,
        userBearing: Float, speed: Float,
    ): String {
        return if (speed < 0.3f) {
            "전방"
        } else {
            getClockDirection(currentLat, currentLon, targetLat, targetLon, userBearing) + " 방향"
        }
    }

    // ========== 도착지 주변 정보 ==========

    /**
     * APPROACHING 진입 시 주변 정보를 미리 로드 (1회만)
     * - 주변 POI 여러 개 (목적지 자체 제외)
     * - 역지오코딩 주소
     */
    private suspend fun loadArrivalInfo() {
        if (arrivalInfoLoaded) return
        arrivalInfoLoaded = true

        // 주변 POI (반경 50m, 최대 5개)
        val allPOIs = tMapApiClient.searchNearbyPOI(destinationLat, destinationLon, 50)
        // 목적지 자체와 이름이 같은 POI 제외
        cachedNearbyPOIs = allPOIs.filter { it.name != destinationName }.take(3)

        // 주소
        cachedAddress = tMapApiClient.reverseGeocode(destinationLat, destinationLon)
    }

    /**
     * APPROACHING 안내: 주변 랜드마크 맥락
     * "주변에 CU편의점, 국민은행이 있습니다"
     */
    private fun buildNearbyContext(): String {
        if (cachedNearbyPOIs.isEmpty()) return ""
        val names = cachedNearbyPOIs.map { it.name }
        return "주변에 ${names.joinToString(", ")}이 있습니다"
    }

    /**
     * NEAR 안내: 입구 방향 계산
     * frontLat/frontLon이 있으면 입구 방향을 시계방향으로 안내
     */
    private fun getEntranceDirection(
        currentLat: Double, currentLon: Double,
        userBearing: Float, speed: Float
    ): String {
        val fLat = destinationFrontLat ?: return ""
        val fLon = destinationFrontLon ?: return ""
        // frontLat/Lon이 목적지 좌표와 거의 같으면 의미 없음
        val frontDist = distanceBetween(destinationLat, destinationLon, fLat, fLon)
        if (frontDist < 2f) return ""

        return if (speed < 0.3f) {
            "입구가 근처에 있습니다"
        } else {
            val dir = getClockDirection(
                currentLat, currentLon, fLat, fLon, userBearing
            )
            "입구는 ${dir} 방향입니다"
        }
    }

    /**
     * ARRIVED 안내: 최종 확인 메시지
     * 랜드마크 상대위치 + 입구 방향 + 주소를 한 번에 안내
     */
    private fun buildArrivalMessage(
        currentLat: Double, currentLon: Double,
        userBearing: Float, speed: Float
    ): String {
        return buildString {
            append("${destinationName}에 도착했습니다.")

            // 주변 랜드마크 단서 (첫 번째만)
            val nearestLandmark = cachedNearbyPOIs.firstOrNull()
            if (nearestLandmark != null) {
                append(" ${nearestLandmark.name} 근처입니다.")
            }

            // 입구 방향
            val entranceDir = getEntranceDirection(currentLat, currentLon, userBearing, speed)
            if (entranceDir.isNotEmpty()) {
                append(" $entranceDir.")
            }

            // 주소
            val address = cachedAddress
            if (!address.isNullOrEmpty()) {
                append(" 주소는 ${address}입니다.")
            }
        }
    }

    // ========== 경로 이탈 감지 ==========

    // routePoints에서 현재 사용자가 지나간 위치 인덱스 (검색 범위 최적화용)
    private var currentRoutePointIndex = 0

    // 연속 이탈 카운트 — GPS 튀김 1회로 재탐색 방지
    private var consecutiveDeviationCount = 0
    private companion object {
        const val DEVIATION_CONFIRM_COUNT = 3       // 3회 연속 이탈 시 확정
        const val BASE_DEVIATION_THRESHOLD = 25f    // 기본 이탈 임계값 (m)
        const val MIN_DEVIATION_THRESHOLD = 20f     // 최소 임계값
        const val MAX_DEVIATION_THRESHOLD = 50f     // 최대 임계값
        const val STATIONARY_SPEED = 0.5f           // 정지 판정 속도 (m/s) — 자세/직진 안내용
        // 시각장애인은 1.8km/h 미만으로 천천히 걷는 경우가 많아 STATIONARY_SPEED(0.5)로
        // 이탈을 막으면 잘못된 경로로 가도 재탐색이 안 됨. 이탈 판정 전용으로 더 낮은
        // 임계값을 둔다(0.1m/s = 거의 정지). iOS CLLocation.speed가 -1(무효)인 경우는
        // 0으로 coerce되므로 이 값도 같이 걸러진다.
        const val DEVIATION_STATIONARY_SPEED = 0.1f
        const val BASE_REROUTE_COOLDOWN = 15_000L   // 기본 재탐색 쿨다운 (ms)
        const val MAX_REROUTE_COOLDOWN = 60_000L    // 최대 재탐색 쿨다운 (ms)
        // Kalman 파라미터는 KalmanHeading.kt 내부 companion object 로 이동.

        // 폴리라인 평행 나침반 — 구간 분할 간격(m). 작을수록 곡선 추종 정밀, 잡음엔 민감.
        const val SEGMENT_INTERVAL_M = 10.0
        // 현재 위치보다 살짝 앞 구간 방위각을 읽어, 사용자가 향하는 방향을 가리키게 하는 lookahead(m).
        const val PARALLEL_LOOKAHEAD_M = 3.0

    }

    /**
     * 경로 이탈 판정 (GPS 정확도/속도 반영)
     *
     * 판정 전략:
     * 1. 정지 상태(0.5m/s 미만)면 GPS 드리프트이므로 이탈 판정 억제
     * 2. GPS accuracy를 임계값에 가산 — 정확도 나쁠수록 관대하게
     * 3. 1회 이탈이 아닌 N회 연속 이탈 시에만 재탐색 트리거
     */
    private fun checkRouteDeviation(
        currentLat: Double, currentLon: Double,
        accuracy: Float, speed: Float
    ): Boolean {
        val route = currentRoute ?: return false

        // 거의 정지 상태에서만 이탈 판정 억제 (GPS 드리프트 오판 방지).
        // STATIONARY_SPEED(0.5)보다 낮은 DEVIATION_STATIONARY_SPEED(0.1)을 쓰는 이유:
        // 시각장애인은 천천히 걸어 0.5m/s 미만으로 이동하는 경우가 많은데, 그 상태에서
        // 잘못된 길로 가도 재탐색이 안 되는 문제가 있었다.
        if (speed < DEVIATION_STATIONARY_SPEED) {
            consecutiveDeviationCount = 0
            return false
        }

        // 동적 임계값: 기본값 + GPS 오차의 절반 (최소~최대 범위 내)
        val dynamicThreshold = (BASE_DEVIATION_THRESHOLD + accuracy * 0.5f)
            .coerceIn(MIN_DEVIATION_THRESHOLD, MAX_DEVIATION_THRESHOLD)

        val minDist: Float
        if (route.routePoints.size >= 2) {
            minDist = findMinDistanceToRoute(currentLat, currentLon, route, speed)
        } else {
            // routePoints가 없으면 waypoint 폴백
            minDist = findMinDistanceToWaypoints(currentLat, currentLon, route)
        }

        if (minDist > dynamicThreshold) {
            consecutiveDeviationCount++
            println("[NavManager] 이탈 감지 ${consecutiveDeviationCount}/$DEVIATION_CONFIRM_COUNT — minDist=${minDist.toInt()}m, threshold=${dynamicThreshold.toInt()}m, speed=${speed}m/s")
        } else {
            consecutiveDeviationCount = 0
        }

        return consecutiveDeviationCount >= DEVIATION_CONFIRM_COUNT
    }

    /**
     * routePoints 선분까지의 최소 거리
     * 속도에 비례해 탐색 범위를 확장 (빠르게 걸으면 더 넓게 탐색)
     */
    private fun findMinDistanceToRoute(
        currentLat: Double, currentLon: Double,
        route: TMapRoute, speed: Float
    ): Float {
        val points = route.routePoints

        // 속도 기반 탐색 범위: 기본 ±5 ~ 최대 ±40 (2m/s=빠른 걷기 → +20)
        val speedBonus = (speed * 10).toInt().coerceAtMost(35)
        val lookAhead = 5 + speedBonus
        val lookBehind = 5

        val searchStart = maxOf(0, currentRoutePointIndex - lookBehind)
        val searchEnd = minOf(points.size - 1, currentRoutePointIndex + lookAhead)

        var minDist = Float.MAX_VALUE
        var closestIndex = currentRoutePointIndex

        for (i in searchStart until searchEnd) {
            val dist = distanceToSegment(
                currentLat, currentLon,
                points[i].lat, points[i].lon,
                points[i + 1].lat, points[i + 1].lon
            )
            if (dist < minDist) {
                minDist = dist
                closestIndex = i
            }
        }

        // 가장 가까운 지점 인덱스 갱신 (뒤로는 안 감)
        // waypoint 동기화는 updateLocation()에서 syncWaypointIndexForwardOnly로 처리
        if (closestIndex > currentRoutePointIndex) {
            currentRoutePointIndex = closestIndex
        }

        return minDist
    }

    /**
     * waypoint까지의 최소 거리 (routePoints 없을 때 폴백)
     */
    private fun findMinDistanceToWaypoints(
        currentLat: Double, currentLon: Double, route: TMapRoute
    ): Float {
        if (route.waypoints.isEmpty()) return Float.MAX_VALUE
        val checkRange = minOf(currentWaypointIndex + 5, route.waypoints.size)
        var minDist = Float.MAX_VALUE
        for (i in maxOf(0, currentWaypointIndex - 1) until checkRange) {
            val wp = route.waypoints[i]
            val dist = distanceBetween(
                currentLat, currentLon, wp.lat, wp.lon
            )
            if (dist < minDist) minDist = dist
        }
        return minDist
    }

    /**
     * routePoint 진행 시 이미 지나간 waypoint 자동 건너뛰기
     *
     * 판정 조건: waypoint이 경로상 현재 위치(currentRoutePointIndex)보다 뒤에 있을 때만 건너뜀
     * → 단순 거리 비교로 "앞에 있는 waypoint"를 실수로 건너뛰는 문제 방지
     */
    /**
     * Forward-Only Waypoint Selection Algorithm
     *
     * 기존 문제: 단순 거리 기반으로 가장 가까운 waypoint를 선택하면
     * U자 도로에서 이미 지나간 waypoint를 다시 타겟으로 잡음.
     *
     * 해결:
     * 1. currentWaypointIndex는 절대 뒤로 가지 않는다 (forward-only)
     * 2. 후보 범위를 currentWaypointIndex ~ +3으로 한정한다
     * 3. 후보 중 경로상 진행 방향(±90도 이내)에 있는 것만 인정한다
     * 4. 현재 waypoint에 도달 판정(10m 이내 + 경로상 통과)되면 다음으로 전진
     */
    private fun syncWaypointIndexForwardOnly(
        route: TMapRoute,
        currentLat: Double,
        currentLon: Double,
        userBearing: Float,
    ) {
        val waypoints = route.waypoints
        if (waypoints.isEmpty()) return

        // skip-ahead fallback 임계값
        val SKIP_AHEAD_MIN_DELTA_M = 3f         // 다음 wp 가 현재 wp 보다 최소 이만큼 더 가까워야 후보
        val SKIP_AHEAD_CROSS_TRACK_M = 15f      // 경로 cross-track 이탈이 이 값 이하여야 (이탈 아님)
        val SKIP_AHEAD_HOLD_MS = 3000L          // 후보 상태가 이만큼 유지되어야 실제 skip
        val SKIP_AHEAD_HARD_TIMEOUT_MS = 20000L // 동일 idx 에서 이만큼 advance 못 하면 강제 skip 1회 허용

        // Step 1: 현재 waypoint 통과 판정
        // 현재 타겟 waypoint에 10m 이내이고, 경로상 이미 지나갔으면 전진.
        // 가상 waypoint 는 5m 간격으로 촘촘하므로 통과 임계도 조금 더 짧게(7m) 적용.
        var lastVirtualPassedThisTick: Waypoint? = null
        while (currentWaypointIndex < waypoints.size) {
            val wp = waypoints[currentWaypointIndex]
            val distToWp = distanceBetween(
                currentLat, currentLon, wp.lat, wp.lon
            )
            val passThreshold = if (wp.isVirtual) 7f else 10f
            if (distToWp > passThreshold) {
                println("[POLL-DBG] idx=$currentWaypointIndex 거리 초과: distToWp=$distToWp threshold=$passThreshold isVirtual=${wp.isVirtual}")

                // ───── skip-ahead fallback ─────
                // 사용자가 wp[N] 의 통과 임계 안에 들어오지 않더라도
                // wp[N+1] 에 일관되게 더 가까워졌고 경로 이탈이 작으면 skip.
                val nextIdx = currentWaypointIndex + 1
                var didSkip = false
                if (nextIdx < waypoints.size) {
                    val nextWp = waypoints[nextIdx]
                    val distToNext = distanceBetween(currentLat, currentLon, nextWp.lat, nextWp.lon)
                    val delta = distToWp - distToNext   // 양수면 다음 wp 가 더 가까움

                    // cross-track: 현재 wp - 다음 wp 선분에 대한 사용자의 수직 이탈
                    val crossM = computeSignedCrossTrack(
                        currentLat = currentLat,
                        currentLon = currentLon,
                        routePoints = listOf(LatLng(wp.lat, wp.lon), LatLng(nextWp.lat, nextWp.lon)),
                        currentRoutePointIndex = 0,
                    )
                    val deviation = kotlin.math.abs(crossM)

                    val now = currentTimeMillis()
                    val candidateValid = delta >= SKIP_AHEAD_MIN_DELTA_M && deviation <= SKIP_AHEAD_CROSS_TRACK_M

                    if (candidateValid) {
                        if (skipCandidateForIndex != currentWaypointIndex) {
                            // 새 후보 등록
                            skipCandidateForIndex = currentWaypointIndex
                            skipCandidateStartMs = now
                            println("[SKIP-AHEAD] 후보 등록 idx=$currentWaypointIndex distToWp=$distToWp distToNext=$distToNext delta=$delta cross=$deviation")
                        } else if (now - skipCandidateStartMs >= SKIP_AHEAD_HOLD_MS) {
                            // hold 충족 — skip 실행
                            println("[SKIP-AHEAD] SKIP 실행 idx=$currentWaypointIndex → ${currentWaypointIndex + 1} (hold ${now - skipCandidateStartMs}ms)")
                            currentWaypointIndex++
                            skipCandidateForIndex = -1
                            skipCandidateStartMs = 0L
                            didSkip = true
                        }
                    } else {
                        // 후보 무효화
                        if (skipCandidateForIndex == currentWaypointIndex) {
                            println("[SKIP-AHEAD] 후보 무효 idx=$currentWaypointIndex delta=$delta cross=$deviation")
                            skipCandidateForIndex = -1
                            skipCandidateStartMs = 0L
                        }
                    }

                    // hard timeout — 같은 idx 에서 너무 오래 멈춰 있으면 cross-track 만 통과해도 강제 skip 1회
                    if (!didSkip && skipCandidateForIndex == currentWaypointIndex
                        && now - skipCandidateStartMs >= SKIP_AHEAD_HARD_TIMEOUT_MS
                        && deviation <= SKIP_AHEAD_CROSS_TRACK_M
                    ) {
                        println("[SKIP-AHEAD] HARD-TIMEOUT 강제 SKIP idx=$currentWaypointIndex (${now - skipCandidateStartMs}ms)")
                        currentWaypointIndex++
                        skipCandidateForIndex = -1
                        skipCandidateStartMs = 0L
                        didSkip = true
                    }
                }

                if (didSkip) continue
                // ───── skip-ahead fallback 끝 ─────

                break
            }

            // 경로상 통과 확인: waypoint 에 대응하는 routePoint 인덱스가
            // 현재 routePoint 진행 인덱스보다 뒤에 있는지 확인.
            //   - 가상 waypoint 는 polyline 위에 sampling 되므로 sourceRoutePointIdx 를 직접 사용
            //     (findClosestRoutePointIndex 호출 자체가 불필요)
            //   - 원본 waypoint 는 기존처럼 가장 가까운 routePoint 를 탐색
            val wpRouteIdx = if (wp.isVirtual && wp.sourceRoutePointIdx >= 0) {
                wp.sourceRoutePointIdx
            } else {
                findClosestRoutePointIndex(route, wp.lat, wp.lon)
            }
            if (wpRouteIdx <= currentRoutePointIndex + 2) {
                // 경로상 이미 지나갔거나 거의 같은 위치 → 전진
                if (wp.isVirtual) lastVirtualPassedThisTick = wp
                currentWaypointIndex++
            } else {
                println("[POLL-DBG] idx=$currentWaypointIndex routePoint 미통과: wpRouteIdx=$wpRouteIdx currentRP=$currentRoutePointIndex")
                break  // 아직 경로상 도달 안 함
            }
        }

        // 한 tick 에서 여러 가상 waypoint 를 한꺼번에 통과했더라도 비프는 마지막 1회만.
        // (연속 비프가 청각 피로를 유발하고 방향 의미도 마지막 점이 가장 최신이라.)
        if (lastVirtualPassedThisTick != null) {
            handleVirtualWaypointPassed(lastVirtualPassedThisTick, currentLat, currentLon, userBearing)
        }
    }

    private fun findClosestRoutePointIndex(
        route: TMapRoute,
        lat: Double, lon: Double
    ): Int {
        val pts = route.routePoints
        if (pts.isEmpty()) return 0

        var minDist = Float.MAX_VALUE
        var minIdx = 0
        val searchStart = maxOf(0, currentRoutePointIndex - 5)
        val searchEnd = minOf(pts.size, currentRoutePointIndex + 40)

        for (i in searchStart until searchEnd) {
            val d = distanceBetween(lat, lon, pts[i].lat, pts[i].lon)
            if (d < minDist) {
                minDist = d
                minIdx = i
            }
        }
        return minIdx
    }

    /**
     * 점(px,py)에서 선분(ax,ay)-(bx,by)까지의 최소 거리 (미터)
     * 수선의 발이 선분 위에 있으면 수선 거리, 아니면 양 끝점까지 거리 중 작은 값
     */
    private fun distanceToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0.0 && dy == 0.0) {
            return distanceBetween(px, py, ax, ay)
        }

        val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)

        val closestLat = ax + clampedT * dx
        val closestLon = ay + clampedT * dy
        return distanceBetween(px, py, closestLat, closestLon)
    }

    /**
     * 재탐색 (점진적 쿨다운)
     * 연속 재탐색 시 간격이 늘어남: 15초 → 30초 → 60초
     */
    private suspend fun reroute(currentLat: Double, currentLon: Double) {
        val now = currentTimeMillis()
        val cooldown = minOf(
            BASE_REROUTE_COOLDOWN * (1 + consecutiveRerouteCount),
            MAX_REROUTE_COOLDOWN
        )
        if (now - lastRerouteTime < cooldown) {
            println("[NavManager] 재탐색 쿨다운 중 — ${(cooldown - (now - lastRerouteTime)) / 1000}초 남음")
            return
        }
        lastRerouteTime = now
        consecutiveRerouteCount++

        println("[NavManager] 🔄 재탐색 시작 — pos=(${currentLat},${currentLon}) → dest=(${destinationLat},${destinationLon})")

        // 같은 메시지 중복 발화 필터에 막히지 않도록 리셋
        lastSpokenMessage = ""
        speak("경로를 이탈했습니다. 다시 탐색합니다.")

        // 이탈 카운트 리셋 — 재탐색 직후 즉시 다시 이탈 판정되지 않도록
        consecutiveDeviationCount = 0

        // 입구 좌표(frontLat/Lon)가 있으면 그쪽으로 라우팅 (도착 판정은 실제 POI 기준)
        val success = startNavigation(
            currentLat, currentLon,
            destinationLat, destinationLon,
            destinationName,
            frontLat = destinationFrontLat,
            frontLon = destinationFrontLon
        )

        if (!success) {
            println("[NavManager] 🔴 재탐색 실패")
            lastSpokenMessage = ""
            speak("경로를 찾을 수 없습니다. 주변 도움을 요청하세요.")
        }
    }

    // ========== Waypoint 안내 ==========

    private fun updateWaypointGuidance(
        currentLat: Double, currentLon: Double,
        userBearing: Float, speed: Float,
    ) {
        val route = currentRoute ?: return
        if (currentWaypointIndex >= route.waypoints.size) return

        val nextWaypoint = route.waypoints[currentWaypointIndex]
        // 가상 waypoint 는 음성 안내 대상이 아님 — syncWaypointIndexForwardOnly 가
        // 통과 시점에 스테레오 비프(handleVirtualWaypointPassed) 로 처리.
        if (nextWaypoint.isVirtual) return
        val distToNext = distanceBetween(
            currentLat, currentLon, nextWaypoint.lat, nextWaypoint.lon
        )

        // waypoint 도착 판정: GPS 오차 감안하여 10m (기존 5m → 회전 안내를 놓치는 문제 해결)
        if (distToNext <= 10f) {
            val roadTransition = getRoadTransitionMessage(nextWaypoint.roadType)
            lastRoadType = nextWaypoint.roadType

            // 이 waypoint 통과 후 진입할 segment — 도로명/거리를 안내에 포함.
            val nextSegment = route.segmentEnteringFromWaypoint(currentWaypointIndex)
            val waypointMsg = buildWaypointMessage(nextWaypoint, nextSegment)

            // 도로 전환 + 기존 안내를 자연스럽게 결합
            val message = when {
                roadTransition.isNotEmpty() && waypointMsg.isNotEmpty() ->
                    "$roadTransition $waypointMsg"
                roadTransition.isNotEmpty() -> roadTransition
                else -> waypointMsg
            }

            if (message.isNotEmpty()) {
                speak(message)
            }
            currentWaypointIndex++
            lastStraightGuidanceTime = currentTimeMillis()
        } else {
            // 사전 안내 거리는 "다음에 도달할 waypoint" 의 종류에 따라 결정.
            //   CROSSWALK → 30m (횡단보도 — 준비 시간 유지)
            //   그 외 KEY → 5m (회전/계단/목적지 — 너무 이른 발화 방지)
            // 현재 segment 가 SAFE(순수 보행자도로) 면 사전 안내 자체 생략 — 목표 ① TTS 피로 감소.
            val currentSegment = route.segments.firstOrNull {
                it.toWaypointIndex == currentWaypointIndex
            }
            val preDist = preAnnounceDistance(nextWaypoint)
            val suppressForSafe = currentSegment?.riskLevel == RiskLevel.SAFE
                    && nextWaypoint.pointType != "CROSSWALK"  // 횡단보도 안내는 SAFE 구간이라도 유지
            if (!suppressForSafe
                && distToNext <= preDist && isKeyPoint(nextWaypoint)
                && currentWaypointIndex != lastPreAnnouncedIndex
            ) {
                lastPreAnnouncedIndex = currentWaypointIndex

                // 횡단보도는 좌/우 위치를 안내 — 시각장애 보행자가 어느 쪽으로 가야 할지 알 수 있게.
                val message = if (nextWaypoint.pointType == "CROSSWALK") {
                    val side = getLeftRightDirection(
                        currentLat, currentLon,
                        nextWaypoint.lat, nextWaypoint.lon,
                        userBearing, speed,
                    )
                    if (side == "전방") {
                        "${distToNext.toInt()}미터 앞에 횡단보도가 있습니다"
                    } else {
                        "${distToNext.toInt()}미터 앞 ${side}에 횡단보도가 있을 예정입니다"
                    }
                } else {
                    "${distToNext.toInt()}미터 앞 ${nextWaypoint.description}"
                }

                // A-3: 일회성 사전안내 — 연속 안내(_guidanceMessage)에 잡아먹히지 않게 이벤트 채널로.
                announceEvent(message, forceRepeat = false, interrupt = false)
                // 사전 안내가 나왔으면 직진 타이머 리셋 (중복 방지)
                lastStraightGuidanceTime = currentTimeMillis()
            }
        }
    }

    /**
     * waypoint 종류에 따른 사전 안내 거리 (m).
     * 횡단보도는 시각장애인에게 가장 중요한 위험 지점이므로 충분한 준비 시간 확보.
     */
    private fun preAnnounceDistance(waypoint: Waypoint): Float = when (waypoint.pointType) {
        "CROSSWALK" -> 30f
        else        -> 5f
    }

    /**
     * 직진 구간 무음 방지 안내.
     * "약 N미터 직진" 형식으로 5초 간격(횡단보도 구간 제외) 발화한다.
     *
     * 과거에는 사용자 bearing vs 경로 bearing 차이로 좌우 보정 멘트도 냈으나,
     * 시각장애 보행자에게 좌우 안내가 체감되지 않아 RouteAnnotator 의 사전 안내로 대체됨.
     */
    private fun provideDirectionalGuidance(
        currentLat: Double, currentLon: Double,
        distToDestination: Float,
    ) {


        val route = currentRoute ?: return
        if (currentWaypointIndex >= route.waypoints.size) return
        if (route.routePoints.size < 2) return

        val nextWaypoint = route.waypoints[currentWaypointIndex]
        val distToNext = distanceBetween(
            currentLat, currentLon, nextWaypoint.lat, nextWaypoint.lon
        )

        // 횡단보도 구간 여부 — 진입 직전 ~ 통과 직후 윈도우.
        // 횡단보도에서는 직진 유지가 안전상 매우 중요하므로 임계값/쿨다운을 강화한다.
        val onCrosswalk = isOnCrosswalkSegment(
            currentLat, currentLon, route.waypoints, currentWaypointIndex
        )

        // 다음 waypoint와의 충돌 방지 게이트:
        //   일반:    25m (waypoint 사전 안내 30m 윈도우와 자연스럽게 분리)
        //   횡단보도: 5m  (도착 임박 직전까지 cross-track 보정을 살려둠)
        val waypointGuard = if (onCrosswalk) 5f else 25f
        if (distToNext <= waypointGuard) return

        // 경로 끝부분 등으로 진행 방향을 계산할 수 없으면 직진 안내도 부정확할 수 있어 생략.
        computeRouteBearingAhead(25f) ?: return

        val now = currentTimeMillis()

        // 2026-05-21 — 좌우 보정 멘트 제거 (bearing/cross-track 기반).
        // 시각장애 보행자에게 "약간 오른쪽으로" 같은 안내는 체감이 어렵고,
        // 굽은 길은 RouteAnnotator 의 사전 안내(announceUpcomingAnnotation) 가 대체한다.
        // 복구가 필요하면 git history 의 onCrosswalk 보정 블록을 참고할 것.

        // 직진 안내 — 시각장애 보행자 안심감을 위해 5초 간격. 횡단보도에서는 직진 안내 자체는 생략.
        if (onCrosswalk) return
        if (now - lastStraightGuidanceTime < 5_000L) return
        lastStraightGuidanceTime = now

        // 현재 진행 중인 segment (currentWaypointIndex 직전에 진입한 segment).
        //   진행 방향이 segment 의 마지막을 향하므로 toWaypointIndex == currentWaypointIndex.
        val currentSegment = route.segments.firstOrNull {
            it.toWaypointIndex == currentWaypointIndex
        }

        val message = if (currentSegment != null) {
            // ─── Distance anchors ────────────────────────────────────────────
            // 안전성 직결: anchor 의미와 메시지 문구가 일치해야 한다.
            //
            // - waypoint anchor (distForWaypointAnchor):
            //     다음 waypoint 까지의 직선거리. "이 구간 N미터 더 이동" /
            //     "N미터 직진" 같은 segment-progress 메시지에 사용.
            //     idx advance 가 정체되면 부정확할 수 있으나, segment-progress
            //     의미에서는 사용자에게 "현재 향하는 다음 지점까지의 거리" 로
            //     해석되어 의미 모순은 없다.
            //
            // - destination anchor (distForDestinationAnchor):
            //     GPS 실측 직선거리 (함수 인자 distToDestination). "도착지까지
            //     약 N미터" 같이 destination 의미를 직접 발화하는 메시지에 필수.
            //     idx 정체와 무관하게 GPS 실측이라 안전.
            //
            // 과거 버그 (2026-05-25 실외 테스트):
            //     "도착지" 분기가 distForWaypointAnchor 를 쓰는 바람에 idx 가
            //     가상 waypoint 에 정체될 때 "도착지까지 25→70m" 식으로 거리가
            //     역전 증가, 사용자가 도착지에서 멀어진다고 들리는 위험 발생.
            // ────────────────────────────────────────────────────────────────
            val distForWaypointAnchor = roundDistanceForTts(distToNext.toInt())
            val distForDestinationAnchor = roundDistanceForTts(distToDestination.toInt())
            when {
                // anchor: waypoint  — "이 구간 N미터 더 이동"
                currentSegment.name == "출발지" -> "약 ${distForWaypointAnchor}미터 더 이동하세요"
                // anchor: destination — 문구가 "도착지까지" 이므로 GPS 실측 사용 (★ 안전 직결)
                currentSegment.name == "도착지" -> "도착지까지 약 ${distForDestinationAnchor}미터"
                // anchor: waypoint — "이 도로 N미터 직진"
                currentSegment.name == "보행자도로" -> "약 ${distForWaypointAnchor}미터 직진"
                currentSegment.name.isBlank() -> "약 ${distForWaypointAnchor}미터 직진"
                else -> "${currentSegment.name} 방향 약 ${distForWaypointAnchor}미터 직진"
            }
        } else {
            // segment 정보 없으면 거리만 안내 (백업)
            val distText = if (distToDestination >= 1000f) {
                val km = distToDestination / 1000f
                val r = kotlin.math.round(km * 10) / 10
                "${r}킬로"
            } else {
                "${distToDestination.toInt()}미터"
            }
            "${distText} 직진하세요"
        }

        speak(message)
    }

    // isCrosswalkWaypoint() / isOnCrosswalkSegment() — KMM 마이그레이션으로
    // shared/commonMain/.../navigation/CrosswalkGuard.kt 로 이동.
    // 같은 패키지(com.example.safewalknav.navigation)이므로 import 없이 자동 호출됨.

    /**
     * 가상 waypoint 통과 시 호출.
     *
     * (2026-05-30 버전):
     *   - 비프음 제거. 곡선 방향을 짧은 음성("오른쪽 방향"/"왼쪽 방향")으로 안내.
     *   - 곡선당 최대 curveMaxAnnouncementsPerCurve(기본 3)회까지만 발화.
     *   - 이탈(cross-track ≥ curveDeviationCriticalM=5m) 시 방향 없이 "이탈하셨습니다".
     *     이탈 발화한 통과에서는 곡선 방향 발화를 생략(이중 발화 방지).
     */
    private fun handleVirtualWaypointPassed(
        passed: Waypoint,
        userLat: Double,
        userLon: Double,
        @Suppress("UNUSED_PARAMETER") userBearing: Float,
    ) {
        val route = currentRoute ?: return
        val nextWp = route.waypoints.getOrNull(currentWaypointIndex) ?: return
        virtualPassCount++

        // (1) 이탈 보정 — 비프 제거, 음성만. 5m 이상이면 방향 없이 "이탈하셨습니다".
        val crossM = computeSignedCrossTrack(
            currentLat = userLat,
            currentLon = userLon,
            routePoints = listOf(
                LatLng(passed.lat, passed.lon),
                LatLng(nextWp.lat, nextWp.lon),
            ),
            currentRoutePointIndex = 0,
        )
        if (kotlin.math.abs(crossM) >= navigatorConfig.curveDeviationCriticalM) {
            speak("이탈하셨습니다")
            return
        }

        // (2) 곡선 방향 음성 — "○○ 방향", 곡선당 최대 N회
        val curveDir = passed.curveDirection ?: return
        val side = when (curveDir) {
            "RIGHT" -> "오른쪽"
            "LEFT"  -> "왼쪽"
            else    -> return
        }

        // 곡선 경계 감지: 직전 가상 통과와 인덱스 차가 1 초과(=실 waypoint가 끼임)거나
        // 방향이 바뀌면 새 곡선 → 카운터 리셋.
        val isNewCurve = (currentWaypointIndex - lastVirtualWpIndex) > 1 ||
                curveDir != lastCurveReminderDirection
        if (isNewCurve) curveAnnounceCount = 0
        lastVirtualWpIndex = currentWaypointIndex

        if (curveAnnounceCount < navigatorConfig.curveMaxAnnouncementsPerCurve) {
            // A-3: 가상 wp 통과 시점 1회 곡선 방향 리마인더 — 일회성.
            announceEvent("${side} 방향", forceRepeat = false, interrupt = false)
            curveAnnounceCount++
        }
        lastCurveReminderDirection = curveDir
    }

    /**
     * RouteAnnotator 가 미리 분석한 annotation 을 사용자 진행 거리 기준으로 발화한다.
     *
     * 발화 조건:
     *   - 2단 안내(2026-05-31): selectAnnouncementCandidate 가 [AnnouncementStage] 로 분기.
     *       * APPROACH (예고, gap (imminent, triggerDist]) → "곧 …" — waypoint 게이트(5m) 적용.
     *       * IMMINENT (직전, gap [0, imminent])           → "지금 …" — 게이트 우회 + 선점 발화.
     *   - 음수 gap(이미 지난 회전)은 selector 에서 제외 — "지난 회전 발화" 0건 불변식 유지.
     *   - dedup 키는 (startWaypointIndex, stage) — 같은 회전 APPROACH 1회 + IMMINENT 1회까지.
     *   - announceMessage 가 비어있지 않음 (STRAIGHT/NONE 은 빈 문자열).
     *
     * 직선 거리 대신 누적 거리를 쓰는 이유: 굽은 경로에서 직선 거리가 실제 진행 거리를
     * 과소평가해 안내가 너무 늦게 나오는 문제가 있었다. (G0 정렬 후 같은 폴리라인 기준)
     *
     * triggerDist 는 annotation type 에 따라 다름:
     *   SLIGHT_CURVE, CURVE, INTERNAL_CURVE → announceDistanceCurveM (20m, A-2 후)
     *   SLIGHT_TURN, TURN                   → announceDistanceTurnM  (25m, A-2 후)
     *   SHARP_TURN                          → announceDistanceSharpM (30m, A-2 후)
     * IMMINENT 윈도우는 announceDistance*M 안쪽의 imminentDistanceM(기본 5m).
     */
    private fun announceUpcomingAnnotation(
        currentLat: Double, currentLon: Double, speed: Float,
    ) {
        if (pathAnnotations.isEmpty()) return
        if (speed < 0.3f) return

        val route = currentRoute ?: return

        val userCum = userCumulativeDistance(currentLat, currentLon, route)
        val pick = selectAnnouncementCandidate(
            annotations = pathAnnotations,
            userCumulativeDistance = userCum,
            announcedKeys = announcedKeys,
            config = navigatorConfig,
        ) ?: return

        // waypoint 사전 안내(5m) 충돌 게이트 — APPROACH 에만 적용.
        // IMMINENT 는 본질상 gap≤5m → distWp 도 작을 수밖에 없어 게이트로 일괄 차단되면 안 됨.
        if (pick.stage == AnnouncementStage.APPROACH &&
            currentWaypointIndex < route.waypoints.size
        ) {
            val wp = route.waypoints[currentWaypointIndex]
            val distWp = distanceBetween(currentLat, currentLon, wp.lat, wp.lon)
            if (distWp <= 5f) return
        }

        val gapR1 = kotlin.math.round(pick.gapM * 10.0) / 10.0
        println(
            "[STAGE] emit idx=${pick.annotation.startWaypointIndex} type=${pick.annotation.type} " +
                "stage=${pick.stage} gap=${gapR1}m"
        )

        // A-3: 회전 사전안내는 일회성 이벤트 채널로 — 연속 안내 덮어쓰기 방지.
        when (pick.stage) {
            AnnouncementStage.APPROACH -> announceEvent(
                message = MessageBuilder.buildAnnotationAnnounce(pick.annotation),
                forceRepeat = false,
                interrupt = false,
            )
            AnnouncementStage.IMMINENT -> announceEvent(
                // 직전은 타이밍 생명선 → iOS TTS 선점.
                message = MessageBuilder.buildImminentAnnounce(pick.annotation),
                forceRepeat = false,
                interrupt = true,
            )
        }
        announcedKeys.add(Pair(pick.annotation.startWaypointIndex, pick.stage))
    }

    /**
     * 사용자의 경로상 누적 진행 거리 (m).
     *
     * 계산:
     *   cumulativeDistances[currentWaypointIndex] = 다음 waypoint 까지의 누적 거리.
     *   거기서 "다음 waypoint 까지의 직선 거리(remaining)" 를 빼면 사용자 진행 거리가 된다.
     *
     * cumulativeDistances 가 비어있으면 0.0.
     */
    private fun userCumulativeDistance(
        currentLat: Double, currentLon: Double, route: TMapRoute,
    ): Double {
        if (cumulativeDistances.isEmpty()) return 0.0
        val idx = currentWaypointIndex.coerceAtMost(cumulativeDistances.size - 1)
        val wp = route.waypoints.getOrNull(idx) ?: return cumulativeDistances[idx]
        val remaining = distanceBetween(currentLat, currentLon, wp.lat, wp.lon).toDouble()
        return (cumulativeDistances[idx] - remaining).coerceAtLeast(0.0)
    }

    // 2026-05-21 — RouteAnnotator 의 사전 분석 결과(announceUpcomingAnnotation) 로 대체됨.
    // 폴리라인 기반 즉석 코너 감지는 곡선/회전을 동일 로직으로 다루는 RouteAnnotator 보다 약하고,
    // 같은 지점을 양쪽에서 안내해 멘트가 겹치는 문제가 있었다.
    // 복구가 필요하면 git history 의 announceUpcomingCorner 본문을 참고할 것.
    // private fun announceUpcomingCorner(...) { ... }

    // computeSignedCrossTrack() — KMM 마이그레이션으로
    // shared/commonMain/.../navigation/CrossTrack.kt 로 이동.
    // 새 시그니처: (currentLat, currentLon, routePoints, currentRoutePointIndex)
    // 호출자(provideDirectionalGuidance)에서 NavigationManager 상태를 인자로 직접 전달.

    /**
     * 현재 위치부터 lookAheadMeters 앞까지 경로의 전체 진행 방향 (bearing)
     */
    private fun computeRouteBearingAhead(lookAheadMeters: Float): Float? {
        val route = currentRoute ?: return null
        val pts = route.routePoints
        if (pts.size < 2) return null

        val startIdx = currentRoutePointIndex.coerceAtMost(pts.size - 1)
        val startPt = pts[startIdx]

        var accumulated = 0f
        var endIdx = startIdx
        for (i in startIdx until pts.size - 1) {
            val seg = distanceBetween(
                pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon
            )
            accumulated += seg
            endIdx = i + 1
            if (accumulated >= lookAheadMeters) break
        }
        if (endIdx == startIdx) return null

        val endPt = pts[endIdx]
        return bearing(startPt.lat, startPt.lon, endPt.lat, endPt.lon)
    }

    /**
     * 폴리라인 평행 도로 방위각 — 사용자가 현재 밟고 있는 구간의 접선 방향.
     *
     * computeRouteBearingAhead 가 "현재~15m 앞" 의 chord(현) 방향이라 곡선에서 폴리라인과
     * 어긋나는 반면, 이 함수는 routeBearingProfile 의 일정 간격 구간 방위각을 돌려주므로
     * 나침반 화살표가 현재 길 방향과 평행하게 정렬된다.
     *
     * distanceAlong = (currentRoutePointIndex 까지 누적 거리) + (현재 위치를 현재 선분에 정사영한 거리).
     * PARALLEL_LOOKAHEAD_M 만큼 앞 구간을 읽어 "사용자가 향하는 방향" 을 가리키게 한다.
     *
     * 프로파일이 비었거나 경로 정보가 부족하면 null → 호출부가 chord 방식으로 폴백.
     */
    private fun computeParallelRoadBearing(currentLat: Double, currentLon: Double): Float? {
        val profile = routeBearingProfile ?: return null
        if (profile.isEmpty) return null
        val route = currentRoute ?: return null
        val pts = route.routePoints
        if (pts.size < 2 || routePointCumulative.size != pts.size) return null

        val idx = currentRoutePointIndex.coerceIn(0, pts.size - 2)
        val proj = alongTrackMeters(currentLat, currentLon, pts[idx], pts[idx + 1])
        val distanceAlong = routePointCumulative[idx] + proj + PARALLEL_LOOKAHEAD_M
        return profile.bearingAt(distanceAlong)
    }

    /**
     * Heading 필터링 — 알고리즘은 KalmanHeading.kt 로 분리됨.
     * 본 메서드는 호출 인터페이스를 유지하기 위한 위임(thin wrapper).
     */
    private fun updateSmoothedHeading(
        rawHeading: Float, speed: Float, accuracy: Float
    ): Float = kalmanHeading.update(rawHeading, speed, accuracy)

    // bearing() / angleDiff() — KMM 마이그레이션으로 shared/commonMain 의 BearingMath.kt 로 이동.
    // 같은 패키지(com.example.safewalknav.navigation)이므로 import 없이 자동 호출됨.

    /**
     * 도로 유형 전환 안내 — 안전/길찾기상 중요한 전환만 안내
     *
     * 안내하는 전환:
     *   → 차도(2): 안전 경고
     *   → 자전거도로(3): 주의
     *   → 지하도(5), 육교(6): 길찾기 필수
     *   위험구간 → 인도(1): 안심 안내
     * 안내하지 않는 전환:
     *   인도 ↔ 기타, 같은 유형 유지 등
     */
    private fun getRoadTransitionMessage(newRoadType: Int): String {
        if (newRoadType == lastRoadType || lastRoadType == -1) return ""

        return when (newRoadType) {
            2 -> "차도 구간입니다."
            3 -> "자전거도로입니다."
            5 -> "지하도입니다."
            6 -> "육교입니다."
            1 -> {
                // 위험 구간에서 인도로 복귀할 때만 안내
                if (lastRoadType in listOf(2, 3, 5, 6)) "인도입니다." else ""
            }
            else -> ""
        }
    }

    /**
     * waypoint 도착 시 음성 안내 메시지를 만든다.
     *
     * @param waypoint 도착한 waypoint
     * @param nextSegment 이 waypoint 통과 후 진입할 segment. null 이면 도로명 정보 생략.
     *
     *   기본 형식: "{turn 안내}. {다음 도로명 + 거리}"
     *   예) CROSSWALK + 다음 테헤란로 233m
     *       → "횡단보도입니다. 직진하세요. 테헤란로 방향 약 230미터 직진"
     */
    private fun buildWaypointMessage(
        waypoint: Waypoint,
        nextSegment: RouteSegment? = null
    ): String {
        val turnMsg = when (waypoint.pointType) {
            "CROSSWALK" -> "횡단보도입니다. ${getTurnDescription(waypoint.turnType)}"
            "TURN" -> getTurnDescription(waypoint.turnType)
            "STAIRS" -> "계단이 있습니다"
            "DESTINATION" -> ""
            else -> if (isKeyPoint(waypoint)) waypoint.description else ""
        }

        val segMsg = nextSegment?.let { buildSegmentDirectionMessage(it) } ?: ""

        return when {
            turnMsg.isNotEmpty() && segMsg.isNotEmpty() -> "$turnMsg $segMsg"
            turnMsg.isNotEmpty() -> turnMsg
            else -> segMsg
        }
    }

    /**
     * segment 의 도로명 + 남은 거리를 TTS 친화적인 한국어로.
     *
     *   "출발지"        → 빈 문자열 (출발 직후엔 도로명 의미 없음)
     *   "도착지"        → "도착지까지 약 N미터"
     *   "보행자도로"    → "약 N미터 직진"        (도로명 생략 — 어색)
     *   빈 문자열       → "약 N미터 직진"
     *   그 외 도로명    → "{이름} 방향 약 N미터 직진"
     *
     *   distance < 20m 이면 안내 생략 (너무 짧으면 노이즈).
     */
    private fun buildSegmentDirectionMessage(segment: RouteSegment): String {
        if (segment.distance < 20) return ""
        val rounded = roundDistanceForTts(segment.distance)
        val distText = "약 ${rounded}미터"
        return when {
            segment.name == "출발지" -> ""
            segment.name == "도착지" -> "도착지까지 ${distText}"
            segment.name == "보행자도로" -> "${distText} 직진"
            segment.name.isBlank() -> "${distText} 직진"
            else -> "${segment.name} 방향 ${distText} 직진"
        }
    }

    /**
     * TTS 안내용 거리 라운딩.
     *   <50m  : m 단위 그대로
     *   <200m : 10m 단위
     *   그 외 : 50m 단위
     */
    private fun roundDistanceForTts(m: Int): Int = when {
        m < 50  -> m
        m < 200 -> ((m + 5) / 10) * 10
        else    -> ((m + 25) / 50) * 50
    }

    private fun isKeyPoint(waypoint: Waypoint): Boolean {
        return waypoint.pointType in listOf("CROSSWALK", "TURN", "STAIRS", "DESTINATION")
    }

    /**
     * 사용자 진행 방향 기준으로 타겟 좌표가 왼쪽/오른쪽/전방 중 어디에 있는지 판정.
     *
     * 진행 방향 결정 우선순위:
     *   1. 이동 중(speed >= 0.3) → userBearing (Kalman 평활화된 heading)
     *   2. 정지 중(speed < 0.3)  → routeBearing (경로 진행 방향, computeRouteBearingAhead)
     *   3. 둘 다 없으면 → "전방" 폴백
     *
     * 판정 임계값:
     *   - |각도 차이| < 20° → "전방"
     *   - 그 외 양수       → "오른쪽" (시계 방향)
     *   - 그 외 음수       → "왼쪽" (반시계 방향)
     *
     * 시각장애 보행자에게 시계방향("2시 방향") 보다 좌/우/전방이 더 직관적이라 도입.
     */
    private fun getLeftRightDirection(
        currentLat: Double, currentLon: Double,
        targetLat: Double, targetLon: Double,
        userBearing: Float, speed: Float,
    ): String {
        val referenceBearing: Float = if (speed >= 0.3f) {
            userBearing
        } else {
            computeRouteBearingAhead(25f) ?: return "전방"
        }

        val bearingToTarget = bearing(currentLat, currentLon, targetLat, targetLon)
        val diff = angleDiff(bearingToTarget, referenceBearing)

        return when {
            abs(diff) < 20f -> "전방"
            diff > 0 -> "오른쪽"
            else -> "왼쪽"
        }
    }

    private fun getTurnDescription(turnType: Int): String {
        return when (turnType) {
            1 -> "직진하세요"
            2 -> "좌회전하세요"
            3 -> "우회전하세요"
            4 -> "유턴하세요"
            5 -> "왼쪽 도로로 진입하세요"
            6 -> "오른쪽 도로로 진입하세요"
            12 -> "10시 방향으로 좌회전하세요"
            13 -> "2시 방향으로 우회전하세요"
            16 -> "8시 방향으로 좌회전하세요"
            17 -> "4시 방향으로 우회전하세요"
            211 -> "횡단보도를 건너세요"
            212 -> "좌측 횡단보도를 건너세요"
            213 -> "우측 횡단보도를 건너세요"
            214 -> "8시 방향 횡단보도를 건너세요"
            215 -> "10시 방향 횡단보도를 건너세요"
            216 -> "2시 방향 횡단보도를 건너세요"
            217 -> "4시 방향 횡단보도를 건너세요"
            else -> ""
        }
    }

    /**
     * 안내 메시지 발화
     * @param forceRepeat true이면 동일 메시지도 반복 발화 (접근 안내용)
     */
    private fun speak(message: String, forceRepeat: Boolean = false) {
        if (!forceRepeat && message == lastSpokenMessage) return
        lastSpokenMessage = message
        _guidanceMessage.value = message

        // 디버그 로그 — 외부 노출은 별도 커밋. 최근 20개만 유지.
        val timestamp = currentTimeMillis()
        val entry = "[${timestamp % 100_000}] $message"
        _announcementLog.value = (_announcementLog.value + entry).takeLast(20)
    }

    /**
     * A-3: 일회성 안내 전용. _guidanceMessage 를 건드리지 않는다.
     * 연속 안내("약 N미터 직진") 가 같은 tick 안에서 덮어써도 이벤트 채널은 영향 없음.
     *
     * @param interrupt iOS TTS 가 현재 발화·큐를 끊고 즉시 발화할지(선점). 회전 직전 같은
     *   타이밍 생명선 안내에서만 true. 다른 일회성 이벤트는 false (큐잉).
     */
    private fun announceEvent(message: String, forceRepeat: Boolean, interrupt: Boolean) {
        if (message.isBlank()) return
        val event = NavAnnouncement(message, forceRepeat, interrupt)
        val ok = _navEvents.tryEmit(event)
        println("[A3] announceEvent emit=$ok interrupt=$interrupt msg=$message")
        navEventListener?.invoke(event)

        // 발화 로그에는 일회성 이벤트도 같이 기록 — 디버그 패널 일관성 유지.
        val timestamp = currentTimeMillis()
        val entry = "[${timestamp % 100_000}] $message"
        _announcementLog.value = (_announcementLog.value + entry).takeLast(20)
    }

    /**
     * A-3: iOS 가 navEvents SharedFlow 를 직접 collect 할 수 없어 콜백으로 노출.
     * 반환된 Cancellable 을 stopNavigation 시점에 cancel() 해 누수 방지.
     * 단일 listener 만 등록 가능 — 새 구독이 들어오면 이전 구독을 덮어쓴다.
     */
    fun observeNavEvents(onEvent: (NavAnnouncement) -> Unit): Cancellable {
        navEventListener = onEvent
        return object : Cancellable {
            override fun cancel() {
                if (navEventListener === onEvent) navEventListener = null
            }
        }
    }

    // ========== CSV 로그 위임 ==========
    // 실제 저장 매체는 headingLogger 가 담당 (Android: AndroidHeadingLogger, 미주입: NoopHeadingLogger).

    private fun openLogWriter() {
        headingLogger.open()
    }

    private fun closeLogWriter() {
        headingLogger.close()
    }

    /**
     * 매 GPS 업데이트마다 CSV 한 줄 기록.
     * route_bearing 은 computeRouteBearingAhead(25m) 결과, null 이면 -1.
     * rotation_vector_heading 은 MainActivity 가 updateCompassHeading 으로 갱신한 최신값(미갱신 시 -1).
     * Kalman 미초기화 상태(첫 GPS 도착 전 호출 등)에서는 kalmanHeading.current 가 -1 을 돌려준다.
     */
    private fun writeLogRow(
        rawBearing: Float, speed: Float, accuracy: Float,
        lat: Double, lon: Double
    ) {
        val routeBearing = computeRouteBearingAhead(25f) ?: -1f
        headingLogger.write(
            timestamp = currentTimeMillis(),
            rawBearing = rawBearing,
            rotationVectorHeading = latestCompassHeading,
            routeBearing = routeBearing,
            kalmanHeading = kalmanHeading.current,
            kalmanGain = kalmanHeading.gain,
            speed = speed,
            accuracy = accuracy,
            lat = lat,
            lon = lon,
        )
    }
}
