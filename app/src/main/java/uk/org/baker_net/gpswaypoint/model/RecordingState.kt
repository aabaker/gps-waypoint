package uk.org.baker_net.gpswaypoint.model

/**
 * RecordingState.kt
 *
 * Indicates whether the app is currently recording track data. SA class is used so that
 * a meaningful name, not just Boolean is associated with it when it is posted.
 *
 * @property isRecording   Wall-clock time of the sample (System.currentTimeMillis()).
 * @property gpsAccuracy   Accuracy of the last GPS fix in metres
 */
data class RecordingState(
    val isRecording: Boolean = false,
    val gpsAccuracy: Float? = null
)
