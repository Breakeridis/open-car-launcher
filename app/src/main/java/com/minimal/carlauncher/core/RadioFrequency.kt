package com.minimal.carlauncher.core

import java.util.Locale
import kotlin.math.roundToInt

/**
 * A broadcast frequency, held in integer kHz so presets compare exactly (88.6 is 88600).
 *
 * Pure Kotlin so the parser is unit testable: head-unit radio apps put the frequency into
 * whatever media-session field they like ("FM 88.60 MHz", "88.6", "AM 1062 kHz" as title,
 * artist or album), so recognising it reliably is the whole trick.
 */
data class RadioFrequency(val band: Band, val khz: Int) {

    enum class Band { FM, AM }

    /** "88.6" / "87.65" for FM, "1062" for AM. */
    val numberText: String
        get() = when (band) {
            Band.FM -> if (khz % 100 == 0) {
                String.format(Locale.US, "%.1f", khz / 1000.0)
            } else {
                String.format(Locale.US, "%.2f", khz / 1000.0)
            }
            Band.AM -> khz.toString()
        }

    val bandText: String get() = band.name

    val unitText: String get() = if (band == Band.FM) "MHz" else "kHz"

    /** Query for MediaSession.playFromSearch / MEDIA_PLAY_FROM_SEARCH, e.g. "88.6 FM". */
    val searchQuery: String get() = "$numberText $bandText"

    /** Compact, stable pref encoding: "FM:88600". */
    fun encode(): String = "${band.name}:$khz"

    /** One manual tuning step up or down, wrapping at the band edges like a real tuner. */
    fun step(up: Boolean): RadioFrequency {
        val (min, max, step) = when (band) {
            Band.FM -> Triple(FM_MIN_KHZ, FM_MAX_KHZ, FM_STEP_KHZ)
            Band.AM -> Triple(AM_MIN_KHZ, AM_MAX_KHZ, AM_STEP_KHZ)
        }
        var next = khz + if (up) step else -step
        if (next > max) next = min
        if (next < min) next = max
        return copy(khz = next)
    }

    companion object {
        const val FM_MIN_KHZ = 87_500
        const val FM_MAX_KHZ = 108_000
        const val FM_STEP_KHZ = 100
        const val AM_MIN_KHZ = 522
        const val AM_MAX_KHZ = 1_710
        const val AM_STEP_KHZ = 9

        private val NUMBER = Regex("""(?<![\d.,])(\d{2,4})(?:[.,](\d{1,3}))?(?![\d.,]*\d)""")
        // "FM1"/"AM2" are the band-memory labels most Chinese tuner apps show; units are
        // matched without a leading word boundary so "88.6MHz" and "1062kHz" still count.
        private val FM_HINT = Regex("""\bfm\d?\b|mhz|\bukw\b|\bvhf\b""", RegexOption.IGNORE_CASE)
        private val AM_HINT = Regex("""\bam\d?\b|khz|\bmw\b|\blw\b""", RegexOption.IGNORE_CASE)

        fun decode(raw: String?): RadioFrequency? {
            if (raw.isNullOrBlank()) return null
            val band = raw.substringBefore(':', "")
            val khz = raw.substringAfter(':', "").toIntOrNull() ?: return null
            return when (band) {
                Band.FM.name -> if (khz in FM_MIN_KHZ..FM_MAX_KHZ) RadioFrequency(Band.FM, khz) else null
                Band.AM.name -> if (khz in AM_MIN_KHZ..AM_MAX_KHZ) RadioFrequency(Band.AM, khz) else null
                else -> null
            }
        }

        /**
         * Finds a frequency inside free text. Deliberately conservative, because the same
         * fields carry song titles on other media sessions:
         *  - a decimal number in 87.5..108 is FM ("88.6", "FM 88,60 MHz");
         *  - an integer in 87..108 is FM only with an FM/MHz hint;
         *  - an integer in 522..1710 is AM only with an AM/kHz hint ("1999" stays a year).
         */
        fun parse(text: String?): RadioFrequency? {
            if (text.isNullOrBlank()) return null
            val fmHint = FM_HINT.containsMatchIn(text)
            val amHint = AM_HINT.containsMatchIn(text)

            for (match in NUMBER.findAll(text)) {
                val whole = match.groupValues[1].toIntOrNull() ?: continue
                val fraction = match.groupValues[2]
                val hasDecimal = fraction.isNotEmpty()

                if (hasDecimal) {
                    val mhz = "$whole.$fraction".toDoubleOrNull() ?: continue
                    val khz = (mhz * 1000).roundToInt()
                    if (khz in FM_MIN_KHZ..FM_MAX_KHZ && !(amHint && !fmHint)) {
                        return RadioFrequency(Band.FM, khz)
                    }
                    continue
                }

                if (fmHint && whole in 87..108) {
                    val khz = whole * 1000
                    if (khz in FM_MIN_KHZ..FM_MAX_KHZ) return RadioFrequency(Band.FM, khz)
                }
                if (amHint && whole in AM_MIN_KHZ..AM_MAX_KHZ) {
                    return RadioFrequency(Band.AM, whole)
                }
            }
            return null
        }

        /**
         * Parses what a person typed into the "save preset" box: "88.6", "1062", "fm 95.2".
         * Unlike [parse], a bare number is accepted and its band inferred from its range.
         */
        fun parseUserInput(text: String?): RadioFrequency? {
            parse(text)?.let { return it }
            val cleaned = text?.trim()?.replace(',', '.') ?: return null
            val value = cleaned.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
            val fmKhz = (value * 1000).roundToInt()
            return when {
                fmKhz in FM_MIN_KHZ..FM_MAX_KHZ -> RadioFrequency(Band.FM, fmKhz)
                value == value.toInt().toDouble() && value.toInt() in AM_MIN_KHZ..AM_MAX_KHZ ->
                    RadioFrequency(Band.AM, value.toInt())
                else -> null
            }
        }

        /** Heuristic for spotting the native tuner among installed packages / media sessions. */
        fun looksLikeRadioPackage(packageName: String): Boolean {
            val p = packageName.lowercase(Locale.US)
            return "radio" in p || p.endsWith(".fm") || ".fm." in p || "fmradio" in p || "tuner" in p
        }
    }
}
