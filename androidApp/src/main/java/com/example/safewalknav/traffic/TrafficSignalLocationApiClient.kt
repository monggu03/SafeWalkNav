package com.example.safewalknav.traffic

import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URL
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrafficSignalLocationApiClient(
    private val apiKey: String
) {
    suspend fun fetchTrafficSignals(): List<TrafficSignalEntity> {

        val allSignals = mutableListOf<TrafficSignalEntity>()

        var start = 1
        val pageSize = 1000

        return try {

            while (true) {

                val end = start + pageSize - 1

                val url =
                    "http://openapi.seoul.go.kr:8088/$apiKey/xml/trafficSafetyA057PInfo/$start/$end/"

                Log.d(
                    "TrafficSignalAPI",
                    "Fetching signals: $start ~ $end"
                )

                val xml = withContext(Dispatchers.IO) {
                    URL(url).readText()
                }

                val pageSignals = withContext(Dispatchers.Default) {
                    parseXml(xml)
                }

                Log.d(
                    "TrafficSignalAPI",
                    "page signals: ${pageSignals.size}"
                )

                if (pageSignals.isEmpty()) {
                    break
                }

                allSignals.addAll(pageSignals)

                // 마지막 페이지 판정
                if (pageSignals.size < pageSize) {
                    break
                }

                start += pageSize
            }

            Log.d(
                "TrafficSignalAPI",
                "total signals: ${allSignals.size}"
            )

            allSignals

        } catch (e: Exception) {

            Log.e(
                "TrafficSignalAPI",
                "Failed to fetch traffic signals",
                e
            )

            emptyList()
        }
    }

    private fun parseXml(xml: String): List<TrafficSignalEntity> {
        val result = mutableListOf<TrafficSignalEntity>()

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var eventType = parser.eventType

        var id: String? = null
        var x: Double? = null
        var y: Double? = null
        var currentTag: String? = null

        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "row") {
                        id = null
                        x = null
                        y = null
                    }
                }

                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    val text = parser.text.trim()

                    when (currentTag) {
                        "SIGNAL_ID", "MNG_ID", "ID" -> {
                            if (id == null && text.isNotBlank()) id = text
                        }

                        "XCRD" -> x = text.toDoubleOrNull()
                        "YCRD" -> y = text.toDoubleOrNull()
                    }
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name == "row") {
                        val rawX = x
                        val rawY = y

                        if (rawX != null && rawY != null) {
                            val converted = CoordinateConverter.epsg5186ToWgs84(rawX, rawY)

                            result.add(
                                TrafficSignalEntity(
                                    id = id ?: "${rawX}_${rawY}",
                                    xcrd = rawX,
                                    ycrd = rawY,
                                    lat = converted.latitude,
                                    lon = converted.longitude,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    currentTag = null
                }
            }

            eventType = parser.next()
        }

        return result
    }
}