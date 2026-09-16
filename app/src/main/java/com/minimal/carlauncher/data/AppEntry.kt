package com.minimal.carlauncher.data

import android.content.ComponentName

/**
 * One launchable activity.
 *
 * [sortKey] is precomputed lowercase: the drawer's search filter runs over every entry on
 * each keystroke, and lowercasing inside that loop is the difference between instant and laggy.
 */
data class AppEntry(
    val component: ComponentName,
    val label: String,
    val sortKey: String
) {
    val packageName: String get() = component.packageName
    val key: String get() = component.flattenToShortString()
}
