package com.minimal.carlauncher.ui

import android.graphics.Paint
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.location.VehicleState
import com.minimal.carlauncher.map.Place
import com.minimal.carlauncher.nav.LatLon
import com.minimal.carlauncher.nav.Maneuvers
import com.minimal.carlauncher.nav.Progress
import com.minimal.carlauncher.nav.Route
import com.minimal.carlauncher.nav.RouteTracker
import com.minimal.carlauncher.nav.RoutingClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.Date
import java.util.Locale

/**
 * In-app turn-by-turn guidance on the launcher's own map: the route is drawn on the portal,
 * the card above it shows the next manoeuvre, the distance to it and the trip summary.
 *
 * Routing is online (OSRM); following the route, the next-turn readout and arrival are all
 * computed on the unit from GPS, so a dropped connection mid-trip only matters if the driver
 * leaves the route and a reroute is needed.
 */
class MapNavigator(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val mapView: MapView,
    /** Adds an overlay below the car arrow. */
    private val addOverlayBelowCar: (Overlay) -> Unit,
    /** The card is shared with the place card; this tells the portal it is free again. */
    private val onEnded: () -> Unit
) {

    private var destination: Place? = null
    private var tracker: RouteTracker? = null
    private var routeJob: Job? = null
    private var lastRouteRequestMs = 0L
    private var offRouteFixes = 0
    private var arrivedAtMs = 0L
    private var lastPosition: LatLon? = null
    private var lastHeading: Float? = null

    private val casing = routeLine(R.color.cockpit_surface, ROUTE_CASING_DP)
    private val line = routeLine(R.color.cockpit_accent, ROUTE_LINE_DP)

    val isActive: Boolean get() = destination != null

    /** Picks up a trip that was in progress when the unit was switched off. */
    fun resumeIfSaved() {
        val place = decode(Prefs.navDestination) ?: return
        start(place, announceResume = true)
    }

    fun start(place: Place, announceResume: Boolean = false) {
        stopInternal(clearSaved = false, notify = false)
        destination = place
        Prefs.navDestination = encode(place)
        showCardShell()
        renderStatus(
            if (announceResume) R.string.nav_resumed
            else if (lastPosition == null) R.string.nav_need_gps
            else R.string.nav_routing
        )
        lastPosition?.let { requestRoute(it) }
    }

    fun stop() = stopInternal(clearSaved = true, notify = true)

    fun onVehicle(state: VehicleState) {
        val lat = state.latitude
        val lon = state.longitude
        if (lat == null || lon == null) return
        val position = LatLon(lat, lon)
        lastPosition = position
        if (state.headingDeg != null) lastHeading = state.headingDeg

        if (destination == null || !state.hasFix) return

        if (arrivedAtMs > 0L) {
            if (SystemClock.elapsedRealtime() - arrivedAtMs > ARRIVED_LINGER_MS) stop()
            return
        }

        val t = tracker
        if (t == null) {
            // No route yet (first fix, or the last attempt failed): retry at a polite pace.
            if (routeJob?.isActive != true && sinceLastRequest() > RETRY_MS) requestRoute(position)
            return
        }

        val progress = t.update(position)
        if (progress.arrived) {
            arrive()
            return
        }

        val tolerance = maxOf(OFF_ROUTE_M, (state.speedMps * 2f).toDouble())
        offRouteFixes = if (progress.offRouteM > tolerance) offRouteFixes + 1 else 0
        if (offRouteFixes >= OFF_ROUTE_FIXES && routeJob?.isActive != true &&
            sinceLastRequest() > REROUTE_MIN_INTERVAL_MS
        ) {
            renderStatus(R.string.nav_rerouting)
            requestRoute(position)
            return
        }
        render(progress)
    }

    // ------------------------------------------------------------------- routing

    private fun requestRoute(from: LatLon) {
        val to = destination ?: return
        lastRouteRequestMs = SystemClock.elapsedRealtime()
        routeJob?.cancel()
        routeJob = activity.lifecycleScope.launch {
            val route = try {
                RoutingClient.route(from, LatLon(to.latitude, to.longitude), lastHeading)
            } catch (e: Exception) {
                if (tracker == null) renderStatus(R.string.nav_no_route)
                return@launch
            }
            if (destination == null) return@launch
            setRoute(route)
            lastPosition?.let { render(tracker!!.update(it)) }
        }
    }

    private fun setRoute(route: Route) {
        tracker = RouteTracker(route)
        offRouteFixes = 0
        val points = route.points.map { GeoPoint(it.lat, it.lon) }
        casing.setPoints(points)
        line.setPoints(points)
        if (!mapView.overlays.contains(casing)) {
            addOverlayBelowCar(casing)
            addOverlayBelowCar(line)
        }
        mapView.invalidate()
    }

    private fun arrive() {
        arrivedAtMs = SystemClock.elapsedRealtime()
        Prefs.navDestination = ""
        binding.cardGlyph.text = Maneuvers.glyph(null)
        binding.cardTitle.setText(R.string.nav_arrived)
        binding.cardSubtitle.text = destination?.title.orEmpty()
        binding.cardSummary.visibility = View.GONE
    }

    private fun stopInternal(clearSaved: Boolean, notify: Boolean) {
        val wasActive = destination != null
        routeJob?.cancel()
        routeJob = null
        destination = null
        tracker = null
        arrivedAtMs = 0L
        offRouteFixes = 0
        mapView.overlays.remove(casing)
        mapView.overlays.remove(line)
        mapView.invalidate()
        if (clearSaved) Prefs.navDestination = ""
        if (wasActive && notify) onEnded()
    }

    private fun sinceLastRequest() = SystemClock.elapsedRealtime() - lastRouteRequestMs

    // ---------------------------------------------------------------------- card

    private fun showCardShell() {
        binding.mapCard.visibility = View.VISIBLE
        binding.headingChip.visibility = View.GONE
        binding.cardGlyph.visibility = View.VISIBLE
        binding.cardGlyph.text = Maneuvers.glyph(null)
        binding.btnCardPrimary.visibility = View.GONE
        binding.btnCardSecondary.setText(R.string.nav_end)
        binding.btnCardSecondary.setOnClickListener { stop() }
    }

    private fun renderStatus(resId: Int) {
        binding.cardTitle.text = destination?.title.orEmpty()
        binding.cardSubtitle.setText(resId)
        binding.cardSummary.visibility = View.GONE
    }

    private fun render(p: Progress) {
        val unit = Prefs.speedUnit
        binding.cardGlyph.text = Maneuvers.glyph(p.nextStep)
        binding.cardTitle.text = Format.distanceText(unit, p.metresToNextStep)
        binding.cardSubtitle.text = Maneuvers.instruction(p.nextStep)
        val eta = DateFormat.getTimeFormat(activity)
            .format(Date(System.currentTimeMillis() + (p.remainingS * 1000).toLong()))
        binding.cardSummary.text = activity.getString(
            R.string.nav_summary,
            Format.distanceText(unit, p.remainingM),
            Format.durationText(p.remainingS),
            eta
        )
        binding.cardSummary.visibility = View.VISIBLE
    }

    private fun routeLine(colorRes: Int, widthDp: Float) = Polyline().apply {
        outlinePaint.apply {
            color = ContextCompat.getColor(activity, colorRes)
            strokeWidth = widthDp * activity.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
    }

    private companion object {
        const val ROUTE_LINE_DP = 8f
        const val ROUTE_CASING_DP = 13f

        /** Beyond this (or two seconds of travel, if larger) from the line counts as off route. */
        const val OFF_ROUTE_M = 45.0
        /** Consecutive off-route fixes before rerouting - one bad fix must not trigger it. */
        const val OFF_ROUTE_FIXES = 3
        const val REROUTE_MIN_INTERVAL_MS = 15_000L
        const val RETRY_MS = 15_000L
        const val ARRIVED_LINGER_MS = 8_000L

        fun encode(p: Place) =
            String.format(Locale.US, "%.6f|%.6f|%s", p.latitude, p.longitude, p.name)

        fun decode(raw: String): Place? {
            val parts = raw.split('|', limit = 3)
            if (parts.size < 3) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            return Place(parts[2], lat, lon)
        }
    }
}
