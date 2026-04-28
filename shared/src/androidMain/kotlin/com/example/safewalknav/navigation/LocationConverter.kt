package com.example.safewalknav.navigation

import android.location.Location

/**
 * Android `Location` → KMM `GpsLocation` 변환.
 *
 * MainActivity 등 안드로이드 측에서 GPS 콜백으로 받은 Location 을
 * NavigationManager.updateLocation() 에 넘기기 전에 호출.
 *
 * accuracy 가 미지원이면 10f 를 사용한다 (NavigationManager 의 기존 fallback 정책 유지).
 */
fun Location.toGpsLocation(): GpsLocation = GpsLocation(
    latitude = latitude,
    longitude = longitude,
    speed = speed,
    bearing = bearing,
    accuracy = if (hasAccuracy()) accuracy else 10f,
    hasAccuracy = hasAccuracy(),
)
