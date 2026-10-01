package com.minimal.carlauncher.core

import java.util.Locale

/**
 * Picks the seek-up / seek-down button among a tuner notification's actions.
 * Pure so it is unit testable; the titles come from Notification.Action.title.
 */
object RadioActions {

    private val NEXT = listOf("next", "seek+", "seek up", "forward", "skip", ">>", "▶▶", "⏭", "下一", "下")
    private val PREV = listOf("prev", "seek-", "seek down", "back", "rewind", "<<", "◀◀", "⏮", "上一", "上")

    /** @return the action index, or null when no button can be identified. */
    fun indexFor(titles: List<String?>, up: Boolean): Int? {
        val lower = titles.map { it?.lowercase(Locale.ROOT)?.trim().orEmpty() }
        val keys = if (up) NEXT else PREV
        val byName = lower.indexOfFirst { t -> t.isNotEmpty() && keys.any { it in t } }
        if (byName >= 0) return byName

        // Unlabelled buttons: media-style layouts put previous first and next last.
        return when {
            lower.size >= 3 -> if (up) lower.lastIndex else 0
            lower.size == 2 -> if (up) 1 else 0
            else -> null
        }
    }
}
