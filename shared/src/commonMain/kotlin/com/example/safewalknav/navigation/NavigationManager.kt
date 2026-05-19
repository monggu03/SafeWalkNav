package com.example.safewalknav.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 순수 GPS 및 센서 퓨전 방위각 데이터를 관리하고 경로 탐색, 목적지 도달 상태,
 * 그리고 횡단보도(CROSSWALK) 구간 진입 여부를 추적하는 매니저 클래스.
 */
class NavigationManager(
    private val tMapApiClient: TMapApiClient,
    private val headingLogger: HeadingLogger
) {
    // --- 상태 Flow들 ---
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _guidanceMessage = MutableStateFlow("")
    val guidanceMessage: StateFlow<String> = _guidanceMessage.asStateFlow()

    private val _debugMessage = MutableStateFlow("")
    val debugMessage: StateFlow<String> = _debugMessage.asStateFlow()

    private val _arrivalState = MutableStateFlow(ArrivalState.FAR)
    val arrivalState: StateFlow<ArrivalState> = _arrivalState.asStateFlow()

    private val _distanceToDestination = MutableStateFlow(Float.MAX_VALUE)
    val distanceToDestination: StateFlow<Float> = _distanceToDestination.asStateFlow()

    // MainActivity.kt에서 신호등 검출 및 안내 게이팅용으로 수집(Collect)하는 필수 StateFlow 복구
    private val _isInCrosswalkZone = MutableStateFlow(false)
    val isInCrosswalkZone: StateFlow<Boolean> = _isInCrosswalkZone.asStateFlow()

    // --- 제어 및 누적 연산 변수 ---
    private var cvLeanAccumulator = 0
    private var lastGuidanceTime = 0L
    private val GUIDANCE_COOLDOWN_MS = 5000L // 💡 요청사항: 쿨타임 5초 정의

    // --- 목적지 정보 데이터 변수 ---
    var destinationLat: Double = 0.0
        private set
    var destinationLon: Double = 0.0
        private set
    var destinationFrontLat: Double? = null
        private set
    var destinationFrontLon: Double? = null
        private set
    var destinationName: String = ""
        private set

    // 구체적인 TMapRoute 타입으로 선언하여 MainActivity와의 컴파일 및 가시성 의존성 해결
    var currentRoute: TMapRoute? = null
        private set
    var lastError: String? = null
        private set

    // --- 알고리즘 컴포넌트 객체 ---
    private val kalmanHeading = KalmanHeading()
    private var lastLocation: GpsLocation? = null

    /**
     * TMap API 연동 목적지 주변 POI 검색 수행 함수
     */
    suspend fun searchDestination(
        keyword: String,
        currentLat: Double?,
        currentLon: Double?
    ): List<POIResult> {
        return try {
            lastError = null
            tMapApiClient.searchPOI(keyword, currentLat, currentLon)
        } catch (e: Exception) {
            lastError = "검색 중 오류가 발생했습니다: ${e.message}"
            emptyList()
        }
    }

    /**
     * 탐색된 GPS 좌표 경로 데이터를 기반으로 보행 안내 주행을 시작합니다.
     */
    suspend fun startNavigation(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        endName: String,
        frontLat: Double? = null,
        frontLon: Double? = null
    ): Boolean {
        return try {
            lastError = null

            // TMapApiClient의 보행자 경로 호출 메서드 연동
            val route = tMapApiClient.searchPedestrianRoute(
                startLat = startLat,
                startLon = startLon,
                endLat = endLat,
                endLon = endLon,
                endName = endName
            )

            if (route != null) {
                currentRoute = route
                destinationLat = endLat
                destinationLon = endLon
                destinationFrontLat = frontLat
                destinationFrontLon = frontLon
                destinationName = endName

                // 알고리즘 필터 초기화 및 로거 오픈
                kalmanHeading.reset()
                headingLogger.open()

                _isNavigating.value = true
                _arrivalState.value = ArrivalState.FAR
                _isInCrosswalkZone.value = false
                _guidanceMessage.value = "$endName 정보로 안내를 시작합니다."
                true
            } else {
                lastError = tMapApiClient.lastError ?: "경로 데이터가 유효하지 않습니다."
                false
            }
        } catch (e: Exception) {
            lastError = "경로 요청 실패: ${e.message}"
            false
        }
    }

    /**
     * 실시간 GPS 기기 업데이트 데이터 동기화 함수
     */
    fun updateLocation(location: GpsLocation) {
        if (!_isNavigating.value) return
        lastLocation = location

        val targetLat = destinationFrontLat ?: destinationLat
        val targetLon = destinationFrontLon ?: destinationLon
        val distance = distanceBetween(location.latitude, location.longitude, targetLat, targetLon)
        _distanceToDestination.value = distance

        // 4단계 도착 상태 전이 (FAR / APPROACHING / NEAR / ARRIVED)
        val nextState = when {
            distance <= NavigationConstants.ARRIVAL_DIST -> ArrivalState.ARRIVED
            distance <= NavigationConstants.NEAR_DIST -> ArrivalState.NEAR
            distance <= NavigationConstants.APPROACHING_DIST -> ArrivalState.APPROACHING
            else -> ArrivalState.FAR
        }

        if (_arrivalState.value != nextState) {
            _arrivalState.value = nextState
            if (nextState == ArrivalState.ARRIVED) {
                _guidanceMessage.value = "목적지에 최종 도착했습니다. 안내를 안전하게 종료합니다."
            }
        }

        // 횡단보도(Crosswalk) 구간 진입 판정 로직 추가
        checkCrosswalkZone(location)

        // 주행 노선 경로 내 진행 이탈률 추적
        processRouteProgress(location)
    }

    /**
     * 센서 컴패스로부터 들어오는 로우(Raw) 방위각을 칼만 필터로 평활화 연산 업데이트 처리합니다.
     */
    fun updateCompassHeading(rawAzimuth: Float, timestamp: Long) {
        if (!_isNavigating.value) return

        val loc = lastLocation
        val speed = loc?.speed ?: 0f
        val accuracy = loc?.accuracy ?: 10f

        val filteredHeading = kalmanHeading.update(rawAzimuth, speed, accuracy)
        val routeTargetBearing = loc?.let {
            bearing(it.latitude, it.longitude, destinationLat, destinationLon)
        } ?: 0f

        headingLogger.write(
            timestamp = timestamp,
            rawBearing = rawAzimuth,
            rotationVectorHeading = rawAzimuth,
            routeBearing = routeTargetBearing,
            kalmanHeading = filteredHeading,
            kalmanGain = kalmanHeading.gain,
            speed = speed,
            accuracy = accuracy,
            lat = loc?.latitude ?: 0.0,
            lon = loc?.longitude ?: 0.0
        )

        _debugMessage.value = "Heading: ${String.format("%.1f", filteredHeading)}°"
    }

    /**
     * TMap Waypoint 정보를 기반으로 현재 사용자가 횡단보도 구간에 인접했는지 판정합니다.
     */
    private fun checkCrosswalkZone(location: GpsLocation) {
        val route = currentRoute ?: return

        // 다음 안내 포인트(Waypoint)들 중 가장 가까운 CROSSWALK 포인트 검색
        val closeCrosswalk = route.waypoints.any { wp ->
            val isCrosswalkType = wp.pointType == "CROSSWALK" || wp.turnType in 211..217
            if (isCrosswalkType) {
                val distToWp = distanceBetween(location.latitude, location.longitude, wp.lat, wp.lon)
                // 프로젝트 정의 요건: 다음 횡단보도 포인트 30m 이내 진입 시 진입 처리
                distToWp <= 30f
            } else {
                false
            }
        }

        if (_isInCrosswalkZone.value != closeCrosswalk) {
            _isInCrosswalkZone.value = closeCrosswalk
        }
    }

    /**
     * 주행 노선 경로 내 진행 이탈률 추적 로직 함수
     */
    private fun processRouteProgress(location: GpsLocation) {
        val currentKHeading = kalmanHeading.current

        if (kalmanHeading.isInitialized && currentKHeading >= 0f) {
            val routeTargetBearing = bearing(location.latitude, location.longitude, destinationLat, destinationLon)
            val devDiff = angleDiff(routeTargetBearing, currentKHeading)

            // 에러 유발 지점 해결: threshold 변수의 타입을 명확히 Float으로 일치시킴 (.toFloat())
            val threshold: Float = if (_isInCrosswalkZone.value) {
                10f
            } else {
                NavigationConstants.LEAN_THRESHOLD.toFloat()
            }

            // 부동소수점 비교 연산 오류 방지를 위해 abs(devDiff) 또한 확실하게 Float 형으로 연산 진행
            if (abs(devDiff).toFloat() > threshold && location.speed > 0.6f) {
                if (devDiff > 0) {
                    _guidanceMessage.value = "경로를 우측으로 이탈하는 경향이 있습니다. 왼쪽으로 방향을 수정하세요."
                } else {
                    _guidanceMessage.value = "경로를 좌측으로 이탈하는 경향이 있습니다. 오른쪽으로 방향을 수정하세요."
                }
            }
        }
    }

    /**
     * 내비게이션 상태를 수동으로 강제 중단 및 종료 처리합니다.
     */
    fun stopNavigation() {
        if (!_isNavigating.value) return
        _isNavigating.value = false
        _arrivalState.value = ArrivalState.FAR
        _isInCrosswalkZone.value = false
        _distanceToDestination.value = Float.MAX_VALUE
        _guidanceMessage.value = "안내를 종료합니다."

        currentRoute = null
        lastLocation = null
        kalmanHeading.reset()
        headingLogger.close()
    }
}