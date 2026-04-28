package com.example.safewalknav.navigation

/**
 * 플랫폼 무관 위치 정보.
 *
 * Android `android.location.Location` 또는 iOS `CLLocation` 처럼 OS 별로 다른 위치 객체를
 * commonMain 에서 다루기 위한 추상화. 각 플랫폼에서 자기 OS 의 Location 을
 * 이 데이터 클래스로 변환해서 NavigationManager 에 넘긴다.
 *
 * Android: shared/androidMain 의 `Location.toGpsLocation()` 확장 함수 사용.
 * iOS: 본 마이그레이션 후 jiminlyy 가 CLLocation 에서 변환하는 코드 작성 예정.
 *
 * @param latitude 위도 (도)
 * @param longitude 경도 (도)
 * @param speed 이동 속도 (m/s). 미지원 디바이스에서는 0f.
 * @param bearing 진행 방향 (0~360°). 미지원 시 0f.
 * @param accuracy GPS 수평 정확도 (m). hasAccuracy 가 false 면 fallback 값(10f) 권장.
 * @param hasAccuracy GPS accuracy 신뢰 여부.
 */
data class GpsLocation(
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val bearing: Float,
    val accuracy: Float,
    val hasAccuracy: Boolean,
)
