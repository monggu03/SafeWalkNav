package com.example.safewalknav.traffic

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object TrafficSignalXmlParser {

    fun parse(xml: String): List<TrafficSignalEntity> {
        val result = mutableListOf<TrafficSignalEntity>()

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var eventType = parser.eventType

        var id: String? = null
        var x: Double? = null
        var y: Double? = null
        var currentTag: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name

                    if (parser.name == "row") {
                        id = null
                        x = null
                        y = null
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()

                    when (currentTag) {
                        "SIGNAL_ID", "MNG_ID", "ID" -> {
                            if (id == null && text.isNotBlank()) {
                                id = text
                            }
                        }

                        "XCRD" -> x = text.toDoubleOrNull()
                        "YCRD" -> y = text.toDoubleOrNull()
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "row") {
                        val rawX = x
                        val rawY = y

                        if (rawX != null && rawY != null) {
                            val converted =
                                CoordinateConverter.epsg5186ToWgs84(rawX, rawY)

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