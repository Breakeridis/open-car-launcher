package com.minimal.carlauncher.map

import com.minimal.carlauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Place(val name: String, val latitude: Double, val longitude: Double) {
    /** First part of the address ("Grote Markt"), for headings. */
    val title: String get() = name.substringBefore(',').trim()

    /** The rest of the address ("Antwerpen, Vlaanderen, België"). */
    val subtitle: String get() = name.substringAfter(',', "").trim()
}

/**
 * Address / POI search through OpenStreetMap's Nominatim - no API key and no Google Play
 * Services, matching the map tiles. Same HttpURLConnection + org.json approach as the updater.
 *
 * Nominatim's usage policy requires an identifying User-Agent and at most one request per
 * second; a person typing into a dialog and pressing Search stays far below that.
 */
object GeocodingClient {

    private const val ENDPOINT = "https://nominatim.openstreetmap.org/search"
    private const val REVERSE_ENDPOINT = "https://nominatim.openstreetmap.org/reverse"
    private const val TIMEOUT_MS = 10_000

    /** @throws java.io.IOException when offline or the server misbehaves. */
    suspend fun search(query: String, nearLat: Double?, nearLon: Double?): List<Place> =
        withContext(Dispatchers.IO) {
            val params = buildString {
                append("format=jsonv2&limit=8&q=")
                append(URLEncoder.encode(query.trim(), "UTF-8"))
                append("&accept-language=")
                append(URLEncoder.encode(Locale.getDefault().toLanguageTag(), "UTF-8"))
                // Prefer (but do not restrict to) results around the car.
                if (nearLat != null && nearLon != null) {
                    append(
                        String.format(
                            Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f&bounded=0",
                            nearLon - 0.5, nearLat + 0.5, nearLon + 0.5, nearLat - 0.5
                        )
                    )
                }
            }
            val connection = URL("$ENDPOINT?$params").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty(
                    "User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
                )
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) {
                    throw java.io.IOException("HTTP ${connection.responseCode}")
                }
                parse(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }

    /** Name of the address at a point (for long-press pins). Null when offline or unknown. */
    suspend fun reverse(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = String.format(
                Locale.US, "%s?format=jsonv2&zoom=18&lat=%.6f&lon=%.6f&accept-language=%s",
                REVERSE_ENDPOINT, lat, lon,
                URLEncoder.encode(Locale.getDefault().toLanguageTag(), "UTF-8")
            )
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty(
                    "User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
                )
                if (connection.responseCode !in 200..299) return@withContext null
                parseReverse(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseReverse(json: String): String? = try {
        org.json.JSONObject(json).optString("display_name").ifBlank { null }
    } catch (e: Exception) {
        null
    }

    /** Never throws: an unexpected payload is simply "no results". */
    fun parse(json: String): List<Place> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val name = o.optString("display_name").ifBlank { o.optString("name") }
            if (name.isBlank()) null else Place(name, lat, lon)
        }
    } catch (e: Exception) {
        emptyList()
    }
}
