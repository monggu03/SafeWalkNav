package com.example.safewalknav.navigation

/**
 * waypoint가 횡단보도 관련인지 판정.
 *
 * 판정 기준:
 *   - pointType == "CROSSWALK"
 *   - turnType ∈ 211..217 — T-Map 횡단보도 안내 코드 그룹
 *     (211 횡단보도, 212~217 좌/우/8/10/2/4시 방향 횡단보도)
 *
 * KMM commonMain — Android/iOS 공통.
 */
fun isCrosswalkWaypoint(wp: Waypoint): Boolean {
    return wp.pointType == "CROSSWALK" || wp.turnType in 211..217
}

/**
 * 현재 위치가 횡단보도 구간(진입 직전 ~ 통과 직후) 안에 있는지.
 *
 * 활성화 윈도우:
 *   1. 다음 waypoint가 횡단보도이고 30m 이내 → 진입 직전
 *   2. 직전 waypoint가 횡단보도이고 20m 이내 → 통과 직후 (인도 복귀까지)
 *
 * 이 윈도우 안에서는 상위 레벨 안내 로직이 임계값을 강화해서
 * 작은 쏠림도 즉시 보정 안내한다.
 *
 * KMM commonMain — Android/iOS 공통.
 *
 * @param currentLat 현재 위도
 * @param currentLon 현재 경도
 * @param waypoints 경로의 waypoint 리스트
 * @param currentWaypointIndex 현재 추적 중인 waypoint 인덱스 (다음 도달 예정)
 * @return 횡단보도 윈도우 안 여부
 */
fun isOnCrosswalkSegment(
    currentLat: Double,
    currentLon: Double,
    waypoints: List<Waypoint>,
    currentWaypointIndex: Int
): Boolean {
    if (waypoints.isEmpty()) return false

    // 1) 다음 waypoint = 진입 예정 횡단보도
    if (currentWaypointIndex < waypoints.size) {
        val next = waypoints[currentWaypointIndex]
        if (isCrosswalkWaypoint(next)) {
            val dist = distanceBetween(currentLat, currentLon, next.lat, next.lon)
            if (dist <= 30f) return true
        }
    }

    // 2) 직전 waypoint = 방금 통과한 횡단보도
    val prevIdx = currentWaypointIndex - 1
    if (prevIdx in waypoints.indices) {
        val prev = waypoints[prevIdx]
        if (isCrosswalkWaypoint(prev)) {
            val dist = distanceBetween(currentLat, currentLon, prev.lat, prev.lon)
            if (dist <= 20f) return true
        }
    }

    return false
}
