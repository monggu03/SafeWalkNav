package com.example.safewalknav.navigation

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
        pageSize: Int = 1000
    ): List<String> {
        val result = mutableListOf<String>()

        var start = 1

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

        return result
    }
}