package com.example.safewalknav.navigation

import android.location.Location
import com.example.safewalknav.location.LocationTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    // 도착지 주변 정보 캐시 (API 반복 호출 방지)
    private var cachedNearbyPOIs: List<POIResult> = emptyList()
    private var cachedAddress: String? = null
    private var arrivalInfoLoaded = false

    // 목적지 입구 좌표 (frontLat/frontLon)
    var destinationFrontLat: Double? = null
        private set
    var destinationFrontLon: Double? = null
        private set

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
    }

    // ========== 경로 추종 ==========

    suspend fun updateLocation(location: Location) {
        if (!_isNavigating.value) return
        currentRoute ?: return

        val currentLat = location.latitude
        val currentLon = location.longitude
        val userBearing = location.bearing
        val speed = location.speed  // m/s
        val accuracy = if (location.hasAccuracy()) location.accuracy else 10f

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

        // waypoint 안내
        updateWaypointGuidance(currentLat, currentLon, userBearing)

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
        if (closestIndex > currentRoutePointIndex) {
            currentRoutePointIndex = closestIndex
            // routePoint 진행에 맞춰 waypoint 자동 동기화
            syncWaypointIndex(route)
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
    private fun syncWaypointIndex(route: TMapRoute) {
        if (route.routePoints.size < 2) return

        while (currentWaypointIndex < route.waypoints.size) {
            val wp = route.waypoints[currentWaypointIndex]

            // waypoint에 가장 가까운 routePoint 인덱스를 찾아서
            // 현재 routePointIndex보다 뒤에 있으면 "지나간 것"으로 판정
            var closestRouteIdx = 0
            var closestDist = Float.MAX_VALUE
            val searchEnd = minOf(route.routePoints.size, currentRoutePointIndex + 15)
            for (i in maxOf(0, currentRoutePointIndex - 10) until searchEnd) {
                val rp = route.routePoints[i]
                val d = LocationTracker.distanceBetween(wp.lat, wp.lon, rp.lat, rp.lon)
                if (d < closestDist) {
                    closestDist = d
                    closestRouteIdx = i
                }
            }

            // waypoint의 경로상 위치가 현재 진행 위치보다 뒤에 있으면 건너뛰기
            if (closestRouteIdx < currentRoutePointIndex - 2) {
                currentWaypointIndex++
            } else {
                break
            }
        }
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
        currentLat: Double, currentLon: Double, userBearing: Float
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
        // 다음 waypoint이 가까우면 waypoint 안내와 충돌 방지
        if (distToNext <= 25f) return

        // 정지 상태에서는 bearing 부정확 — 방향 보정 안내는 생략, 위치 안내만
        val stationary = speed < 0.5f

        // 경로 진행 방향 계산 (앞으로 ~10m lookahead)
        val routeBearing = computeRouteBearingAhead(10f) ?: return

        val now = System.currentTimeMillis()

        // 점진적/회전 보정 (최근 8초 내 유사 안내 없었을 때)
        if (!stationary) {
            val diff = angleDiff(routeBearing, userBearing)
            val absDiff = abs(diff)
            if (absDiff >= 20f && now - lastStraightGuidanceTime >= 8_000L) {
                lastStraightGuidanceTime = now
                val side = if (diff > 0) "오른쪽" else "왼쪽"
                val message = if (absDiff >= 45f) {
                    "${side}으로 도세요"
                } else {
                    "${side}으로 살짝 꺾으세요"
                }
                speak(message, forceRepeat = true)
                return
            }
        }

        // 직진 안내 (20초 간격 유지)
        if (now - lastStraightGuidanceTime < 20_000L) return
        lastStraightGuidanceTime = now

        val distText = if (distToDestination >= 1000f) {
            "${String.format("%.1f", distToDestination / 1000f)}킬로"
        } else {
            "${distToDestination.toInt()}미터"
        }
        speak("직진하세요. 목적지까지 $distText")
    }

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

    /** 두 지점 간 방위각 (0~360) */
    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val from = Location("a").apply { latitude = lat1; longitude = lon1 }
        val to = Location("b").apply { latitude = lat2; longitude = lon2 }
        var b = from.bearingTo(to)
        if (b < 0) b += 360f
        return b
    }

    /** 각도 차이 (-180 ~ +180). 양수 = b 기준 a가 오른쪽 */
    private fun angleDiff(target: Float, current: Float): Float {
        var d = target - current
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

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
}
