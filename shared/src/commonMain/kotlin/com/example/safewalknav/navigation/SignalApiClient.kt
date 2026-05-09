package com.example.safewalknav.navigation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 서울 T-Data 신호제어기 잔여시간 API 클라이언트.
 *
 * 보안 주의:
 *   API 키는 절대 코드에 하드코딩 금지. 생성자로 주입받음.
 *   Android: local.properties → BuildConfig.T_DATA_API_KEY → MainActivity 가 주입.
 *   iOS:     Info.plist 또는 별도 secret 파일 → AppDependencies 가 주입.
 *
 * 응답 스키마는 T-Data 포털의 출력인자 표를 기준으로 검증 필요.
 * 현재 [SignalItem] 의 필드 (signalState/remainTime/lat/lon) 가 실제 응답과
 * 일치하는지 확인 안 됨 — 실 호출 + 응답 dump 로 매핑 정합성 검증해야 함.
 */
@Serializable
data class TrafficSignalResponse(
    val status: String? = null,
    val items: List<SignalItem> = emptyList()
)

@Serializable
data class SignalItem(
    val itstId: String,      // 신호제어기 ID
    val signalState: Int,    // 신호 상태 (스키마 검증 필요)
    val remainTime: Int,     // 남은 시간 (단위 검증 필요 — 초/센티초)
    val lat: Double,
    val lon: Double
)

class SignalApiClient(private val apiKey: String) {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true   // API 응답 중 정의되지 않은 필드는 무시
                coerceInputValues = true   // null 방지
            })
        }
    }

    suspend fun fetchTrafficSignalData(itstId: String): TrafficSignalResponse {
        if (apiKey.isBlank()) {
            return TrafficSignalResponse(status = "ERROR_NO_API_KEY")
        }

        val url = "https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/" +
                "v2xSignalPhaseTimingFusionInformation/1.0"

        return try {
            client.get(url) {
                parameter("apiKey", apiKey)
                parameter("itstId", itstId)
                parameter("type", "json")
                parameter("pageNo", 1)
                parameter("numOfRows", 10)
            }.body()
        } catch (e: Exception) {
            e.printStackTrace()
            TrafficSignalResponse(status = "ERROR")
        }
    }
}