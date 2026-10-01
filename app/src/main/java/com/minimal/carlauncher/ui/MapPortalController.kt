package com.minimal.carlauncher.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Constants
import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.location.VehicleState
import com.minimal.carlauncher.map.GeocodingClient
import com.minimal.carlauncher.map.Place
import com.minimal.carlauncher.map.VehicleOverlay
import com.minimal.carlauncher.nav.Geo
import com.minimal.carlauncher.nav.LatLon
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.abs

/**
 * The circular moving-map portal: follows the car, rotates heading-up (or stays north-up),
 * supports pan / pinch-zoom, recenter, address search, long-press pins and in-app navigation
 * drawn on this map (no hand-off to another navigation app).
 *
 * Head-unit notes (Allwinner T507 / A133): the GPU is modest, so the map is only rotated when
 * the heading has moved by a visible amount, and only redrawn when something actually changed.
 * A 1 Hz GPS fix with a ~1 s animateTo gives smooth tracking without a continuous render loop.
 */
class MapPortalController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val isMoving: () -> Boolean
) {

    private val mapView: MapView get() = binding.mapView

    private val vehicle = VehicleOverlay(
        fillColor = ContextCompat.getColor(activity, R.color.cockpit_accent),
        outlineColor = ContextCompat.getColor(activity, R.color.cockpit_surface),
        sizePx = activity.resources.getDimension(R.dimen.map_vehicle_size)
    )

    /** The pinned place (search result or long-press) and its marker. */
    private var pinned: Place? = null
    private var destinationMarker: Marker? = null

    private val search = MapSearchPanel(activity, binding) { place -> showPlace(place) }

    private val navigator by lazy {
        MapNavigator(activity, binding, mapView, this::addBelowCar) { onNavigationEnded() }
    }

    /** False after the driver pans; the auto-recenter timer or the button turns it back on. */
    private var following = true
    private var hadFix = false
    private var lastHeading: Float? = null
    private var lastPosition: GeoPoint? = null

    private val autoRecenter = Runnable { setFollowing(true) }

    @SuppressLint("ClickableViewAccessibility")
    fun bind() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        // Larger labels: an in-dash panel is read from ~70 cm, not from hand distance.
        mapView.setTilesScaledToDpi(true)
        mapView.setMinZoomLevel(3.0)
        mapView.setMaxZoomLevel(19.0)
        mapView.setVerticalMapRepetitionEnabled(false)
        // Long-press anywhere drops a pin. Index 0, so markers still get their own taps first.
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { onLongPress(it) }
                return p != null
            }
        }))
        mapView.overlays.add(vehicle)

        applyDayNight()

        val camera = Prefs.mapCamera()
        if (camera != null) {
            mapView.controller.setZoom(camera.third)
            mapView.controller.setCenter(GeoPoint(camera.first, camera.second))
        } else {
            mapView.controller.setZoom(4.0)
        }

        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> onUserGesture()
            }
            false   // never consume: osmdroid still does the panning and zooming
        }

        binding.btnRecenter.setOnClickListener { setFollowing(true) }
        binding.btnMapSearch.setOnClickListener { openSearch() }
        binding.headingChip.setOnClickListener { toggleOrientation() }

        search.bind()
        renderControls()
        navigator.resumeIfSaved()
    }

    fun onResume() = mapView.onResume()

    fun onPause() {
        mapView.onPause()
        mapView.removeCallbacks(autoRecenter)
        val c = mapView.mapCenter
        Prefs.saveMapCamera(c.latitude, c.longitude, mapView.zoomLevelDouble)
    }

    fun onDestroy() = mapView.onDetach()

    fun render(state: VehicleState) {
        val lat = state.latitude
        val lon = state.longitude
        var dirty = false

        if (lat != null && lon != null) {
            val point = GeoPoint(lat, lon)
            val moved = lastPosition?.let { it.distanceToAsDouble(point) > 0.5 } ?: true
            if (moved) {
                vehicle.position = point
                lastPosition = point
                dirty = true
                if (following && state.hasFix) {
                    if (!hadFix) {
                        // First fix after boot: jump straight there at street level.
                        if (mapView.zoomLevelDouble < 10.0) {
                            mapView.controller.setZoom(Constants.MAP_DEFAULT_ZOOM)
                        }
                        mapView.controller.setCenter(point)
                    } else {
                        mapView.controller.animateTo(point)
                    }
                }
            }
        }
        hadFix = hadFix || state.hasFix

        val heading = state.headingDeg
        if (heading == null) {
            if (lastHeading != null) dirty = true
            vehicle.headingDeg = null
        } else if (lastHeading == null || angleDelta(heading, lastHeading!!) >= HEADING_STEP_DEG) {
            vehicle.headingDeg = heading
            lastHeading = heading
            dirty = true
        }
        if (heading == null) lastHeading = null

        // While the driver is panning the map is left exactly as it is - rotating it under a
        // finger makes panning impossible. Following: heading-up, or north-up if chosen.
        if (following) {
            val headingNow = vehicle.headingDeg
            val targetOrientation =
                if (Prefs.mapHeadingUp && headingNow != null) -headingNow else 0f
            if (angleDelta(targetOrientation, mapView.mapOrientation) >= HEADING_STEP_DEG ||
                (targetOrientation == 0f && mapView.mapOrientation != 0f)
            ) {
                mapView.setMapOrientation(targetOrientation)
                dirty = true
            }
        }
        binding.compassBezel.northDeg = mapView.mapOrientation

        if (dirty) mapView.invalidate()

        navigator.onVehicle(state)
    }

    // ------------------------------------------------------------------ following

    private fun onUserGesture() {
        if (following) setFollowing(false)
        mapView.removeCallbacks(autoRecenter)
        mapView.postDelayed(autoRecenter, Constants.MAP_AUTO_RECENTER_MS)
    }

    private fun setFollowing(value: Boolean) {
        following = value
        if (value) {
            mapView.removeCallbacks(autoRecenter)
            lastPosition?.let { mapView.controller.animateTo(it) }
        }
        renderControls()
        // Re-evaluate orientation straight away rather than at the next fix.
        lastHeading = null
    }

    private fun toggleOrientation() {
        Prefs.mapHeadingUp = !Prefs.mapHeadingUp
        Toast.makeText(
            activity,
            if (Prefs.mapHeadingUp) R.string.map_heading_up else R.string.map_north_up,
            Toast.LENGTH_SHORT
        ).show()
        lastHeading = null
        renderControls()
    }

    private fun renderControls() {
        // Recenter is emphasised only when it would actually do something.
        binding.btnRecenter.alpha = if (following) 0.55f else 1f
        binding.textMapMode.setText(
            if (Prefs.mapHeadingUp) R.string.map_mode_heading else R.string.map_mode_north
        )
    }

    private fun applyDayNight() {
        val night = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val tiles = mapView.overlayManager.tilesOverlay
        // OSM's standard style has no night variant; inverting it gives a dark map with light
        // roads, which keeps the cockpit dark instead of a bright disc in the middle of it.
        tiles.setColorFilter(if (night) NIGHT_TILES else null)
        val bg = ContextCompat.getColor(activity, R.color.cockpit_surface_alt)
        tiles.setLoadingBackgroundColor(bg)
        tiles.setLoadingLineColor(bg)
    }

    // ------------------------------------------------------- search, pins, navigation

    private fun openSearch() {
        // Typing an address is exactly the task a moving driver must not do.
        if (isMoving()) {
            toast(R.string.map_search_while_driving)
            return
        }
        search.open(lastPosition?.let { LatLon(it.latitude, it.longitude) })
    }

    /** BACK closes the search panel first, then a pin card. True when it handled the press. */
    fun handleBack(): Boolean {
        if (search.isOpen) {
            search.close()
            return true
        }
        if (pinned != null && !navigator.isActive) {
            clearPin()
            return true
        }
        return false
    }

    /** HOME pressed: close the search panel, keep the pin and any active navigation. */
    fun reset() {
        if (search.isOpen) search.close()
    }

    private fun onLongPress(point: GeoPoint) {
        mapView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        // Like a pan: stop following so the pin stays in view, snap back later.
        onUserGesture()
        val place = Place(
            activity.getString(R.string.map_dropped_pin) + ", " + String.format(
                Locale.US, "%.5f, %.5f", point.latitude, point.longitude
            ),
            point.latitude, point.longitude
        )
        showPlace(place, moveCamera = false)
        // Replace the coordinates with a street address when one is available.
        activity.lifecycleScope.launch {
            val name = GeocodingClient.reverse(point.latitude, point.longitude) ?: return@launch
            if (pinned?.latitude == place.latitude && pinned?.longitude == place.longitude) {
                showPlace(Place(name, place.latitude, place.longitude), moveCamera = false)
            }
        }
    }

    private fun showPlace(place: Place, moveCamera: Boolean = true) {
        pinned = place
        val point = GeoPoint(place.latitude, place.longitude)
        destinationMarker?.let { mapView.overlays.remove(it) }
        val marker = Marker(mapView).apply {
            position = point
            title = place.name
            icon = ContextCompat.getDrawable(activity, R.drawable.ic_place_marker)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // Returning true also suppresses osmdroid's default info bubble.
            setOnMarkerClickListener { _, _ ->
                if (!navigator.isActive) showPlaceCard(place)
                true
            }
        }
        destinationMarker = marker
        addBelowCar(marker)
        mapView.invalidate()

        if (moveCamera) {
            setFollowing(false)
            mapView.removeCallbacks(autoRecenter)
            mapView.controller.animateTo(point)
        }
        if (!navigator.isActive) showPlaceCard(place)
    }

    private fun showPlaceCard(place: Place) {
        binding.mapCard.visibility = View.VISIBLE
        binding.headingChip.visibility = View.GONE
        binding.cardGlyph.visibility = View.GONE
        binding.cardTitle.text = place.title
        binding.cardSubtitle.text = place.subtitle
        binding.cardSubtitle.visibility = if (place.subtitle.isBlank()) View.GONE else View.VISIBLE
        val from = lastPosition
        if (from != null) {
            binding.cardSummary.text = Format.distanceText(
                Prefs.speedUnit,
                Geo.distanceM(LatLon(from.latitude, from.longitude), LatLon(place.latitude, place.longitude))
            )
            binding.cardSummary.visibility = View.VISIBLE
        } else {
            binding.cardSummary.visibility = View.GONE
        }
        binding.btnCardPrimary.visibility = View.VISIBLE
        binding.btnCardPrimary.setOnClickListener { startNavigation(place) }
        binding.btnCardSecondary.setText(R.string.map_clear_pin)
        binding.btnCardSecondary.setOnClickListener { clearPin() }
    }

    private fun startNavigation(place: Place) {
        binding.cardSubtitle.visibility = View.VISIBLE
        navigator.start(place)
        // Guidance view: follow the car, heading-up if chosen, close enough to read turns.
        if (mapView.zoomLevelDouble < NAV_MIN_ZOOM) mapView.controller.setZoom(NAV_ZOOM)
        setFollowing(true)
    }

    private fun clearPin() {
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = null
        pinned = null
        hideCard()
        mapView.invalidate()
        setFollowing(true)
    }

    private fun onNavigationEnded() {
        clearPin()
    }

    private fun hideCard() {
        binding.mapCard.visibility = View.GONE
        binding.headingChip.visibility = View.VISIBLE
        binding.cardSubtitle.visibility = View.VISIBLE
    }

    /** Route lines and pins go below the car arrow, so the car is never hidden. */
    private fun addBelowCar(overlay: Overlay) {
        val index = mapView.overlays.indexOf(vehicle)
        if (index < 0) mapView.overlays.add(overlay) else mapView.overlays.add(index, overlay)
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Smaller rotations are invisible at a glance and would only cost redraws. */
        const val HEADING_STEP_DEG = 2f

        /** Guidance zoom: individual junctions readable. */
        const val NAV_ZOOM = 16.5
        const val NAV_MIN_ZOOM = 15.0

        /** Colour inversion: light roads on a dark background, slightly dimmed for night. */
        val NIGHT_TILES = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -0.9f, 0f, 0f, 0f, 235f,
                    0f, -0.9f, 0f, 0f, 235f,
                    0f, 0f, -0.9f, 0f, 235f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        fun angleDelta(a: Float, b: Float): Float {
            val d = abs(Format.normalizeDegrees(a) - Format.normalizeDegrees(b))
            return if (d > 180f) 360f - d else d
        }
    }
}
