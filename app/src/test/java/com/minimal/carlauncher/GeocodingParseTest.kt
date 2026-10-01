package com.minimal.carlauncher

import com.minimal.carlauncher.map.GeocodingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocodingParseTest {

    @Test
    fun `parses nominatim results and skips broken entries`() {
        val json = """
            [
              {"lat": "51.2194", "lon": "4.4025", "display_name": "Antwerpen, Belgium"},
              {"lat": "x", "lon": "4.0", "display_name": "Broken"},
              {"lat": "50.85", "lon": "4.35", "display_name": "", "name": "Brussel"}
            ]
        """.trimIndent()
        val places = GeocodingClient.parse(json)
        assertEquals(2, places.size)
        assertEquals("Antwerpen, Belgium", places[0].name)
        assertEquals(51.2194, places[0].latitude, 1e-6)
        assertEquals("Brussel", places[1].name)
    }

    @Test
    fun `garbage is no results, not a crash`() {
        assertTrue(GeocodingClient.parse("<html>rate limited</html>").isEmpty())
        assertTrue(GeocodingClient.parse("{}").isEmpty())
    }
}
