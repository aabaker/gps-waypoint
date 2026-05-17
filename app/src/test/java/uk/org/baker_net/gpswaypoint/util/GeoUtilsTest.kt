package uk.org.baker_net.gpswaypoint.util

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * GeoUtilsTest.kt
 *
 * Unit tests for the pure geographic calculation functions in [GeoUtils].
 * No Android dependencies are required; these run on the JVM.
 *
 * Reference values were computed using independently published formulas
 * (https://www.movable-type.co.uk/scripts/latlong.html) and cross-checked
 * with Google Maps distance measurements.
 */
class GeoUtilsTest {

    companion object {
        /** Allowed absolute error for distance assertions (0.5 %). */
        private const val DISTANCE_TOLERANCE_RATIO = 0.005

        /** Allowed absolute error for bearing assertions (degrees). */
        private const val BEARING_TOLERANCE_DEG = 0.1
    }

    // -------------------------------------------------------------------------
    // haversineDistance
    // -------------------------------------------------------------------------

    /**
     * Zero distance: same point should return 0.
     */
    @Test
    fun haversineDistance_samePoint_returnsZero() {
        val d = GeoUtils.haversineDistance(51.5074, -0.1278, 51.5074, -0.1278)
        assertEquals(0.0, d, 0.001)
    }

    /**
     * Known distance: London to Paris ≈ 341 km.
     * Validates the formula with a real-world reference.
     */
    @Test
    fun haversineDistance_londonToParis_approx341km() {
        // London: 51.5074° N, 0.1278° W
        // Paris:  48.8566° N, 2.3522° E
        // expected value calculated using https://www.cqsrg.org/tools/GCDistance/
        val d = GeoUtils.haversineDistance(51.5074, -0.1278, 48.8566, 2.3522)
        val expected = 343_923.0  // metres
        val tolerance = expected * DISTANCE_TOLERANCE_RATIO
        assertTrue(
            "Expected ~341 km but got ${d / 1000} km",
            abs(d - expected) < tolerance
        )
    }

    /**
     * Short distance: two points 100 m apart (north–south along same longitude).
     * Confirms accuracy for navigation-scale distances.
     */
    @Test
    fun haversineDistance_shortDistanceNorthSouth_approx100m() {
        // 1° latitude ≈ 111 320 m; 0.0009° ≈ 100 m
        val lat1 = 51.5000
        val lat2 = 51.5009
        val lon  = -0.1278
        val d = GeoUtils.haversineDistance(lat1, lon, lat2, lon)
        assertTrue("Expected ~100 m, got $d", abs(d - 100.0) < 5.0)
    }

    /**
     * Distance is commutative: d(A,B) == d(B,A).
     */
    @Test
    fun haversineDistance_isCommutative() {
        val d1 = GeoUtils.haversineDistance(51.5074, -0.1278, 48.8566, 2.3522)
        val d2 = GeoUtils.haversineDistance(48.8566, 2.3522, 51.5074, -0.1278)
        assertEquals(d1, d2, 0.001)
    }

    /**
     * Crossing the antimeridian (±180° longitude): should still give a sensible result.
     */
    @Test
    fun haversineDistance_acrossAntimeridian_positive() {
        // Two points near the international date line
        val d = GeoUtils.haversineDistance(0.0, 179.9, 0.0, -179.9)
        assertTrue("Distance across antimeridian should be ~22 km, got $d", d < 30_000)
        assertTrue("Distance must be positive", d > 0)
    }

    // -------------------------------------------------------------------------
    // bearing
    // -------------------------------------------------------------------------

    /**
     * Bearing due North: same longitude, second point at higher latitude → 0°.
     */
    @Test
    fun bearing_dueNorth_returns0() {
        val b = GeoUtils.bearing(51.0, 0.0, 52.0, 0.0)
        assertEquals(0f, b, BEARING_TOLERANCE_DEG.toFloat())
    }

    /**
     * Bearing due East: same latitude, second point at higher longitude → 90°.
     */
    @Test
    fun bearing_dueEast_returns90() {
        val b = GeoUtils.bearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(90f, b, BEARING_TOLERANCE_DEG.toFloat())
    }

    /**
     * Bearing due South: same longitude, second point at lower latitude → 180°.
     */
    @Test
    fun bearing_dueSouth_returns180() {
        val b = GeoUtils.bearing(52.0, 0.0, 51.0, 0.0)
        assertEquals(180f, b, BEARING_TOLERANCE_DEG.toFloat())
    }

    /**
     * Bearing due West: same latitude, second point at lower longitude → 270°.
     */
    @Test
    fun bearing_dueWest_returns270() {
        val b = GeoUtils.bearing(0.0, 1.0, 0.0, 0.0)
        assertEquals(270f, b, BEARING_TOLERANCE_DEG.toFloat())
    }

    /**
     * Bearing result is always in [0, 360).
     */
    @Test
    fun bearing_alwaysInRange0To360() {
        val points = listOf(
            Pair(51.5, -0.1) to Pair(48.8, 2.3),
            Pair(48.8, 2.3) to Pair(51.5, -0.1),
            Pair(0.0, 0.0)  to Pair(-1.0, -1.0),
            Pair(-33.9, 151.2) to Pair(35.7, 139.7)
        )
        for ((from, to) in points) {
            val b = GeoUtils.bearing(from.first, from.second, to.first, to.second)
            assertTrue("Bearing $b not in [0,360)", b >= 0f && b < 360f)
        }
    }

    // -------------------------------------------------------------------------
    // formatDistance
    // -------------------------------------------------------------------------

    @Test
    fun formatDistance_lessThan1000m_showsMetres() {
        assertEquals("500 m", GeoUtils.formatDistance(500f))
        assertEquals("0 m",   GeoUtils.formatDistance(0f))
        assertEquals("999 m", GeoUtils.formatDistance(999f))
    }

    @Test
    fun formatDistance_atLeast1000m_showsKilometres() {
        assertEquals("1.0 km", GeoUtils.formatDistance(1000f))
        assertEquals("1.5 km", GeoUtils.formatDistance(1500f))
        assertEquals("10.0 km", GeoUtils.formatDistance(10_000f))
    }

    // -------------------------------------------------------------------------
    // formatBearing
    // -------------------------------------------------------------------------

    @Test
    fun formatBearing_north_containsN() {
        val result = GeoUtils.formatBearing(0f)
        assertTrue("Expected N in '$result'", result.contains("N"))
        assertFalse("Should not contain S", result.contains("S"))
    }

    @Test
    fun formatBearing_east_containsE() {
        val result = GeoUtils.formatBearing(90f)
        assertTrue("Expected E in '$result'", result.contains("E"))
    }

    @Test
    fun formatBearing_southwest_containsSW() {
        val result = GeoUtils.formatBearing(225f)
        assertTrue("Expected SW in '$result'", result.contains("SW"))
    }

    @Test
    fun formatBearing_includesNumericDegrees() {
        val result = GeoUtils.formatBearing(45f)
        assertTrue("Should contain '045'", result.contains("045"))
    }

    // -------------------------------------------------------------------------
    // remainingRouteDistance
    // -------------------------------------------------------------------------

    @Test
    fun remainingRouteDistance_emptyList_returnsZero() {
        val d = GeoUtils.remainingRouteDistance(emptyList(), 0)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun remainingRouteDistance_atLastWaypoint_returnsZero() {
        val wps = listOf(Pair(51.5, 0.0), Pair(51.6, 0.0), Pair(51.7, 0.0))
        val d = GeoUtils.remainingRouteDistance(wps, 2)  // last index
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun remainingRouteDistance_atFirstWaypoint_returnsTotalRouteDistance() {
        // Three points spaced roughly 11 km apart north-south
        val wps = listOf(Pair(51.0, 0.0), Pair(51.1, 0.0), Pair(51.2, 0.0))
        val fromFirst  = GeoUtils.remainingRouteDistance(wps, 0)
        val fromSecond = GeoUtils.remainingRouteDistance(wps, 1)
        // From first should be greater than from second
        assertTrue("Route from 0 should be > route from 1", fromFirst > fromSecond)
        // Segment from 0→1 should equal fromFirst - fromSecond (approximately)
        val seg01 = GeoUtils.haversineDistance(51.0, 0.0, 51.1, 0.0)
        assertEquals(seg01, fromFirst - fromSecond, 1.0)
    }

    @Test
    fun remainingRouteDistance_beyondLastIndex_returnsZero() {
        val wps = listOf(Pair(51.0, 0.0), Pair(51.1, 0.0))
        val d = GeoUtils.remainingRouteDistance(wps, 5)
        assertEquals(0.0, d, 0.001)
    }
}
