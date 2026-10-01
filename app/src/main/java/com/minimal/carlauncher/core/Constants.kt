package com.minimal.carlauncher.core

import com.minimal.carlauncher.BuildConfig

/** A rebranded projection product: one display name, several candidate package ids. */
data class ProjectionTarget(val displayName: String, val candidates: List<String>)

object Constants {

    // TODO: set GITHUB_OWNER / GITHUB_REPO in app/build.gradle.kts before shipping.
    // While they still start with "TODO_" the updater stays in the NotConfigured state and
    // never touches the network.
    val GITHUB_OWNER: String = BuildConfig.GITHUB_OWNER
    val GITHUB_REPO: String = BuildConfig.GITHUB_REPO

    val isUpdaterConfigured: Boolean
        get() = !GITHUB_OWNER.startsWith("TODO_") && !GITHUB_REPO.startsWith("TODO_")

    const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    /** Number of user-assignable dock slots. The three system actions are not part of this. */
    const val DOCK_SLOT_COUNT = 4

    /** Poll GitHub at most this often (unauthenticated limit is 60 req/hour/IP). */
    const val UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    /** A GPS fix older than this means "no fix" rather than a stale reading. */
    const val FIX_STALE_MS = 5_000L

    /** Below this, GNSS receivers report random-walk noise, so render 0. */
    const val SPEED_NOISE_GATE_MPS = 0.6f

    /** Above this, GPS course-over-ground beats the magnetometer for heading. */
    const val GPS_BEARING_MIN_MPS = 1.5f

    /** Favourite-station chips on the radio widget. */
    const val RADIO_PRESET_COUNT = 4

    /** How long to wait for the tuner to report a requested frequency before saying it failed. */
    const val RADIO_TUNE_CONFIRM_MS = 2_500L

    /** After the driver pans the map, snap back to following the car after this long. */
    const val MAP_AUTO_RECENTER_MS = 20_000L

    /** Zoom used the first time a GPS fix arrives (street level, readable at a glance). */
    const val MAP_DEFAULT_ZOOM = 16.0

    /**
     * Phone-projection adapters, tried in order. These ids are firmware dependent -
     * verify on the target unit with:
     *   adb shell pm list packages | grep -iE "link|auto|carplay"
     *
     * Allwinner T507 / A133 units (NWD K2401P platform and its rebrands) commonly ship
     * Zjinnova's ZLink, which is why its package is tried first.
     */
    val PROJECTION_TARGETS = listOf(
        ProjectionTarget("ZLink", listOf("com.zjinnova.zlink", "com.zjinglink.zlink", "com.carlinkit.zlink", "cn.manstep.phonemirror")),
        ProjectionTarget("AutoKit", listOf("com.autokit.carplay", "com.carbit.autokit", "com.autokit.link")),
        ProjectionTarget("EasyConnection", listOf("net.easyconn", "net.easyconn.carman")),
        ProjectionTarget("Headunit Reloaded", listOf("com.hur.headunit", "com.hur.reloaded")),
    )
}
