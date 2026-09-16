package com.minimal.carlauncher.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure display formatting. Kept free of Android types so it is unit testable. */
object Format {

    const val UNIT_KMH = "kmh"
    const val UNIT_MPH = "mph"

    private const val MPS_TO_KMH = 3.6f
    private const val MPS_TO_MPH = 2.236936f

    private val POINTS_16 = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    )
    private val POINTS_8 = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun mpsTo(unit: String, mps: Float): Float =
        if (unit == UNIT_MPH) mps * MPS_TO_MPH else mps * MPS_TO_KMH

    /** Integer-only readout: decimals jitter and are unreadable at a glance while driving. */
    fun speedText(unit: String, mps: Float, hasFix: Boolean): String {
        if (!hasFix) return "--"
        val gated = if (mps < Constants.SPEED_NOISE_GATE_MPS) 0f else mps
        return mpsTo(unit, gated).roundToInt().toString()
    }

    fun unitLabel(unit: String): String = if (unit == UNIT_MPH) "MPH" else "KM/H"

    fun otherUnit(unit: String): String = if (unit == UNIT_MPH) UNIT_KMH else UNIT_MPH

    fun normalizeDegrees(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    fun cardinal(deg: Float, sixteenPoint: Boolean = true): String {
        val n = normalizeDegrees(deg)
        return if (sixteenPoint) {
            POINTS_16[((n / 22.5f) + 0.5f).toInt() % 16]
        } else {
            POINTS_8[((n / 45f) + 0.5f).toInt() % 8]
        }
    }

    fun degreesText(deg: Float): String = "${normalizeDegrees(deg).roundToInt() % 360}°"

    /**
     * Hysteresis so the cardinal label does not flicker between two points at a stoplight:
     * keep the previous label until the heading is [marginDeg] past the sector boundary.
     */
    fun cardinalWithHysteresis(
        deg: Float,
        previous: String?,
        sixteenPoint: Boolean = true,
        marginDeg: Float = 3f
    ): String {
        val fresh = cardinal(deg, sixteenPoint)
        if (previous == null || previous == fresh) return fresh
        val points = if (sixteenPoint) POINTS_16 else POINTS_8
        val idx = points.indexOf(previous)
        if (idx < 0) return fresh
        val sector = 360f / points.size
        val centre = idx * sector
        var delta = normalizeDegrees(deg) - centre
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        // Still within the old sector plus the margin - keep the old label.
        return if (abs(delta) <= sector / 2f + marginDeg) previous else fresh
    }

    fun byteProgressText(bytes: Long, total: Long): String {
        val mbDone = bytes / 1_048_576.0
        return if (total > 0) {
            String.format("%.1f / %.1f MB", mbDone, total / 1_048_576.0)
        } else {
            String.format("%.1f MB", mbDone)
        }
    }

    fun percent(bytes: Long, total: Long): Int =
        if (total <= 0L) 0 else ((bytes * 100L) / total).toInt().coerceIn(0, 100)
}
