package com.example.safewalknav.navigation

/**
 * 플랫폼 무관 현재 시각 (Unix epoch millis).
 *
 * commonMain 코드에서 시각 기록/타이머가 필요할 때 사용. 각 플랫폼에서 actual 로 위임.
 *   Android: System.currentTimeMillis()
 *   iOS: NSDate().timeIntervalSince1970 * 1000 (jiminlyy 가 actual 작성)
 */
expect fun currentTimeMillis(): Long
