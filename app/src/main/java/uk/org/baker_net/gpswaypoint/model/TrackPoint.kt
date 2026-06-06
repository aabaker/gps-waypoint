package uk.org.baker_net.gpswaypoint.model

/**
 * TrackPoint.kt
 *
 * Represents a single recorded position sample stored during an activity.
 * Each point is written as a <Trackpoint> element in the exported TCX file.
 *
 * @property timestampMs   Wall-clock time of the sample (System.currentTimeMillis()).
 * @property latitude      WGS-84 latitude in decimal degrees.
 * @property longitude     WGS-84 longitude in decimal degrees.
 * @property altitudeM     Altitude in metres (null if unavailable).
 * @property heartRateBpm  Heart rate in beats per minute (null if no BLE monitor connected).
 * @property distanceM     Cumulative distance from the activity start in metres.
 */
data class TrackPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val heartRateBpm: Int?,
    val distanceM: Double,
    val speed: Float?
)
