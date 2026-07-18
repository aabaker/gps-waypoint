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

    @Test
    fun formatDistance_imperial_lessThan1000ft_showsFeet() {
        // 100 m ≈ 328 ft
        assertEquals("328 ft", GeoUtils.formatDistance(100f, GeoUtils.UnitSystem.IMPERIAL))
        assertEquals("0 ft", GeoUtils.formatDistance(0f, GeoUtils.UnitSystem.IMPERIAL))
    }

    @Test
    fun formatDistance_imperial_atLeast1000ft_showsMiles() {
        // 1 mile = 1609.344 m
        assertEquals("1.0 mi", GeoUtils.formatDistance(1609.344f, GeoUtils.UnitSystem.IMPERIAL))
        assertEquals("2.0 mi", GeoUtils.formatDistance(3218.688f, GeoUtils.UnitSystem.IMPERIAL))
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

    // rollingAverageBearing
    data class TestCase(
        val description: String,
        val existingAngle: Float,
        val newAngle: Float,
        val proportion: Float,
        val expectedMin: Float,
        val expectedMax: Float
    )

    @Test
    fun testRollingAverageBearing() {
        val tolerance = 0.5f

        val testCases = listOf(
            TestCase(
                description = "Equal weight, 0 and 90 -> 45",
                existingAngle = 0f,
                newAngle = 90f,
                proportion = 0.5f,
                expectedMin = 45f - tolerance,
                expectedMax = 45f + tolerance
            ),
            TestCase(
                description = "Equal weight, 90 and 180 -> 135",
                existingAngle = 90f,
                newAngle = 180f,
                proportion = 0.5f,
                expectedMin = 135f - tolerance,
                expectedMax = 135f + tolerance
            ),
            TestCase(
                description = "Equal weight, 180 and 270 -> 225",
                existingAngle = 180f,
                newAngle = 270f,
                proportion = 0.5f,
                expectedMin = 225f - tolerance,
                expectedMax = 225f + tolerance
            ),
            TestCase(
                description = "Equal weight, 270 and 360 -> 315",
                existingAngle = 270f,
                newAngle = 360f,
                proportion = 0.5f,
                expectedMin = 315f - tolerance,
                expectedMax = 315f + tolerance
            ),
            TestCase(
                description = "Proportion 0 -> result equals existingAngle",
                existingAngle = 45f,
                newAngle = 200f,
                proportion = 0f,
                expectedMin = 45f - tolerance,
                expectedMax = 45f + tolerance
            ),
            TestCase(
                description = "Proportion 1 -> result equals newAngle",
                existingAngle = 45f,
                newAngle = 200f,
                proportion = 1f,
                expectedMin = 200f - tolerance,
                expectedMax = 200f + tolerance
            ),
            TestCase(
                description = "Small proportion, biased toward existing",
                existingAngle = 100f,
                newAngle = 160f,
                proportion = 0.1f,
                expectedMin = 104f - tolerance,
                expectedMax = 108f + tolerance
            ),
            // Corner cases: wrap-around near 0/360 boundary
            TestCase(
                description = "Corner case: 355 and 5 should average near 0/360",
                existingAngle = 355f,
                newAngle = 5f,
                proportion = 0.5f,
                expectedMin = 359f - tolerance,
                expectedMax = 361f + tolerance  // handled via modulo check below
            ),
            TestCase(
                description = "Corner case: 358 and 2 should average near 0/360",
                existingAngle = 358f,
                newAngle = 2f,
                proportion = 0.5f,
                expectedMin = 359f - tolerance,
                expectedMax = 361f + tolerance
            ),
            TestCase(
                description = "Corner case: 350 and 10 -> ~0/360 with 50/50 weight",
                existingAngle = 350f,
                newAngle = 10f,
                proportion = 0.5f,
                expectedMin = 359f - tolerance,
                expectedMax = 361f + tolerance
            ),
            TestCase(
                description = "Corner case: high proportion toward new near boundary (2 degrees)",
                existingAngle = 359f,
                newAngle = 1f,
                proportion = 0.8f,
                expectedMin = 359.5f - tolerance,
                expectedMax = 361f + tolerance
            ),
            TestCase(
                description = "Corner case: low proportion toward new near boundary",
                existingAngle = 359f,
                newAngle = 1f,
                proportion = 0.2f,
                expectedMin = 358.5f - tolerance,
                expectedMax = 360.5f + tolerance
            ),
            TestCase(
                description = "Same angle -> same result",
                existingAngle = 123f,
                newAngle = 123f,
                proportion = 0.5f,
                expectedMin = 123f - tolerance,
                expectedMax = 123f + tolerance
            ),
            TestCase(
                description = "Due north: 0 and 0 -> 0",
                existingAngle = 0f,
                newAngle = 0f,
                proportion = 0.5f,
                expectedMin = 0f - tolerance,
                expectedMax = 0f + tolerance
            ),
            TestCase(
                description = "Due north boundaries: 360 and 0 -> ~0 or ~360",
                existingAngle = 360f,
                newAngle = 0f,
                proportion = 0.5f,
                expectedMin = 0f - tolerance,
                expectedMax = 0f + tolerance
            )
        )

        for (tc in testCases) {
            val result = GeoUtils.rollingAverageBearing(tc.existingAngle, tc.newAngle, tc.proportion)

            // Normalise result and expected bounds into 0..360 for wrap-around cases
            val normResult = (result + 360f) % 360f
            val normMin   = tc.expectedMin % 360f
            val normMax   = tc.expectedMax % 360f

            val inRange = if (normMin <= normMax) {
                normResult in normMin..normMax
            } else {
                // Range wraps around 0/360
                normResult >= normMin || normResult <= normMax
            }

            assertTrue(
                "${tc.description}: result=$result (normalised=$normResult) not in [$normMin, $normMax]",
                inRange
            )
        }
    }
}
