package com.minimal.carlauncher.core

/**
 * Reads the string table of a .dex file - every class name, action, extra key and literal
 * the code uses. Used by the radio-app inspector to find a vendor tuner's private broadcast
 * actions and keys. Pure and defensive: malformed input yields what could be read.
 */
object DexStrings {

    private const val STRING_IDS_SIZE_OFF = 0x38
    private const val STRING_IDS_OFF_OFF = 0x3C

    fun isDex(bytes: ByteArray): Boolean =
        bytes.size > 0x70 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

    fun read(bytes: ByteArray): List<String> {
        if (!isDex(bytes)) return emptyList()
        val out = ArrayList<String>()
        try {
            val count = u32(bytes, STRING_IDS_SIZE_OFF)
            val idsOff = u32(bytes, STRING_IDS_OFF_OFF)
            if (count < 0 || idsOff < 0) return out
            for (i in 0 until count) {
                val idPos = idsOff + i * 4
                if (idPos + 4 > bytes.size) break
                val dataOff = u32(bytes, idPos)
                if (dataOff < 0 || dataOff >= bytes.size) continue
                // string_data_item: ULEB128 utf16 length, then MUTF-8 bytes, NUL-terminated.
                var p = dataOff
                while (p < bytes.size && (bytes[p].toInt() and 0x80) != 0) p++
                p++
                decodeMutf8(bytes, p)?.let { out += it }
            }
        } catch (e: Exception) {
            // Keep whatever was read.
        }
        return out
    }

    /** Printable-ASCII runs of at least [minLen] chars - the classic `strings`, for vdex/odex. */
    fun rawStrings(bytes: ByteArray, minLen: Int = 6): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                sb.append(c.toChar())
            } else {
                if (sb.length >= minLen) out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.length >= minLen) out += sb.toString()
        return out
    }

    private fun decodeMutf8(bytes: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var p = start
        while (p < bytes.size) {
            val a = bytes[p].toInt() and 0xFF
            if (a == 0) return sb.toString()
            when {
                a < 0x80 -> {
                    sb.append(a.toChar()); p += 1
                }
                (a and 0xE0) == 0xC0 && p + 1 < bytes.size -> {
                    val b = bytes[p + 1].toInt() and 0x3F
                    sb.append((((a and 0x1F) shl 6) or b).toChar()); p += 2
                }
                (a and 0xF0) == 0xE0 && p + 2 < bytes.size -> {
                    val b = bytes[p + 1].toInt() and 0x3F
                    val c = bytes[p + 2].toInt() and 0x3F
                    sb.append((((a and 0x0F) shl 12) or (b shl 6) or c).toChar()); p += 3
                }
                else -> return null
            }
        }
        return null
    }

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
