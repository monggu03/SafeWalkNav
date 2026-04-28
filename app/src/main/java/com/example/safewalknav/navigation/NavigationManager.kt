package com.example.safewalknav.navigation

import com.example.safewalknav.location.LocationTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.math.abs

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
    private val tMapApiClient: TMapApiClient
) {
    var currentRoute: TMapRoute? = null
        private set

    private var currentWaypointIndex = 0

    var destinationLat = 0.0
        private set
    var destinationLon = 0.0
        private set
    var destinationName = ""
        private set

    // 도착 상태
    private val _arrivalState = MutableStateFlow(ArrivalState.FAR)
    val arrivalState: StateFlow<ArrivalState> = _arrivalState

    // 안내 메시지
    private val _guidanceMessage = MutableStateFlow("")
    val guidanceMessage: StateFlow<String> = _guidanceMessage

    // 내비게이션 활성 여부
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating

    // 목적지까지 실시간 거리 (오디오 비콘용)
    private val _distanceToDestination = MutableStateFlow(Float.MAX_VALUE)
    val distanceToDestination: StateFlow<Float> = _distanceToDestination

    val lastError: String? get() = tMapApiClient.lastError

    private var lastSpokenMessage = ""
    private var lastGuidanceTime = 0L
    private var lastRerouteTime = 0L
    private var lastPreAnnouncedIndex = -1
    private var consecutiveRerouteCount = 0  // 연속 재탐색 횟수 (쿨다운 점진 증가)
    private var lastStraightGuidanceTime = 0L // 직진 구간 안내 타이머
    private var lastCornerAnnouncedIdx = -1   // 폴리라인 코너 중복 안내 방지
    private var lastRoadType = -1 // 이전 구간 도로 유형 (전환 안내용)

    // ========== Heading Smoothing (Circular Kalman Filter) ==========
    // 알고리즘 본체는 shared/commonMain/.../navigation/KalmanHeading.kt 에 분리됨.
    // 자세한 설명/파라미터는 그쪽 docstring 참조.
    private val kalmanHeading = KalmanHeading(stationarySpeed = STATIONARY_SPEED)

    // ========== CSV 로그 (Heading 분석용) ==========
    // MainActivity가 setLogDirectory(context.getExternalFilesDir(DIRECTORY_DOCUMENTS))로 지정.
    // 미지정이면 로그 비활성.
    private var logDirectory: File? = null
    private var logWriter: BufferedWriter? = null
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

    // ========== 외부 주입 (로깅/센서) ==========

    /**
     * CSV 로그 저장 디렉터리 지정.
     * MainActivity onCreate에서 `context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)`로 한 번 호출한다.
     * targetSdk 34 환경에서 권한 없이 쓰기 가능한 app-scoped 경로를 사용한다.
     */
    fun setLogDirectory(dir: File?) {
        logDirectory = dir
    }

    /**
     * MainActivity의 센서 퓨전(가속도+자력계) azimuth를 최신값으로 받는다.
     * CSV `rotation_vector_heading` 필드 기록용. heading 판정 로직 자체는 GPS bearing 기반 그대로 유지.
     */
    fun updateCompassHeading(azimuth: Float) {
        latestCompassHeading = azimuth
    }

    // ========== 경로 탐색 ==========

    suspend fun searchDestination(keyword: String): List<POIResult> {
        return tMapApiClient.searchPOI(keyword)
    }

    suspend fun startNavigation(
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double,
        endName: String,
        frontLat: Double? = null,
        frontLon: Double? = null
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
        _isNavigating.value = true
        _arrivalState.value = ArrivalState.FAR
        _distanceToDestination.value = Float.MAX_VALUE
        cachedNearbyPOIs = emptyList()
        cachedAddress = null
        arrivalInfoLoaded = false
        consecutiveDeviationCount = 0
        lastCornerAnnouncedIdx = -1
        lastRoadType = if (route.waypoints.isNotEmpty()) route.waypoints[0].roadType else -1

        // Heading Kalman 상태 리셋
        kalmanHeading.reset()

        // CSV 로그 시작 (logDirectory 미지정이면 no-op)
        openLogWriter()

        val totalMin = route.totalTime / 60
        val totalM = route.totalDistance
        _guidanceMessage.value =
            "${endName}까지 ${totalM}미터, 약 ${totalMin}분 소요됩니다. 안내를 시작합니다."

        return true
    }

    fun stopNavigation() {
        _isNavigating.value = false
        currentRoute = null
        currentWaypointIndex = 0
        currentRoutePointIndex = 0
        _arrivalState.value = ArrivalState.FAR
        _distanceToDestination.value = Float.MAX_VALUE
        _guidanceMessage.value = "안내를 종료합니다"
        lastSpokenMessage = ""
        lastGuidanceTime = 0L
        lastRerouteTime = 0L
        lastPreAnnouncedIndex = -1
        lastStraightGuidanceTime = 0L
        lastCornerAnnouncedIdx = -1
        lastRoadType = -1
        cachedNearbyPOIs = emptyList()
        cachedAddress = null
        arrivalInfoLoaded = false
        consecutiveDeviationCount = 0
        consecutiveRerouteCount = 0

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
        lastSpokenMessage = ""
        consecutiveDeviationCount = 0
        consecutiveRerouteCount = 0

        // Heading Kalman 상태 리셋
        kalmanHeading.reset()

        // CSV 로그 종료
        closeLogWriter()
    }

    // ========== 경로 추종 ==========

    suspend fun updateLocation(location: GpsLocation) {
        if (!_isNavigating.value) return
        currentRoute ?: return

        val currentLat = location.latitude
        val currentLon = location.longitude
        val speed = location.speed  // m/s
        val rawBearing = location.bearing
        // GPS accuracy 는 GpsLocation 변환 시점에 fallback(10f) 적용되므로 여기선 그대로 사용.
        val accuracy = location.accuracy
        val userBearing = updateSmoothedHeading(rawBearing, speed, accuracy)

        // CSV 로그 기록 (기존 로직에 영향 없음, writer 미초기화 시 no-op)
        writeLogRow(rawBearing, speed, accuracy, currentLat, currentLon)

        // 도착 판정은 실제 POI 또는 입구(frontLat) 중 더 가까운 쪽 기준
        val distToDest = LocationTracker.distanceBetween(
            currentLat, currentLon, destinationLat, destinationLon
        )
        val fLat = destinationFrontLat
        val fLon = destinationFrontLon
        val distToDestination = if (fLat != null && fLon != null) {
            val distToFront = LocationTracker.distanceBetween(
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
        currentRoute?.let {
            syncWaypointIndexForwardOnly(it, currentLat, currentLon)
        }

        // waypoint 안내
        updateWaypointGuidance(currentLat, currentLon)

        // 폴리라인 기반 코너 선제 안내 (T-Map waypoint 누락 보완)
        if (_arrivalState.value == ArrivalState.FAR) {
            announceUpcomingCorner(currentLat, currentLon, speed)
        }

        // 직진 구간 무음 방지 + 점진적 꺾임 보정 안내
        if (_arrivalState.value == ArrivalState.FAR) {
            provideDirectionalGuidance(
                currentLat, currentLon, userBearing, speed, distToDestination
            )
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
            distToDestination <= 2f -> ArrivalState.ARRIVED
            distToDestination <= 5f -> ArrivalState.NEAR
            distToDestination <= 15f -> {
                if (previousState == ArrivalState.NEAR && distToDestination <= 7f) {
                    ArrivalState.NEAR
                } else {
                    ArrivalState.APPROACHING
                }
            }
            else -> {
                if (previousState == ArrivalState.APPROACHING && distToDestination <= 18f) {
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
            val now = System.currentTimeMillis()
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

    /**
     * 안전한 시계 방향 계산
     * 속도가 너무 낮으면(정지 상태) bearing이 부정확하므로 "전방" 으로 대체
     */
    private fun getClockDirSafe(
        currentLat: Double, currentLon: Double,
        userBearing: Float, speed: Float
    ): String {
        // 속도 0.3m/s 미만 = 거의 정지 → bearing 부정확
        return if (speed < 0.3f) {
            "전방"
        } else {
            LocationTracker.getClockDirection(
                currentLat, currentLon,
                destinationLat, destinationLon,
                userBearing
            ) + " 방향"
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
        val frontDist = LocationTracker.distanceBetween(destinationLat, destinationLon, fLat, fLon)
        if (frontDist < 2f) return ""

        return if (speed < 0.3f) {
            "입구가 근처에 있습니다"
        } else {
            val dir = LocationTracker.getClockDirection(
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
        const val STATIONARY_SPEED = 0.5f           // 정지 판정 속도 (m/s)
        const val BASE_REROUTE_COOLDOWN = 15_000L   // 기본 재탐색 쿨다운 (ms)
        const val MAX_REROUTE_COOLDOWN = 60_000L    // 최대 재탐색 쿨다운 (ms)
        // Kalman 파라미터는 KalmanHeading.kt 내부 companion object 로 이동.
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

        // 정지 상태: GPS 드리프트로 인한 오판 방지
        if (speed < STATIONARY_SPEED) {
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
            val dist = LocationTracker.distanceBetween(
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
        currentLon: Double
    ) {
        val waypoints = route.waypoints
        if (waypoints.isEmpty()) return

        // Step 1: 현재 waypoint 통과 판정
        // 현재 타겟 waypoint에 10m 이내이고, 경로상 이미 지나갔으면 전진
        while (currentWaypointIndex < waypoints.size) {
            val wp = waypoints[currentWaypointIndex]
            val distToWp = LocationTracker.distanceBetween(
                currentLat, currentLon, wp.lat, wp.lon
            )

            if (distToWp > 10f) break  // 아직 멀면 중단

            // 경로상 통과 확인: waypoint에 가장 가까운 routePoint 인덱스가
            // 현재 routePoint 진행 인덱스보다 뒤에 있는지 확인
            val wpRouteIdx = findClosestRoutePointIndex(route, wp.lat, wp.lon)
            if (wpRouteIdx <= currentRoutePointIndex + 2) {
                // 경로상 이미 지나갔거나 거의 같은 위치 → 전진
                currentWaypointIndex++
            } else {
                break  // 아직 경로상 도달 안 함
            }
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
            val d = LocationTracker.distanceBetween(lat, lon, pts[i].lat, pts[i].lon)
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
            return LocationTracker.distanceBetween(px, py, ax, ay)
        }

        val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)

        val closestLat = ax + clampedT * dx
        val closestLon = ay + clampedT * dy
        return LocationTracker.distanceBetween(px, py, closestLat, closestLon)
    }

    /**
     * 재탐색 (점진적 쿨다운)
     * 연속 재탐색 시 간격이 늘어남: 15초 → 30초 → 60초
     */
    private suspend fun reroute(currentLat: Double, currentLon: Double) {
        val now = System.currentTimeMillis()
        val cooldown = minOf(
            BASE_REROUTE_COOLDOWN * (1 + consecutiveRerouteCount),
            MAX_REROUTE_COOLDOWN
        )
        if (now - lastRerouteTime < cooldown) return
        lastRerouteTime = now
        consecutiveRerouteCount++

        speak("경로를 이탈했습니다. 다시 탐색합니다.")

        val success = startNavigation(
            currentLat, currentLon,
            destinationLat, destinationLon,
            destinationName
        )

        if (!success) {
            speak("경로를 찾을 수 없습니다. 주변 도움을 요청하세요.")
        }
    }

    // ========== Waypoint 안내 ==========

    private fun updateWaypointGuidance(
        currentLat: Double, currentLon: Double
    ) {
        val route = currentRoute ?: return
        if (currentWaypointIndex >= route.waypoints.size) return

        val nextWaypoint = route.waypoints[currentWaypointIndex]
        val distToNext = LocationTracker.distanceBetween(
            currentLat, currentLon, nextWaypoint.lat, nextWaypoint.lon
        )

        // waypoint 도착 판정: GPS 오차 감안하여 10m (기존 5m → 회전 안내를 놓치는 문제 해결)
        if (distToNext <= 10f) {
            val roadTransition = getRoadTransitionMessage(nextWaypoint.roadType)
            lastRoadType = nextWaypoint.roadType

            val waypointMsg = buildWaypointMessage(nextWaypoint)

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
            lastStraightGuidanceTime = System.currentTimeMillis()
        } else if (distToNext <= 30f && isKeyPoint(nextWaypoint)
            && currentWaypointIndex != lastPreAnnouncedIndex
        ) {
            // 사전 안내: 30m 전에 미리 알림 (기존 20m → GPS 오차 감안 확대)
            lastPreAnnouncedIndex = currentWaypointIndex
            val message = "${distToNext.toInt()}미터 앞 ${nextWaypoint.description}"
            speak(message)
            // 사전 안내가 나왔으면 직진 타이머 리셋 (중복 방지)
            lastStraightGuidanceTime = System.currentTimeMillis()
        }
    }

    /**
     * 방향 기반 안내
     * - 사용자 진행방향 vs 경로(폴리라인 lookahead) 방향 비교
     * - 일치: "직진하세요" (20초 간격)
     * - 20~45° 차이: "오른쪽/왼쪽으로 살짝 꺾으세요" (점진적 곡선 대응)
     * - 45° 이상: "오른쪽/왼쪽으로 도세요" (waypoint 누락된 코너 대응)
     */
    private fun provideDirectionalGuidance(
        currentLat: Double, currentLon: Double,
        userBearing: Float, speed: Float, distToDestination: Float
    ) {
        val route = currentRoute ?: return
        if (currentWaypointIndex >= route.waypoints.size) return
        if (route.routePoints.size < 2) return

        val nextWaypoint = route.waypoints[currentWaypointIndex]
        val distToNext = LocationTracker.distanceBetween(
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

        // 정지 상태에서는 bearing 부정확 — 방향 보정 안내는 생략, 위치 안내만
        val stationary = speed < 0.5f

        // 경로 진행 방향 계산 (앞으로 ~25m lookahead — 완만한 곡률도 감지)
        val routeBearing = computeRouteBearingAhead(25f) ?: return

        val now = System.currentTimeMillis()

        // 횡단보도 구간 강화 임계값:
        //   bearing diff: 15° → 10°
        //   cross-track:  2m  → 1.0m
        //   쿨다운:        6s  → 3s
        val cooldownMs = if (onCrosswalk) 3_000L else 6_000L
        val bearingThreshold = if (onCrosswalk) 10f else 15f
        val crossTrackThreshold = if (onCrosswalk) 1.0f else 2f

        // 점진적 곡선 대응: bearing 차이 + 측면 이탈(cross-track) 둘 다 판정
        if (!stationary && now - lastStraightGuidanceTime >= cooldownMs) {
            val diff = angleDiff(routeBearing, userBearing)
            val absDiff = abs(diff)
            val crossTrack = computeSignedCrossTrack(
                currentLat, currentLon, route.routePoints, currentRoutePointIndex
            )
            val absCross = abs(crossTrack)

            // 1. bearing 기반 (큰 편차 우선)
            if (absDiff >= bearingThreshold) {
                lastStraightGuidanceTime = now
                val side = if (diff > 0) "오른쪽" else "왼쪽"
                val message = if (onCrosswalk) {
                    // 횡단보도에서는 짧고 즉각적인 멘트로 — 직진 유지에 집중
                    "횡단보도. 약간 ${side}으로"
                } else when {
                    absDiff >= 90f -> "방향이 크게 벗어났습니다. ${side}으로 돌아주세요"
                    absDiff >= 45f -> "${side}으로 방향을 틀어주세요"
                    else -> "약간 ${side}으로 가세요"
                }
                speak(message, forceRepeat = true)
                return
            }

            // 2. cross-track 기반 (완만한 곡선에서 점진적 측면 드리프트 감지)
            // crossTrack > 0 → 사용자가 경로 왼쪽에 있음 → 오른쪽으로 가야 함
            if (absCross >= crossTrackThreshold) {
                lastStraightGuidanceTime = now
                val side = if (crossTrack > 0) "오른쪽" else "왼쪽"
                val message = if (onCrosswalk) {
                    "횡단보도. 약간 ${side}으로"
                } else if (absCross >= 5f) {
                    "${side}으로 이동하세요"
                } else {
                    "약간 ${side}으로 가세요"
                }
                speak(message, forceRepeat = true)
                return
            }
        }

        // 직진 안내 (20초 간격 유지) — 횡단보도에서는 직진 안내 자체는 생략 (중복 방지)
        if (onCrosswalk) return
        if (now - lastStraightGuidanceTime < 20_000L) return
        lastStraightGuidanceTime = now

        val distText = if (distToDestination >= 1000f) {
            "${String.format("%.1f", distToDestination / 1000f)}킬로"
        } else {
            "${distToDestination.toInt()}미터"
        }
        speak("직진하세요. 목적지까지 $distText")
    }

    // isCrosswalkWaypoint() / isOnCrosswalkSegment() — KMM 마이그레이션으로
    // shared/commonMain/.../navigation/CrosswalkGuard.kt 로 이동.
    // 같은 패키지(com.example.safewalknav.navigation)이므로 import 없이 자동 호출됨.

    /**
     * 폴리라인 코너 선제 안내
     * T-Map waypoint이 없는 각도 변화(골목→인도 진입 등)를 routePoints 기하로 감지.
     * 15m 이내 앞에서 30° 이상 꺾이는 지점을 미리 안내.
     */
    private fun announceUpcomingCorner(
        currentLat: Double, currentLon: Double, speed: Float
    ) {
        val route = currentRoute ?: return
        val pts = route.routePoints
        if (pts.size < 3) return
        if (speed < 0.3f) return // 이동 중일 때만

        // 다음 waypoint이 너무 가까우면 waypoint 안내와 충돌
        if (currentWaypointIndex < route.waypoints.size) {
            val wp = route.waypoints[currentWaypointIndex]
            val distWp = LocationTracker.distanceBetween(
                currentLat, currentLon, wp.lat, wp.lon
            )
            if (distWp <= 15f) return
        }

        val startIdx = currentRoutePointIndex
        val endIdx = minOf(pts.size - 2, startIdx + 30)
        var accumulated = 0f

        for (i in startIdx until endIdx) {
            val a = pts[i]
            val b = pts[i + 1]
            val c = pts[i + 2]
            val seg = LocationTracker.distanceBetween(a.lat, a.lon, b.lat, b.lon)
            accumulated += seg
            if (accumulated > 15f) return

            val b1 = bearing(a.lat, a.lon, b.lat, b.lon)
            val b2 = bearing(b.lat, b.lon, c.lat, c.lon)
            val diff = angleDiff(b2, b1)

            if (abs(diff) >= 30f) {
                val cornerIdx = i + 1
                if (cornerIdx == lastCornerAnnouncedIdx) return
                // 코너까지 거리 (현재 위치 → 코너점)
                val distToCorner = LocationTracker.distanceBetween(
                    currentLat, currentLon, b.lat, b.lon
                ).toInt().coerceAtLeast(1)
                lastCornerAnnouncedIdx = cornerIdx
                lastStraightGuidanceTime = System.currentTimeMillis()
                val side = if (diff > 0) "오른쪽" else "왼쪽"
                val verb = if (abs(diff) >= 60f) "도세요" else "꺾으세요"
                speak("${distToCorner}미터 앞 ${side}으로 ${verb}")
                return
            }
        }
    }

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
            val seg = LocationTracker.distanceBetween(
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

    private fun buildWaypointMessage(waypoint: Waypoint): String {
        return when (waypoint.pointType) {
            "CROSSWALK" -> "횡단보도입니다. ${getTurnDescription(waypoint.turnType)}"
            "TURN" -> getTurnDescription(waypoint.turnType)
            "STAIRS" -> "계단이 있습니다"
            "DESTINATION" -> ""
            else -> {
                if (isKeyPoint(waypoint)) waypoint.description else ""
            }
        }
    }

    private fun isKeyPoint(waypoint: Waypoint): Boolean {
        return waypoint.pointType in listOf("CROSSWALK", "TURN", "STAIRS", "DESTINATION")
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
    }

    // ========== CSV 로그 헬퍼 ==========

    /**
     * 내비게이션 시작 시 새 CSV 파일을 연다.
     * 포맷: timestamp,raw_bearing,rotation_vector_heading,route_bearing,speed,accuracy,lat,lon
     * logDirectory가 null이면 no-op.
     */
    private fun openLogWriter() {
        closeLogWriter()
        val dir = logDirectory ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "SafeWalk_heading_log_${System.currentTimeMillis()}.csv")
            val writer = BufferedWriter(FileWriter(file))
            // 컬럼 추가: kalman_heading, kalman_gain
            // 기존 heading_analysis.py는 raw_bearing/route_bearing만 보면 되므로 호환성 유지됨
            writer.write(
                "timestamp,raw_bearing,rotation_vector_heading,route_bearing," +
                        "kalman_heading,kalman_gain,speed,accuracy,lat,lon"
            )
            writer.newLine()
            logWriter = writer
            Logger.d("HeadingLog", "CSV 로그 시작: ${file.absolutePath}")
        } catch (e: Exception) {
            Logger.w("HeadingLog", "CSV 로그 초기화 실패: ${e.message}")
            logWriter = null
        }
    }

    private fun closeLogWriter() {
        val writer = logWriter ?: return
        try {
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            Logger.w("HeadingLog", "CSV 로그 종료 실패: ${e.message}")
        }
        logWriter = null
    }

    /**
     * 매 GPS 업데이트마다 CSV 한 줄 기록.
     * route_bearing은 computeRouteBearingAhead(25m) 결과, null이면 -1.
     * rotation_vector_heading은 MainActivity가 updateCompassHeading으로 갱신한 최신값(미갱신 시 -1).
     */
    private fun writeLogRow(
        rawBearing: Float, speed: Float, accuracy: Float,
        lat: Double, lon: Double
    ) {
        val writer = logWriter ?: return
        try {
            val routeBearing = computeRouteBearingAhead(25f) ?: -1f
            // Kalman 미초기화 상태(첫 GPS 도착 전 호출 등) 대비
            // KalmanHeading.current 는 미초기화 시 -1 을 돌려준다 (CSV 호환).
            val kHeading = kalmanHeading.current
            val kGain = kalmanHeading.gain
            val line = "${System.currentTimeMillis()},$rawBearing,$latestCompassHeading," +
                    "$routeBearing,$kHeading,$kGain,$speed,$accuracy,$lat,$lon"
            writer.write(line)
            writer.newLine()
            // 강제 종료 대비 즉시 flush (1Hz 수준이라 오버헤드 무시 가능)
            writer.flush()
        } catch (e: Exception) {
            Logger.w("HeadingLog", "CSV 로그 쓰기 실패: ${e.message}")
        }
    }
}
