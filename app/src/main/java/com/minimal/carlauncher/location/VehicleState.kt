package com.minimal.carlauncher.location

enum class HeadingSource { NONE, GPS, SENSOR }

/** Everything the dashboard gauges and the map portal need, in one immutable snapshot. */
data class VehicleState(
    val hasFix: Boolean = false,
    val speedMps: Float = 0f,
    val headingDeg: Float? = null,
    val headingSource: HeadingSource = HeadingSource.NONE,
    val gpsEnabled: Boolean = true,
    val permissionGranted: Boolean = false,
    val preciseLocation: Boolean = false,
    /** Last known position; kept after the fix goes stale so the map keeps a car marker. */
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null
}
