package com.minimal.carlauncher.core

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Single SharedPreferences file behind a typed facade.
 *
 * Deliberately not DataStore: the launcher needs a synchronous read of theme and dock
 * contents inside onCreate, before the first frame. SharedPreferences is loaded once and
 * kept in memory, which is exactly the right shape here.
 */
object Prefs {

    private const val FILE = "car_launcher"

    const val KEY_SPEED_UNIT = "pref_speed_unit"
    const val KEY_THEME_MODE = "pref_theme_mode"
    const val KEY_COMPASS_SOURCE = "pref_compass_source"
    const val KEY_COMPASS_16 = "pref_compass_16point"
    const val KEY_NAV_PACKAGE = "pref_nav_package"
    const val KEY_MUSIC_PACKAGE = "pref_music_package"
    const val KEY_PROJECTION_PACKAGE = "pref_projection_package"
    const val KEY_DOCK_SLOTS = "pref_dock_slots"
    const val KEY_DASHCAM_PACKAGE = "pref_dashcam_package"
    const val KEY_DASHCAM_AUTOSTART = "pref_dashcam_autostart"
    const val KEY_DASHCAM_RETURN_HOME = "pref_dashcam_return_home"
    const val KEY_LAST_SEEN_TAG = "pref_last_seen_release_tag"
    const val KEY_LAST_CHECK_MS = "pref_last_update_check_ms"
    const val KEY_CACHED_TAG = "pref_cached_latest_tag"
    const val KEY_CACHED_URL = "pref_cached_latest_url"
    const val KEY_PENDING_APK = "pref_pending_apk_path"
    const val KEY_FIRST_RUN_DONE = "pref_first_run_done"
    const val KEY_RADIO_PACKAGE = "pref_radio_package"
    const val KEY_RADIO_PRESETS = "pref_radio_presets"
    const val KEY_RADIO_LAST = "pref_radio_last_frequency"
    const val KEY_RADIO_LAST_NAME = "pref_radio_last_station"
    const val KEY_VEHICLE_SETTINGS_PACKAGE = "pref_vehicle_settings_package"
    const val KEY_MAP_LAT = "pref_map_lat"
    const val KEY_MAP_LON = "pref_map_lon"
    const val KEY_MAP_ZOOM = "pref_map_zoom"
    const val KEY_MAP_HEADING_UP = "pref_map_heading_up"

    const val COMPASS_AUTO = "auto"
    const val COMPASS_GPS = "gps"
    const val COMPASS_SENSOR = "sensor"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var speedUnit: String
        get() = sp.getString(KEY_SPEED_UNIT, Format.UNIT_KMH) ?: Format.UNIT_KMH
        set(value) = sp.edit().putString(KEY_SPEED_UNIT, value).apply()

    /** One of the AppCompatDelegate.MODE_NIGHT_* constants. Defaults to the dark cockpit. */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_YES)
        set(value) = sp.edit().putInt(KEY_THEME_MODE, value).apply()

    var compassSource: String
        get() = sp.getString(KEY_COMPASS_SOURCE, COMPASS_AUTO) ?: COMPASS_AUTO
        set(value) = sp.edit().putString(KEY_COMPASS_SOURCE, value).apply()

    var compass16Point: Boolean
        get() = sp.getBoolean(KEY_COMPASS_16, false)
        set(value) = sp.edit().putBoolean(KEY_COMPASS_16, value).apply()

    var navPackage: String?
        get() = sp.getString(KEY_NAV_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_NAV_PACKAGE, value).apply()

    var musicPackage: String?
        get() = sp.getString(KEY_MUSIC_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_MUSIC_PACKAGE, value).apply()

    var projectionPackage: String?
        get() = sp.getString(KEY_PROJECTION_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_PROJECTION_PACKAGE, value).apply()

    /** Dashcam / DVR app started once per launcher process so its overlay is up. */
    var dashcamPackage: String?
        get() = sp.getString(KEY_DASHCAM_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_DASHCAM_PACKAGE, value).apply()

    var dashcamAutoStart: Boolean
        get() = sp.getBoolean(KEY_DASHCAM_AUTOSTART, false)
        set(value) = sp.edit().putBoolean(KEY_DASHCAM_AUTOSTART, value).apply()

    /**
     * Try to bring the dashboard back after launching the dashcam. Best-effort: Android 10+
     * restricts background activity starts, so some ROMs will ignore it and the user simply
     * presses HOME once.
     */
    var dashcamReturnHome: Boolean
        get() = sp.getBoolean(KEY_DASHCAM_RETURN_HOME, true)
        set(value) = sp.edit().putBoolean(KEY_DASHCAM_RETURN_HOME, value).apply()

    var dockSlots: List<String>
        get() = DockCodec.decode(sp.getString(KEY_DOCK_SLOTS, null))
        set(value) = sp.edit().putString(KEY_DOCK_SLOTS, DockCodec.encode(value)).apply()

    var lastSeenReleaseTag: String
        get() = sp.getString(KEY_LAST_SEEN_TAG, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_SEEN_TAG, value).apply()

    var lastUpdateCheckMs: Long
        get() = sp.getLong(KEY_LAST_CHECK_MS, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_CHECK_MS, value).apply()

    var cachedLatestTag: String
        get() = sp.getString(KEY_CACHED_TAG, "") ?: ""
        set(value) = sp.edit().putString(KEY_CACHED_TAG, value).apply()

    var cachedLatestUrl: String
        get() = sp.getString(KEY_CACHED_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_CACHED_URL, value).apply()

    var pendingApkPath: String
        get() = sp.getString(KEY_PENDING_APK, "") ?: ""
        set(value) = sp.edit().putString(KEY_PENDING_APK, value).apply()

    var firstRunDone: Boolean
        get() = sp.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_FIRST_RUN_DONE, value).apply()

    // ------------------------------------------------------------------ radio

    /** The head unit's native tuner app. Null means "detect by package name". */
    var radioPackage: String?
        get() = sp.getString(KEY_RADIO_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_RADIO_PACKAGE, value).apply()

    /** Fixed-length preset slots, each a [RadioFrequency.encode] string or "" for empty. */
    var radioPresets: List<String>
        get() = DockCodec.decode(sp.getString(KEY_RADIO_PRESETS, null), Constants.RADIO_PRESET_COUNT)
        set(value) = sp.edit().putString(KEY_RADIO_PRESETS, DockCodec.encode(value)).apply()

    /**
     * Last frequency seen on the tuner, so the widget shows a station the instant the unit
     * wakes from ACC-off, before the radio app has republished its media session.
     */
    var radioLastFrequency: String
        get() = sp.getString(KEY_RADIO_LAST, "") ?: ""
        set(value) = sp.edit().putString(KEY_RADIO_LAST, value).apply()

    var radioLastStation: String
        get() = sp.getString(KEY_RADIO_LAST_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_RADIO_LAST_NAME, value).apply()

    // ---------------------------------------------------------------- vehicle

    /** The factory "car settings" app. Null falls back to Android's own Settings. */
    var vehicleSettingsPackage: String?
        get() = sp.getString(KEY_VEHICLE_SETTINGS_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_VEHICLE_SETTINGS_PACKAGE, value).apply()

    // -------------------------------------------------------------------- map

    /** Last camera position, restored on boot so the portal never opens on an empty ocean. */
    fun mapCamera(): Triple<Double, Double, Double>? {
        if (!sp.contains(KEY_MAP_LAT)) return null
        val lat = sp.getFloat(KEY_MAP_LAT, 0f).toDouble()
        val lon = sp.getFloat(KEY_MAP_LON, 0f).toDouble()
        val zoom = sp.getFloat(KEY_MAP_ZOOM, 16f).toDouble()
        if (lat !in -85.0..85.0 || lon !in -180.0..180.0) return null
        return Triple(lat, lon, zoom)
    }

    fun saveMapCamera(lat: Double, lon: Double, zoom: Double) {
        sp.edit()
            .putFloat(KEY_MAP_LAT, lat.toFloat())
            .putFloat(KEY_MAP_LON, lon.toFloat())
            .putFloat(KEY_MAP_ZOOM, zoom.toFloat())
            .apply()
    }

    /** True = map rotates so travel direction is up; false = north-up. */
    var mapHeadingUp: Boolean
        get() = sp.getBoolean(KEY_MAP_HEADING_UP, true)
        set(value) = sp.edit().putBoolean(KEY_MAP_HEADING_UP, value).apply()
}
