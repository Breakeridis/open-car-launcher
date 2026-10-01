package com.minimal.carlauncher.nav

/** Turns OSRM manoeuvres into a glanceable arrow and a short English instruction. */
object Maneuvers {

    fun glyph(step: RouteStep?): String {
        if (step == null) return "◉"
        return when (step.type) {
            "arrive" -> "◉"
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> "⟳"
            else -> when (step.modifier) {
                "uturn" -> "↶"
                "sharp left" -> "↙"
                "left" -> "←"
                "slight left" -> "↖"
                "slight right" -> "↗"
                "right" -> "→"
                "sharp right" -> "↘"
                else -> "↑"
            }
        }
    }

    fun instruction(step: RouteStep?): String {
        if (step == null) return "Arrive at destination"
        val onto = if (step.roadName.isNotBlank()) " onto ${step.roadName}" else ""
        val direction = step.modifier?.let { mod ->
            when (mod) {
                "uturn" -> "Make a U-turn"
                "straight" -> "Continue straight"
                else -> "Turn $mod"
            }
        } ?: "Continue"
        return when (step.type) {
            "arrive" -> "Arrive at destination"
            "roundabout", "rotary" -> {
                val exit = step.roundaboutExit?.let { "take the ${ordinal(it)} exit" } ?: "go through"
                "At the roundabout, $exit$onto"
            }
            "merge" -> "Merge$onto"
            "on ramp" -> "Take the ramp$onto"
            "off ramp" -> "Take the exit$onto"
            "fork" -> "Keep ${step.modifier?.replace("slight ", "") ?: "straight"}$onto"
            "end of road" -> "At the end of the road, ${direction.lowercase()}$onto"
            "continue", "new name" -> "Continue$onto"
            else -> "$direction$onto"
        }
    }

    private fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}
