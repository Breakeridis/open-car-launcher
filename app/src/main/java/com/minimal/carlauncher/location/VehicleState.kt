package com.minimal.carlauncher.location

enum class HeadingSource { NONE, GPS, SENSOR }

/** Everything the dashboard gauges need, in one immutable snapshot. */
data class VehicleState(
    val hasFix: Boolean = false,
    val speedMps: Float = 0f,
    val headingDeg: Float? = null,
    val headingSource: HeadingSource = HeadingSource.NONE,
    val gpsEnabled: Boolean = true,
    val permissionGranted: Boolean = false,
    val preciseLocation: Boolean = false
)
