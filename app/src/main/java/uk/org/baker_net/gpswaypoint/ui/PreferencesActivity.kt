package uk.org.baker_net.gpswaypoint.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uk.org.baker_net.gpswaypoint.R
import uk.org.baker_net.gpswaypoint.data.HeartRateMonitorMode
import uk.org.baker_net.gpswaypoint.databinding.ActivityPreferencesBinding
import uk.org.baker_net.gpswaypoint.util.GeoUtils

/**
 * PreferencesActivity.kt
 *
 * Lets the user choose:
 *   - The measurement unit system (metric or imperial) used throughout the
 *     navigation display.
 *   - Which heart-rate monitor to use when a recording/navigation session
 *     starts: any compatible monitor, none (disabled), or one specific monitor
 *     picked from a live BLE scan.
 *
 * The BLE scan for the monitor drop-down runs for as long as this screen is
 * visible (started in onResume, stopped in onPause) so the list grows as new
 * monitors are switched on nearby, without connecting to any of them.
 * Choices are only persisted when the user taps Save.
 */
class PreferencesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreferencesBinding
    private val viewModel: PreferencesViewModel by viewModels()

    private lateinit var monitorAdapter: ArrayAdapter<String>

    /** Currently backing list for [monitorAdapter], kept in sync with the ViewModel. */
    private var currentOptions: List<HrMonitorOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreferencesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarPreferences)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupUnitsRadioGroup()
        setupMonitorSpinner()
        setupSaveButton()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        if (hasBluetoothScanPermission()) {
            viewModel.startScanning()
        } else {
            Toast.makeText(
                this, getString(R.string.preferences_bluetooth_permission_missing), Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopScanning()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /**
     * Selects the radio button matching the currently saved unit system.
     *
     * Input:  none
     * Output: [binding.rgUnits] selection set.
     */
    private fun setupUnitsRadioGroup() {
        when (viewModel.savedUnitSystem) {
            GeoUtils.UnitSystem.METRIC -> binding.rbMetric.isChecked = true
            GeoUtils.UnitSystem.IMPERIAL -> binding.rbImperial.isChecked = true
        }
    }

    /**
     * Creates the spinner adapter and observes the ViewModel's live list of
     * monitor options, keeping the spinner's current selection stable as new
     * devices are appended to the list.
     *
     * Input:  none
     * Output: [binding.spinnerHrMonitor] populated and kept up to date.
     */
    private fun setupMonitorSpinner() {
        monitorAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        monitorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerHrMonitor.adapter = monitorAdapter
    }

    /**
     * Observes [PreferencesViewModel.monitorOptions] and rebuilds the spinner
     * contents whenever the discovered-device list changes, preserving the
     * previously-selected option (by identity, i.e. same mode/address) if it's
     * still present, and otherwise selecting the saved preference the first
     * time the list is populated.
     *
     * Input:  none
     * Output: [monitorAdapter] and [binding.spinnerHrMonitor] updated.
     */
    private fun observeViewModel() {
        viewModel.monitorOptions.observe(this) { options ->
            val previousSelection = currentSelectedOption()
            currentOptions = options

            monitorAdapter.clear()
            monitorAdapter.addAll(options.map { labelFor(it) })
            monitorAdapter.notifyDataSetChanged()

            val indexToSelect = when {
                previousSelection != null -> options.indexOfOption(previousSelection)
                else -> options.indexOfOption(savedOptionEquivalent())
            }
            if (indexToSelect >= 0) {
                binding.spinnerHrMonitor.setSelection(indexToSelect)
            }
        }
    }

    /**
     * Wires the Save button to persist the current selections.
     *
     * Input:  none
     * Output: On click, preferences saved and the screen closes.
     */
    private fun setupSaveButton() {
        binding.btnSavePreferences.setOnClickListener {
            val units = if (binding.rbImperial.isChecked) GeoUtils.UnitSystem.IMPERIAL else GeoUtils.UnitSystem.METRIC
            val selectedMonitor = currentOptions.getOrNull(binding.spinnerHrMonitor.selectedItemPosition)
                ?: HrMonitorOption.Any
            viewModel.savePreferences(units, selectedMonitor)
            Toast.makeText(this, getString(R.string.preferences_saved_toast), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * Returns the option currently selected in the spinner, if any.
     *
     * Input:  none
     * Output: @return The selected [HrMonitorOption], or null if nothing is selected yet.
     */
    private fun currentSelectedOption(): HrMonitorOption? {
        val pos = binding.spinnerHrMonitor.selectedItemPosition
        return currentOptions.getOrNull(pos)
    }

    /**
     * Builds an [HrMonitorOption] equivalent to the preference saved before
     * this screen was opened, used to pre-select the spinner on first load.
     *
     * Input:  none
     * Output: @return [HrMonitorOption] matching the saved preference.
     */
    private fun savedOptionEquivalent(): HrMonitorOption {
        val saved = viewModel.savedHeartRateMonitorPreference
        return when (saved.mode) {
            HeartRateMonitorMode.NONE -> HrMonitorOption.None
            HeartRateMonitorMode.DEVICE -> saved.deviceAddress?.let {
                HrMonitorOption.Device(it, saved.deviceName ?: it)
            } ?: HrMonitorOption.Any
            HeartRateMonitorMode.ANY -> HrMonitorOption.Any
        }
    }

    /**
     * Finds the index of an option matching [target] by mode/address rather
     * than by list identity, since the list is rebuilt on every update.
     *
     * Input:  @param target Option to look for.
     * Output: @return Index in the receiver list, or -1 if not found.
     */
    private fun List<HrMonitorOption>.indexOfOption(target: HrMonitorOption): Int =
        indexOfFirst { sameOption(it, target) }

    private fun sameOption(a: HrMonitorOption, b: HrMonitorOption): Boolean = when {
        a is HrMonitorOption.Any && b is HrMonitorOption.Any -> true
        a is HrMonitorOption.None && b is HrMonitorOption.None -> true
        a is HrMonitorOption.Device && b is HrMonitorOption.Device -> a.address == b.address
        else -> false
    }

    /**
     * Human-readable label for a monitor option, shown in the spinner.
     *
     * Input:  @param option Option to label.
     * Output: @return Display string.
     */
    private fun labelFor(option: HrMonitorOption): String = when (option) {
        is HrMonitorOption.Any -> getString(R.string.preferences_hr_any)
        is HrMonitorOption.None -> getString(R.string.preferences_hr_none)
        is HrMonitorOption.Device -> "${option.name} (${option.address})"
    }

    /**
     * Checks whether the app currently holds the BLE scan permission needed
     * to run discovery on this screen.
     *
     * Input:  none
     * Output: @return true if scanning is permitted.
     */
    private fun hasBluetoothScanPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN
        else
            Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }
}
