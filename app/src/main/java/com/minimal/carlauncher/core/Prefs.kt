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
}
