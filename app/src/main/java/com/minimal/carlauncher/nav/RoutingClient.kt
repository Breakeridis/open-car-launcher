package com.minimal.carlauncher.nav

import com.minimal.carlauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Driving routes from OSRM - no API key, no Google Play Services, same HttpURLConnection
 * approach as the rest of the app.
 *
 * The public router.project-osrm.org server is a demo service with a fair-use policy: fine for
 * one car asking for a route now and then (and a reroute when off course), not for a fleet.
 * For more, point [ENDPOINT] at your own OSRM instance or a commercial one.
 */
object RoutingClient {

    private const val ENDPOINT = "https://router.project-osrm.org/route/v1/driving"
    private const val TIMEOUT_MS = 12_000

    /** @throws IOException when offline, on HTTP errors, or when no route exists. */
    suspend fun route(from: LatLon, to: LatLon, headingDeg: Float?): Route =
        withContext(Dispatchers.IO) {
            val coords = String.format(
                Locale.US, "%.6f,%.6f;%.6f,%.6f", from.lon, from.lat, to.lon, to.lat
            )
            // A bearing hint stops the router from starting the trip with a U-turn.
            val bearings = headingDeg?.let {
                String.format(Locale.US, "&bearings=%d,45;", it.toInt().mod(360))
            }.orEmpty()
            val url = "$ENDPOINT/$coords?overview=full&geometries=polyline6&steps=true$bearings"

            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty(
                    "User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
                )
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                RouteParser.parseOsrm(body) ?: throw IOException("No route (HTTP $code)")
            } finally {
                connection.disconnect()
            }
        }
}
