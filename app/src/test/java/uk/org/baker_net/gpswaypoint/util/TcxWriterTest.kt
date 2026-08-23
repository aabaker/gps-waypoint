package uk.org.baker_net.gpswaypoint.util

import uk.org.baker_net.gpswaypoint.model.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * TcxWriterTest.kt
 *
 * Unit tests for [TcxWriter].  All tests write to an in-memory stream and
 * validate the resulting XML string without any Android dependencies.
 */
class TcxWriterTest {

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Writes [points] to an in-memory buffer and returns the XML as a String.
     *
     * Input:  @param points List of TrackPoint objects to serialise.
     * Output: @return UTF-8 XML string produced by TcxWriter.
     */
    private fun writeTcx(points: List<TrackPoint>): String {
        val out = ByteArrayOutputStream()
        TcxWriter.write(out, points)
        return out.toString(Charsets.UTF_8)
    }

    private fun makePoint(
        ts: Long = System.currentTimeMillis(),
        lat: Double = 51.5,
        lon: Double = -0.1,
        alt: Double? = 10.0,
        hr: Int? = 72,
        dist: Double = 0.0
    ) = TrackPoint(ts, lat, lon, alt, hr, dist, null)

    // -------------------------------------------------------------------------
    // Structure tests
    // -------------------------------------------------------------------------

    @Test
    fun write_producesValidXmlDeclaration() {
        val xml = writeTcx(emptyList())
        assertTrue("Should start with XML declaration", xml.startsWith("<?xml"))
    }

    @Test
    fun write_containsTrainingCenterDatabaseRoot() {
        val xml = writeTcx(emptyList())
        assertTrue(xml.contains("TrainingCenterDatabase"))
    }

    @Test
    fun write_containsActivityElement() {
        val xml = writeTcx(emptyList())
        assertTrue(xml.contains("<Activity"))
    }

    @Test
    fun write_containsLapElement() {
        val xml = writeTcx(emptyList())
        assertTrue(xml.contains("<Lap"))
    }

    // -------------------------------------------------------------------------
    // Empty track
    // -------------------------------------------------------------------------

    @Test
    fun write_emptyTrack_producesValidOutput() {
        val xml = writeTcx(emptyList())
        assertFalse("Should not be empty", xml.isBlank())
        assertTrue(xml.contains("</TrainingCenterDatabase>"))
    }

    @Test
    fun write_emptyTrack_distanceIsZero() {
        val xml = writeTcx(emptyList())
        assertTrue(xml.contains("<DistanceMeters>0.00</DistanceMeters>"))
    }

    // -------------------------------------------------------------------------
    // Single point
    // -------------------------------------------------------------------------

    @Test
    fun write_singlePoint_containsLatitude() {
        val xml = writeTcx(listOf(makePoint(lat = 51.5074)))
        assertTrue("Should contain latitude", xml.contains("51.5074"))
    }

    @Test
    fun write_singlePoint_containsLongitude() {
        val xml = writeTcx(listOf(makePoint(lon = -0.1278)))
        assertTrue("Should contain longitude", xml.contains("-0.1278"))
    }

    @Test
    fun write_singlePoint_containsHeartRate() {
        val xml = writeTcx(listOf(makePoint(hr = 145)))
        assertTrue("Should contain heart rate value", xml.contains("<Value>145</Value>"))
    }

    @Test
    fun write_singlePoint_containsAltitude() {
        val xml = writeTcx(listOf(makePoint(alt = 123.4)))
        assertTrue("Should contain altitude", xml.contains("123.4"))
    }

    @Test
    fun write_singlePoint_noHeartRate_noHrElement() {
        val xml = writeTcx(listOf(makePoint(hr = null)))
        assertFalse("Should not contain HR element", xml.contains("HeartRateBpm"))
    }

    @Test
    fun write_singlePoint_noAltitude_noAltElement() {
        val xml = writeTcx(listOf(makePoint(alt = null)))
        assertFalse("Should not contain AltitudeMeters", xml.contains("AltitudeMeters"))
    }

    // -------------------------------------------------------------------------
    // Multiple points
    // -------------------------------------------------------------------------

    @Test
    fun write_multiplePoints_allTrackpointsPresent() {
        val points = listOf(
            makePoint(ts = 1000L, dist = 0.0),
            makePoint(ts = 2000L, dist = 10.0),
            makePoint(ts = 3000L, dist = 20.0)
        )
        val xml = writeTcx(points)
        val count = xml.split("<Trackpoint>").size - 1
        assertEquals("Should have 3 Trackpoint elements", 3, count)
    }

    @Test
    fun write_multiplePoints_cumulativeDistanceInLastPoint() {
        val points = listOf(
            makePoint(dist = 0.0),
            makePoint(dist = 500.0),
            makePoint(dist = 1000.0)
        )
        val xml = writeTcx(points)
        assertTrue("Lap distance should be 1000 m", xml.contains("1000.00"))
    }

    @Test
    fun write_averageHeartRate_presentInLap() {
        val points = listOf(
            makePoint(hr = 100),
            makePoint(hr = 120),
            makePoint(hr = 140)
        )
        val xml = writeTcx(points)
        // Average = 120
        assertTrue("Lap should contain AverageHeartRateBpm",
            xml.contains("AverageHeartRateBpm"))
        assertTrue("Average HR value should be 120",
            xml.contains("<Value>120</Value>"))
    }

    // -------------------------------------------------------------------------
    // XML safety
    // -------------------------------------------------------------------------

    @Test
    fun write_activityNameWithSpecialChars_escapedProperly() {
        val out = ByteArrayOutputStream()
        TcxWriter.write(out, emptyList(), activityName = "Walk & Run <Test>")
        val xml = out.toString(Charsets.UTF_8)
        assertTrue("& should be escaped", xml.contains("&amp;"))
        assertTrue("< should be escaped", xml.contains("&lt;"))
        assertTrue("> should be escaped", xml.contains("&gt;"))
    }

    // -------------------------------------------------------------------------
    // Duration
    // -------------------------------------------------------------------------

    @Test
    fun write_multiplePoints_durationCalculatedCorrectly() {
        val base = 1_700_000_000_000L  // fixed epoch ms
        val points = listOf(
            makePoint(ts = base,           dist = 0.0),
            makePoint(ts = base + 60_000L, dist = 100.0)  // 60 seconds later
        )
        val xml = writeTcx(points)
        assertTrue("Duration should be 60 s", xml.contains("<TotalTimeSeconds>60.0</TotalTimeSeconds>"))
    }
}
