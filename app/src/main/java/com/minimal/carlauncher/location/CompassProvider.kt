package com.minimal.carlauncher.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Magnetometer-derived heading, used only as the stationary fallback.
 *
 * A head unit sits inside a metal dashboard next to speaker magnets and a switching power
 * supply, so magnetometer heading there is routinely tens of degrees off. Above walking pace
 * the GPS course-over-ground is far better, so [DashboardViewModel] prefers it. Many units
 * have no magnetometer at all, hence [isAvailable].
 */
class CompassProvider(context: Context) {

    private val sensorManager = context.applicationContext
        .getSystemService(SensorManager::class.java)

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = rotationSensor != null

    /** Cold flow of filtered azimuth in degrees [0, 360). */
    fun headings(): Flow<Float> = callbackFlow {
        val sensor = rotationSensor
        if (sensor == null || sensorManager == null) {
            close()
            return@callbackFlow
        }

        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)

        // Low-pass filter in the vector domain so the 359 -> 0 wrap does not spike.
        var sinA = 0.0
        var cosA = 0.0
        var seeded = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // Device mounted upright in the dash rather than lying flat.
                SensorManager.remapCoordinateSystem(
                    rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
                )
                SensorManager.getOrientation(remapped, orientation)

                val radians = orientation[0].toDouble()
                if (!seeded) {
                    sinA = sin(radians)
                    cosA = cos(radians)
                    seeded = true
                } else {
                    sinA += ALPHA * (sin(radians) - sinA)
                    cosA += ALPHA * (cos(radians) - cosA)
                }

                val deg = Math.toDegrees(atan2(sinA, cosA)).toFloat()
                trySend(((deg % 360f) + 360f) % 360f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // SENSOR_DELAY_UI (~60ms), never FASTEST - this runs on the launcher's main looper.
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)

        awaitClose { runCatching { sensorManager.unregisterListener(listener) } }
    }

    private companion object {
        const val ALPHA = 0.12
    }
}
