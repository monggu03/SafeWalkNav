package com.example.safewalknav.navigation.tbfw

import com.example.safewalknav.navigation.geo.angleDiff
import com.example.safewalknav.navigation.geo.bearing
import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.tmap.Waypoint

/**
 * TBFW (Trust-Based Forward Waypoint) Navigator의 facade.
 *
 * GpsJumpDetector + ForwardOnlyTracker + JumpGuidancePolicy + MessageBuilder 를 묶어서
 * 호출하는 쪽이 update() 한 번만 부르면 모든 결과를 받을 수 있게 한다.
 *
 * 사용 패턴:
 *   1. 시작 시 한 번 생성:
 *        val navigator = TrustBasedNavigator(waypoints)
 *
 *   2. 위치 업데이트마다 호출 (보통 1Hz):
 *        val nowMs = clockNow()
 *        val result = navigator.update(userState, nowMs, isInCrosswalk)
 *        ttsManager.speak(result.message)
 *
 * 알고리즘 흐름:
 *   1. 현재 목표 waypoint 가져오기
 *   2. 거리/방향 계산
 *   3. GPS 점프 감지 (GpsJumpDetector)
 *   4. waypoint 통과 판정 (ForwardOnlyTracker, 점프 레벨 기반)
 *   5. 안내 정책 결정 (JumpGuidancePolicy)
 *   6. 안내 메시지 생성 (MessageBuilder)
 *   7. annotation 사전 안내
 *   8. NavigationResult로 묶어서 반환
 *
 * @param waypoints 따라갈 waypoint 리스트 (TMap에서 받은 경로)
 * @param config 튜닝 가능한 threshold 설정 (기본값 OK)
 * @param annotations RouteAnnotator 결과. 사전 안내(곡선/회전) 가 필요하지 않은 호출자는
 *                    비워둘 수 있고 그 경우 annotationAnnouncement 는 항상 null 이다.
 */
class TrustBasedNavigator(
    private val waypoints: List<Waypoint>,
    private val config: NavigatorConfig = NavigatorConfig(),
    private val annotations: List<PathAnnotation> = emptyList(),
) {
    private val tracker = ForwardOnlyTracker(waypoints)
    private val jumpDetector = GpsJumpDetector(config)
    private val guidancePolicy = JumpGuidancePolicy(config)

    /**
     * waypoints[0] 부터 waypoints[i] 까지의 누적 거리.
     * cumulativeDistances[k] = sum(distance(waypoints[i], waypoints[i+1]) for i in 0..k-1).
     */
    private val cumulativeDistances: List<Double> = run {
        if (waypoints.isEmpty()) return@run emptyList()
        val out = ArrayList<Double>(waypoints.size)
        out.add(0.0)
        var acc = 0.0
        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]
            val b = waypoints[i]
            acc += distanceBetween(a.lat, a.lon, b.lat, b.lon).toDouble()
            out.add(acc)
        }
        out
    }

    /**
     * 이미 사전 안내한 annotation 의 startWaypointIndex 집합. 같은 annotation 을 두 번
     * 발화하지 않기 위함.
     */
    private val announcedAnnotationIds = mutableSetOf<Int>()

    /**
     * 매 위치 업데이트마다 호출.
     *
     * @param userState 현재 사용자 상태 (GPS + heading)
     * @param currentTimeMs 현재 시각 (ms, epoch). GPS 점프 감지 + 안내 정책에 필요.
     * @param isInCrosswalkZone 외부에서 결정한 횡단보도 zone 여부.
     *                          호출자가 모르면 false 전달 (안전 기본값).
     *                          // TODO(post-jump-detection): NavigationManager 통합 시 실제 값 연결
     * @return 안내 메시지 + 부가 정보
     */
    fun update(
        userState: UserState,
        currentTimeMs: Long,
        isInCrosswalkZone: Boolean = false,
    ): NavigationResult {
        // 종료 상태 체크
        if (tracker.isFinished) {
            return finishedResult()
        }

        val target = tracker.currentTarget ?: return finishedResult()
        val location = userState.location

        // Step 1: 거리/방향 계산
        val distance = tracker.distanceToTarget(location.latitude, location.longitude)
        val targetBearing = bearing(
            lat1 = location.latitude,
            lon1 = location.longitude,
            lat2 = target.lat,
            lon2 = target.lon
        )
        val headingDifference = angleDiff(targetBearing, userState.effectiveHeading)

        // Step 2: GPS 점프 감지
        val jumpLevel = jumpDetector.update(location, currentTimeMs)

        // Step 3: 통과 판정 (점프 레벨 기반)
        val didPass = tracker.update(
            distance = distance,
            jumpLevel = jumpLevel,
            headingDiff = headingDifference,
            config = config
        )

        // Step 4: 안내 정책 결정
        val guidanceAction = guidancePolicy.decide(
            jumpLevel = jumpLevel,
            nowMs = currentTimeMs,
            isInCrosswalkZone = isInCrosswalkZone,
        )

        // Step 5: 메시지 생성
        // ANNOUNCE_DEGRADED 일 때는 점프 안내 문구를 우선 발화.
        // 그 외엔 일반 메시지 빌더가 점프 레벨/통과 여부에 따라 적절히 처리.
        val targetForMessage = if (didPass) tracker.currentTarget else target
        val message = if (guidanceAction == JumpGuidanceAction.ANNOUNCE_DEGRADED) {
            MessageBuilder.MSG_GPS_DEGRADED
        } else {
            MessageBuilder.build(
                jumpLevel = jumpLevel,
                distance = distance,
                didPassWaypoint = didPass,
                isFinished = tracker.isFinished,
                currentTarget = targetForMessage,
            )
        }

        // Step 6: 사전 안내 (annotation) 트리거 검사
        // didPass 한 직후라면 distance 가 옛 target 기준이므로 새 target 기준으로 재계산해
        // 사용자 누적 거리가 정확하게 잡히게 한다.
        val distanceForAnnouncement = if (didPass) {
            tracker.distanceToTarget(location.latitude, location.longitude)
        } else {
            distance
        }
        val announcement = pickPendingAnnouncement(distanceForAnnouncement)

        // Step 7: 결과 묶기
        return NavigationResult(
            message = message,
            didPassWaypoint = didPass,
            currentWaypointIndex = tracker.currentIndex,
            jumpLevel = jumpLevel,
            distanceToWaypoint = distance,
            headingDiff = headingDifference,
            isFinished = tracker.isFinished,
            guidanceAction = guidanceAction,
            annotationAnnouncement = announcement,
        )
    }

    /**
     * 현재 사용자 위치를 경로 시작점 기준 누적 거리로 환산.
     *
     * 근사식:
     *   cumulative_user ≈ cumulativeDistances[currentIndex] - distanceToTarget
     *   (currentIndex 의 waypoint 까지의 누적 거리에서, 그 waypoint 까지 남은 거리만큼 빼면
     *    사용자가 그 segment 내에서 어디쯤 있는지가 된다.)
     */
    private fun userCumulativeDistance(distanceToTarget: Float): Double {
        if (cumulativeDistances.isEmpty()) return 0.0
        val idx = tracker.currentIndex.coerceAtMost(cumulativeDistances.size - 1)
        return (cumulativeDistances[idx] - distanceToTarget).coerceAtLeast(0.0)
    }

    /**
     * 곧 도달할 (announce 거리 이내) annotation 중 아직 발화하지 않은 첫 번째 것을 골라
     * 안내 문장을 돌려준다. 한 번 발화한 annotation 은 다시는 돌려주지 않는다.
     */
    private fun pickPendingAnnouncement(distanceToTarget: Float): String? {
        if (annotations.isEmpty()) return null
        val userCum = userCumulativeDistance(distanceToTarget)
        val candidate = annotations.firstOrNull { ann ->
            if (ann.startWaypointIndex in announcedAnnotationIds) return@firstOrNull false
            val triggerDist = announceDistanceFor(ann.type)
            val gap = ann.distanceFromStartM - userCum
            gap in 0.0..triggerDist
        } ?: return null
        announcedAnnotationIds.add(candidate.startWaypointIndex)
        return candidate.announceMessage.takeIf { it.isNotBlank() }
    }

    private fun announceDistanceFor(type: PathSegmentType): Double = when (type) {
        PathSegmentType.SHARP_TURN -> config.announceDistanceSharpM
        PathSegmentType.TURN, PathSegmentType.SLIGHT_TURN -> config.announceDistanceTurnM
        PathSegmentType.INTERNAL_CURVE -> config.announceDistanceCurveM
        else -> config.announceDistanceCurveM
    }

    /**
     * 모든 waypoint를 통과한 종료 상태에서 반환할 결과.
     */
    private fun finishedResult(): NavigationResult = NavigationResult(
        message = MessageBuilder.MSG_ARRIVED_DESTINATION,
        didPassWaypoint = false,
        currentWaypointIndex = tracker.currentIndex,
        jumpLevel = GpsJumpLevel.NORMAL,
        distanceToWaypoint = 0f,
        headingDiff = 0f,
        isFinished = true,
        guidanceAction = JumpGuidanceAction.SILENT,
        annotationAnnouncement = null,
    )

    /**
     * 디버그용 — 현재 진행 상태.
     */
    fun debugInfo(): String = tracker.debugInfo()
}
