package uk.org.baker_net.gpswaypoint.util

import uk.org.baker_net.gpswaypoint.model.Waypoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * GpxParser.kt
 *
 * Parses a GPX 1.1 file and extracts an ordered list of waypoints.
 *
 * Supported GPX elements:
 *   <wpt>  – individual waypoints (processed in document order)
 *   <rte> / <rtept> – route points
 *   <trk> / <trkseg> / <trkpt> – track points
 *
 * All three element types are merged into a single list preserving document order,
 * which allows GPX files exported by common apps (Garmin, OsmAnd, Komoot, etc.)
 * to be used without modification.
 *
 * This parser has no Android UI dependencies and can be used from a background thread.
 */
object GpxParser {

    /**
     * Parses the supplied GPX input stream and returns a list of [Waypoint] objects.
     *
     * Input:
     *   @param stream An open [InputStream] positioned at the start of a GPX file.
     *                 The caller is responsible for closing the stream after this call.
     *
     * Output:
     *   @return Ordered, non-null list of [Waypoint] objects.  May be empty if the
     *           GPX file contains no recognised point elements.
     *
     * @throws org.xmlpull.v1.XmlPullParserException if the XML is malformed.
     * @throws java.io.IOException on read errors.
     */
    fun parse(stream: InputStream, parser: XmlPullParser): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        // Track state through nested elements
        var inPointElement = false    // true inside <wpt>, <rtept>, or <trkpt>
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentName: String? = null
        var currentEle: Double? = null
        var captureText = false
        val textBuffer = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag == "wpt" || tag == "rtept" || tag == "trkpt") {
                        inPointElement = true
                        currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        currentName = null
                        currentEle = null
                    } else if (inPointElement && (tag == "name" || tag == "ele")) {
                        captureText = true
                        textBuffer.clear()
                    }
                }

                XmlPullParser.TEXT -> {
                    if (captureText) textBuffer.append(parser.text)
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name
                    if (captureText) {
                        val text = textBuffer.toString().trim()
                        when (tag) {
                            "name" -> currentName = text
                            "ele"  -> currentEle = text.toDoubleOrNull()
                        }
                        captureText = false
                    }
                    if (tag == "wpt" || tag == "rtept" || tag == "trkpt") {
                        val lat = currentLat
                        val lon = currentLon
                        if (lat != null && lon != null) {
                            waypoints.add(
                                Waypoint(
                                    name      = currentName ?: "WP${waypoints.size + 1}",
                                    latitude  = lat,
                                    longitude = lon,
                                    elevation = currentEle
                                )
                            )
                        }
                        inPointElement = false
                    }
                }
            }
            eventType = parser.next()
        }
        if (waypoints.isEmpty()) {
            return waypoints
        }
        var prevLat: Double? = null
        var prevLon: Double? = null
        var totalDist = 0.0f
        val wptIterator = waypoints.listIterator(waypoints.size)
        while (wptIterator.hasPrevious()) {
            val wpt = wptIterator.previous()
            if ((prevLat != null) && (prevLon != null)) {
                totalDist += GeoUtils.haversineDistance(prevLat, prevLon,
                    wpt.latitude, wpt.longitude).toFloat()
            }
            wpt.distanceRemain = totalDist
            prevLat = wpt.latitude
            prevLon = wpt.longitude
        }
        return waypoints
    }
}
