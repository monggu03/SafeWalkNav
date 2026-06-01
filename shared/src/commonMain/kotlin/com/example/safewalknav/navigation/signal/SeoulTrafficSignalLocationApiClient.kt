package com.example.safewalknav.navigation.signal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

class SeoulTrafficSignalLocationApiClient(
    private val apiKey: String
) {
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
        }
    }

    suspend fun fetchTrafficSignalXmlPages(
        // 2026-06-02 OOM 대응 — 1000 → 500. 한 페이지 XML 크기 절반으로 줄여 메모리 압박 완화.
        // 페이지 수는 약 2배로 늘지만 네트워크 요청 시간은 큰 차이 없음 (HTTP keep-alive).
        pageSize: Int = 500
    ): List<String> {
        val result = mutableListOf<String>()

        var start = 1

        // 호출이 실패해도(iOS ATS 차단, 네트워크 오류 등) Kotlin/Native 가 abort 하지 않도록
        // 모든 예외를 흡수하고 지금까지 모은 결과만 반환한다.
        // Android 는 정상 호출 시 영향 없음.
        try {
            while (true) {
                val end = start + pageSize - 1

                val url =
                    "http://openapi.seoul.go.kr:8088/$apiKey/xml/trafficSafetyA057PInfo/$start/$end/"

                val response = httpClient.get(url)

                if (!response.status.isSuccess()) {
                    break
                }

                val xml = response.bodyAsText()

                if (xml.isBlank()) {
                    break
                }

                result.add(xml)

                if (!xml.contains("<row>")) {
                    break
                }

                start += pageSize
            }
        } catch (e: Exception) {
            // 첫 페이지조차 못 받으면 빈 리스트 반환.
            // (Repository 가 빈 리스트를 빈 캐시로 인식해서 안전하게 동작)
        }

        return result
    }
}
