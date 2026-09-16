package com.minimal.carlauncher

import com.minimal.carlauncher.core.DockCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DockCodecTest {

    @Test
    fun `round-trips a fixed-length dock`() {
        val slots = listOf("com.a/.Main", "", "com.b/.Main", "")
        assertEquals(slots, DockCodec.decode(DockCodec.encode(slots)))
    }

    @Test
    fun `decode never throws on malformed input`() {
        val empty = DockCodec.empty()
        assertEquals(empty, DockCodec.decode(null))
        assertEquals(empty, DockCodec.decode(""))
        assertEquals(empty, DockCodec.decode("   "))
        assertEquals(empty, DockCodec.decode("not json at all"))
        assertEquals(empty, DockCodec.decode("{\"unexpected\":\"object\"}"))
    }

    @Test
    fun `decode pads a short array and truncates a long one`() {
        assertEquals(
            listOf("com.a/.Main", "", "", ""),
            DockCodec.decode("[\"com.a/.Main\"]")
        )
        assertEquals(
            listOf("a", "b", "c", "d"),
            DockCodec.decode("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]")
        )
    }

    @Test
    fun `decode honours a custom slot count`() {
        assertEquals(listOf("a", ""), DockCodec.decode("[\"a\"]", size = 2))
        assertEquals(2, DockCodec.empty(2).size)
    }

    @Test
    fun `removePackage blanks matching slots and keeps positions`() {
        val slots = listOf("com.a/.Main", "com.b/.Main", "", "com.a/.Other")
        assertEquals(
            listOf("", "com.b/.Main", "", ""),
            DockCodec.removePackage(slots, "com.a")
        )
    }

    @Test
    fun `removePackage returns null when nothing matched`() {
        val slots = listOf("com.a/.Main", "", "", "")
        assertNull(DockCodec.removePackage(slots, "com.zzz"))
    }

    @Test
    fun `removePackage does not match on a package name prefix`() {
        val slots = listOf("com.abc/.Main", "", "", "")
        assertNull(DockCodec.removePackage(slots, "com.ab"))
    }
}
