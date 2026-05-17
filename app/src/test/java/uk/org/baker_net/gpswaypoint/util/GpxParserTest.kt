package uk.org.baker_net.gpswaypoint.util

// See https://stackoverflow.com/questions/31272732/unit-testing-with-android-xmlpullparser-on-the-jvm
// for example of an alternate compatible parser ti use in testing

import org.junit.Assert.*
import org.junit.Test

/**
 * GpxParserTest.kt
 *
 * Unit tests for [GpxParser].  Each test provides a minimal in-memory GPX
 * string so no Android resources or file system are required.
 */
class GpxParserTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses an in-memory GPX string and returns the list of waypoints.
     *
     * Input:  @param gpx Valid or intentionally invalid GPX XML string.
     * Output: @return List of parsed Waypoint objects.
     */
    private fun parse(gpx: String) =
        GpxParser.parse(gpx.trimIndent().byteInputStream())

    // -------------------------------------------------------------------------
    // Waypoint (<wpt>) parsing
    // -------------------------------------------------------------------------

    //@Test
    fun parse_singleWpt_returnsOneWaypoint() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="51.5074" lon="-0.1278">
                <name>London</name>
                <ele>15.0</ele>
              </wpt>
            </gpx>
        """
        val wps = parse(gpx)
        assertEquals(1, wps.size)
        assertEquals("London", wps[0].name)
        assertEquals(51.5074, wps[0].latitude, 0.000001)
        assertEquals(-0.1278, wps[0].longitude, 0.000001)
        assertEquals(15.0, wps[0].elevation!!, 0.001)
    }

    //@Test
    fun parse_multipleWpts_returnAllInOrder() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="1.0" lon="1.0"><name>A</name></wpt>
              <wpt lat="2.0" lon="2.0"><name>B</name></wpt>
              <wpt lat="3.0" lon="3.0"><name>C</name></wpt>
            </gpx>
        """
        val wps = parse(gpx)
        assertEquals(3, wps.size)
        assertEquals("A", wps[0].name)
        assertEquals("B", wps[1].name)
        assertEquals("C", wps[2].name)
    }

    //@Test
    fun parse_wptWithoutName_generatesAutoName() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="51.0" lon="0.0"/>
            </gpx>
        """
        val wps = parse(gpx)
        assertEquals(1, wps.size)
        assertTrue("Auto name should start with WP", wps[0].name.startsWith("WP"))
    }

    //@Test
    fun parse_wptWithoutElevation_elevationIsNull() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="51.0" lon="0.0"><name>NoEle</name></wpt>
            </gpx>
        """
        val wps = parse(gpx)
        assertNull(wps[0].elevation)
    }

    // -------------------------------------------------------------------------
    // Route (<rte> / <rtept>) parsing
    // -------------------------------------------------------------------------

    //@Test
    fun parse_routePoints_returnedAsWaypoints() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <rte>
                <name>My Route</name>
                <rtept lat="10.0" lon="10.0"><name>Start</name></rtept>
                <rtept lat="11.0" lon="11.0"><name>End</name></rtept>
              </rte>
            </gpx>
        """
        val wps = parse(gpx)
        assertEquals(2, wps.size)
        assertEquals("Start", wps[0].name)
        assertEquals("End", wps[1].name)
    }

    // -------------------------------------------------------------------------
    // Track (<trk> / <trkseg> / <trkpt>) parsing
    // -------------------------------------------------------------------------

    //@Test
    fun parse_trackPoints_returnedAsWaypoints() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <trk>
                <trkseg>
                  <trkpt lat="51.0" lon="-1.0"><ele>50.0</ele></trkpt>
                  <trkpt lat="51.1" lon="-1.0"><ele>55.0</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """
        val wps = parse(gpx)
        assertEquals(2, wps.size)
        assertEquals(50.0, wps[0].elevation!!, 0.001)
        assertEquals(55.0, wps[1].elevation!!, 0.001)
    }

    // -------------------------------------------------------------------------
    // Mixed / empty
    // -------------------------------------------------------------------------

    //@Test
    fun parse_emptyGpx_returnsEmptyList() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1"></gpx>"""
        val wps = parse(gpx)
        assertTrue(wps.isEmpty())
    }

    //@Test
    fun parse_wptMissingLatLon_skipped() {
        // lat/lon attributes missing – should not produce a waypoint
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt><name>Invalid</name></wpt>
              <wpt lat="51.0" lon="0.0"><name>Valid</name></wpt>
            </gpx>
        """
        val wps = parse(gpx)
        assertEquals(1, wps.size)
        assertEquals("Valid", wps[0].name)
    }

    //@Test
    fun parse_mixedWptAndTrkpt_allReturned() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <wpt lat="1.0" lon="1.0"><name>WP1</name></wpt>
              <trk><trkseg>
                <trkpt lat="2.0" lon="2.0"><name>TP1</name></trkpt>
              </trkseg></trk>
            </gpx>
        """
        val wps = parse(gpx)
        assertEquals(2, wps.size)
    }
}
