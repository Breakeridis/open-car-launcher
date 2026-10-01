package com.minimal.carlauncher.core

/** One start tag from a compiled Android XML file, with its nesting depth (root = 0). */
data class XmlElement(val depth: Int, val tag: String, val attrs: Map<String, String>)

/**
 * Minimal reader for Android's compiled binary XML (the AndroidManifest.xml inside an APK).
 *
 * Only what the radio-app inspector needs: element names, nesting and attribute values. The
 * framework attributes it cares about are recognised by resource id, because release builds
 * often strip the attribute *names* from the string pool. Pure and defensive: malformed input
 * returns the elements parsed so far.
 */
object BinaryXml {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val UTF8_FLAG = 0x100
    private const val NO_INDEX = -1

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_BOOLEAN = 0x12

    /** android:* attributes by resource id. */
    private val KNOWN_ATTRS = mapOf(
        0x01010003 to "name",
        0x01010006 to "permission",
        0x01010010 to "exported",
        0x01010018 to "authorities",
        0x0101000e to "enabled",
        0x0101020c to "minSdkVersion"
    )

    fun parse(bytes: ByteArray): List<XmlElement> {
        val out = ArrayList<XmlElement>()
        if (bytes.size < 8 || u16(bytes, 0) != RES_XML_TYPE) return out
        var strings: List<String> = emptyList()
        var resMap = IntArray(0)
        var depth = 0
        try {
            var pos = u16(bytes, 2)   // skip the file header
            while (pos + 8 <= bytes.size) {
                val type = u16(bytes, pos)
                val headerSize = u16(bytes, pos + 2)
                val size = u32(bytes, pos + 4)
                if (size < 8 || pos + size > bytes.size) break
                when (type) {
                    RES_STRING_POOL_TYPE -> strings = readStringPool(bytes, pos)
                    RES_XML_RESOURCE_MAP_TYPE -> {
                        val n = (size - headerSize) / 4
                        resMap = IntArray(n) { u32(bytes, pos + headerSize + it * 4) }
                    }
                    RES_XML_START_ELEMENT_TYPE -> {
                        val ext = pos + headerSize
                        val name = strings.getOrNull(u32(bytes, ext + 4)) ?: "?"
                        val attrStart = u16(bytes, ext + 8)
                        val attrSize = u16(bytes, ext + 10)
                        val attrCount = u16(bytes, ext + 12)
                        val attrs = LinkedHashMap<String, String>()
                        for (i in 0 until attrCount) {
                            val a = ext + attrStart + i * attrSize
                            if (a + 20 > pos + size) break
                            val nameIdx = u32(bytes, a + 4)
                            val raw = u32(bytes, a + 8)
                            val dataType = bytes[a + 15].toInt() and 0xFF
                            val data = u32(bytes, a + 16)
                            val resId = if (nameIdx in resMap.indices) resMap[nameIdx] else 0
                            val attrName = KNOWN_ATTRS[resId]
                                ?: strings.getOrNull(nameIdx)?.ifBlank { null }
                                ?: String.format("0x%08x", resId)
                            attrs[attrName] = value(strings, raw, dataType, data)
                        }
                        out += XmlElement(depth, name, attrs)
                        depth++
                    }
                    RES_XML_END_ELEMENT_TYPE -> depth = (depth - 1).coerceAtLeast(0)
                }
                pos += size
            }
        } catch (e: Exception) {
            // Keep whatever was parsed.
        }
        return out
    }

    private fun value(strings: List<String>, raw: Int, dataType: Int, data: Int): String {
        if (raw != NO_INDEX) strings.getOrNull(raw)?.let { return it }
        return when (dataType) {
            TYPE_STRING -> strings.getOrNull(data) ?: ""
            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
            TYPE_INT_DEC -> data.toString()
            TYPE_REFERENCE -> String.format("@0x%08x", data)
            else -> String.format("0x%x", data)
        }
    }

    private fun readStringPool(b: ByteArray, chunk: Int): List<String> {
        val count = u32(b, chunk + 8)
        val flags = u32(b, chunk + 16)
        val stringsStart = u32(b, chunk + 20)
        val utf8 = (flags and UTF8_FLAG) != 0
        val headerSize = u16(b, chunk + 2)
        return List(count) { i ->
            try {
                val off = chunk + stringsStart + u32(b, chunk + headerSize + i * 4)
                if (utf8) readUtf8(b, off) else readUtf16(b, off)
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun readUtf16(b: ByteArray, off: Int): String {
        var p = off
        var len = u16(b, p); p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(b, p); p += 2
        }
        val chars = CharArray(len) { u16(b, p + it * 2).toChar() }
        return String(chars)
    }

    private fun readUtf8(b: ByteArray, off: Int): String {
        var p = off
        // UTF-16 length (skipped), then UTF-8 byte length; each 1 or 2 bytes.
        p += if (b[p].toInt() and 0x80 != 0) 2 else 1
        var len = b[p].toInt() and 0xFF
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2
        } else {
            p += 1
        }
        return String(b, p, len, Charsets.UTF_8)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
