package com.example.safewalknav.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class TrafficIntersection(
    val itstId: String,
    val itstNm: String?,
    val lat: Double,
    val lon: Double
)

object TrafficIntersectionParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(jsonText: String): List<TrafficIntersection> {
        return try {
            val root = json.parseToJsonElement(jsonText)

            val array = when (root) {
                is JsonArray -> root
                is JsonObject -> findFirstArray(root) ?: return emptyList()
                else -> return emptyList()
            }

            array.mapNotNull { element ->
                val obj = element.jsonObject

                val itstId = obj.string("itstId") ?: return@mapNotNull null
                val itstNm = obj.string("itstNm")
                val lat = obj.double("mapCtptIntLat") ?: return@mapNotNull null
                val lon = obj.double("mapCtptIntLot") ?: return@mapNotNull null

                TrafficIntersection(
                    itstId = itstId,
                    itstNm = itstNm,
                    lat = lat,
                    lon = lon
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun findNearest(
        intersections: List<TrafficIntersection>,
        lat: Double,
        lon: Double,
        radiusMeters: Float = 100f
    ): TrafficIntersection? {
        return intersections
            .map { intersection ->
                intersection to distanceBetween(
                    lat,
                    lon,
                    intersection.lat,
                    intersection.lon
                )
            }
            .filter { (_, dist) -> dist <= radiusMeters }
            .minByOrNull { (_, dist) -> dist }
            ?.first
    }

    private fun findFirstArray(obj: JsonObject): JsonArray? {
        for ((_, value) in obj) {
            when (value) {
                is JsonArray -> return value

                is JsonObject -> {
                    val nested = findFirstArray(value)
                    if (nested != null) {
                        return nested
                    }
                }

                else -> {
                    // JsonPrimitive, JsonNull 등은 무시
                }
            }
        }

        return null
    }

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.double(key: String): Double? {
        return (this[key] as? JsonPrimitive)?.doubleOrNull
    }
}