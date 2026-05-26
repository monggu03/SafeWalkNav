package com.example.safewalknav.navigation.walking

import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.tmap.Waypoint

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
 *   1. 다음 waypoint가 횡단보도이고 50m 이내 → 진입 직전 (보도 대기 시점부터)
 *   2. 직전 waypoint가 횡단보도이고 30m 이내 → 통과 직후 (인도 복귀까지)
 *
 * 시각장애인은 횡단보도 직전 보도에서 신호등 확인 + 안전 판단해야 하므로
 * 30m 보다 너그럽게 50m. GPS 정확도 ±20m 도 흡수.
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

        // 1) FORWARD: 앞에 있는 가장 가까운 CROSSWALK 가 50m 이내인지 검사.
        //
        // 이전 버그: 기존엔 waypoints[currentWaypointIndex] 가 정확히 CROSSWALK 인 경우만
        //   체크했음. 하지만 currentWaypointIndex 는 "다음 도달할 waypoint" 이고, 그게
        //   CROSSWALK 가 아니라 직전의 TURN/WAYPOINT 일 수 있음. 그 경우 사용자가 횡단보도
        //   50m 안에 들어와도 zone 이 안 켜지고, 결국 사용자가 횡단보도 코앞 (idx 가 advance
        //   되는 ~10m 거리) 에 가서야 zone 활성화 — 50m 사전 안내 윈도우가 사실상 무용지물.
        //
        // 수정: 다음 LOOK_AHEAD 개 waypoint 까지 스캔하여 첫 번째 CROSSWALK 와의 거리 체크.
        val lookAheadEnd = minOf(currentWaypointIndex + LOOK_AHEAD, waypoints.size)
        for (i in currentWaypointIndex until lookAheadEnd) {
            val wp = waypoints[i]
            if (isCrosswalkWaypoint(wp)) {
                val dist = distanceBetween(currentLat, currentLon, wp.lat, wp.lon)
                return dist <= 50f
                // 가장 가까운 CROSSWALK 한 개만 체크 (그 뒤 횡단보도는 더 멀 가능성).
                // 50m 안이면 활성, 밖이면 비활성 — 어느 쪽이든 결정 났으니 즉시 return.
            }
        }

        // 2) BACKWARD: 직전 LOOK_BACK 개 waypoint 안에 CROSSWALK 있고 30m 이내면 활성
        //    (방금 횡단보도를 건너 인도로 진입한 직후의 안전 윈도우)
        val backStart = maxOf(0, currentWaypointIndex - LOOK_BACK)
        for (i in (currentWaypointIndex - 1) downTo backStart) {
            val wp = waypoints[i]
            if (isCrosswalkWaypoint(wp)) {
                val dist = distanceBetween(currentLat, currentLon, wp.lat, wp.lon)
                return dist <= 30f
            }
        }

        return false
    }

/**
 * 앞쪽으로 몇 개의 waypoint 까지 CROSSWALK 를 탐색할지.
 * 5 정도가 적당 — 너무 작으면 (1~2) 기존 버그처럼 zone 활성화 지연,
 * 너무 크면 (10+) 다른 경로의 멀리 있는 CROSSWALK 까지 잡아서 prematurely 활성화.
 */
private const val LOOK_AHEAD = 5

/**
 * 뒤쪽으로 몇 개의 waypoint 까지 방금 지난 CROSSWALK 를 탐색할지.
 * 가상 waypoint 가 끼어들어 있을 수 있어 1보다 크게.
 */
private const val LOOK_BACK = 3
