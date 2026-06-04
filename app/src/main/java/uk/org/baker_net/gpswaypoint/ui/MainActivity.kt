package uk.org.baker_net.gpswaypoint.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uk.org.baker_net.gpswaypoint.R
import uk.org.baker_net.gpswaypoint.databinding.ActivityMainBinding
import uk.org.baker_net.gpswaypoint.model.NavigationState
import uk.org.baker_net.gpswaypoint.service.NavigationService
import uk.org.baker_net.gpswaypoint.util.GeoUtils

/**
 * MainActivity.kt
 *
 * The single Activity of the GPS Waypoint Navigator app.
 *
 * Responsibilities:
 *   1. Request ACCESS_FINE_LOCATION (and optionally BLUETOOTH permissions).
 *   2. Bind to [NavigationService] and hand the binder reference to [NavigationViewModel].
 *   3. Observe [NavigationViewModel.navigationState] and render the UI.
 *   4. Handle toolbar menu actions (load GPX, start/stop recording).
 *   5. Shut down cleanly if location permission is denied.
 */
class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // View binding & ViewModel
    // -------------------------------------------------------------------------

    private lateinit var binding: ActivityMainBinding
    private val viewModel: NavigationViewModel by viewModels()

    // -------------------------------------------------------------------------
    // Service binding
    // -------------------------------------------------------------------------

    private var navigationService: NavigationService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as NavigationService.NavigationBinder
            navigationService = b.getService()
            viewModel.service = navigationService
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            navigationService = null
            viewModel.service = null
            serviceBound = false
        }
    }

    // -------------------------------------------------------------------------
    // Permission launchers
    // -------------------------------------------------------------------------

    /**
     * Launcher for the ACCESS_FINE_LOCATION permission request.
     * If denied the app shows a message and finishes.
     */
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fineGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!fineGranted) {
                Toast.makeText(
                    this,
                    "Location permission is required for navigation. Closing.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@registerForActivityResult
            }
            // Permission granted – start the service and GPS
            startNavigationService()
            // Also request BLE permissions now (non-fatal if denied)
            requestBluetoothPermissions()
        }

    /**
     * Launcher for BLE permissions (Android 12+).  Non-fatal if denied.
     */
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val anyGranted = perms.values.any { it }
            if (!anyGranted) {
                Toast.makeText(this, "Bluetooth denied – heart rate monitor unavailable", Toast.LENGTH_SHORT).show()
            }
        }

    /**
     * Launcher for the GPX file picker (SAF).
     * Selected URI is forwarded to [NavigationViewModel.loadGpxFile].
     */
    private val gpxPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.loadGpxFile(it) }
        }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupClickListeners()
        observeViewModel()
        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Re-bind in case the activity was recreated
        if (!serviceBound) bindNavigationService()
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // -------------------------------------------------------------------------
    // Menu
    // -------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_load_gpx -> {
                gpxPickerLauncher.launch(arrayOf("*/*"))
                true
            }
            R.id.action_toggle_record -> {
                viewModel.toggleRecording()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    /**
     * Checks current permission state and requests what is needed.
     * Starts the service immediately if location is already granted.
     *
     * Input:  none
     * Output: Permission dialogs may be shown; service started if already permitted.
     */
    private fun checkAndRequestPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            startNavigationService()
            requestBluetoothPermissions()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /**
     * Requests BLE permissions on Android 12+.  On older versions BLUETOOTH
     * and BLUETOOTH_ADMIN are normal permissions declared in the manifest.
     *
     * Input:  none
     * Output: Permission dialog shown on Android 12+.
     */
    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Service management
    // -------------------------------------------------------------------------

    /**
     * Starts the [NavigationService] as a foreground service and binds to it.
     *
     * Input:  none
     * Output: Service started; [serviceConnection] callback will fire.
     */
    private fun startNavigationService() {
        val intent = Intent(this, NavigationService::class.java)
        startForegroundService(intent)
        bindNavigationService()
    }

    /**
     * Binds this activity to the running [NavigationService].
     *
     * Input:  none
     * Output: [serviceConnection.onServiceConnected] will be called.
     */
    private fun bindNavigationService() {
        val intent = Intent(this, NavigationService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    /**
     * Attaches click handlers for the Prev / Next waypoint buttons and the
     * record toggle button.
     *
     * Input:  none
     * Output: Button listeners installed.
     */
    private fun setupClickListeners() {
        binding.btnPrevWaypoint.setOnClickListener { viewModel.previousWaypoint() }
        binding.btnNextWaypoint.setOnClickListener { viewModel.nextWaypoint()  }
        binding.btnRecord.setOnClickListener { viewModel.toggleRecording() }
        binding.btnStop.setOnClickListener { viewModel.stopNav() }
    }

    /**
     * Sets up LiveData observers that drive all UI updates.
     *
     * Input:  none
     * Output: Observers attached to [viewModel].
     */
    private fun observeViewModel() {
        viewModel.navigationState.observe(this) { state -> renderNavState(state) }
        viewModel.toastMessage.observe(this) { msg ->
            msg?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }
        viewModel.locationState.observe(this) { state ->
            // Update record button label
            binding.btnRecord.apply {
                text = if (state.isRecording)
                    getString(R.string.stop_recording)
                else
                    getString(R.string.start_recording)
            }
            binding.tvAccuracy.text = if (state.gpsAccuracy != null) GeoUtils.formatDistance(state.gpsAccuracy) else "--"
            binding.tvElapsed.text = if (state.isRecording) GeoUtils.formatDistance(state.elapsedDistanceM) else "--"
            binding.tvHeartRate.text = state.heartRateBpm
                ?.let { "$it bpm" }
                ?: getString(R.string.hr_not_connected)
        }
    }

    /**
     * Renders the current [NavigationState] onto all UI widgets.
     *
     * Input:  @param state The latest navigation state from the ViewModel.
     * Output: All text views, arrow view, and button labels updated.
     */
    private fun renderNavState(state: NavigationState) {
        when (state) {
            is NavigationState.NoRoute -> {
                binding.tvWaypointName.text    = getString(R.string.no_route_loaded)
                binding.tvDistance.text        = "--"
                binding.tvBearing.text         = "--"
                binding.tvRemain.text          = "--"
                binding.tvWaypointCounter.text = "- / -"
                binding.arrowView.arrowRotationDeg = 0f
                binding.arrowView.hasValidFix  = false
                binding.btnStop.isEnabled      = false
            }

            is NavigationState.Navigating -> {
                val wp = state.currentWaypoint
                binding.tvWaypointName.text = wp.name
                binding.tvDistance.text     = GeoUtils.formatDistance(state.distanceToTarget)
                binding.tvBearing.text      = GeoUtils.formatBearing(state.bearingToTarget)
                binding.tvRemain.text       = GeoUtils.formatDistance(state.distanceToTarget +
                        state.currentWaypoint.distanceRemain)
                binding.tvWaypointCounter.text =
                    "${state.currentIndex + 1} / ${state.waypoints.size}"


                binding.arrowView.arrowRotationDeg = state.arrowRotation
                binding.arrowView.hasValidFix      = true
                navigationService?.let { binding.btnStop.isEnabled = !it.isRecording }
            }
        }
    }
}
