package uk.org.baker_net.gpswaypoint.util

import kotlin.math.*

/**
 * GeoUtils.kt
 *
 * Pure, stateless geographic calculation utilities.
 * All functions use the WGS-84 ellipsoid approximated as a sphere
 * (mean radius 6 371 000 m), which gives errors < 0.3 % for distances
 * relevant to outdoor navigation.
 *
 * These functions have no Android dependencies and are fully unit-testable.
 */
object GeoUtils {

    /** Mean radius of the Earth in metres (WGS-84 sphere approximation). */
    const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Calculates the great-circle distance between two geographic coordinates
     * using the Haversine formula.
     *
     * Inputs:
     *   @param lat1 Latitude of point A in decimal degrees.
     *   @param lon1 Longitude of point A in decimal degrees.
     *   @param lat2 Latitude of point B in decimal degrees.
     *   @param lon2 Longitude of point B in decimal degrees.
     *
     * Output:
     *   @return Distance in metres (always ≥ 0).
     */
    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_M * c
    }

    /**
     * Calculates the initial bearing (forward azimuth) from point A to point B.
     * The result is the compass direction you would face at A to travel the
     * shortest arc to B.
     *
     * Inputs:
     *   @param lat1 Latitude of origin in decimal degrees.
     *   @param lon1 Longitude of origin in decimal degrees.
     *   @param lat2 Latitude of destination in decimal degrees.
     *   @param lon2 Longitude of destination in decimal degrees.
     *
     * Output:
     *   @return Bearing in degrees, range [0, 360).  0 = North, 90 = East, etc.
     */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val x = sin(dLon) * cos(lat2Rad)
        val y = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val brng = Math.toDegrees(atan2(x, y))
        return ((brng + 360) % 360).toFloat()
    }

    /**
     * Calculate the rolling average of a bearing. Claude got to write this but was
     * given a very specific prompt to the extent that ChatGPT produces very similar
     * code for the same prompt
     *
     * @param existingAngle angle in degrees
     * @param newAngle update value in degrees
     * @param proportion proportion of the new angle to include in the result
     *
     * @return The update angle
     */

    fun rollingAverageBearing(existingAngle: Float, newAngle: Float, proportion: Float): Float {
        require(existingAngle in 0f..360f) { "existingAngle must be in range 0..360" }
        require(newAngle in 0f..360f) { "newAngle must be in range 0..360" }
        require(proportion in 0f..1f) { "proportion must be in range 0..1" }

        val existingRad = Math.toRadians(existingAngle.toDouble())
        val newRad = Math.toRadians(newAngle.toDouble())

        val existingX = cos(existingRad)
        val existingY = sin(existingRad)
        val newX = cos(newRad)
        val newY = sin(newRad)

        val avgX = existingX * (1f - proportion) + newX * proportion
        val avgY = existingY * (1f - proportion) + newY * proportion

        val resultRad = atan2(avgY, avgX)
        val resultDeg = Math.toDegrees(resultRad).toFloat()

        return (resultDeg + 360f) % 360f
    }


    /** Feet per metre (1 ft = 0.3048 m). */
    private const val METRES_PER_FOOT = 0.3048

    /** Feet per mile, used to switch from feet to miles once a distance is large. */
    private const val FEET_PER_MILE = 5_280.0

    /**
     * Formats a distance in metres to a human-readable string in the requested
     * [units] system.
     *
     * Metric: values < 1 000 m are shown as whole metres; values ≥ 1 000 m are
     * shown in kilometres with one decimal place.
     *
     * Imperial: values < 1 000 ft are shown as whole feet; values ≥ 1 000 ft are
     * shown in miles with one decimal place.
     *
     * Input:
     *   @param metres Distance in metres (must be ≥ 0).
     *   @param units  Measurement unit system to render in. Defaults to
     *                 [UnitSystem.METRIC] for backwards compatibility with
     *                 existing call sites and tests.
     *
     * Output:
     *   @return Formatted string, e.g. "342 m", "1.4 km", "342 ft", or "1.4 mi".
     */
    fun formatDistance(metres: Float, units: UnitSystem = UnitSystem.METRIC): String =
        when (units) {
            UnitSystem.METRIC ->
                if (metres < 1_000f) "${metres.toInt()} m"
                else "${"%.1f".format(metres / 1_000f)} km"
            UnitSystem.IMPERIAL -> {
                val feet = metres / METRES_PER_FOOT
                if (feet < 1_000.0) "${feet.toInt()} ft"
                else "${"%.1f".format(feet / FEET_PER_MILE)} mi"
            }
        }

    /**
     * Formats a bearing in degrees to a cardinal/intercardinal compass label
     * (N, NE, E, SE, S, SW, W, NW) plus the numeric value.
     *
     * Input:
     *   @param degrees Bearing in degrees, range [0, 360).
     *
     * Output:
     *   @return String such as "045° NE".
     */
    fun formatBearing(degrees: Float): String {
        val cardinals = arrayOf("N","NE","E","SE","S","SW","W","NW","N")
        val index = ((degrees + 22.5f) / 45f).toInt() % 8
        return "%03.0f° %s".format(degrees, cardinals[index])
    }

    /**
     * Calculates cumulative route distance: the sum of great-circle segments
     * between consecutive waypoints from [fromIndex] to the end of the list.
     *
     * Inputs:
     *   @param waypoints  Ordered list of (latitude, longitude) pairs represented
     *                     as Pair<Double, Double>.
     *   @param fromIndex  Start index (inclusive).  Typically the current waypoint index.
     *
     * Output:
     *   @return Remaining route distance in metres.  Returns 0 if [fromIndex] is
     *           at or past the last waypoint.
     */
    fun remainingRouteDistance(waypoints: List<Pair<Double, Double>>, fromIndex: Int): Double {
        if (fromIndex >= waypoints.size - 1) return 0.0
        var total = 0.0
        for (i in fromIndex until waypoints.size - 1) {
            val (lat1, lon1) = waypoints[i]
            val (lat2, lon2) = waypoints[i + 1]
            total += haversineDistance(lat1, lon1, lat2, lon2)
        }
        return total
    }
}
