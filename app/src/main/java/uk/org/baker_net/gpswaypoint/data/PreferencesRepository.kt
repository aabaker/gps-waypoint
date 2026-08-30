package uk.org.baker_net.gpswaypoint.data

import android.content.Context
import uk.org.baker_net.gpswaypoint.util.GeoUtils

/**
 * How the app selects a BLE heart-rate monitor when a navigation or
 * recording session starts.
 *
 *   ANY    – connect to the first compatible monitor found.
 *   NONE   – heart-rate monitoring is disabled entirely; no scan is started.
 *   DEVICE – connect only to the specific monitor identified by
 *            [HeartRateMonitorPreference.deviceAddress].
 */
enum class HeartRateMonitorMode {
    ANY,
    NONE,
    DEVICE
}

/**
 * Heart-rate monitor preference: the selection [mode] plus, when [mode] is
 * [HeartRateMonitorMode.DEVICE], the specific monitor's BLE address and
 * last-known display name.
 */
data class HeartRateMonitorPreference(
    val mode: HeartRateMonitorMode,
    val deviceAddress: String? = null,
    val deviceName: String? = null
)

/**
 * The map screen's background style.
 *
 *   BLACK – a plain black background; no map tiles are downloaded and no
 *           network access is performed.
 *   OSM   – live OpenStreetMap raster tiles are downloaded and displayed.
 */
enum class MapBackground {
    BLACK,
    OSM
}

/**
 * PreferencesRepository.kt
 *
 * Typed façade over [PreferencesDbHelper] exposing the app's user
 * preferences: the measurement unit system and the heart-rate monitor
 * selection. Used both by the preferences screen (to read/write choices)
 * and by [uk.org.baker_net.gpswaypoint.service.NavigationService] (to decide
 * how to format distances and whether/which monitor to connect to).
 *
 * All operations are synchronous SQLite calls on a single small row, so
 * callers may use this directly from the main thread.
 */
class PreferencesRepository(context: Context) {

    companion object {
        const val KEY_UNIT_SYSTEM = "unit_system"
        const val KEY_HR_MODE = "hr_mode"
        const val KEY_HR_DEVICE_ADDRESS = "hr_device_address"
        const val KEY_HR_DEVICE_NAME = "hr_device_name"
        const val KEY_MAP_BACKGROUND = "map_background"
    }

    private val db = PreferencesDbHelper(context)

    /**
     * Reads the current measurement unit system.
     *
     * Input:  none
     * Output: @return The saved [UnitSystem], or [UnitSystem.METRIC] if never set.
     */
    fun getUnitSystem(): GeoUtils.UnitSystem =
        when (db.get(KEY_UNIT_SYSTEM)) {
            GeoUtils.UnitSystem.IMPERIAL.name -> GeoUtils.UnitSystem.IMPERIAL
            else -> GeoUtils.UnitSystem.METRIC
        }

    /**
     * Stores the measurement unit system.
     *
     * Input:  @param units New [UnitSystem] to persist.
     * Output: Preference saved to the database.
     */
    fun setUnitSystem(units: GeoUtils.UnitSystem) {
        db.set(KEY_UNIT_SYSTEM, units.name)
    }

    /**
     * Reads the current heart-rate monitor preference.
     *
     * Input:  none
     * Output: @return The saved [HeartRateMonitorPreference], or mode
     *         [HeartRateMonitorMode.ANY] (matching the app's original,
     *         pre-preferences behaviour) if never set.
     */
    fun getHeartRateMonitorPreference(): HeartRateMonitorPreference {
        val mode = when (db.get(KEY_HR_MODE)) {
            HeartRateMonitorMode.NONE.name -> HeartRateMonitorMode.NONE
            HeartRateMonitorMode.DEVICE.name -> HeartRateMonitorMode.DEVICE
            else -> HeartRateMonitorMode.ANY
        }
        return HeartRateMonitorPreference(
            mode = mode,
            deviceAddress = db.get(KEY_HR_DEVICE_ADDRESS),
            deviceName = db.get(KEY_HR_DEVICE_NAME)
        )
    }

    /**
     * Stores the heart-rate monitor preference.
     *
     * Input:  @param preference New [HeartRateMonitorPreference] to persist.
     * Output: Preference saved to the database; the stored device
     *         address/name are cleared unless [preference].mode is
     *         [HeartRateMonitorMode.DEVICE].
     */
    fun setHeartRateMonitorPreference(preference: HeartRateMonitorPreference) {
        db.set(KEY_HR_MODE, preference.mode.name)
        if (preference.mode == HeartRateMonitorMode.DEVICE) {
            db.set(KEY_HR_DEVICE_ADDRESS, preference.deviceAddress)
            db.set(KEY_HR_DEVICE_NAME, preference.deviceName)
        } else {
            db.set(KEY_HR_DEVICE_ADDRESS, null)
            db.set(KEY_HR_DEVICE_NAME, null)
        }
    }

    /**
     * Reads the current map background preference.
     *
     * Input:  none
     * Output: @return The saved [MapBackground], or [MapBackground.BLACK] if
     *         never set, so the map performs no network access by default.
     */
    fun getMapBackground(): MapBackground =
        when (db.get(KEY_MAP_BACKGROUND)) {
            MapBackground.OSM.name -> MapBackground.OSM
            else -> MapBackground.BLACK
        }

    /**
     * Stores the map background preference.
     *
     * Input:  @param background New [MapBackground] to persist.
     * Output: Preference saved to the database.
     */
    fun setMapBackground(background: MapBackground) {
        db.set(KEY_MAP_BACKGROUND, background.name)
    }
}
