package com.minimal.carlauncher

import com.minimal.carlauncher.core.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun `strips a leading v`() {
        assertTrue(SemVer.isNewer("v1.0.1", "1.0.0"))
        assertEquals(0, SemVer.compare("v1.2.3", "1.2.3"))
        assertEquals(0, SemVer.compare("V1.2.3", "v1.2.3"))
    }

    @Test
    fun `compares numerically, not lexically`() {
        assertTrue(SemVer.isNewer("1.10.0", "1.9.9"))
        assertTrue(SemVer.isNewer("2.0.0", "1.99.99"))
        assertFalse(SemVer.isNewer("1.9.9", "1.10.0"))
    }

    @Test
    fun `pads differing segment counts`() {
        assertEquals(0, SemVer.compare("1.2", "1.2.0"))
        assertEquals(0, SemVer.compare("1", "1.0.0.0"))
        assertTrue(SemVer.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun `a release outranks its own pre-release`() {
        assertTrue(SemVer.isNewer("1.0.0", "1.0.0-rc2"))
        assertFalse(SemVer.isNewer("1.0.0-rc2", "1.0.0"))
        assertTrue(SemVer.isNewer("1.0.0-rc2", "1.0.0-rc1"))
    }

    @Test
    fun `ignores build metadata`() {
        assertEquals(0, SemVer.compare("1.2.3+build7", "1.2.3"))
        assertTrue(SemVer.isNewer("1.2.4+b1", "1.2.3+b9"))
    }

    @Test
    fun `garbage degrades to zero instead of throwing`() {
        assertEquals(0, SemVer.compare("", ""))
        assertTrue(SemVer.isNewer("1.0.0", "garbage"))
        assertFalse(SemVer.isNewer("garbage", "1.0.0"))
        assertEquals(0, SemVer.compare("...", "0.0.0"))
    }

    @Test
    fun `tolerates non-numeric segment suffixes`() {
        assertEquals(0, SemVer.compare("1.2.3abc", "1.2.3"))
    }
}
