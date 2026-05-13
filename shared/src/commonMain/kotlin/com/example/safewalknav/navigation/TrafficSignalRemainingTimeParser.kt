package com.example.safewalknav.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SignalRemainingInfo(
    val direction: String,
    val stateName: String,
    val isWalkGreen: Boolean,
    val remainingSeconds: Int?
)

object TrafficSignalRemainingTimeParser {

    private val pedestrianDirections = listOf(
        "nt", "ne", "et", "se", "st", "sw", "wt", "nw"
    )

    fun parse(rawJson: String): List<SignalRemainingInfo> {
        return try {
            val root = Json.parseToJsonElement(rawJson)
            val array = root.jsonArray

            if (array.isEmpty()) return emptyList()

            val obj = array[0].jsonObject

            pedestrianDirections.mapNotNull { dir ->
                val stateName = obj["${dir}PdsgStatNm"]
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf { it.isNotBlank() }

                val remainingSeconds = obj["${dir}PdsgRmdrCs"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toIntOrNull()
                    ?.let { it / 10 }

                if (stateName == null && remainingSeconds == null) {
                    null
                } else {
                    SignalRemainingInfo(
                        direction = dir,
                        stateName = stateName ?: "상태없음",
                        isWalkGreen =
                            stateName?.contains("녹색") == true ||
                                    stateName?.contains("Green", ignoreCase = true) == true,
                        remainingSeconds = remainingSeconds
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}