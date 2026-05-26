package uk.org.baker_net.gpswaypoint.model

/**
 * RecordingState.kt
 *
 * Indicates whether the app is currently recording track data. SA class is used so that
 * a meaningful name, not just Boolean is associated with it when it is posted.
 *
 * @property isRecording   Wall-clock time of the sample (System.currentTimeMillis()).
 */
data class RecordingState(
    val isRecording: Boolean = false
)
