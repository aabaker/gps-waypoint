package uk.org.baker_net.gpswaypoint.model

/**
 * Waypoint.kt
 *
 * Immutable data class representing a single GPX waypoint.
 *
 * @property name      Human-readable name parsed from the <name> element (may be empty).
 * @property latitude  WGS-84 latitude in decimal degrees.
 * @property longitude WGS-84 longitude in decimal degrees.
 * @property elevation Optional elevation in metres above mean sea level.
 */
data class Waypoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null
)
