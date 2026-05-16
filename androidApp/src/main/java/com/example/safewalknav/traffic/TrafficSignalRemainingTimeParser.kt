package com.example.safewalknav.traffic

import org.json.JSONArray
import org.json.JSONObject

data class TrafficSignalRemainingTime(
    val itstId: String,
    val remainSeconds: Int?,
    val rawFieldName: String?,
    val status: TrafficLightStatus
)

enum class TrafficLightStatus {
    GREEN,
    RED_OR_WAIT,
    UNKNOWN
}

object TrafficSignalRemainingTimeParser {

    fun parse(jsonText: String): TrafficSignalRemainingTime? {
        return try {
            val root = JSONObject(jsonText)
            val rows = findFirstArray(root) ?: return null

            if (rows.length() == 0) {
                return null
            }

            val row = rows.getJSONObject(0)

            val itstId = row.optString("itstId", "")

            val pedestrianFields = listOf(
                "ntPdsgRmdrCs",
                "etPdsgRmdrCs",
                "stPdsgRmdrCs",
                "wtPdsgRmdrCs",
                "nePdsgRmdrCs",
                "sePdsgRmdrCs",
                "swPdsgRmdrCs",
                "nwPdsgRmdrCs"
            )

            val remainCandidate = pedestrianFields
                .mapNotNull { field ->
                    val value = row.optNullableInt(field)
                    if (value != null && value > 0) {
                        field to value
                    } else {
                        null
                    }
                }
                .minByOrNull { it.second }

            val remainSeconds = remainCandidate?.second?.let { centiSeconds ->
                centiSeconds / 10
            }

            val status = when {
                remainSeconds != null && remainSeconds > 0 -> TrafficLightStatus.GREEN
                pedestrianFields.any { row.has(it) } -> TrafficLightStatus.RED_OR_WAIT
                else -> TrafficLightStatus.UNKNOWN
            }

            TrafficSignalRemainingTime(
                itstId = itstId,
                remainSeconds = remainSeconds,
                rawFieldName = remainCandidate?.first,
                status = status
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) {
            return null
        }

        val raw = opt(key)

        return when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Double -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    private fun findFirstArray(obj: JSONObject): JSONArray? {
        val keys = obj.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)

            when (value) {
                is JSONArray -> return value
                is JSONObject -> {
                    val nested = findFirstArray(value)
                    if (nested != null) {
                        return nested
                    }
                }
            }
        }

        return null
    }
}