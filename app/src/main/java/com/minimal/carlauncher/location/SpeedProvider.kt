package com.minimal.carlauncher.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GPS speed and course, via the framework LocationManager.
 *
 * Deliberately NOT FusedLocationProviderClient: many aftermarket head units ship without
 * Google Play Services (or with a stub), where the fused client silently never fires. The
 * framework API is always present, adds no dependency, and gives the raw GNSS speed - which
 * is what a speedometer wants, unsmoothed by wifi/cell fusion.
 */
class SpeedProvider(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    val hasCoarsePermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    val isGpsEnabled: Boolean
        get() = try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) {
            false
        }

    /**
     * Cold flow of GPS fixes. Collection registers the listener; cancelling it calls
     * removeUpdates, so the receiver is only running while the launcher is visible.
     */
    @SuppressLint("MissingPermission")
    fun locations(): Flow<Location> = callbackFlow {
        // Bound to a local: smart-casting a property inside the awaitClose closure is fragile.
        val manager = locationManager
        if (!hasPermission || manager == null) {
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            // Still abstract in some old AOSP stubs - supply empty bodies.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        // Seed from the last known fix, but only if it is fresh - otherwise the gauge would
        // open at yesterday's 90 km/h.
        lastFreshLocation()?.let { trySend(it) }

        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,   // 1 Hz, matching a typical GNSS fix rate
                0f,       // MUST be 0: a distance filter freezes the readout when stationary
                listener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    @SuppressLint("MissingPermission")
    fun lastFreshLocation(): Location? {
        val manager = locationManager
        if (!hasPermission || manager == null) return null
        val last = try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            null
        } ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
        return if (ageMs in 0..10_000L) last else null
    }

    /** Speed in m/s, with the API 31+ accuracy gate applied where available. */
    fun speedOf(location: Location): Float {
        if (!location.hasSpeed()) return 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            location.hasSpeedAccuracy() &&
            location.speedAccuracyMetersPerSecond > 2f
        ) {
            return 0f
        }
        return location.speed
    }
}
