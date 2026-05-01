@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.example.safewalknav.navigation

import kotlinx.cinterop.useContents
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocation

/**
 * iOS CLLocation을 commonMain의 GpsLocation으로 변환하는 확장 함수.
 *
 * iOS에서 GPS 위치를 받으면 CLLocation 객체로 들어오는데, 이를 NavigationManager가
 * 이해할 수 있는 GpsLocation으로 변환해서 넘기기 위한 어댑터.
 */
fun CLLocation.toGpsLocation(): GpsLocation = GpsLocation(
    latitude = coordinate.useContents { latitude },
    longitude = coordinate.useContents { longitude },
    speed = speed.toFloat().coerceAtLeast(0f),
    bearing = course.toFloat().coerceAtLeast(0f),
    accuracy = horizontalAccuracy.toFloat(),
    hasAccuracy = horizontalAccuracy >= 0,
)