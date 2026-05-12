package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.Waypoint
import com.example.safewalknav.navigation.angleDiff
import com.example.safewalknav.navigation.bearing

/**
 * TBFW (Trust-Based Forward Waypoint) Navigator의 facade.
 *
 * TrustScoreCalculator + ForwardOnlyTracker + MessageBuilder를 묶어서
 * 호출하는 쪽이 update() 한 번만 부르면 모든 결과를 받을 수 있게 한다.
 *
 * 사용 패턴:
 *   1. 시작 시 한 번 생성:
 *        val navigator = TrustBasedNavigator(waypoints)
 *
 *   2. 위치 업데이트마다 호출 (보통 1Hz):
 *        val result = navigator.update(userState)
 *        ttsManager.speak(result.message)
 *
 * 알고리즘 흐름:
 *   1. 현재 목표 waypoint 가져오기
 *   2. 거리 계산
 *   3. 목표 bearing 계산
 *   4. heading 차이 계산
 *   5. Trust Score 계산 + 분류
 *   6. waypoint 통과 판정 (ForwardOnlyTracker)
 *   7. 안내 메시지 생성 (MessageBuilder)
 *   8. NavigationResult로 묶어서 반환
 *
 * @param waypoints 따라갈 waypoint 리스트 (TMap에서 받은 경로)
 * @param config 튜닝 가능한 threshold 설정 (기본값 OK)
 */
class TrustBasedNavigator(
    waypoints: List<Waypoint>,
    private val config: NavigatorConfig = NavigatorConfig()
) {
    private val tracker = ForwardOnlyTracker(waypoints)

    /**
     * 매 위치 업데이트마다 호출.
     *
     * @param userState 현재 사용자 상태 (GPS + heading)
     * @return 안내 메시지 + 부가 정보
     */
    fun update(userState: UserState): NavigationResult {
        // 종료 상태 체크
        if (tracker.isFinished) {
            return finishedResult()
        }

        val target = tracker.currentTarget ?: return finishedResult()
        val location = userState.location

        // Step 1: 거리 계산
        val distance = tracker.distanceToTarget(location.latitude, location.longitude)

        // Step 2: 목표 bearing 계산 (현재 위치 → waypoint 방향)
        val targetBearing = bearing(
            lat1 = location.latitude,
            lon1 = location.longitude,
            lat2 = target.lat,
            lon2 = target.lon
        )

        // Step 3: heading 차이 계산 (양수 = 목표가 오른쪽, 음수 = 왼쪽)
        val currentHeading = userState.effectiveHeading
        val headingDifference = angleDiff(targetBearing, currentHeading)

        // Step 4: GPS accuracy 처리 — hasAccuracy=false면 보수적 fallback
        val effectiveAccuracy = if (location.hasAccuracy) {
            location.accuracy
        } else {
            // accuracy 정보가 없으면 의심스러운 값으로 처리
            50f
        }

        // Step 5: Trust Score 계산
        val trustScore = TrustScoreCalculator.calculate(
            gpsAccuracy = effectiveAccuracy,
            headingDiff = headingDifference,
            speed = location.speed,
            config = config
        )
        val trustLevel = TrustScoreCalculator.classify(trustScore)

        // Step 6: waypoint 통과 판정
        val didPass = tracker.update(
            distance = distance,
            trustScore = trustScore,
            trustLevel = trustLevel,
            headingDiff = headingDifference,
            config = config
        )

        // Step 7: 안내 메시지 생성
        // 통과한 직후라면 다음 waypoint를 기준으로 메시지 생성
        val targetForMessage = if (didPass) tracker.currentTarget else target
        val message = MessageBuilder.build(
            trustLevel = trustLevel,
            distance = distance,
            didPassWaypoint = didPass,
            isFinished = tracker.isFinished,
            currentTarget = targetForMessage
        )

        // Step 8: 결과 묶기
        return NavigationResult(
            message = message,
            didPassWaypoint = didPass,
            currentWaypointIndex = tracker.currentIndex,
            trustScore = trustScore,
            trustLevel = trustLevel,
            distanceToWaypoint = distance,
            headingDiff = headingDifference,
            isFinished = tracker.isFinished
        )
    }

    /**
     * 모든 waypoint를 통과한 종료 상태에서 반환할 결과.
     */
    private fun finishedResult(): NavigationResult = NavigationResult(
        message = MessageBuilder.MSG_ARRIVED_DESTINATION,
        didPassWaypoint = false,
        currentWaypointIndex = tracker.currentIndex,
        trustScore = 100,
        trustLevel = TrustLevel.HIGH,
        distanceToWaypoint = 0f,
        headingDiff = 0f,
        isFinished = true
    )

    /**
     * 디버그용 — 현재 진행 상태.
     */
    fun debugInfo(): String = tracker.debugInfo()
}
