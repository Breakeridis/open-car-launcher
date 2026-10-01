package com.minimal.carlauncher.ui

import android.app.Application
import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minimal.carlauncher.core.Constants
import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.location.CompassProvider
import com.minimal.carlauncher.location.HeadingSource
import com.minimal.carlauncher.location.SpeedProvider
import com.minimal.carlauncher.location.VehicleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds the live vehicle state for the dashboard.
 *
 * Scoped to the activity's ViewModelStore, so it survives the recreate that a theme switch
 * triggers - the GPS session is not torn down and the speed readout does not blink.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val speedProvider = SpeedProvider(app)
    private val compassProvider = CompassProvider(app)

    private val _vehicle = MutableStateFlow(VehicleState())
    val vehicle: StateFlow<VehicleState> = _vehicle.asStateFlow()

    private val _speedUnit = MutableStateFlow(Prefs.speedUnit)
    val speedUnit: StateFlow<String> = _speedUnit.asStateFlow()

    val compassAvailable: Boolean get() = compassProvider.isAvailable

    private var locationJob: Job? = null
    private var compassJob: Job? = null
    private var tickerJob: Job? = null

    private var lastLocation: Location? = null
    private var lastFixElapsedMs = 0L
    private var sensorHeading: Float? = null

    /**
     * Last GPS course, held while the car is stopped (and across reboots), so the map shows
     * the way the car points instead of losing direction at every traffic light. Most head
     * units have no magnetometer, so without this there would be no heading at all at rest.
     */
    private var courseHeading: Float? = Prefs.lastCourseDeg
    private var courseAnchor: Location? = null

    /** Called from onResume. Nothing is registered while another app is in the foreground. */
    fun start() {
        if (locationJob == null && speedProvider.hasPermission) {
            locationJob = viewModelScope.launch {
                speedProvider.locations().collect { location ->
                    lastLocation = location
                    lastFixElapsedMs = SystemClock.elapsedRealtime()
                    updateCourse(location)
                    publish()
                }
            }
        }
        if (compassJob == null && compassProvider.isAvailable) {
            compassJob = viewModelScope.launch {
                compassProvider.headings().collect { heading ->
                    sensorHeading = heading
                    publish()
                }
            }
        }
        if (tickerJob == null) {
            // Degrades the readout to "--" when the antenna is obstructed, rather than
            // leaving the last value frozen on screen.
            tickerJob = viewModelScope.launch {
                while (isActive) {
                    delay(1_000L)
                    publish()
                }
            }
        }
        publish()
    }

    /** Called from onPause. */
    fun stop() {
        locationJob?.cancel(); locationJob = null
        compassJob?.cancel(); compassJob = null
        tickerJob?.cancel(); tickerJob = null
        Prefs.lastCourseDeg = courseHeading
    }

    /**
     * Course from the receiver when it reports one while moving; otherwise computed from two
     * fixes far enough apart to beat position noise - some GNSS chips report speed but never
     * a bearing.
     */
    private fun updateCourse(location: Location) {
        val speed = speedProvider.speedOf(location)
        if (location.hasBearing() && speed > Constants.GPS_BEARING_MIN_MPS) {
            courseHeading = location.bearing
            courseAnchor = location
            return
        }
        val anchor = courseAnchor
        if (anchor == null) {
            courseAnchor = location
            return
        }
        val distance = anchor.distanceTo(location)
        val noise = if (location.hasAccuracy()) location.accuracy else 10f
        if (distance >= COURSE_MIN_DISTANCE_M && distance > noise * 2f) {
            courseHeading = Format.normalizeDegrees(anchor.bearingTo(location))
            courseAnchor = location
        }
    }

    /** Re-checks the permission after the runtime dialog and starts the GPS session if granted. */
    fun onPermissionResult() {
        publish()
        start()
    }

    fun toggleSpeedUnit(): String {
        val next = Format.otherUnit(_speedUnit.value)
        Prefs.speedUnit = next
        _speedUnit.value = next
        return next
    }

    fun refreshPrefs() {
        _speedUnit.value = Prefs.speedUnit
        publish()
    }

    private fun publish() {
        val location = lastLocation
        val ageMs = SystemClock.elapsedRealtime() - lastFixElapsedMs
        val hasFix = location != null && ageMs in 0..Constants.FIX_STALE_MS

        val speedMps = if (hasFix && location != null) speedProvider.speedOf(location) else 0f

        var heading: Float? = null
        var source = HeadingSource.NONE
        val preference = Prefs.compassSource

        // GPS course-over-ground wins whenever the vehicle is actually moving: a magnetometer
        // inside a metal dash, next to speaker magnets, is routinely tens of degrees out.
        val gpsUsable = hasFix && location != null &&
            location.hasBearing() && speedMps > Constants.GPS_BEARING_MIN_MPS

        val sensor = if (preference != Prefs.COMPASS_GPS) sensorHeading else null
        when {
            gpsUsable && preference != Prefs.COMPASS_SENSOR -> {
                heading = location!!.bearing
                source = HeadingSource.GPS
            }
            preference == Prefs.COMPASS_SENSOR && sensor != null -> {
                heading = sensor
                source = HeadingSource.SENSOR
            }
            // Held / computed course beats a stationary magnetometer reading: it is where the
            // car actually points, not a guess distorted by the dashboard's metal and magnets.
            courseHeading != null -> {
                heading = courseHeading
                source = HeadingSource.GPS
            }
            sensor != null -> {
                heading = sensor
                source = HeadingSource.SENSOR
            }
        }

        _vehicle.value = VehicleState(
            hasFix = hasFix,
            speedMps = speedMps,
            headingDeg = heading,
            headingSource = source,
            gpsEnabled = speedProvider.isGpsEnabled,
            permissionGranted = speedProvider.hasPermission,
            preciseLocation = speedProvider.hasPermission,
            latitude = location?.latitude,
            longitude = location?.longitude
        )
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    private companion object {
        /** Two fixes closer than this are mostly GNSS noise, not a direction of travel. */
        const val COURSE_MIN_DISTANCE_M = 15f
    }
}
