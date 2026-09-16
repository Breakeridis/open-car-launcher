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

    /** Called from onResume. Nothing is registered while another app is in the foreground. */
    fun start() {
        if (locationJob == null && speedProvider.hasPermission) {
            locationJob = viewModelScope.launch {
                speedProvider.locations().collect { location ->
                    lastLocation = location
                    lastFixElapsedMs = SystemClock.elapsedRealtime()
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

        if (gpsUsable && preference != Prefs.COMPASS_SENSOR) {
            heading = location!!.bearing
            source = HeadingSource.GPS
        } else if (preference != Prefs.COMPASS_GPS) {
            sensorHeading?.let {
                heading = it
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
            preciseLocation = speedProvider.hasPermission
        )
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
