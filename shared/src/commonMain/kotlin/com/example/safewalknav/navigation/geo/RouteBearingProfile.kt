package com.example.safewalknav.navigation.geo

import com.example.safewalknav.navigation.tmap.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 경로 폴리라인을 일정 간격으로 나눈 한 구간과 그 구간의 진행 방위각.
 *
 * @param startDist 경로 시작점부터 이 구간 시작까지의 누적 거리 (m)
 * @param endDist   경로 시작점부터 이 구간 끝까지의 누적 거리 (m)
 * @param bearing   이 구간의 진행 방위각 (0~360°, 북=0). 폴리라인에 평행.
 */
data class BearingInterval(
    val startDist: Double,
    val endDist: Double,
    val bearing: Float,
)

/**
 * 경로 폴리라인을 일정 간격(intervalMeters)으로 재샘플링해 만든 "구간별 방위각" 프로파일.
 *
 * 목적: 나침반의 도로 방향 화살표(targetBearing)를 사용자가 현재 밟고 있는
 * 폴리라인 구간에 **평행**하게 맞추기 위함. 굽은 길에서도 lookahead chord 가 아니라
 * 로컬 구간의 접선 방향을 돌려주므로, 사용자가 길을 따라 자연스럽게 정렬할 수 있다.
 *
 * 동작:
 *   - 원본 routePoints 는 간격이 불규칙(수 cm ~ 수십 m)하므로 그대로 쓰면 방위각이 튄다.
 *   - intervalMeters(기본 10m) 단위로 폴리라인 위 점을 보간 샘플링하고,
 *     인접 두 샘플 사이의 방위각을 그 구간 대표 방위각으로 삼아 평활화한다.
 *
 * KMM commonMain — Android/iOS 공통.
 */
class RouteBearingProfile private constructor(
    val intervals: List<BearingInterval>,
    val totalLength: Double,
) {
    val isEmpty: Boolean get() = intervals.isEmpty()

    /**
     * 경로 시작점부터의 누적 거리(distanceAlong)에 해당하는 구간의 방위각.
     * 범위를 벗어나면 첫/마지막 구간 방위각으로 클램프. 비어 있으면 null.
     */
    fun bearingAt(distanceAlong: Double): Float? {
        if (intervals.isEmpty()) return null
        val d = distanceAlong.coerceIn(0.0, totalLength)
        // 구간 수가 많지 않고(보통 수십~수백) 호출이 GPS tick(≈2초)당 1회뿐이라 선형 탐색으로 충분.
        for (iv in intervals) {
            if (d < iv.endDist) return iv.bearing
        }
        return intervals.last().bearing
    }

    companion object {
        /**
         * routePoints 폴리라인으로부터 구간별 방위각 프로파일 생성.
         *
         * @param routePoints 경로 전체 폴리라인 좌표
         * @param intervalMeters 한 구간 길이 (m). 작을수록 곡선 추종이 정밀하지만 잡음에 민감.
         */
        fun build(
            routePoints: List<LatLng>,
            intervalMeters: Double = 10.0,
        ): RouteBearingProfile {
            if (routePoints.size < 2 || intervalMeters <= 0.0) {
                return RouteBearingProfile(emptyList(), 0.0)
            }

            // 원본 폴리라인 누적 거리
            val cum = DoubleArray(routePoints.size)
            for (i in 1 until routePoints.size) {
                cum[i] = cum[i - 1] + distanceBetween(
                    routePoints[i - 1].lat, routePoints[i - 1].lon,
                    routePoints[i].lat, routePoints[i].lon,
                ).toDouble()
            }
            val total = cum[routePoints.size - 1]
            if (total <= 0.0) return RouteBearingProfile(emptyList(), 0.0)

            val intervals = ArrayList<BearingInterval>()
            var d = 0.0
            var startPt = routePoints.first()
            while (d < total - 1e-6) {
                val endDist = minOf(d + intervalMeters, total)
                val endPt = interpolateAt(routePoints, cum, endDist)
                val b = bearing(startPt.lat, startPt.lon, endPt.lat, endPt.lon)
                intervals.add(BearingInterval(d, endDist, b))
                startPt = endPt
                d = endDist
            }
            return RouteBearingProfile(intervals, total)
        }

        /**
         * 폴리라인 위에서 누적 거리 dist 에 해당하는 좌표를 선형 보간.
         */
        private fun interpolateAt(
            points: List<LatLng>,
            cum: DoubleArray,
            dist: Double,
        ): LatLng {
            if (dist <= 0.0) return points.first()
            val total = cum[cum.size - 1]
            if (dist >= total) return points.last()

            // dist 를 포함하는 선분 [i, i+1] 탐색
            var i = 0
            while (i < cum.size - 1 && cum[i + 1] < dist) i++

            val segLen = cum[i + 1] - cum[i]
            val t = if (segLen < 1e-9) 0.0 else (dist - cum[i]) / segLen
            val a = points[i]
            val b = points[i + 1]
            return LatLng(
                lat = a.lat + (b.lat - a.lat) * t,
                lon = a.lon + (b.lon - a.lon) * t,
            )
        }
    }
}

/**
 * routePoints 폴리라인의 각 점까지의 누적 거리 (m). cum[0] = 0.
 * NavigationManager 가 currentRoutePointIndex → distanceAlong 매핑에 사용.
 */
fun cumulativeAlongRoute(points: List<LatLng>): List<Double> {
    if (points.isEmpty()) return emptyList()
    val out = ArrayList<Double>(points.size)
    out.add(0.0)
    var acc = 0.0
    for (i in 1 until points.size) {
        acc += distanceBetween(
            points[i - 1].lat, points[i - 1].lon,
            points[i].lat, points[i].lon,
        ).toDouble()
        out.add(acc)
    }
    return out
}

/**
 * 현재 위치를 선분 [a, b] 에 정사영했을 때, a 로부터의 along-track 거리 (m, 0~선분길이로 클램프).
 *
 * 현재 위도 기준 로컬 ENU 근사 → 2D 내적. 보행자 스케일에서 충분히 정확.
 */
fun alongTrackMeters(
    currentLat: Double,
    currentLon: Double,
    a: LatLng,
    b: LatLng,
): Double {
    val latScale = 111320.0
    val lonScale = 111320.0 * cos(currentLat * PI / 180.0)

    val ax = (a.lon - currentLon) * lonScale
    val ay = (a.lat - currentLat) * latScale
    val bx = (b.lon - currentLon) * lonScale
    val by = (b.lat - currentLat) * latScale

    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-9) return 0.0

    // P=(0,0) (현재 위치). t = (AP·AB)/|AB|^2, AP = P - A = (-ax, -ay)
    val t = ((-ax) * dx + (-ay) * dy) / len2
    val tc = t.coerceIn(0.0, 1.0)
    return tc * sqrt(len2)
}
