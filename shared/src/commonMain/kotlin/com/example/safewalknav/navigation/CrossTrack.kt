package com.example.safewalknav.navigation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 현재 경로 선분에 대한 부호 있는 수직 이탈 (cross-track error, 미터).
 *
 * 완만한 곡선 도로에서 사용자가 직진만 하면 경로 선에서 점점 벗어나게 되는데,
 * bearing 차이가 아직 작을 때에도 이 측면 드리프트는 감지 가능하다.
 *
 * 부호 규약:
 *   - 양수 = 사용자가 경로의 왼쪽에 있음 → 안내: "오른쪽으로 가세요"
 *   - 음수 = 사용자가 경로의 오른쪽에 있음 → 안내: "왼쪽으로 가세요"
 *   - 0 = 경로 위
 *
 * 계산 방식: 현재 위도 기준 로컬 ENU(East-North-Up) 근사 → 2D cross product.
 * 보행자 스케일(수십~수백 m)에서는 충분히 정확하다.
 *
 * KMM commonMain — Android/iOS 공통.
 *
 * @param currentLat 현재 위도
 * @param currentLon 현재 경도
 * @param routePoints 경로 전체 폴리라인
 * @param currentRoutePointIndex 현재 사용자가 위치한 segment 시작 인덱스
 * @return 부호 있는 수직 이탈 (m). 경로 정보 부족 시 0f.
 */
fun computeSignedCrossTrack(
    currentLat: Double,
    currentLon: Double,
    routePoints: List<LatLng>,
    currentRoutePointIndex: Int
): Float {
    if (routePoints.size < 2) return 0f

    val idx = currentRoutePointIndex.coerceAtMost(routePoints.size - 2)
    val a = routePoints[idx]
    val b = routePoints[idx + 1]

    // 현재 위도 기준 로컬 ENU 근사 (x=east, y=north, 단위: m)
    val latScale = 111320.0
    val lonScale = 111320.0 * cos(currentLat * PI / 180.0)

    val ax = (a.lon - currentLon) * lonScale
    val ay = (a.lat - currentLat) * latScale
    val bx = (b.lon - currentLon) * lonScale
    val by = (b.lat - currentLat) * latScale

    val dx = bx - ax
    val dy = by - ay
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.001) return 0f

    // P=(0,0) 기준 AP = (-ax,-ay), cross(AB, AP) = dx*(-ay) - dy*(-ax) = dy*ax - dx*ay
    val cross = dy * ax - dx * ay
    return (cross / len).toFloat()
}
