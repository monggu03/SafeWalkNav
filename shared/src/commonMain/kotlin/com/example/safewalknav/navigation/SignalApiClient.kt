package com.example.safewalknav.navigation

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 1. 데이터 클래스 (JSON 변환을 위해 @Serializable 필수)
@Serializable
data class TrafficSignalResponse(
    val status: String? = null,
    val items: List<SignalItem> = emptyList()
)

@Serializable
data class SignalItem(
    val itstId: String,      // 신호등 ID
    val signalState: Int,    // 신호 상태
    val remainTime: Int,     // 남은 시간
    val lat: Double,
    val lon: Double
)

// 2. Ktor 클라이언트 구현
object SignalApiClient {
    private val client = HttpClient {
        // JSON 파싱 설정
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true // API 응답 중 정의하지 않은 필드는 무시
                coerceInputValues = true // null 방지
            })
        }
    }

    suspend fun fetchTrafficSignalData(itstId: String): TrafficSignalResponse {
        // 실제 API 정보 (보내주신 내용 적용)
        val myApiKey = "5973299f-004f-4d44-abf3-f2bcab256e43"
        // 주소에 apikey가 포함되어 있다면 뒤의 파라미터는 떼고 기본 주소만 써도 됩니다.
        val url = "https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/v2xSignalPhaseTimingFusionInformation/1.0"

        return try {
            client.get(url) {
                parameter("apiKey", myApiKey)
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