package com.example.safewalknav.navigation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * 서울 T-Data 신호 API 클라이언트.
 *
 * 역할:
 * 1. 교차로 Map API 호출
 * 2. 신호제어기 잔여시간 API 호출
 *
 * API KEY는 Android에서 BuildConfig.T_DATA_API_KEY로 주입한다.
 * 이 파일은 API 호출만 담당하고, JSON 파싱은 androidApp에서 처리한다.
 */
class SignalApiClient(
    private val apiKey: String
) {
    private val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
        }
    }

    /**
     * 교차로 Map 정보 API 호출.
     *
     * 반환값: raw JSON 문자열
     */
    suspend fun fetchIntersectionData(): String? {
        if (apiKey.isBlank()) {
            return "ERROR_NO_API_KEY"
        }

        val url = "http://t-data.seoul.go.kr/apig/apiman-gateway/tapi/" +
                "v2xCrossroadMapInformation/1.0"

        return try {
            val response = client.get(url) {
                parameter("apikey", apiKey)
            }

            val body = response.bodyAsText()

            if (!response.status.isSuccess()) {
                return "ERROR_HTTP_${response.status.value}: ${body.take(300)}"
            }

            body
        } catch (e: Exception) {
            "ERROR_EXCEPTION: ${e::class.simpleName}: ${e.message}"
        }
    }

    /**
     * 신호제어기 잔여시간 API 호출.
     *
     * @param itstId 교차로 ID
     * 반환값: raw JSON 문자열
     */
    suspend fun fetchSignalRemainingData(
        itstId: String,
        pageNo: Int = 1,
        numOfRows: Int = 10
    ): String? {
        if (apiKey.isBlank()) {
            return "ERROR_NO_API_KEY"
        }

        val url = "https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/" +
                "v2xSignalPhaseTimingFusionInformation/1.0"

        return try {
            val response = client.get(url) {
                parameter("apikey", apiKey)
                parameter("type", "json")
                parameter("pageNo", pageNo)
                parameter("numOfRows", numOfRows)
                parameter("itstId", itstId)
            }

            val body = response.bodyAsText()

            if (!response.status.isSuccess()) {
                return "ERROR_HTTP_${response.status.value}: ${body.take(300)}"
            }
            body

        } catch (e: Exception) {
            "ERROR_EXCEPTION: ${e::class.simpleName}: ${e.message}"
        }
    }
}