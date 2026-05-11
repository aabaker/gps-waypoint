package com.example.gpswaypoint.util

import com.example.gpswaypoint.model.TrackPoint
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * TcxWriter.kt
 *
 * Writes a list of [TrackPoint] objects to an output stream in Garmin's TCX
 * (Training Centre XML) format version 2.
 *
 * The output conforms to the ActivityExtensions schema, which allows heart-rate
 * and position data to be imported by Garmin Connect, Strava, and similar platforms.
 *
 * This class has no Android dependencies and can be called from any thread.
 */
object TcxWriter {

    /** ISO 8601 date-time formatter required by the TCX schema. */
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Writes activity data as a TCX document to the provided [OutputStream].
     *
     * Inputs:
     *   @param outputStream  Open stream to write XML bytes to.  The caller is
     *                        responsible for flushing and closing the stream.
     *   @param trackPoints   Ordered list of recorded [TrackPoint] samples.
     *                        May be empty, in which case an empty activity is written.
     *   @param activityName  Human-readable label for the activity (default "Walk").
     *   @param sport         TCX Sport attribute value, e.g. "Running", "Biking",
     *                        or "Other" (default "Other").
     *
     * Output:
     *   Writes UTF-8 XML to [outputStream].  Throws [java.io.IOException] on write error.
     */
    fun write(
        outputStream: OutputStream,
        trackPoints: List<TrackPoint>,
        activityName: String = "GPS Waypoint Activity",
        sport: String = "Other"
    ) {
        val writer = outputStream.bufferedWriter(Charsets.UTF_8)

        val startTime = if (trackPoints.isNotEmpty())
            isoFormat.format(Date(trackPoints.first().timestampMs))
        else
            isoFormat.format(Date())

        val totalDistanceM = trackPoints.lastOrNull()?.distanceM ?: 0.0
        val avgHr = trackPoints.mapNotNull { it.heartRateBpm }
            .takeIf { it.isNotEmpty() }
            ?.average()?.toInt()
        val maxHr = trackPoints.mapNotNull { it.heartRateBpm }
            .takeIf { it.isNotEmpty() }
            ?.max()

        // --- TCX document header ---
        writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
        writer.newLine()
        writer.write(
            """<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2
  http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd">"""
        )
        writer.newLine()

        writer.write("  <Activities>")
        writer.newLine()
        writer.write("""    <Activity Sport="$sport">""")
        writer.newLine()
        writer.write("      <Id>$startTime</Id>")
        writer.newLine()
        writer.write("      <Notes>${escapeXml(activityName)}</Notes>")
        writer.newLine()
        writer.write("      <Lap StartTime=\"$startTime\">")
        writer.newLine()

        // --- Lap-level summary ---
        val durationSec = if (trackPoints.size >= 2) {
            (trackPoints.last().timestampMs - trackPoints.first().timestampMs) / 1000.0
        } else 0.0
        writer.write("        <TotalTimeSeconds>${"%.1f".format(durationSec)}</TotalTimeSeconds>")
        writer.newLine()
        writer.write("        <DistanceMeters>${"%.2f".format(totalDistanceM)}</DistanceMeters>")
        writer.newLine()
        if (avgHr != null) {
            writer.write("        <AverageHeartRateBpm><Value>$avgHr</Value></AverageHeartRateBpm>")
            writer.newLine()
        }
        if (maxHr != null) {
            writer.write("        <MaximumHeartRateBpm><Value>$maxHr</Value></MaximumHeartRateBpm>")
            writer.newLine()
        }
        writer.write("        <Intensity>Active</Intensity>")
        writer.newLine()
        writer.write("        <TriggerMethod>Manual</TriggerMethod>")
        writer.newLine()
        writer.write("        <Track>")
        writer.newLine()

        // --- Individual trackpoints ---
        for (tp in trackPoints) {
            writer.write("          <Trackpoint>")
            writer.newLine()
            writer.write("            <Time>${isoFormat.format(Date(tp.timestampMs))}</Time>")
            writer.newLine()
            writer.write("            <Position>")
            writer.newLine()
            writer.write("              <LatitudeDegrees>${tp.latitude}</LatitudeDegrees>")
            writer.newLine()
            writer.write("              <LongitudeDegrees>${tp.longitude}</LongitudeDegrees>")
            writer.newLine()
            writer.write("            </Position>")
            writer.newLine()
            tp.altitudeM?.let {
                writer.write("            <AltitudeMeters>${"%.1f".format(it)}</AltitudeMeters>")
                writer.newLine()
            }
            writer.write("            <DistanceMeters>${"%.2f".format(tp.distanceM)}</DistanceMeters>")
            writer.newLine()
            tp.heartRateBpm?.let {
                writer.write("            <HeartRateBpm><Value>$it</Value></HeartRateBpm>")
                writer.newLine()
            }
            writer.write("          </Trackpoint>")
            writer.newLine()
        }

        // --- Close all elements ---
        writer.write("        </Track>")
        writer.newLine()
        writer.write("      </Lap>")
        writer.newLine()
        writer.write("    </Activity>")
        writer.newLine()
        writer.write("  </Activities>")
        writer.newLine()
        writer.write("</TrainingCenterDatabase>")
        writer.newLine()

        writer.flush()
    }

    /**
     * Escapes characters that are special in XML content (&, <, >, ", ').
     *
     * Input:  @param text Raw string.
     * Output: @return XML-safe string.
     */
    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
