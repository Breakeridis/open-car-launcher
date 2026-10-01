package com.minimal.carlauncher

import com.minimal.carlauncher.core.RadioFrequency
import com.minimal.carlauncher.core.RadioFrequency.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioFrequencyTest {

    @Test
    fun `parses the formats head-unit tuners put in media metadata`() {
        assertEquals(RadioFrequency(Band.FM, 88_600), RadioFrequency.parse("88.6"))
        assertEquals(RadioFrequency(Band.FM, 88_600), RadioFrequency.parse("FM 88.60 MHz"))
        assertEquals(RadioFrequency(Band.FM, 95_200), RadioFrequency.parse("FM1  95,2"))
        assertEquals(RadioFrequency(Band.FM, 103_700), RadioFrequency.parse("103.7MHz"))
        assertEquals(RadioFrequency(Band.FM, 87_650), RadioFrequency.parse("87.65"))
        assertEquals(RadioFrequency(Band.FM, 98_000), RadioFrequency.parse("FM 98"))
        assertEquals(RadioFrequency(Band.AM, 1_062), RadioFrequency.parse("AM 1062"))
        assertEquals(RadioFrequency(Band.AM, 999), RadioFrequency.parse("999kHz"))
    }

    @Test
    fun `does not mistake song metadata for a frequency`() {
        assertNull(RadioFrequency.parse("99 Luftballons"))
        assertNull(RadioFrequency.parse("Summer of 1969"))
        assertNull(RadioFrequency.parse("Track 05"))
        assertNull(RadioFrequency.parse("12345"))
        assertNull(RadioFrequency.parse("120.5"))
        assertNull(RadioFrequency.parse(""))
        assertNull(RadioFrequency.parse(null))
    }

    @Test
    fun `user input accepts bare numbers`() {
        assertEquals(RadioFrequency(Band.FM, 95_200), RadioFrequency.parseUserInput("95.2"))
        assertEquals(RadioFrequency(Band.FM, 101_000), RadioFrequency.parseUserInput("101"))
        assertEquals(RadioFrequency(Band.AM, 1_062), RadioFrequency.parseUserInput("1062"))
        assertNull(RadioFrequency.parseUserInput("5"))
        assertNull(RadioFrequency.parseUserInput("abc"))
    }

    @Test
    fun `display text`() {
        assertEquals("88.6", RadioFrequency(Band.FM, 88_600).numberText)
        assertEquals("87.65", RadioFrequency(Band.FM, 87_650).numberText)
        assertEquals("1062", RadioFrequency(Band.AM, 1_062).numberText)
        assertEquals("88.6 FM", RadioFrequency(Band.FM, 88_600).searchQuery)
    }

    @Test
    fun `encode and decode round-trip and reject garbage`() {
        val f = RadioFrequency(Band.FM, 103_700)
        assertEquals(f, RadioFrequency.decode(f.encode()))
        assertNull(RadioFrequency.decode(""))
        assertNull(RadioFrequency.decode("FM:1"))
        assertNull(RadioFrequency.decode("XX:88600"))
        assertNull(RadioFrequency.decode("garbage"))
    }

    @Test
    fun `steps wrap at the band edges`() {
        assertEquals(88_700, RadioFrequency(Band.FM, 88_600).step(up = true).khz)
        assertEquals(87_500, RadioFrequency(Band.FM, 108_000).step(up = true).khz)
        assertEquals(108_000, RadioFrequency(Band.FM, 87_500).step(up = false).khz)
        assertEquals(1_071, RadioFrequency(Band.AM, 1_062).step(up = true).khz)
    }

    @Test
    fun `recognises tuner package names`() {
        assertTrue(RadioFrequency.looksLikeRadioPackage("com.android.fmradio"))
        assertTrue(RadioFrequency.looksLikeRadioPackage("com.vendor.car.radio"))
        assertFalse(RadioFrequency.looksLikeRadioPackage("com.spotify.music"))
    }
}

class RadioMetadataTest {

    @Test
    fun `frequency as title, station as artist`() {
        val r = com.minimal.carlauncher.core.RadioMetadata.extract(
            listOf("FM 88.6", "Radio Nova", "Now playing: Daft Punk")
        )
        assertEquals(RadioFrequency(Band.FM, 88_600), r.frequency)
        assertEquals("Radio Nova", r.stationName)
        assertEquals("Now playing: Daft Punk", r.radioText)
    }

    @Test
    fun `station as title, frequency later, duplicates ignored`() {
        val r = com.minimal.carlauncher.core.RadioMetadata.extract(
            listOf("BBC R2", null, "", "95.2 MHz", "95.2", "BBC R2")
        )
        assertEquals(RadioFrequency(Band.FM, 95_200), r.frequency)
        assertEquals("BBC R2", r.stationName)
        assertNull(r.radioText)
    }

    @Test
    fun `no frequency keeps the text fields`() {
        val r = com.minimal.carlauncher.core.RadioMetadata.extract(listOf("Radio Nova", "Jazz"))
        assertNull(r.frequency)
        assertEquals("Radio Nova", r.stationName)
        assertEquals("Jazz", r.radioText)
    }
}
