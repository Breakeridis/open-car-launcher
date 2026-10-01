package com.minimal.carlauncher.nav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position. Plain Kotlin so all route maths is unit testable on the JVM. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Distance from [p] to the segment [a]-[b], and how far along the segment (0..1) the closest
     * point lies. Uses a local flat projection, which is exact enough over a road segment.
     */
    fun toSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val k = cos(rad(p.lat))
        val ax = (a.lon - p.lon) * k
        val ay = a.lat - p.lat
        val bx = (b.lon - p.lon) * k
        val by = b.lat - p.lat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val metresPerDegree = EARTH_RADIUS_M * PI / 180.0
        return sqrt(cx * cx + cy * cy) * metresPerDegree to t
    }

    private fun rad(deg: Double) = deg * PI / 180.0
}
