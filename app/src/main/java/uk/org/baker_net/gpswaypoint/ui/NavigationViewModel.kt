package uk.org.baker_net.gpswaypoint.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import uk.org.baker_net.gpswaypoint.R
import uk.org.baker_net.gpswaypoint.model.NavigationState
import uk.org.baker_net.gpswaypoint.model.Waypoint
import uk.org.baker_net.gpswaypoint.service.NavigationService
import uk.org.baker_net.gpswaypoint.util.GeoUtils
import uk.org.baker_net.gpswaypoint.util.GpxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.org.baker_net.gpswaypoint.model.LocationState

/**
 * NavigationViewModel.kt
 *
 * AndroidViewModel that bridges the [NavigationService] and the UI.
 *
 * Responsibilities:
 *   - Parsing GPX files on a background coroutine and forwarding the result to the service.
 *   - Computing and publishing a [NavigationState] LiveData whenever any piece of state
 *     changes in the service (location, compass, HR, waypoint index).
 *   - Delegating user actions (next/prev WP, start/stop recording) to the service.
 *
 * The ViewModel holds a reference to the bound [NavigationService] which is set by
 * [MainActivity] after the service bind completes.
 */
class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    // -------------------------------------------------------------------------
    // LiveData
    // -------------------------------------------------------------------------

    private val _navigationState = MutableLiveData<NavigationState>(NavigationState.NoRoute)

    /** The last observed value of the HR monitor name, used to detect changes. */
    private var lastHrMonName: String? = null

    /**
     * Observable navigation state consumed by [MainActivity].
     * Emits a new value whenever position, heading, heart rate, or waypoint changes.
     */
    val navigationState: LiveData<NavigationState> = _navigationState

    private val _toastMessage = MutableLiveData<String?>()

    /**
     * One-shot messages to display as Toasts.  The observer must reset this to null
     * after consuming the event.
     */
    val toastMessage: LiveData<String?> = _toastMessage

    private val _locationState = MutableLiveData<LocationState>()
    val locationState: LiveData<LocationState> = _locationState

    // -------------------------------------------------------------------------
    // Service reference
    // -------------------------------------------------------------------------

    /** Set by MainActivity when the service bind is established. */
    var service: NavigationService? = null
        set(value) {
            field = value
            value?.onStateChanged = { refreshState() }
            refreshState()
        }

    // -------------------------------------------------------------------------
    // GPX loading
    // -------------------------------------------------------------------------

    /**
     * Parses a GPX file from the provided [uri] (obtained via SAF file picker)
     * on an IO coroutine and loads the resulting waypoints into the service.
     *
     * Input:
     *   @param uri Content URI pointing to the GPX file (e.g. content://…).
     *
     * Output:
     *   Updates [navigationState] via [refreshState] after loading.
     *   Posts a toast if parsing fails or the file is empty.
     */
    fun loadGpxFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: run {
                        _toastMessage.postValue("Cannot open file")
                        return@launch
                    }
                val waypoints: List<Waypoint> = stream.use { GpxParser.parse(it) }
                if (waypoints.isEmpty()) {
                    _toastMessage.postValue("No waypoints found in GPX file")
                    return@launch
                }
                service?.startNavigating()
                service?.loadWaypoints(waypoints)
                _toastMessage.postValue("Loaded ${waypoints.size} waypoints")
            } catch (e: Exception) {
                _toastMessage.postValue("Failed to parse GPX: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // User actions
    // -------------------------------------------------------------------------

    /**
     * Requests the service to advance to the next waypoint.
     *
     * Input:  none
     * Output: [navigationState] updated with the new waypoint index.
     */
    fun nextWaypoint() {
        service?.nextWaypoint()
    }

    /**
     * Requests the service to go back to the previous waypoint.
     *
     * Input:  none
     * Output: [navigationState] updated with the new waypoint index.
     */
    fun previousWaypoint() {
        service?.previousWaypoint()
    }

    /**
     * Starts or stops the track recorder depending on its current state.
     *
     * Input:  none
     * Output: [isRecording] toggled on the service; [navigationState] updated.
     *         Emits a toast with the save path when recording stops.
     */
    fun toggleRecording() {
        val svc = service ?: return
        if (svc.isRecording) {
            val file = svc.stopRecording()
            _toastMessage.postValue(
                if (file != null) "Saved: ${file.name}" else "Save failed"
            )
        } else {
            svc.startNavigating()
            svc.startRecording()
        }
        refreshState()
    }

    /**
     * Stop navigation functions and hence battery usage
     *
     * Input: none
     * Output: none
     */
    fun stopNav() {
        _navigationState.postValue(NavigationState.NoRoute)
        val svc = service ?: return
        svc.stopNavigating()
    }

    // -------------------------------------------------------------------------
    // State computation
    // -------------------------------------------------------------------------

    /**
     * Reads the latest values from the bound service and posts a new [NavigationState]
     * to [_navigationState].  Called on every state-change callback from the service.
     *
     * Input:  none (reads service fields directly)
     * Output: [_navigationState] updated.
     */
    private fun refreshState() {
        val svc = service

        _locationState.postValue(
            LocationState(
                isRecording = svc?.isRecording ?: false,
                gpsAccuracy = svc?.gpsAccuracy,
                elapsedDistanceM = svc?.elapsedDistanceM ?: 0f,
                heartRateBpm = svc?.lastHeartRate
            )
        )

        if (svc?.hrMonitorName != lastHrMonName) {
            val name = svc?.hrMonitorName
            _toastMessage.postValue(if (name == null) "HR Monitor Disconnected" else "$name connected")
            lastHrMonName = name
        }

        val wps = svc?.waypoints ?: emptyList()
        if (wps.isEmpty()) {
            _navigationState.postValue(NavigationState.NoRoute)
            return
        }

        val idx = svc!!.currentWaypointIndex
        val target = wps[idx]
        val loc = svc.lastLocation

        val bearingToTarget: Float
        val distanceToTarget: Float

        if (loc != null) {
            bearingToTarget = GeoUtils.bearing(
                loc.latitude, loc.longitude,
                target.latitude, target.longitude
            )
            distanceToTarget = GeoUtils.haversineDistance(
                loc.latitude, loc.longitude,
                target.latitude, target.longitude
            ).toFloat()
        } else {
            bearingToTarget = 0f
            distanceToTarget = 0f
        }

        _navigationState.postValue(
            NavigationState.Navigating(
                waypoints         = wps,
                currentIndex      = idx,
                bearingToTarget   = bearingToTarget,
                deviceBearing     = svc.deviceBearing,
                distanceToTarget  = distanceToTarget
            )
        )
    }

    override fun onCleared() {
        service?.onStateChanged = null
        super.onCleared()
    }
}
