package com.example.safewalknav.navigation

/**
 * Heading 분석용 CSV 로그 인터페이스.
 *
 * NavigationManager 가 매 GPS 업데이트마다 한 줄씩 기록한다. 실제 저장 매체(파일/메모리/원격)는
 * 플랫폼별 구현체가 결정. 안드로이드는 `AndroidHeadingLogger` (shared/androidMain) 가
 * `getExternalFilesDir(DIRECTORY_DOCUMENTS)` 안에 timestamp 파일명으로 기록.
 *
 * CSV 컬럼:
 *   timestamp, raw_bearing, rotation_vector_heading, route_bearing,
 *   kalman_heading, kalman_gain, speed, accuracy, lat, lon
 *
 * 분석 도구: tools/heading_analysis.py 가 이 CSV 를 읽어 Before/After 시각화.
 */
interface HeadingLogger {

    /** 새 로그 세션 시작 (파일 열기 등). 내비게이션 시작 시 호출. */
    fun open()

    /** 한 줄 기록. open() 전 또는 close() 후 호출되면 no-op. */
    fun write(
        timestamp: Long,
        rawBearing: Float,
        rotationVectorHeading: Float,
        routeBearing: Float,
        kalmanHeading: Float,
        kalmanGain: Double,
        speed: Float,
        accuracy: Float,
        lat: Double,
        lon: Double,
    )

    /** 세션 종료 (파일 flush/close). 내비게이션 종료/도착 시 호출. */
    fun close()
}

/**
 * 로깅을 비활성화하고 싶을 때 사용하는 no-op 구현. NavigationManager 의 기본값.
 * 실제 로깅을 원하면 호출자에서 AndroidHeadingLogger 를 주입.
 */
object NoopHeadingLogger : HeadingLogger {
    override fun open() {}
    override fun write(
        timestamp: Long,
        rawBearing: Float,
        rotationVectorHeading: Float,
        routeBearing: Float,
        kalmanHeading: Float,
        kalmanGain: Double,
        speed: Float,
        accuracy: Float,
        lat: Double,
        lon: Double,
    ) {}
    override fun close() {}
}
