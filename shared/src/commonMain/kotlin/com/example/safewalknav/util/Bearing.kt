package com.example.safewalknav.util

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 두 GPS 좌표 사이의 방위각 (0~360도, 북=0, 동=90, 남=180, 서=270).
 *
 * KMM POC: 이 함수가 shared 모듈에서 안드로이드 측으로 호출 가능한지 검증한다.
 * 본 마이그레이션 시 NavigationManager.bearing()을 이걸로 교체할 예정.
 *
 * `android.location.Location` 같은 OS 의존이 없는 순수 Kotlin 수학 함수라 commonMain에 둠.
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
