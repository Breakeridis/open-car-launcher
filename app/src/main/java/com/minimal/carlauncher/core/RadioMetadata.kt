package com.minimal.carlauncher.core

/** What the radio widget shows, pulled out of a tuner's media-session text fields. */
data class RadioReadout(
    val frequency: RadioFrequency?,
    val stationName: String?,
    val radioText: String?
)

/**
 * Tuner apps disagree on where they put things: some publish the frequency as the title and
 * the RDS Program Service name as the artist, others the reverse, others "FM1 88.6" plus
 * RadioText in the album field. This takes the fields in priority order and sorts them out.
 */
object RadioMetadata {

    fun extract(fields: List<String?>): RadioReadout {
        val texts = fields.mapNotNull { it?.trim()?.ifBlank { null } }.distinct()

        var frequency: RadioFrequency? = null
        var frequencyField: String? = null
        for (t in texts) {
            val parsed = RadioFrequency.parse(t)
            if (parsed != null) {
                frequency = parsed
                frequencyField = t
                break
            }
        }

        // Anything that is not just the frequency restated is station name, then radio text.
        val rest = texts.filter {
            it != frequencyField && (frequency == null || RadioFrequency.parse(it) != frequency)
        }
        return RadioReadout(
            frequency = frequency,
            stationName = rest.getOrNull(0),
            radioText = rest.getOrNull(1)
        )
    }
}
