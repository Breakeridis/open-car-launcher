package com.minimal.carlauncher.core

import org.json.JSONArray

/**
 * Encodes the dock as a FIXED-LENGTH JSON array of flattened ComponentName strings,
 * where "" is an empty slot. Fixed length keeps slot positions stable: removing an app
 * blanks its slot instead of shifting everything left.
 *
 * decode() must never throw - the launcher inflates its dock during onCreate and a
 * malformed pref must degrade to defaults, not to a crash loop on the home screen.
 */
object DockCodec {

    fun empty(size: Int = Constants.DOCK_SLOT_COUNT): List<String> = List(size) { "" }

    fun encode(slots: List<String>): String {
        val arr = JSONArray()
        slots.forEach { arr.put(it) }
        return arr.toString()
    }

    fun decode(raw: String?, size: Int = Constants.DOCK_SLOT_COUNT): List<String> {
        if (raw.isNullOrBlank()) return empty(size)
        return try {
            val arr = JSONArray(raw)
            List(size) { i ->
                if (i < arr.length()) arr.optString(i, "") else ""
            }
        } catch (e: Exception) {
            empty(size)
        }
    }

    /** Blanks every slot referencing [packageName]; returns null when nothing changed. */
    fun removePackage(slots: List<String>, packageName: String): List<String>? {
        var changed = false
        val out = slots.map { entry ->
            if (entry.isNotEmpty() && entry.substringBefore('/') == packageName) {
                changed = true
                ""
            } else {
                entry
            }
        }
        return if (changed) out else null
    }
}
