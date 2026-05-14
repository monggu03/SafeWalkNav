// 📁 shared/commonMain/.../navigation/GuidanceStrategy.kt

package com.example.safewalknav.navigation

data class GuidanceConfig(
    val preAlertDistance: Int,      // 몇 미터 전부터 사전 안내
    val repeatIntervalMs: Long,    // 안내 반복 주기
    val useVibration: Boolean,     // 진동 사용 여부
    val useTone: Boolean,          // 경고음 사용 여부
    val announcementPrefix: String // TTS 앞에 붙일 경고 문구
)

object GuidanceStrategy {

    fun forSegment(risk: SegmentRisk): GuidanceConfig {
        return when (risk.dangerLevel) {
            DangerLevel.DANGER -> GuidanceConfig(
                preAlertDistance = 50,       // 50m 전부터 안내
                repeatIntervalMs = 3000,    // 3초마다 반복
                useVibration = true,
                useTone = true,
                announcementPrefix = "주의. "
            )
            DangerLevel.CAUTION -> GuidanceConfig(
                preAlertDistance = 30,       // 30m 전 (기존과 동일)
                repeatIntervalMs = 5000,    // 5초마다 반복
                useVibration = true,
                useTone = false,
                announcementPrefix = ""
            )
            DangerLevel.SAFE -> GuidanceConfig(
                preAlertDistance = 20,       // 최소한의 안내
                repeatIntervalMs = 10000,   // 10초마다
                useVibration = false,
                useTone = false,
                announcementPrefix = ""
            )
        }
    }
}