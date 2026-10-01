package com.minimal.carlauncher

import com.minimal.carlauncher.core.BinaryXml
import com.minimal.carlauncher.core.DexStrings
import com.minimal.carlauncher.core.XmlElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InspectorParsingTest {

    // ----------------------------------------------------------------- dex

    private fun dexWith(strings: List<ByteArray>): ByteArray {
        val headerSize = 0x70
        val idsSize = strings.size * 4
        val data = strings.map { byteArrayOf(it.size.toByte()) + it + byteArrayOf(0) }
        val total = headerSize + idsSize + data.sumOf { it.size }
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("dex\n035\u0000".toByteArray(Charsets.ISO_8859_1))
        buf.putInt(0x38, strings.size)
        buf.putInt(0x3C, headerSize)
        var dataOff = headerSize + idsSize
        data.forEachIndexed { i, d ->
            buf.putInt(headerSize + i * 4, dataOff)
            buf.position(dataOff)
            buf.put(d)
            dataOff += d.size
        }
        return buf.array()
    }

    @Test
    fun `reads dex strings including multi-byte MUTF-8`() {
        val dex = dexWith(
            listOf(
                "com.nwd.radio.FREQ".toByteArray(),
                byteArrayOf(0xC3.toByte(), 0xA9.toByte()),                    // é
                byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte())      // €
            )
        )
        assertTrue(DexStrings.isDex(dex))
        assertEquals(listOf("com.nwd.radio.FREQ", "é", "€"), DexStrings.read(dex))
    }

    @Test
    fun `dex reader survives garbage`() {
        assertTrue(DexStrings.read(ByteArray(10)).isEmpty())
        val truncated = dexWith(listOf("abcdef".toByteArray())).copyOf(0x74)
        DexStrings.read(truncated)   // must not throw
    }

    @Test
    fun `raw strings finds printable runs`() {
        val bytes = byteArrayOf(0, 1) + "com.nwd.ACTION_FREQ".toByteArray() + byteArrayOf(0) +
            "abc".toByteArray() + byteArrayOf(-1) + "RADIO_BAND".toByteArray()
        assertEquals(listOf("com.nwd.ACTION_FREQ", "RADIO_BAND"), DexStrings.rawStrings(bytes, 6))
    }

    // ----------------------------------------------------------- binary xml

    private class Le {
        val buf: ByteBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(v: Int) = apply { buf.putShort(v.toShort()) }
        fun u32(v: Int) = apply { buf.putInt(v) }
        fun bytes(): ByteArray = buf.array().copyOf(buf.position())
    }

    private fun stringPool(strings: List<String>): ByteArray {
        val data = Le()
        val offsets = strings.map { s ->
            val off = data.buf.position()
            data.u16(s.length)
            s.forEach { data.u16(it.code) }
            data.u16(0)
            off
        }
        while (data.buf.position() % 4 != 0) data.u16(0)
        val body = data.bytes()
        val header = 28
        val start = header + strings.size * 4
        val c = Le().u16(0x0001).u16(header).u32(start + body.size)
            .u32(strings.size).u32(0).u32(0).u32(start).u32(0)
        offsets.forEach { c.u32(it) }
        return c.bytes() + body
    }

    private fun startElement(name: Int, attrs: List<IntArray>): ByteArray {
        // attrs: [nameIdx, rawValue, dataType, data]
        val c = Le().u16(0x0102).u16(16).u32(16 + 20 + 20 * attrs.size)
            .u32(1).u32(-1)                                  // line, comment
            .u32(-1).u32(name).u16(20).u16(20).u16(attrs.size).u16(0).u16(0).u16(0)
        attrs.forEach { a -> c.u32(-1).u32(a[0]).u32(a[1]).u16(8).apply { buf.put(0.toByte()) }.apply { buf.put(a[2].toByte()) }.u32(a[3]) }
        return c.bytes()
    }

    private fun endElement(name: Int) =
        Le().u16(0x0103).u16(16).u32(24).u32(1).u32(-1).u32(-1).u32(name).bytes()

    private fun manifest(): ByteArray {
        // 0 name, 1 exported, 2 receiver, 3 action, 4 com.x.R, 5 com.x.ACTION
        val pool = stringPool(listOf("name", "exported", "receiver", "action", "com.x.R", "com.x.ACTION"))
        val resMap = Le().u16(0x0180).u16(8).u32(16).u32(0x01010003).u32(0x01010010).bytes()
        val body = pool + resMap +
            startElement(2, listOf(intArrayOf(0, 4, 0x03, 4), intArrayOf(1, -1, 0x12, -1))) +
            startElement(3, listOf(intArrayOf(0, 5, 0x03, 5))) +
            endElement(3) + endElement(2)
        return Le().u16(0x0003).u16(8).u32(8 + body.size).bytes() + body
    }

    @Test
    fun `parses components, attributes by resource id, and nesting`() {
        assertEquals(
            listOf(
                XmlElement(0, "receiver", mapOf("name" to "com.x.R", "exported" to "true")),
                XmlElement(1, "action", mapOf("name" to "com.x.ACTION"))
            ),
            BinaryXml.parse(manifest())
        )
    }

    @Test
    fun `binary xml survives truncation and garbage`() {
        val full = manifest()
        // Cut off the closing tags: the elements read so far must still come back.
        val partial = BinaryXml.parse(full.copyOf(full.size - 30))
        assertEquals("receiver", partial.first().tag)
        assertTrue(BinaryXml.parse(ByteArray(3)).isEmpty())
        assertTrue(BinaryXml.parse("<manifest/>".toByteArray()).isEmpty())
    }
}
