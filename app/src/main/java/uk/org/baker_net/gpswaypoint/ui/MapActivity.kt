package uk.org.baker_net.gpswaypoint.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import uk.org.baker_net.gpswaypoint.data.MapBackground
import uk.org.baker_net.gpswaypoint.data.PreferencesRepository
import uk.org.baker_net.gpswaypoint.databinding.ActivityMapBinding
import uk.org.baker_net.gpswaypoint.service.NavigationService

/**
 * MapActivity.kt
 *
 * Displays the planned route (GPX waypoints, drawn in red), the elapsed
 * route (recorded track points, drawn in blue) and the current GPS fix
 * (a white marker) on a map, using MapLibre Native's Android SDK.
 *
 * (MapLibre was chosen over osmdroid because osmdroid was archived by its
 * maintainers in November 2024 and no longer receives releases. MapLibre is
 * the actively maintained open-source fork of the old Mapbox GL Native SDK,
 * published to Maven Central under org.maplibre.gl:android-sdk.)
 *
 * Touch behaviour:
 *   - Single-finger drag pans the map (scroll gesture).
 *   - Pinch-to-zoom, rotation, tilt, double-tap zoom and quick-zoom gestures
 *     are all disabled; zooming is only possible via the on-screen + / -
 *     buttons.
 *
 * Background:
 *   - Either a plain black background (a single [BackgroundLayer], no
 *     sources, no network access) or live OpenStreetMap raster tiles (a
 *     [RasterSource] + [RasterLayer]), per the user's saved [MapBackground]
 *     preference.
 *
 * The route/location data itself is drawn as three [GeoJsonSource]s (planned
 * route, elapsed route, current location) with matching [LineLayer]/
 * [CircleLayer] styling, added on top of whichever background style loads.
 *
 * This screen binds to the already-running [NavigationService] purely to
 * read its live state; it never starts, stops, or otherwise controls the
 * service, so opening or closing the map has no effect on navigation or
 * recording.
 *
 * Pressing the toolbar's Up arrow, or the system Back button, returns to
 * [MainActivity], which remains on the back stack beneath this activity.
 */
class MapActivity : AppCompatActivity() {

    companion object {
        /** Initial zoom level used the first time the map is shown. */
        private const val DEFAULT_ZOOM = 17.0

        /** Width, in dp, of the planned/elapsed route lines. */
        private const val ROUTE_LINE_WIDTH_DP = 4f

        /** Radius, in dp, of the current-location circle marker. */
        private const val LOCATION_MARKER_RADIUS_DP = 7f

        /** How often the map overlays are refreshed from the service, in milliseconds. */
        private const val REFRESH_INTERVAL_MS = 1000L

        private const val SOURCE_PLANNED_ROUTE = "planned-route-source"
        private const val SOURCE_ELAPSED_ROUTE = "elapsed-route-source"
        private const val SOURCE_CURRENT_LOCATION = "current-location-source"
        private const val LAYER_PLANNED_ROUTE = "planned-route-layer"
        private const val LAYER_ELAPSED_ROUTE = "elapsed-route-layer"
        private const val LAYER_CURRENT_LOCATION = "current-location-layer"
        private const val LAYER_BACKGROUND = "plain-background-layer"

        private const val SOURCE_OSM_TILES = "osm-tiles-source"
        private const val LAYER_OSM_TILES = "osm-tiles-layer"
        private const val OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        private const val OSM_TILE_SIZE = 256
        private const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
    }

    private lateinit var binding: ActivityMapBinding
    private lateinit var preferencesRepository: PreferencesRepository

    private var navigationService: NavigationService? = null
    private var serviceBound = false

    private var maplibreMap: MapLibreMap? = null
    private var plannedRouteSource: GeoJsonSource? = null
    private var elapsedRouteSource: GeoJsonSource? = null
    private var currentLocationSource: GeoJsonSource? = null

    private val handler = Handler(Looper.getMainLooper())

    /** True once the map has been auto-centred on the first available GPS fix. */
    private var hasCentredMap = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            navigationService = (binder as NavigationService.NavigationBinder).getService()
            serviceBound = true
            refreshOverlays()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            navigationService = null
            serviceBound = false
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshOverlays()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // MapLibre must be initialised before any MapView is created/inflated.
        MapLibre.getInstance(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarMap)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        preferencesRepository = PreferencesRepository(this)

        binding.mapView.onCreate(savedInstanceState)
        setupZoomButtons()

        binding.mapView.getMapAsync { map ->
            maplibreMap = map
            configureGestures(map)
            map.cameraPosition = CameraPosition.Builder().zoom(DEFAULT_ZOOM).build()
            map.setStyle(buildBaseStyle()) { style ->
                addRouteAndLocationLayers(style)
                refreshOverlays()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
        bindService(
            Intent(this, NavigationService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // -------------------------------------------------------------------------
    // Map setup
    // -------------------------------------------------------------------------

    /**
     * Wires the on-screen + / - buttons to the map camera's zoom in/out,
     * the only way to change zoom level since all touch-zoom gestures are
     * disabled in [configureGestures].
     *
     * Input:  none
     * Output: Button click listeners installed.
     */
    private fun setupZoomButtons() {
        binding.btnZoomIn.setOnClickListener {
            maplibreMap?.animateCamera(CameraUpdateFactory.zoomIn())
        }
        binding.btnZoomOut.setOnClickListener {
            maplibreMap?.animateCamera(CameraUpdateFactory.zoomOut())
        }
    }

    /**
     * Disables every multi-touch and quick-zoom gesture (pinch-zoom, double
     * tap, quick zoom, rotate, tilt) while leaving single-finger drag-to-pan
     * (the scroll gesture) enabled, so the +/- buttons become the only way
     * to zoom.
     *
     * Input:  @param map The loaded [MapLibreMap].
     * Output: [MapLibreMap.getUiSettings] updated; the on-screen compass is
     *         also hidden since rotation is disabled.
     */
    private fun configureGestures(map: MapLibreMap) {
        map.uiSettings.apply {
            isScrollGesturesEnabled = true
            isZoomGesturesEnabled = false
            isDoubleTapGesturesEnabled = false
            isQuickZoomGesturesEnabled = false
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isCompassEnabled = false
        }
    }

    /**
     * Builds the base style — before the route/location overlays are added —
     * according to the saved map background preference.
     *
     * Input:  none
     * Output: @return A [Style.Builder] for either a plain black background
     *         (no sources, no network access) or a raster layer of live
     *         OpenStreetMap tiles.
     */
    private fun buildBaseStyle(): Style.Builder {
        val builder = Style.Builder()
        return when (preferencesRepository.getMapBackground()) {
            MapBackground.OSM -> {
                val tileSet = TileSet("2.1.0", OSM_TILE_URL)
                tileSet.attribution = OSM_ATTRIBUTION
                builder
                    .withSource(RasterSource(SOURCE_OSM_TILES, tileSet, OSM_TILE_SIZE))
                    .withLayer(RasterLayer(LAYER_OSM_TILES, SOURCE_OSM_TILES))
            }
            MapBackground.BLACK -> builder.withLayer(
                BackgroundLayer(LAYER_BACKGROUND).withProperties(
                    PropertyFactory.backgroundColor(Color.BLACK)
                )
            )
        }
    }

    /**
     * Adds the three data-driven overlays — planned route (red), elapsed
     * route (blue), and current location (white) — on top of the base
     * style, starting out empty until the first [refreshOverlays] call.
     *
     * Input:  @param style The loaded [Style], after the base background is applied.
     * Output: [plannedRouteSource], [elapsedRouteSource] and [currentLocationSource]
     *         initialised; matching layers added to [style].
     */
    private fun addRouteAndLocationLayers(style: Style) {
        val plannedSource = GeoJsonSource(SOURCE_PLANNED_ROUTE, emptyFeatureCollection())
        val elapsedSource = GeoJsonSource(SOURCE_ELAPSED_ROUTE, emptyFeatureCollection())
        val locationSource = GeoJsonSource(SOURCE_CURRENT_LOCATION, emptyFeatureCollection())

        style.addSource(plannedSource)
        style.addSource(elapsedSource)
        style.addSource(locationSource)

        style.addLayer(
            LineLayer(LAYER_PLANNED_ROUTE, SOURCE_PLANNED_ROUTE).withProperties(
                PropertyFactory.lineColor(Color.RED),
                PropertyFactory.lineWidth(ROUTE_LINE_WIDTH_DP)
            )
        )
        style.addLayer(
            LineLayer(LAYER_ELAPSED_ROUTE, SOURCE_ELAPSED_ROUTE).withProperties(
                PropertyFactory.lineColor(Color.BLUE),
                PropertyFactory.lineWidth(ROUTE_LINE_WIDTH_DP)
            )
        )
        style.addLayer(
            CircleLayer(LAYER_CURRENT_LOCATION, SOURCE_CURRENT_LOCATION).withProperties(
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleRadius(LOCATION_MARKER_RADIUS_DP),
                PropertyFactory.circleStrokeColor(Color.DKGRAY),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )

        plannedRouteSource = plannedSource
        elapsedRouteSource = elapsedSource
        currentLocationSource = locationSource
    }

    // -------------------------------------------------------------------------
    // Live overlay updates
    // -------------------------------------------------------------------------

    /**
     * Reads the latest waypoints, recorded track points, and current
     * location from the bound [NavigationService] and pushes them into the
     * map's GeoJSON sources. The map is centred on the device's position the
     * first time a GPS fix becomes available, so the initial view is useful
     * without requiring a manual pan.
     *
     * Input:  none
     * Output: [plannedRouteSource], [elapsedRouteSource] and [currentLocationSource]
     *         updated with the latest GeoJSON data.
     */
    private fun refreshOverlays() {
        val svc = navigationService ?: return
        val map = maplibreMap ?: return

        plannedRouteSource?.setGeoJson(
            lineFeatureCollection(svc.waypoints.map { LatLng(it.latitude, it.longitude) })
        )
        elapsedRouteSource?.setGeoJson(
            lineFeatureCollection(svc.getTrackPoints().map { LatLng(it.latitude, it.longitude) })
        )

        val loc = svc.lastLocation
        if (loc != null) {
            val point = Point.fromLngLat(loc.longitude, loc.latitude)
            currentLocationSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(point))))
            if (!hasCentredMap) {
                map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)))
                hasCentredMap = true
            }
        } else {
            currentLocationSource?.setGeoJson(emptyFeatureCollection())
        }
    }

    /**
     * Builds a GeoJSON [FeatureCollection] containing a single LineString
     * feature from [points], or an empty collection if fewer than two points
     * are given (a LineString requires at least two coordinates).
     *
     * Input:  @param points Ordered list of positions to connect.
     * Output: @return A [FeatureCollection] suitable for [GeoJsonSource.setGeoJson].
     */
    private fun lineFeatureCollection(points: List<LatLng>): FeatureCollection {
        if (points.size < 2) return emptyFeatureCollection()
        val lineString = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
        return FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(lineString)))
    }

    /**
     * Input:  none
     * Output: @return An empty [FeatureCollection], used to clear a source
     *         (e.g. when there is no current GPS fix, or too few points for a line).
     */
    private fun emptyFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(arrayOf())
}
