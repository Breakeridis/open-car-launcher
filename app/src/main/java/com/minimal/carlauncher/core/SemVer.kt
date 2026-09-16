package com.minimal.carlauncher.core

/**
 * Semantic-version comparison tolerant of everything GitHub tags actually contain:
 * a leading "v", differing segment counts ("1.2" == "1.2.0"), build metadata and
 * pre-release suffixes ("1.2.3-rc1+build7"), and outright garbage.
 */
object SemVer {

    /** @return negative if a < b, 0 if equal, positive if a > b. */
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)

        val partsA = numbers(coreA)
        val partsB = numbers(coreB)
        val size = maxOf(partsA.size, partsB.size)
        for (i in 0 until size) {
            val x = partsA.getOrElse(i) { 0 }
            val y = partsB.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }

        // SemVer 11: a release outranks any pre-release of the same core version.
        return when {
            preA.isEmpty() && preB.isEmpty() -> 0
            preA.isEmpty() -> 1
            preB.isEmpty() -> -1
            else -> preA.compareTo(preB)
        }
    }

    fun isNewer(latestTag: String, current: String): Boolean = compare(latestTag, current) > 0

    /** Strips a leading v/V and separates the numeric core from any pre-release suffix. */
    private fun split(raw: String): Pair<String, String> {
        var s = raw.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        s = s.substringBefore('+')
        val dash = s.indexOf('-')
        return if (dash >= 0) s.substring(0, dash) to s.substring(dash + 1) else s to ""
    }

    /** "1.2.3" -> [1, 2, 3]; unparsable segments become 0 rather than throwing. */
    private fun numbers(core: String): List<Int> {
        if (core.isEmpty()) return emptyList()
        return core.split('.').map { seg ->
            seg.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
