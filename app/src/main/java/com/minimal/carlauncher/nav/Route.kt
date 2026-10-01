package com.minimal.carlauncher.nav

import org.json.JSONObject

/** One manoeuvre, in OSRM's vocabulary (type "turn", modifier "left", ...). */
data class RouteStep(
    val type: String,
    val modifier: String?,
    val roadName: String,
    val location: LatLon,
    val roundaboutExit: Int?
)

data class Route(
    val points: List<LatLon>,
    val steps: List<RouteStep>,
    val distanceM: Double,
    val durationS: Double
)

object RouteParser {

    /**
     * Parses an OSRM /route response (geometries=polyline6, steps=true).
     * Returns null for anything that is not a usable route - never throws.
     */
    fun parseOsrm(json: String): Route? = try {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") {
            null
        } else {
            val route = root.getJSONArray("routes").getJSONObject(0)
            val points = decodePolyline(route.getString("geometry"), precision = 6)
            val steps = mutableListOf<RouteStep>()
            val legs = route.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val legSteps = legs.getJSONObject(l).getJSONArray("steps")
                for (s in 0 until legSteps.length()) {
                    val step = legSteps.getJSONObject(s)
                    val m = step.getJSONObject("maneuver")
                    val loc = m.getJSONArray("location")   // [lon, lat]
                    steps += RouteStep(
                        type = m.optString("type"),
                        modifier = m.optString("modifier").ifBlank { null },
                        roadName = step.optString("name").ifBlank { step.optString("ref") },
                        location = LatLon(loc.getDouble(1), loc.getDouble(0)),
                        roundaboutExit = if (m.has("exit")) m.optInt("exit") else null
                    )
                }
            }
            if (points.size < 2) null
            else Route(points, steps, route.optDouble("distance"), route.optDouble("duration"))
        }
    } catch (e: Exception) {
        null
    }

    /** Google encoded-polyline algorithm; OSRM's polyline6 is the same with 1e6 precision. */
    fun decodePolyline(encoded: String, precision: Int = 5): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = mutableListOf<LatLon>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            result = 0L
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }
}
