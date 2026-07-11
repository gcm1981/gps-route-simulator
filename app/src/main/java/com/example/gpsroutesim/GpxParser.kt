package com.example.gpsroutesim

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parser minimale per file GPX (tag <trkpt> dentro <trkseg>/<trk>,
 * oppure <rtept> dentro <rte>). Estrae lat, lon, elevation e time se presenti.
 */
object GpxParser {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parse(input: InputStream): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()

        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var eventType = parser.eventType
        var inPoint = false
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var timeMs: Long? = null
        var currentTag: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = tagName
                    if (tagName == "trkpt" || tagName == "rtept" || tagName == "wpt") {
                        inPoint = true
                        ele = null
                        timeMs = null
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inPoint) {
                        val text = parser.text?.trim()
                        if (!text.isNullOrEmpty()) {
                            when (currentTag) {
                                "ele" -> ele = text.toDoubleOrNull()
                                "time" -> timeMs = parseIsoTime(text)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName == "trkpt" || tagName == "rtept" || tagName == "wpt") {
                        points.add(RoutePoint(lat, lon, timeMs, ele))
                        inPoint = false
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }

        return points
    }

    private fun parseIsoTime(text: String): Long? {
        return try {
            // Rimuove eventuale 'Z' finale gestita separatamente, e i millisecondi extra
            val cleaned = text.replace("Z", "").substringBefore(".")
            isoFormat.parse(cleaned)?.time
        } catch (e: Exception) {
            null
        }
    }
}

private fun String.toDoubleOrNull(): Double? = try {
    this.toDouble()
} catch (e: Exception) {
    null
}
