package com.minimal.carlauncher

import com.minimal.carlauncher.core.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `converts metres per second to both units`() {
        assertEquals(36f, Format.mpsTo(Format.UNIT_KMH, 10f), 0.001f)
        assertEquals(22.369f, Format.mpsTo(Format.UNIT_MPH, 10f), 0.001f)
    }

    @Test
    fun `shows a placeholder until there is a fix`() {
        assertEquals("--", Format.speedText(Format.UNIT_KMH, 25f, hasFix = false))
        assertEquals("90", Format.speedText(Format.UNIT_KMH, 25f, hasFix = true))
    }

    @Test
    fun `gates stationary GNSS noise to zero`() {
        // A parked receiver reports a few tenths of a m/s of random walk.
        assertEquals("0", Format.speedText(Format.UNIT_KMH, 0.4f, hasFix = true))
        assertEquals("0", Format.speedText(Format.UNIT_MPH, 0.59f, hasFix = true))
        assertEquals("3", Format.speedText(Format.UNIT_KMH, 0.7f, hasFix = true))
    }

    @Test
    fun `unit label and toggle round-trip`() {
        assertEquals("KM/H", Format.unitLabel(Format.UNIT_KMH))
        assertEquals("MPH", Format.unitLabel(Format.UNIT_MPH))
        assertEquals(Format.UNIT_MPH, Format.otherUnit(Format.UNIT_KMH))
        assertEquals(Format.UNIT_KMH, Format.otherUnit(Format.UNIT_MPH))
    }

    @Test
    fun `maps degrees to 16 cardinal points`() {
        assertEquals("N", Format.cardinal(0f))
        assertEquals("NNE", Format.cardinal(22.5f))
        assertEquals("NE", Format.cardinal(45f))
        assertEquals("E", Format.cardinal(90f))
        assertEquals("S", Format.cardinal(180f))
        assertEquals("W", Format.cardinal(270f))
        assertEquals("N", Format.cardinal(350f))
    }

    @Test
    fun `maps degrees to 8 cardinal points`() {
        assertEquals("N", Format.cardinal(0f, sixteenPoint = false))
        assertEquals("NE", Format.cardinal(45f, sixteenPoint = false))
        assertEquals("E", Format.cardinal(90f, sixteenPoint = false))
        assertEquals("N", Format.cardinal(350f, sixteenPoint = false))
    }

    @Test
    fun `normalises out-of-range and negative bearings`() {
        assertEquals("N", Format.cardinal(-5f))
        assertEquals("E", Format.cardinal(450f))
        assertEquals(0f, Format.normalizeDegrees(360f), 0.001f)
        assertEquals(355f, Format.normalizeDegrees(-5f), 0.001f)
    }

    @Test
    fun `hysteresis holds the label just past a sector boundary`() {
        // 12 degrees is inside NNE, but only just - keep showing N.
        assertEquals("N", Format.cardinalWithHysteresis(12f, previous = "N"))
        // 20 degrees is clear of the boundary, so commit to the new label.
        assertEquals("NNE", Format.cardinalWithHysteresis(20f, previous = "N"))
        // With no previous label there is nothing to hold on to.
        assertEquals("NNE", Format.cardinalWithHysteresis(12f, previous = null))
    }

    @Test
    fun `download percentage is clamped and safe when the size is unknown`() {
        assertEquals(0, Format.percent(0L, 100L))
        assertEquals(50, Format.percent(50L, 100L))
        assertEquals(100, Format.percent(100L, 100L))
        assertEquals(0, Format.percent(10L, -1L))
        assertEquals(0, Format.percent(10L, 0L))
    }
}
