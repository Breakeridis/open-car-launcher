package com.minimal.carlauncher.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.minimal.carlauncher.util.IntentUtil
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.abs

/**
 * The circular moving-map portal: follows the car, rotates heading-up (or stays north-up),
 * supports pan / pinch-zoom, recenter and address search.
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

    private var destination: Marker? = null

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

        renderControls()
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
        tiles.setColorFilter(if (night) TilesOverlay.INVERTED_COLORS else null)
        val bg = ContextCompat.getColor(activity, R.color.cockpit_surface_alt)
        tiles.setLoadingBackgroundColor(bg)
        tiles.setLoadingLineColor(bg)
    }

    // --------------------------------------------------------------------- search

    private fun openSearch() {
        // Typing an address is exactly the task a moving driver must not do.
        if (isMoving()) {
            toast(R.string.map_search_while_driving)
            return
        }
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.map_search_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.map_search_title)
            .setView(input)
            .setPositiveButton(R.string.map_search_go) { _, _ -> runSearch(input.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                runSearch(input.text.toString())
                true
            } else {
                false
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun runSearch(query: String) {
        if (query.isBlank()) return
        toast(R.string.map_searching)
        val near = lastPosition
        activity.lifecycleScope.launch {
            val results = try {
                GeocodingClient.search(query, near?.latitude, near?.longitude)
            } catch (e: Exception) {
                toast(R.string.map_search_offline)
                return@launch
            }
            if (results.isEmpty()) {
                toast(R.string.map_search_none)
                return@launch
            }
            AlertDialog.Builder(activity)
                .setTitle(query)
                .setItems(results.map { it.name }.toTypedArray()) { _, which ->
                    showPlace(results[which])
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun showPlace(place: Place) {
        val point = GeoPoint(place.latitude, place.longitude)
        destination?.let { mapView.overlays.remove(it) }
        val pin = Marker(mapView).apply {
            position = point
            title = place.name
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // Returning true also suppresses osmdroid's default info bubble.
            setOnMarkerClickListener { _, _ ->
                offerNavigation(place)
                true
            }
        }
        destination = pin
        // Keep the car arrow drawn above the pin.
        mapView.overlays.add(mapView.overlays.indexOf(vehicle).coerceAtLeast(0), pin)

        setFollowing(false)
        mapView.removeCallbacks(autoRecenter)
        mapView.controller.animateTo(point)
        offerNavigation(place)
    }

    private fun offerNavigation(place: Place) {
        AlertDialog.Builder(activity)
            .setTitle(place.name)
            .setPositiveButton(R.string.map_navigate) { _, _ -> navigateTo(place) }
            .setNeutralButton(R.string.map_clear_pin) { _, _ ->
                destination?.let { mapView.overlays.remove(it) }
                destination = null
                setFollowing(true)
            }
            .setNegativeButton(R.string.action_close) { _, _ ->
                mapView.postDelayed(autoRecenter, Constants.MAP_AUTO_RECENTER_MS)
            }
            .show()
    }

    /** Hands the destination to the navigation app on the Navigation tile (or any geo: app). */
    private fun navigateTo(place: Place) {
        val label = Uri.encode(place.name.substringBefore(','))
        val uri = Uri.parse(
            String.format(
                java.util.Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                place.latitude, place.longitude, place.latitude, place.longitude, label
            )
        )
        val intent = Intent(Intent.ACTION_VIEW, uri)
        Prefs.navPackage?.let { intent.setPackage(IntentUtil.packageOf(it)) }
        if (!IntentUtil.startSafely(activity, intent)) {
            intent.setPackage(null)
            if (!IntentUtil.startSafely(activity, intent)) toast(R.string.map_no_nav_app)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Smaller rotations are invisible at a glance and would only cost redraws. */
        const val HEADING_STEP_DEG = 2f

        fun angleDelta(a: Float, b: Float): Float {
            val d = abs(Format.normalizeDegrees(a) - Format.normalizeDegrees(b))
            return if (d > 180f) 360f - d else d
        }
    }
}
