package com.minimal.carlauncher.radio

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Pulls the visible text out of another app's notification.
 *
 * Vendor tuner apps on Allwinner-based head units often publish no media session at all and
 * show the frequency only in an ongoing notification - frequently a custom RemoteViews layout,
 * whose text is not in the standard extras. So both are read: the extras, and (for the tuner's
 * own notification only) the custom layout inflated and walked for TextViews.
 */
object NotificationText {

    private val EXTRA_KEYS = arrayOf(
        Notification.EXTRA_TITLE,
        Notification.EXTRA_TITLE_BIG,
        Notification.EXTRA_TEXT,
        Notification.EXTRA_SUB_TEXT,
        Notification.EXTRA_INFO_TEXT,
        Notification.EXTRA_SUMMARY_TEXT,
        Notification.EXTRA_BIG_TEXT
    )

    fun fromExtras(n: Notification): List<String> {
        val extras = n.extras
        val out = EXTRA_KEYS.mapNotNull { key -> extras?.getCharSequence(key)?.toString() }
        return (out + listOfNotNull(n.tickerText?.toString()))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Must run on the main thread. Never throws: a layout that will not inflate is just empty. */
    @Suppress("DEPRECATION")
    fun fromCustomLayout(context: Context, n: Notification): List<String> {
        val views = n.contentView ?: n.bigContentView ?: return emptyList()
        return try {
            val root = views.apply(context, FrameLayout(context))
            val out = mutableListOf<String>()
            collect(root, out)
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun all(context: Context, n: Notification): List<String> =
        (fromExtras(n) + fromCustomLayout(context, n)).distinct()

    private fun collect(view: View, out: MutableList<String>) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView) {
            view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }
}
