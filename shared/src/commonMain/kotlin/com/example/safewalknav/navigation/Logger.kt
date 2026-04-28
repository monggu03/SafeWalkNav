package com.example.safewalknav.navigation

/**
 * 플랫폼 무관 로깅.
 *
 * commonMain 코드에서 디버그/경고 메시지를 출력하기 위한 추상화.
 * 각 플랫폼에서 actual 구현으로 OS 의 로깅 시스템에 위임.
 *
 * Android: `android.util.Log` 사용.
 * iOS: `NSLog` 또는 `os_log` (jiminlyy 가 actual 작성).
 *
 * 사용 예:
 *   Logger.d("HeadingLog", "CSV 로그 시작")
 *   Logger.w("HeadingLog", "CSV 로그 쓰기 실패: ${e.message}")
 */
expect object Logger {
    /** 디버그 로그 (개발 중 흐름 추적용) */
    fun d(tag: String, message: String)

    /** 경고 로그 (예외 / 비정상 상황) */
    fun w(tag: String, message: String)
}
