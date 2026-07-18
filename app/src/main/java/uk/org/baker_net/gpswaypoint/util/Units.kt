package uk.org.baker_net.gpswaypoint.util

/**
 * Units.kt
 *
 * The measurement unit system used when formatting distances for on-screen
 * display. Deliberately has no Android dependencies so it can be shared by
 * both the pure [GeoUtils] formatting functions and the preferences storage
 * layer without pulling Android classes into JVM-only unit tests.
 */
enum class UnitSystem {
    METRIC,
    IMPERIAL
}
