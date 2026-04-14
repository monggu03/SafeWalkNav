package com.example.safewalknav.navigation

import android.location.Location
import android.util.Log
import com.example.safewalknav.BuildConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TMap REST API 클라이언트
 * 보행자 경로 탐색, POI 검색, 역지오코딩 담당
 */
class TMapApiClient {

    // 마지막 API 오류 메시지 (UI에서 구체적 에러 표시용)
    var lastError: String? = null
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val appKey = BuildConfig.TMAP_APP_KEY
    private val baseUrl = "https://apis.openapi.sk.com/tmap"

    /**
     * 보행자 경로 탐색
     */
    suspend fun searchPedestrianRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        startName: String = "출발지",
        endName: String = "목적지"
    ): TMapRoute? = withContext(Dispatchers.IO) {
        lastError = null
        try {
            val url = "$baseUrl/routes/pedestrian?version=1"

            val json = """
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

            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("appKey", appKey)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                lastError = "서버 오류(${response.code}). 다시 시도해주세요"
                return@withContext null
            }

            parsePedestrianRoute(responseBody)
        } catch (e: java.net.UnknownHostException) {
            lastError = "인터넷 연결을 확인하세요"
            null
        } catch (e: java.net.SocketTimeoutException) {
            lastError = "서버 응답이 없습니다. 다시 시도해주세요"
            null
        } catch (e: Exception) {
            lastError = "오류가 발생했습니다. 다시 시도해주세요"
            null
        }
    }

    /**
     * POI 검색 (목적지 검색)
     */
    suspend fun searchPOI(keyword: String): List<POIResult> = withContext(Dispatchers.IO) {
        lastError = null
        try {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val url = "$baseUrl/pois?version=1&searchKeyword=$encodedKeyword&count=5"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("appKey", appKey)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()

            if (!response.isSuccessful) {
                lastError = "서버 오류(${response.code}). 다시 시도해주세요"
                return@withContext emptyList()
            }

            parsePOIResults(responseBody)
        } catch (e: java.net.UnknownHostException) {
            lastError = "인터넷 연결을 확인하세요"
            emptyList()
        } catch (e: java.net.SocketTimeoutException) {
            lastError = "서버 응답이 없습니다. 다시 시도해주세요"
            emptyList()
        } catch (e: Exception) {
            lastError = "오류가 발생했습니다. 다시 시도해주세요"
            emptyList()
        }
    }

    /**
     * 역지오코딩 (좌표 → 주소/장소명)
     * 목적지 근처에서 "CU편의점 앞입니다" 같은 랜드마크 안내용
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/geo/reversegeocoding?" +
                    "version=1&lat=$lat&lon=$lon&coordType=WGS84GEO&addressType=A10"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("appKey", appKey)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) return@withContext null

            parseReverseGeocode(responseBody)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 주변 POI 검색 (도착지 근처 랜드마크 찾기)
     */
    suspend fun searchNearbyPOI(lat: Double, lon: Double, radius: Int = 50): List<POIResult> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/pois/search/around?" +
                        "version=1&centerLat=$lat&centerLon=$lon&radius=$radius&count=5"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("appKey", appKey)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext emptyList()

                if (!response.isSuccessful) return@withContext emptyList()

                parsePOIResults(responseBody)
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ========== JSON 파싱 ==========

    private fun parsePedestrianRoute(json: String): TMapRoute? {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val features = root.getAsJsonArray("features")

            var totalDistance = 0
            var totalTime = 0
            val waypoints = mutableListOf<Waypoint>()
            val routePoints = mutableListOf<LatLng>()

            for (feature in features) {
                val obj = feature.asJsonObject
                val geometry = obj.getAsJsonObject("geometry")
                val properties = obj.getAsJsonObject("properties")
                val geometryType = geometry.get("type").asString

                // 첫 번째 feature에서 전체 거리/시간 추출 (Point든 LineString이든)
                if (totalDistance == 0) {
                    totalDistance = properties.get("totalDistance")?.asInt ?: 0
                    totalTime = properties.get("totalTime")?.asInt ?: 0
                }

                // Point = 안내 포인트 (교차로, 횡단보도 등)
                if (geometryType == "Point") {
                    val coords = geometry.getAsJsonArray("coordinates")
                    val lon = coords[0].asDouble
                    val lat = coords[1].asDouble

                    val turnType = properties.get("turnType")?.asInt ?: 0
                    val description = properties.get("description")?.asString ?: ""
                    val distance = properties.get("totalDistance")?.asInt ?: 0

                    waypoints.add(
                        Waypoint(
                            lat = lat,
                            lon = lon,
                            turnType = turnType,
                            description = description,
                            distance = distance,
                            roadType = properties.get("roadType")?.asInt ?: 0,
                            pointType = classifyPointType(turnType, description)
                        )
                    )
                }

                // LineString = 경로 선분 (지도에 그리기용)
                if (geometryType == "LineString") {
                    val coords = geometry.getAsJsonArray("coordinates")
                    for (coord in coords) {
                        val pair = coord.asJsonArray
                        routePoints.add(LatLng(pair[1].asDouble, pair[0].asDouble))
                    }
                }
            }

            return TMapRoute(
                totalDistance = totalDistance,
                totalTime = totalTime,
                waypoints = waypoints,
                routePoints = routePoints
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun parsePOIResults(json: String): List<POIResult> {
        val results = mutableListOf<POIResult>()
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val searchPoiInfo = root.getAsJsonObject("searchPoiInfo")
            val pois = searchPoiInfo.getAsJsonObject("pois").getAsJsonArray("poi")

            for (poi in pois) {
                val obj = poi.asJsonObject
                val name = obj.get("name").asString

                // POI 실좌표(lat/lon) 우선, 없으면 자동차용 매핑좌표(noorLat/noorLon) fallback
                val rawLat = obj.get("lat")?.asString?.toDoubleOrNull()
                val rawLon = obj.get("lon")?.asString?.toDoubleOrNull()
                val noorLat = obj.get("noorLat")?.asString?.toDoubleOrNull()
                val noorLon = obj.get("noorLon")?.asString?.toDoubleOrNull()

                val lat = rawLat ?: noorLat ?: continue
                val lon = rawLon ?: noorLon ?: continue

                val address = obj.get("upperAddrName")?.asString ?: ""
                val frontLat = obj.get("frontLat")?.asString?.toDoubleOrNull()
                val frontLon = obj.get("frontLon")?.asString?.toDoubleOrNull()

                if (rawLat != null && rawLon != null && noorLat != null && noorLon != null) {
                    val delta = FloatArray(1)
                    Location.distanceBetween(rawLat, rawLon, noorLat, noorLon, delta)
                    Log.d(
                        "TMapPOI",
                        "$name  raw=($rawLat,$rawLon)  noor=($noorLat,$noorLon)  " +
                                "front=($frontLat,$frontLon)  Δ(raw↔noor)=${delta[0].toInt()}m"
                    )
                } else {
                    Log.d("TMapPOI", "$name  usingFallback=${rawLat == null}  used=($lat,$lon)")
                }

                results.add(POIResult(name, lat, lon, address, frontLat, frontLon))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    private fun parseReverseGeocode(json: String): String? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val addressInfo = root.getAsJsonObject("addressInfo")
            addressInfo.get("fullAddress")?.asString
        } catch (e: Exception) {
            null
        }
    }

    /**
     * TMap turnType 코드를 SafeWalk 포인트 유형으로 분류
     */
    private fun classifyPointType(turnType: Int, description: String): String {
        return when {
            turnType == 200 -> "DESTINATION"     // 목적지 도착
            turnType in 211..217 -> "CROSSWALK"  // 횡단보도 (모든 방향)
            turnType in 1..8 -> "TURN"           // 방향 전환
            description.contains("횡단보도") -> "CROSSWALK"
            description.contains("계단") -> "STAIRS"
            else -> "WAYPOINT"
        }
    }
}

/**
 * POI 검색 결과
 */
data class POIResult(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String,
    val frontLat: Double? = null,
    val frontLon: Double? = null
)
