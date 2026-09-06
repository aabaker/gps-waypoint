package uk.org.baker_net.gpswaypoint.model

/**
 * LocationState.kt
 *
 * This class contains state data that is valid whenever location data is being gathered,
 * it excludes any data relating to waypoint tracking.
 *
 * @property isRecording   Wall-clock time of the sample (System.currentTimeMillis()).
 * @property gpsAccuracy   Accuracy of the last GPS fix in metres
 * @property locationEnabled   Whether the system location/GPS setting is currently switched on.
 * @property satelliteCount    Number of GNSS satellites currently being tracked
 *                             (null if unknown, e.g. location services disabled).
 * @property elapsedDistanceM  Total distance travelled since recording started, metres.
 * @property heartRateBpm      Most recent heart-rate reading (null = no monitor).
 */
data class LocationState(
    val isRecording: Boolean = false,
    val gpsAccuracy: Float? = null,
    val locationEnabled: Boolean = true,
    val satelliteCount: Int? = null,
    val elapsedDistanceM: Float,
    val heartRateBpm: Int?
)
