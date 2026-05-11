package com.example.gpswaypoint.model

import org.junit.Assert.*
import org.junit.Test

/**
 * NavigationStateTest.kt
 *
 * Unit tests for the computed properties on [NavigationState.Navigating].
 */
class NavigationStateTest {

    private fun makeState(
        bearingToTarget: Float = 90f,
        deviceBearing: Float = 0f,
        currentIndex: Int = 0
    ) = NavigationState.Navigating(
        waypoints         = listOf(
            Waypoint("A", 51.5, 0.0),
            Waypoint("B", 51.6, 0.1)
        ),
        currentIndex      = currentIndex,
        bearingToTarget   = bearingToTarget,
        deviceBearing     = deviceBearing,
        distanceToTarget  = 500f,
        elapsedDistanceM  = 100f,
        heartRateBpm      = null,
        isRecording       = false
    )

    // -------------------------------------------------------------------------
    // arrowRotation
    // -------------------------------------------------------------------------

    @Test
    fun arrowRotation_targetDueEastDeviceFacingNorth_returns90() {
        // Target is East (90°), device faces North (0°) → arrow points 90° right
        val state = makeState(bearingToTarget = 90f, deviceBearing = 0f)
        assertEquals(90f, state.arrowRotation, 0.001f)
    }

    @Test
    fun arrowRotation_targetDueNorthDeviceFacingEast_returns270() {
        // Target is North (0°), device faces East (90°) → arrow points 270°
        val state = makeState(bearingToTarget = 0f, deviceBearing = 90f)
        assertEquals(270f, state.arrowRotation, 0.001f)
    }

    @Test
    fun arrowRotation_sameDirectionAsTarget_returns0() {
        // Device faces exactly toward the target
        val state = makeState(bearingToTarget = 135f, deviceBearing = 135f)
        assertEquals(0f, state.arrowRotation, 0.001f)
    }

    @Test
    fun arrowRotation_alwaysInRange0To360() {
        for (bearing in 0..350 step 10) {
            for (device in 0..350 step 10) {
                val state = makeState(
                    bearingToTarget = bearing.toFloat(),
                    deviceBearing   = device.toFloat()
                )
                val rot = state.arrowRotation
                assertTrue("Rotation $rot not in [0,360)", rot >= 0f && rot < 360f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // currentWaypoint convenience property
    // -------------------------------------------------------------------------

    @Test
    fun currentWaypoint_indexZero_returnsFirstWaypoint() {
        val state = makeState(currentIndex = 0)
        assertEquals("A", state.currentWaypoint.name)
    }

    @Test
    fun currentWaypoint_indexOne_returnsSecondWaypoint() {
        val state = makeState(currentIndex = 1)
        assertEquals("B", state.currentWaypoint.name)
    }
}
