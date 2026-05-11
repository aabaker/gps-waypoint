package com.example.gpswaypoint.model

/**
 * NavigationState.kt
 *
 * Sealed class hierarchy that represents every possible state the navigation
 * screen can be in.  The ViewModel exposes a LiveData<NavigationState> that the
 * UI observes and renders accordingly.
 */
sealed class NavigationState {

    /** No GPX file has been loaded yet. */
    object NoRoute : NavigationState()

    /**
     * A route is loaded and the user is navigating.
     *
     * @property waypoints         Full ordered list of waypoints.
     * @property currentIndex      Index of the active waypoint (target).
     * @property bearingToTarget   True bearing from device to target, degrees 0-360.
     * @property deviceBearing     Device heading from compass, degrees 0-360.
     * @property distanceToTarget  Straight-line distance to target in metres.
     * @property elapsedDistanceM  Total distance travelled since recording started, metres.
     * @property heartRateBpm      Most recent heart-rate reading (null = no monitor).
     * @property isRecording       Whether the track recorder is active.
     */
    data class Navigating(
        val waypoints: List<Waypoint>,
        val currentIndex: Int,
        val bearingToTarget: Float,
        val deviceBearing: Float,
        val distanceToTarget: Float,
        val elapsedDistanceM: Float,
        val heartRateBpm: Int?,
        val isRecording: Boolean
    ) : NavigationState() {
        /** Convenience: the active waypoint. */
        val currentWaypoint: Waypoint get() = waypoints[currentIndex]

        /** Bearing the on-screen arrow should point, relative to screen-up = 0°. */
        val arrowRotation: Float get() = (bearingToTarget - deviceBearing + 360f) % 360f
    }
}
