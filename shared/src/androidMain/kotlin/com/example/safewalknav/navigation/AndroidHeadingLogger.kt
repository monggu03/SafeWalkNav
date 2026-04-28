package com.example.safewalknav.navigation

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * `HeadingLogger` Android 구현 — 외부 저장소 디렉토리에 timestamp 파일명으로 CSV 기록.
 *
 * MainActivity 사용 예:
 *   val logger = AndroidHeadingLogger(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!)
 *   navigationManager = NavigationManager(TMapApiClient(...), logger)
 *
 * @param directory 로그 파일을 둘 디렉토리. app-scoped 경로(권한 불필요)를 권장.
 */
class AndroidHeadingLogger(
    private val directory: File,
) : HeadingLogger {

    private var writer: BufferedWriter? = null

    override fun open() {
        close()
        try {
            if (!directory.exists()) directory.mkdirs()
            val file = File(directory, "SafeWalk_heading_log_${System.currentTimeMillis()}.csv")
            val w = BufferedWriter(FileWriter(file))
            w.write(
                "timestamp,raw_bearing,rotation_vector_heading,route_bearing," +
                        "kalman_heading,kalman_gain,speed,accuracy,lat,lon"
            )
            w.newLine()
            writer = w
            Logger.d("HeadingLog", "CSV 로그 시작: ${file.absolutePath}")
        } catch (e: Exception) {
            Logger.w("HeadingLog", "CSV 로그 초기화 실패: ${e.message}")
            writer = null
        }
    }

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
    ) {
        val w = writer ?: return
        try {
            val line = "$timestamp,$rawBearing,$rotationVectorHeading,$routeBearing," +
                    "$kalmanHeading,$kalmanGain,$speed,$accuracy,$lat,$lon"
            w.write(line)
            w.newLine()
            // 강제 종료 대비 즉시 flush (1Hz 수준이라 오버헤드 무시 가능)
            w.flush()
        } catch (e: Exception) {
            Logger.w("HeadingLog", "CSV 로그 쓰기 실패: ${e.message}")
        }
    }

    override fun close() {
        val w = writer ?: return
        try {
            w.flush()
            w.close()
        } catch (e: Exception) {
            Logger.w("HeadingLog", "CSV 로그 종료 실패: ${e.message}")
        }
        writer = null
    }
}
