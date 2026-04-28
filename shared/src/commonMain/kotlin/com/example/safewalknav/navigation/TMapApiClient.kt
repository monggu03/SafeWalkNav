package com.example.safewalknav.navigation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * TMap REST API 클라이언트 (Ktor 기반, KMM commonMain).
 *
 * 기존 OkHttp+Gson 구현을 이식한 것이며 호출 인터페이스는 동일하다.
 *   - searchPedestrianRoute: 보행자 경로 탐색
 *   - searchPOI:             POI 키워드 검색 (목적지)
 *   - searchNearbyPOI:       반경 내 주변 POI (도착지 랜드마크)
 *   - reverseGeocode:        좌표 → 주소
 *   - lastError:             마지막 호출의 사용자용 에러 메시지
 *
 * @param appKey TMap 개발자센터에서 발급받은 앱 키. Android 측에서는 BuildConfig.TMAP_APP_KEY 전달.
 *
 * NOTE: HttpClient 는 외부 주입하지 않고 내부에서 생성한다. 외부 주입을 노출하면
 *       app 모듈이 Ktor 의존성을 transitive 하게 보게 되어야 하는데, 그건 다음 단계의
 *       Logger expect/actual 패턴과 함께 정리할 예정.
 */
class TMapApiClient(
    private val appKey: String,
) {

    /** 마지막 API 오류 메시지 (UI에서 구체적 에러 표시용) */
    var lastError: String? = null
        private set

    private val baseUrl = "https://apis.openapi.sk.com/tmap"

    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ========== 공개 API ==========

    /** 보행자 경로 탐색 */
    suspend fun searchPedestrianRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        startName: String = "출발지",
        endName: String = "목적지",
    ): TMapRoute? {
        lastError = null
        return runCatching {
            val response: HttpResponse = httpClient.post("$baseUrl/routes/pedestrian") {
                parameter("version", 1)
                contentType(ContentType.Application.Json)
                headers { append("appKey", appKey) }
                setBody(
                    """
                    {
                        "startX": "$startLon",
                        "startY": "$startLat",
                        "endX": "$endLon",
                        "endY": "$endLat",
                        "startName": "$startName",
                        "endName": "$endName",
                        "reqCoordType": "WGS84GEO",
                        "resCoordType": "WGS84GEO"
                    }
                    """.trimIndent()
                )
            }

            if (!response.status.isSuccess()) {
                lastError = "서버 오류(${response.status.value}). 다시 시도해주세요"
                return@runCatching null
            }

            parsePedestrianRoute(response.bodyAsText())
        }.getOrElse { e ->
            lastError = mapException(e)
            null
        }
    }

    /** POI 검색 (목적지 검색) */
    suspend fun searchPOI(keyword: String): List<POIResult> {
        lastError = null
        return runCatching {
            val response: HttpResponse = httpClient.get("$baseUrl/pois") {
                parameter("version", 1)
                parameter("searchKeyword", keyword)
                parameter("count", 5)
                headers { append("appKey", appKey) }
            }

            if (!response.status.isSuccess()) {
                lastError = "서버 오류(${response.status.value}). 다시 시도해주세요"
                return@runCatching emptyList()
            }

            parsePOIResults(response.bodyAsText())
        }.getOrElse { e ->
            lastError = mapException(e)
            emptyList()
        }
    }

    /**
     * 역지오코딩 (좌표 → 주소/장소명).
     * 목적지 근처에서 "CU편의점 앞입니다" 같은 랜드마크 안내용.
     * 실패 시 null 반환 (lastError 갱신 안 함 — 부가 기능이라 조용히 실패).
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = runCatching {
        val response: HttpResponse = httpClient.get("$baseUrl/geo/reversegeocoding") {
            parameter("version", 1)
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("coordType", "WGS84GEO")
            parameter("addressType", "A10")
            headers { append("appKey", appKey) }
        }
        if (!response.status.isSuccess()) return@runCatching null
        parseReverseGeocode(response.bodyAsText())
    }.getOrNull()

    /** 주변 POI 검색 (도착지 근처 랜드마크). 실패 시 빈 리스트, 조용히 실패. */
    suspend fun searchNearbyPOI(lat: Double, lon: Double, radius: Int = 50): List<POIResult> =
        runCatching {
            val response: HttpResponse = httpClient.get("$baseUrl/pois/search/around") {
                parameter("version", 1)
                parameter("centerLat", lat)
                parameter("centerLon", lon)
                parameter("radius", radius)
                parameter("count", 5)
                headers { append("appKey", appKey) }
            }
            if (!response.status.isSuccess()) return@runCatching emptyList()
            parsePOIResults(response.bodyAsText())
        }.getOrElse { emptyList() }

    // ========== JSON 파싱 ==========

    private fun parsePedestrianRoute(text: String): TMapRoute? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val features = root["features"]?.jsonArray ?: return@runCatching null

        var totalDistance = 0
        var totalTime = 0
        val waypoints = mutableListOf<Waypoint>()
        val routePoints = mutableListOf<LatLng>()

        for (feature in features) {
            val obj = feature.jsonObject
            val geometry = obj["geometry"]?.jsonObject ?: continue
            val properties = obj["properties"]?.jsonObject ?: continue
            val geometryType = properties["geometryType"]?.string()
                ?: geometry["type"]?.string()
                ?: continue

            // 첫 번째 feature에서 전체 거리/시간 추출
            if (totalDistance == 0) {
                totalDistance = properties.int("totalDistance") ?: 0
                totalTime = properties.int("totalTime") ?: 0
            }

            // Point = 안내 포인트 (교차로, 횡단보도 등)
            if (geometryType == "Point") {
                val coords = geometry["coordinates"]?.jsonArray ?: continue
                val lon = (coords[0] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
                val lat = (coords[1] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue

                val turnType = properties.int("turnType") ?: 0
                val description = properties.string("description") ?: ""
                val distance = properties.int("totalDistance") ?: 0
                val roadType = properties.int("roadType") ?: 0

                waypoints.add(
                    Waypoint(
                        lat = lat,
                        lon = lon,
                        turnType = turnType,
                        description = description,
                        distance = distance,
                        roadType = roadType,
                        pointType = classifyPointType(turnType, description)
                    )
                )
            }

            // LineString = 경로 선분 (지도 폴리라인용)
            if (geometryType == "LineString") {
                val coords = geometry["coordinates"]?.jsonArray ?: continue
                for (coord in coords) {
                    val pair = coord.jsonArray
                    val lon = (pair[0] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
                    val lat = (pair[1] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
                    routePoints.add(LatLng(lat, lon))
                }
            }
        }

        TMapRoute(
            totalDistance = totalDistance,
            totalTime = totalTime,
            waypoints = waypoints,
            routePoints = routePoints,
        )
    }.getOrNull()

    private fun parsePOIResults(text: String): List<POIResult> = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val pois = root["searchPoiInfo"]?.jsonObject
            ?.get("pois")?.jsonObject
            ?.get("poi")?.jsonArray
            ?: return@runCatching emptyList()

        val results = mutableListOf<POIResult>()
        for (poiElement in pois) {
            val obj = poiElement.jsonObject
            val name = obj.string("name") ?: continue

            // POI 실좌표(lat/lon) 우선, 없으면 자동차용 매핑좌표(noorLat/noorLon) fallback
            val rawLat = obj.string("lat")?.toDoubleOrNull()
            val rawLon = obj.string("lon")?.toDoubleOrNull()
            val noorLat = obj.string("noorLat")?.toDoubleOrNull()
            val noorLon = obj.string("noorLon")?.toDoubleOrNull()

            val lat = rawLat ?: noorLat ?: continue
            val lon = rawLon ?: noorLon ?: continue

            val address = obj.string("upperAddrName") ?: ""
            val frontLat = obj.string("frontLat")?.toDoubleOrNull()
            val frontLon = obj.string("frontLon")?.toDoubleOrNull()

            results.add(POIResult(name, lat, lon, address, frontLat, frontLon))
        }
        results
    }.getOrElse { emptyList() }

    private fun parseReverseGeocode(text: String): String? = runCatching {
        json.parseToJsonElement(text).jsonObject
            .get("addressInfo")?.jsonObject
            ?.string("fullAddress")
    }.getOrNull()

    /** TMap turnType 코드를 SafeWalk 포인트 유형으로 분류 */
    private fun classifyPointType(turnType: Int, description: String): String = when {
        turnType == 200 -> "DESTINATION"     // 목적지 도착
        turnType in 211..217 -> "CROSSWALK"  // 횡단보도 (모든 방향)
        turnType in 1..8 -> "TURN"           // 방향 전환
        description.contains("횡단보도") -> "CROSSWALK"
        description.contains("계단") -> "STAIRS"
        else -> "WAYPOINT"
    }

    /** Ktor/네트워크 예외를 사용자용 메시지로 변환. OS 무관 메시지만 사용. */
    private fun mapException(e: Throwable): String {
        val msg = e.message ?: ""
        return when {
            // 기존 java.net.UnknownHostException 대체
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("UnresolvedAddress", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true) ->
                "인터넷 연결을 확인하세요"
            // 기존 java.net.SocketTimeoutException 대체
            msg.contains("timeout", ignoreCase = true) ->
                "서버 응답이 없습니다. 다시 시도해주세요"
            else -> "오류가 발생했습니다. 다시 시도해주세요"
        }
    }
}

// ========== JSON 헬퍼 (kotlinx.serialization 트리 탐색용) ==========
// Gson `obj.get("key").asString` 과 비슷한 사용감을 위한 확장.

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun kotlinx.serialization.json.JsonElement.string(): String? =
    (this as? JsonPrimitive)?.contentOrNull
