package com.minimal.carlauncher.nav

/** Where the car is along the route right now. */
data class Progress(
    /** The next manoeuvre to announce, or null once only arrival is left. */
    val nextStep: RouteStep?,
    val metresToNextStep: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** Distance from the car to the route line. */
    val offRouteM: Double,
    val arrived: Boolean
)

/**
 * Snaps GPS fixes onto a route and tracks which manoeuvre comes next.
 *
 * Stateful only in [lastIndex]: the search for the nearest segment starts where the car was
 * last time, so a route that passes the same street twice (or a parallel carriageway) does
 * not make the progress jump backwards, and each fix costs a short scan instead of a full one.
 */
class RouteTracker(val route: Route) {

    private val pts = route.points
    /** Cumulative distance from the start to each point. */
    private val along = DoubleArray(pts.size)
    /** Distance along the route of each step's manoeuvre point. */
    private val stepAlong: DoubleArray

    private var lastIndex = 0

    init {
        for (i in 1 until pts.size) along[i] = along[i - 1] + Geo.distanceM(pts[i - 1], pts[i])
        var searchFrom = 0
        stepAlong = DoubleArray(route.steps.size) { s ->
            // Steps are in route order, so each one is searched for after the previous one.
            val target = route.steps[s].location
            var best = searchFrom
            var bestD = Double.MAX_VALUE
            for (i in searchFrom until pts.size) {
                val d = Geo.distanceM(pts[i], target)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            searchFrom = best
            along[best]
        }
    }

    val totalM: Double get() = along.last()

    fun update(position: LatLon): Progress {
        val from = (lastIndex - BACKTRACK).coerceAtLeast(0)
        val to = (lastIndex + LOOKAHEAD).coerceAtMost(pts.size - 1)

        var bestI = from
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        for (i in from until to) {
            val (d, t) = Geo.toSegment(position, pts[i], pts[i + 1])
            if (d < bestD) {
                bestD = d
                bestI = i
                bestT = t
            }
        }
        if (pts.size == 1) bestD = Geo.distanceM(position, pts[0])
        lastIndex = bestI

        val segLen = if (bestI + 1 < pts.size) along[bestI + 1] - along[bestI] else 0.0
        val here = along[bestI] + bestT * segLen
        val remaining = (totalM - here).coerceAtLeast(0.0)

        // The departure step (index 0) is never "next".
        var next: Int? = null
        for (s in 1 until stepAlong.size) {
            if (stepAlong[s] > here + PASSED_M) {
                next = s
                break
            }
        }
        val nextStep = next?.let { route.steps[it] }?.takeIf { it.type != "arrive" }
        val toNext = next?.let { stepAlong[it] - here } ?: remaining

        val seconds = if (totalM > 0) route.durationS * remaining / totalM else 0.0
        return Progress(
            nextStep = nextStep,
            metresToNextStep = if (nextStep == null) remaining else toNext,
            remainingM = remaining,
            remainingS = seconds,
            offRouteM = bestD,
            arrived = remaining < ARRIVED_M
        )
    }

    private companion object {
        const val BACKTRACK = 5
        const val LOOKAHEAD = 400
        /** A manoeuvre counts as done once the car is this far beyond it. */
        const val PASSED_M = 8.0
        const val ARRIVED_M = 25.0
    }
}
