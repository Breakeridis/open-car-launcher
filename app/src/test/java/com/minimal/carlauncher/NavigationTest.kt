package com.minimal.carlauncher

import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.nav.Geo
import com.minimal.carlauncher.nav.LatLon
import com.minimal.carlauncher.nav.Maneuvers
import com.minimal.carlauncher.nav.Route
import com.minimal.carlauncher.nav.RouteParser
import com.minimal.carlauncher.nav.RouteStep
import com.minimal.carlauncher.nav.RouteTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    @Test
    fun `decodes the reference polyline`() {
        val pts = RouteParser.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6)
        assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(40.7, pts[1].lat, 1e-6)
        assertEquals(-126.453, pts[2].lon, 1e-6)
    }

    @Test
    fun `parses an OSRM route and rejects errors`() {
        val json = """
            {"code":"Ok","routes":[{"distance":1234.5,"duration":99.0,
              "geometry":"_p~iF~ps|U_ulLnnqC",
              "legs":[{"steps":[
                {"name":"Main St","maneuver":{"type":"depart","location":[-12.02,3.85]}},
                {"name":"","ref":"N1","maneuver":{"type":"turn","modifier":"left","location":[-12.05,3.9]}},
                {"name":"","maneuver":{"type":"roundabout","exit":2,"location":[-12.06,3.95]}},
                {"name":"","maneuver":{"type":"arrive","location":[-12.095,4.07]}}
              ]}]}]}
        """.trimIndent()
        val route = RouteParser.parseOsrm(json)
        assertNotNull(route)
        route!!
        assertEquals(2, route.points.size)
        assertEquals(4, route.steps.size)
        assertEquals("N1", route.steps[1].roadName)
        assertEquals("left", route.steps[1].modifier)
        assertEquals(3.9, route.steps[1].location.lat, 1e-9)
        assertEquals(2, route.steps[2].roundaboutExit)
        assertEquals(1234.5, route.distanceM, 1e-9)

        assertNull(RouteParser.parseOsrm("""{"code":"NoRoute","routes":[]}"""))
        assertNull(RouteParser.parseOsrm("<html>"))
    }

    /** ~1.1 km due north with a right turn half way. */
    private fun straightRoute(): Route {
        val pts = (0..10).map { LatLon(50.0 + it * 0.001, 4.0) }
        val steps = listOf(
            RouteStep("depart", null, "A", pts[0], null),
            RouteStep("turn", "right", "B", pts[5], null),
            RouteStep("arrive", null, "", pts[10], null)
        )
        return Route(pts, steps, Geo.distanceM(pts[0], pts[10]), 120.0)
    }

    @Test
    fun `tracks the next manoeuvre and the remaining distance`() {
        val tracker = RouteTracker(straightRoute())
        val total = tracker.totalM

        val p1 = tracker.update(LatLon(50.002, 4.0))
        assertEquals("right", p1.nextStep?.modifier)
        assertEquals(total * 0.3, p1.metresToNextStep, 2.0)
        assertEquals(total * 0.8, p1.remainingM, 2.0)
        assertEquals(96.0, p1.remainingS, 1.0)
        assertTrue(p1.offRouteM < 1.0)
        assertFalse(p1.arrived)

        // Past the turn only arrival is left.
        val p2 = tracker.update(LatLon(50.006, 4.0))
        assertNull(p2.nextStep)
        assertEquals(p2.remainingM, p2.metresToNextStep, 1e-6)

        assertTrue(tracker.update(LatLon(50.00999, 4.0)).arrived)
    }

    @Test
    fun `measures how far off the route the car is`() {
        val tracker = RouteTracker(straightRoute())
        // 0.001 degrees of longitude at 50 N is ~71.5 m.
        assertEquals(71.5, tracker.update(LatLon(50.003, 4.001)).offRouteM, 1.5)
    }

    @Test
    fun `instructions and glyphs`() {
        val left = RouteStep("turn", "left", "Main St", LatLon(0.0, 0.0), null)
        assertEquals("Turn left onto Main St", Maneuvers.instruction(left))
        assertEquals("←", Maneuvers.glyph(left))
        val round = RouteStep("roundabout", "right", "", LatLon(0.0, 0.0), 3)
        assertEquals("At the roundabout, take the 3rd exit", Maneuvers.instruction(round))
        assertEquals("Arrive at destination", Maneuvers.instruction(null))
    }

    @Test
    fun `distance and duration text`() {
        assertEquals("350 m", Format.distanceText(Format.UNIT_KMH, 347.0))
        assertEquals("2.4 km", Format.distanceText(Format.UNIT_KMH, 2_410.0))
        assertEquals("12 km", Format.distanceText(Format.UNIT_KMH, 12_400.0))
        assertEquals("1.5 mi", Format.distanceText(Format.UNIT_MPH, 2_414.0))
        assertEquals("300 ft", Format.distanceText(Format.UNIT_MPH, 91.0))
        assertEquals("45 s", Format.durationText(45.0))
        assertEquals("18 min", Format.durationText(1_075.0))
        assertEquals("1 h 05 min", Format.durationText(3_900.0))
    }
}
