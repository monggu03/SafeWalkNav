package com.example.safewalknav.navigation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 두 GPS 좌표 사이의 방위각 (0~360도, 북=0, 동=90, 남=180, 서=270).
 *
 * Great-circle bearing 공식 — `android.location.Location.bearingTo()`와 동일한 결과를
 * 순수 Kotlin으로 계산해서 commonMain에 둘 수 있게 한다.
 *
 * KMM commonMain — Android/iOS 공통.
 */
fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val lat1Rad = lat1 * PI / 180.0
    val lat2Rad = lat2 * PI / 180.0
    val deltaLon = (lon2 - lon1) * PI / 180.0

    val y = sin(deltaLon) * cos(lat2Rad)
    val x = cos(lat1Rad) * sin(lat2Rad) -
            sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)

    val rad = atan2(y, x)
    val deg = rad * 180.0 / PI
    return ((deg + 360.0) % 360.0).toFloat()
}

/**
 * 두 각도(degree) 간의 부호 있는 차이 (-180 ~ +180).
 *
 * 양수 결과 = `current` 기준으로 `target`이 오른쪽(시계 방향).
 * 360도 경계 자동 처리.
 *
 * 사용 예:
 *   - 진행 방향(userBearing)과 경로 방향(routeBearing) 비교
 *   - 코너 각도 계산
 */
fun angleDiff(target: Float, current: Float): Float {
    var d = target - current
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    return d
}

/**
 * 두 GPS 좌표 사이의 great-circle 거리 (m).
 *
 * Haversine 공식 사용. 안드로이드 `Location.distanceBetween()` 대비 0.5% 이내 오차로
 * 보행 안내에 충분한 정확도. WGS84 ellipsoid가 아닌 구면 가정.
 *
 * KMM commonMain — Android/iOS 공통.
 */
fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val earthRadius = 6371000.0  // 지구 반지름 (m)
    val phi1 = lat1 * PI / 180.0
    val phi2 = lat2 * PI / 180.0
    val dPhi = (lat2 - lat1) * PI / 180.0
    val dLambda = (lon2 - lon1) * PI / 180.0

    val sinDPhi = sin(dPhi / 2.0)
    val sinDLambda = sin(dLambda / 2.0)
    val a = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return (earthRadius * c).toFloat()
}
