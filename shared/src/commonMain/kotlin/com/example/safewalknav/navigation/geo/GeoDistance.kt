package com.example.safewalknav.navigation.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 두 GPS 좌표 사이의 great-circle 거리 (m).
 *
 * Haversine 공식. WGS84 ellipsoid가 아닌 구면 가정이나 보행 안내엔 충분(오차 ~0.5%).
 * 원래 geo/BearingMath.kt 에 있던 함수만 이번 MVP 범위(POI 거리필터·횡단보도 접근판정)로
 * 최소 복원한 것 — bearing/angleDiff 등 heading 관련 함수는 되살리지 않는다.
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
