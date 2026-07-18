package uk.org.baker_net.gpswaypoint.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import uk.org.baker_net.gpswaypoint.ble.HeartRateManager
import uk.org.baker_net.gpswaypoint.data.HeartRateMonitorMode
import uk.org.baker_net.gpswaypoint.data.HeartRateMonitorPreference
import uk.org.baker_net.gpswaypoint.data.PreferencesRepository
import uk.org.baker_net.gpswaypoint.util.UnitSystem

/**
 * A single row in the heart-rate monitor drop-down: either the fixed "Any"
 * or "None" choice, or a specific BLE device discovered while this screen
 * is open.
 */
sealed class HrMonitorOption {
    /** Accept any compatible heart-rate monitor found when a recording/navigation starts. */
    object Any : HrMonitorOption()

    /** Disable heart-rate monitoring entirely. */
    object None : HrMonitorOption()

    /** A specific BLE monitor, identified by its address. */
    data class Device(val address: String, val name: String) : HrMonitorOption()
}

/**
 * PreferencesViewModel.kt
 *
 * Backs [PreferencesActivity]. Responsibilities:
 *   - Loading the currently saved unit system and heart-rate monitor preference.
 *   - Running a live BLE discovery scan (via [HeartRateManager.startDiscovery])
 *     while the preferences screen is open, so the monitor drop-down grows as
 *     new devices are found, without connecting to any of them.
 *   - Saving the user's choices back to [PreferencesRepository] on request.
 */
class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = PreferencesRepository(application)
    private var heartRateManager: HeartRateManager? = null

    /** Fixed choices plus any devices discovered so far, in display order. */
    private val _monitorOptions = MutableLiveData<List<HrMonitorOption>>(
        listOf(HrMonitorOption.Any, HrMonitorOption.None)
    )
    val monitorOptions: LiveData<List<HrMonitorOption>> = _monitorOptions

    /** The unit system as currently saved (read once when the screen opens). */
    val savedUnitSystem: UnitSystem = preferencesRepository.getUnitSystem()

    /** The heart-rate monitor preference as currently saved (read once when the screen opens). */
    val savedHeartRateMonitorPreference: HeartRateMonitorPreference =
        preferencesRepository.getHeartRateMonitorPreference()

    init {
        // Make sure a previously-selected specific device is present in the
        // drop-down immediately, even before it's re-discovered by the scan.
        val saved = savedHeartRateMonitorPreference
        if (saved.mode == HeartRateMonitorMode.DEVICE && saved.deviceAddress != null) {
            addDiscoveredDevice(saved.deviceAddress, saved.deviceName ?: saved.deviceAddress)
        }
    }

    /**
     * Starts a continuous BLE scan for heart-rate monitors so the drop-down
     * list can grow live while the user is on this screen. Safe to call
     * multiple times (e.g. from onResume); does not connect to any device.
     *
     * Input:  none
     * Output: [monitorOptions] gains a new entry each time a new monitor is found.
     */
    fun startScanning() {
        if (heartRateManager != null) return
        val manager = HeartRateManager(getApplication(), object : HeartRateManager.HeartRateCallback {
            override fun onHeartRate(bpm: Int) { /* not used during discovery */ }
            override fun onConnectionStateChanged(connected: Boolean, name: String?) { /* not used during discovery */ }
        })
        heartRateManager = manager
        manager.startDiscovery(object : HeartRateManager.DiscoveryCallback {
            override fun onDeviceFound(address: String, name: String?) {
                addDiscoveredDevice(address, name ?: address)
            }
        })
    }

    /**
     * Stops the BLE discovery scan. Must be called when the screen is no
     * longer visible (e.g. onPause/onStop) to avoid draining the battery.
     *
     * Input:  none
     * Output: none
     */
    fun stopScanning() {
        heartRateManager?.stopDiscovery()
        heartRateManager = null
    }

    /**
     * Adds a newly discovered device to [monitorOptions] if it isn't already
     * present, keyed by BLE address.
     *
     * Input:  @param address BLE MAC address of the device.
     *         @param name Display name to show in the drop-down.
     * Output: [monitorOptions] updated with the new entry appended.
     */
    private fun addDiscoveredDevice(address: String, name: String) {
        val current = _monitorOptions.value.orEmpty()
        if (current.any { it is HrMonitorOption.Device && it.address == address }) return
        _monitorOptions.value = current + HrMonitorOption.Device(address, name)
    }

    /**
     * Persists the user's chosen unit system and heart-rate monitor option.
     *
     * Input:  @param units Selected [UnitSystem].
     *         @param monitor Selected [HrMonitorOption].
     * Output: Preferences saved to the database.
     */
    fun savePreferences(units: UnitSystem, monitor: HrMonitorOption) {
        preferencesRepository.setUnitSystem(units)
        val preference = when (monitor) {
            is HrMonitorOption.Any -> HeartRateMonitorPreference(HeartRateMonitorMode.ANY)
            is HrMonitorOption.None -> HeartRateMonitorPreference(HeartRateMonitorMode.NONE)
            is HrMonitorOption.Device -> HeartRateMonitorPreference(
                mode = HeartRateMonitorMode.DEVICE,
                deviceAddress = monitor.address,
                deviceName = monitor.name
            )
        }
        preferencesRepository.setHeartRateMonitorPreference(preference)
    }

    override fun onCleared() {
        stopScanning()
        super.onCleared()
    }
}
