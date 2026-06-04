package uk.org.baker_net.gpswaypoint.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import uk.org.baker_net.gpswaypoint.R
import uk.org.baker_net.gpswaypoint.ble.HeartRateManager
import uk.org.baker_net.gpswaypoint.model.TrackPoint
import uk.org.baker_net.gpswaypoint.model.Waypoint
import uk.org.baker_net.gpswaypoint.ui.MainActivity
import uk.org.baker_net.gpswaypoint.util.GeoUtils
import uk.org.baker_net.gpswaypoint.util.TcxWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * NavigationService.kt
 *
 * Foreground service that owns GPS location updates, compass heading updates,
 * BLE heart-rate reception, and track recording.  Running as a foreground service
 * ensures the OS does not suspend GPS callbacks when the app moves to the background.
 *
 * Clients bind to this service and interact through [NavigationBinder].
 *
 * Lifecycle:
 *   1. MainActivity binds via [Context.bindService].
 *   2. MainActivity calls [startForeground] when the user grants location permission.
 *   3. On unbind the service continues running (GPS stays alive).
 *   4. [stopSelf] is called when the user explicitly quits or denies permission.
 */
class NavigationService : Service() {

    companion object {
        private const val TAG = "NavigationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "navigation_channel"

        /** Minimum distance change (metres) between GPS fixes delivered to the listener. */
        private const val GPS_MIN_DISTANCE_M = 5f

        /** Minimum time (milliseconds) between GPS fixes. */
        private const val GPS_MIN_TIME_MS = 2_000L

        /** Waypoint auto-advance radius in metres. */
        const val ARRIVAL_RADIUS_M = 25.0

        /** Recording interval: store a TrackPoint every N metres of movement. */
        private const val RECORD_MIN_DISTANCE_M = 5.0

        /** Maximum time since last heart rate update before heart rate data is ignored. */
        private val MAX_DATA_AGE: Duration = 20.seconds

        /** Intent action used to bring MainActivity to front from the notification. */
        const val ACTION_NAVIGATE = "uk.org.baker_net.gpswaypoint.NAVIGATE"

        /** The proportion of the new value that is used in the bearing rolling average */
        const val BEARING_UPDATE_PROPORTION = 0.15f
    }

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------

    /** Public interface exposed to bound clients. */
    inner class NavigationBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    private val binder = NavigationBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------



    /** Ordered list of waypoints loaded from a GPX file. */
    var waypoints: List<Waypoint> = emptyList()
        private set

    /** Index of the currently targeted waypoint. */
    var currentWaypointIndex: Int = 0
        private set

    /** Most recent device location (null until first GPS fix). */
    var lastLocation: Location? = null
        private set

    /** Accuracy associated with last GPS position */
    var gpsAccuracy: Float? = null
        private set

    /** Most recent compass heading in degrees [0, 360). */
    var deviceBearing: Float = 0f
        private set

    /** Most recent heart-rate reading in BPM (null = no monitor). */
    var lastHeartRate: Int? = null
        private set

    /** The name of the connected HR monitor */
    var hrMonitorName: String? = null

    /** Whether the track recorder is active. */
    var isRecording: Boolean = false
        private set

    /** Cumulative distance travelled during the current recording in metres. */
    var elapsedDistanceM: Float = 0f
        private set

    /** Called whenever any navigation state changes (location, bearing, HR, WP index). */
    var onStateChanged: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /** The timer used to measure age of sensor data, needs t */
    private val timeSource = TimeSource.Monotonic

    /** When heart rate was last updated */
    var lastHeartRateTime: TimeSource.Monotonic.ValueTimeMark = timeSource.markNow()

    /** When Location was last updated */
    var lastGpsTime: TimeSource.Monotonic.ValueTimeMark = timeSource.markNow()

    private val handler = Handler(Looper.getMainLooper())

    private var isNavigating = false
    private val trackPoints = mutableListOf<TrackPoint>()
    private var lastRecordedLocation: Location? = null

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private var accelerometerValues: FloatArray? = null
    private var magnetometerValues: FloatArray? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var heartRateManager: HeartRateManager? = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initialising…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopNavigating()
        super.onDestroy()
    }

    fun startNavigating() {
        if (!isNavigating) {
            startGps()
            startCompass()
            startHeartRateMonitor()
            isNavigating = true
            handler.post(tickRunnable)
        }
    }

    fun stopNavigating() {
        stopGps()
        stopCompass()
        heartRateManager?.disconnect()
        lastHeartRate = null
        waypoints = emptyList()
        elapsedDistanceM = 0f
        gpsAccuracy = null
        isNavigating = false
        handler.removeCallbacks(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (isNavigating) {
                onTick()
                handler.postDelayed(this, 1000L)
            }
        }
    }


    // -------------------------------------------------------------------------
    // Timer
    // -------------------------------------------------------------------------

    private fun onTick() {
        // Check if heart rate data is stale
        if (lastHeartRateTime < timeSource.markNow() - MAX_DATA_AGE) {
            lastHeartRate = null
        }
        // Check if GPS data is stale
        if (lastGpsTime < timeSource.markNow() - MAX_DATA_AGE) {
            gpsAccuracy = null
        }
        onStateChanged?.invoke()
    }
    // -------------------------------------------------------------------------
    // GPS
    // -------------------------------------------------------------------------

    private val locationListener = object : LocationListener {
        /**
         * Called by the system with each new GPS fix.
         *
         * Input:  @param location The new device [Location].
         * Output: Updates [lastLocation], advances waypoint if within arrival radius,
         *         appends a [TrackPoint] if recording, notifies [onStateChanged].
         */
        override fun onLocationChanged(location: Location) {
            val prev = lastLocation
            lastLocation = location
            gpsAccuracy = location.accuracy
            lastGpsTime = timeSource.markNow()

            // Accumulate elapsed distance
            if (prev != null) {
                val delta = prev.distanceTo(location).toDouble()
                if (isRecording) {
                    elapsedDistanceM += delta.toFloat()
                    maybeRecordTrackPoint(location)
                }
            }

            // Auto-advance waypoint if within arrival radius
            checkWaypointArrival(location)

            updateNotification()
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * Registers for GPS updates from the system LocationManager.
     * Requires ACCESS_FINE_LOCATION permission – must be granted before calling.
     *
     * Input:  none
     * Output: GPS callbacks begin arriving on [locationListener].
     */
    @Suppress("MissingPermission")
    fun startGps() {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            GPS_MIN_TIME_MS,
            GPS_MIN_DISTANCE_M,
            locationListener,
            Looper.getMainLooper()
        )
        Log.d(TAG, "GPS started")
    }

    /**
     * Removes GPS update listener, stopping battery drain.
     *
     * Input:  none
     * Output: No further location callbacks delivered.
     */
    private fun stopGps() {
        locationManager.removeUpdates(locationListener)
        Log.d(TAG, "GPS stopped")
    }

    // -------------------------------------------------------------------------
    // Compass
    // -------------------------------------------------------------------------

    private val sensorListener = object : SensorEventListener {
        /**
         * Called for each accelerometer or magnetometer reading.
         * Combines both sensor values to derive the device heading using the
         * rotation matrix approach, which compensates for device tilt.
         *
         * Input:  @param event The raw [SensorEvent].
         * Output: Updates [deviceBearing] and calls [onStateChanged].
         */
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    accelerometerValues = event.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD ->
                    magnetometerValues = event.values.clone()
            }
            val acc = accelerometerValues ?: return
            val mag = magnetometerValues ?: return

            if (SensorManager.getRotationMatrix(rotationMatrix, null, acc, mag)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val azimuthRad = orientationAngles[0]  // radians, -π..+π
                deviceBearing = GeoUtils.rollingAverageBearing(deviceBearing,
                    ((Math.toDegrees(azimuthRad.toDouble()) + 360) % 360).toFloat(),
                    BEARING_UPDATE_PROPORTION)

                onStateChanged?.invoke()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    /**
     * Registers accelerometer and magnetometer listeners for compass functionality.
     *
     * Input:  none
     * Output: Compass callbacks begin arriving on [sensorListener].
     */
    private fun startCompass() {
        sensorManager.registerListener(
            sensorListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI
        )
        sensorManager.registerListener(
            sensorListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
            SensorManager.SENSOR_DELAY_UI
        )
        Log.d(TAG, "Compass started")
    }

    /**
     * Unregisters sensor listeners to save battery.
     *
     * Input:  none
     * Output: No further sensor callbacks delivered.
     */
    private fun stopCompass() {
        sensorManager.unregisterListener(sensorListener)
        Log.d(TAG, "Compass stopped")
    }

    // -------------------------------------------------------------------------
    // Waypoint management
    // -------------------------------------------------------------------------

    /**
     * Loads a new set of waypoints and resets navigation to the first waypoint.
     *
     * Input:  @param wps Non-empty ordered list of [Waypoint] objects.
     * Output: [waypoints] and [currentWaypointIndex] are updated; [onStateChanged] called.
     */
    fun loadWaypoints(wps: List<Waypoint>) {
        waypoints = wps
        currentWaypointIndex = 0
        onStateChanged?.invoke()
        Log.d(TAG, "Loaded ${wps.size} waypoints")
    }

    /**
     * Advances to the next waypoint in the list (wraps to 0 at end).
     *
     * Input:  none
     * Output: [currentWaypointIndex] incremented; [onStateChanged] called.
     */
    fun nextWaypoint() {
        if (waypoints.isNotEmpty()) {
            currentWaypointIndex = (currentWaypointIndex + 1) % waypoints.size
            onStateChanged?.invoke()
        }
    }

    /**
     * Moves to the previous waypoint in the list (wraps to last at start).
     *
     * Input:  none
     * Output: [currentWaypointIndex] decremented; [onStateChanged] called.
     */
    fun previousWaypoint() {
        if (waypoints.isNotEmpty()) {
            currentWaypointIndex = (currentWaypointIndex - 1 + waypoints.size) % waypoints.size
            onStateChanged?.invoke()
        }
    }

    /**
     * Checks whether the device has arrived within [ARRIVAL_RADIUS_M] of the
     * current waypoint and, if so, auto-advances to the next one.
     *
     * Input:  @param location Current device [Location].
     * Output: May increment [currentWaypointIndex].
     */
    private fun checkWaypointArrival(location: Location) {
        val wps = waypoints
        if (wps.isEmpty()) return
        val target = wps[currentWaypointIndex]
        val dist = GeoUtils.haversineDistance(
            location.latitude, location.longitude,
            target.latitude, target.longitude
        )
        if (dist <= ARRIVAL_RADIUS_M && currentWaypointIndex < wps.size - 1) {
            Log.d(TAG, "Arrived at waypoint $currentWaypointIndex, advancing")
            currentWaypointIndex++
        }
    }

    // -------------------------------------------------------------------------
    // Track recording
    // -------------------------------------------------------------------------

    /**
     * Starts recording track points and heart-rate data.
     *
     * Input:  none
     * Output: [isRecording] = true; [trackPoints] and [elapsedDistanceM] reset.
     */
    fun startRecording() {
        trackPoints.clear()
        elapsedDistanceM = 0f
        lastRecordedLocation = null
        isRecording = true
        if (!isNavigating) {
            startGps()
            startHeartRateMonitor()
        }
        Log.d(TAG, "Recording started")
    }

    /**
     * Stops recording and writes the accumulated data to a TCX file in the app's
     * external files directory (no storage permission required on API 26+).
     *
     * Input:  none
     * Output: TCX file written; [isRecording] = false.
     *         Returns the [File] written, or null on error.
     */
    fun stopRecording(): File? {
        isRecording = false
        if (!isNavigating) {
            stopGps()
            heartRateManager?.disconnect()
            lastHeartRate = null
            gpsAccuracy = null
        }
        Log.d(TAG, "Recording stopped, ${trackPoints.size} points")
        return saveTcx()
    }

    /**
     * Appends a [TrackPoint] to the recording buffer if the device has moved at
     * least [RECORD_MIN_DISTANCE_M] since the last recorded point.
     *
     * Input:  @param location Current GPS [Location].
     * Output: [trackPoints] list may be extended.
     */
    private fun maybeRecordTrackPoint(location: Location) {
        val last = lastRecordedLocation
        if (last != null && last.distanceTo(location) < RECORD_MIN_DISTANCE_M) return
        lastRecordedLocation = location
        trackPoints.add(
            TrackPoint(
                timestampMs  = location.time,
                latitude     = location.latitude,
                longitude    = location.longitude,
                altitudeM    = if (location.hasAltitude()) location.altitude else null,
                heartRateBpm = lastHeartRate,
                distanceM    = elapsedDistanceM.toDouble()
            )
        )
    }

    /**
     * Writes the in-memory track points to a new TCX file.
     *
     * Input:  none
     * Output: @return The [File] written, or null if an error occurs.
     */
    private fun saveTcx(): File? {
        return try {
            val dir = getExternalFilesDir("recordings") ?: filesDir
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "activity_$ts.tcx")
            file.outputStream().use { TcxWriter.write(it, trackPoints) }
            Log.d(TAG, "TCX saved: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save TCX", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // BLE Heart Rate
    // -------------------------------------------------------------------------

    /**
     * Initialises and starts scanning for a BLE heart-rate monitor.
     *
     * Input:  none
     * Output: [heartRateManager] created and scan started.
     */
    private fun startHeartRateMonitor() {
        heartRateManager = HeartRateManager(this, object : HeartRateManager.HeartRateCallback {
            override fun onHeartRate(bpm: Int) {
                lastHeartRate = bpm
                lastHeartRateTime = timeSource.markNow()
                onStateChanged?.invoke()
            }
            override fun onConnectionStateChanged(connected: Boolean, name: String?) {
                Log.d(TAG, "HR monitor connected=$connected")
                hrMonitorName = name
                onStateChanged?.invoke()
            }
        })
        heartRateManager?.startScan()
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    /**
     * Creates the persistent notification channel required on Android O+.
     *
     * Input:  none
     * Output: Channel registered with the system.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navigation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "GPS navigation status"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    /**
     * Constructs a [Notification] showing the current status text.
     *
     * Input:  @param statusText Short description of current navigation status.
     * Output: @return Built [Notification] ready to display.
     */
    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_NAVIGATE
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Waypoint Navigator")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /**
     * Refreshes the foreground notification with the current waypoint and distance.
     *
     * Input:  none
     * Output: System notification updated.
     */
    private fun updateNotification() {
        val loc = lastLocation ?: return
        val wps = waypoints
        if (wps.isEmpty()) return
        val target = wps[currentWaypointIndex]
        val dist = GeoUtils.haversineDistance(
            loc.latitude, loc.longitude, target.latitude, target.longitude
        )
        val text = "→ ${target.name}  ${GeoUtils.formatDistance(dist.toFloat())}"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
