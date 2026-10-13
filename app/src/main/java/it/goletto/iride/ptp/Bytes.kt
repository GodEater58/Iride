package it.goletto.iride.ptp

import java.io.ByteArrayOutputStream

/** Costruisce payload little-endian (formato di PTP e PTP/IP). */
class Buf {
    private val o = ByteArrayOutputStream()

    fun u8(v: Int): Buf {
        o.write(v and 0xFF)
        return this
    }

    fun u16(v: Int): Buf {
        u8(v)
        u8(v ushr 8)
        return this
    }

    fun u32(v: Int): Buf {
        u16(v)
        u16(v ushr 16)
        return this
    }

    fun u64(v: Long): Buf {
        u32(v.toInt())
        u32((v ushr 32).toInt())
        return this
    }

    fun raw(b: ByteArray): Buf {
        o.write(b, 0, b.size)
        return this
    }

    /** Stringa UTF-16LE terminata da zero (usata nel pacchetto di init PTP/IP). */
    fun utf16z(s: String): Buf {
        for (ch in s) u16(ch.code)
        u16(0)
        return this
    }

    fun bytes(): ByteArray = o.toByteArray()
}

fun le16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

fun le32(b: ByteArray, o: Int): Int =
    le16(b, o) or (le16(b, o + 2) shl 16)

fun le64(b: ByteArray, o: Int): Long =
    (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)

fun readUtf16z(b: ByteArray, o: Int): String {
    val sb = StringBuilder()
    var i = o
    while (i + 1 < b.size) {
        val c = le16(b, i)
        if (c == 0) break
        sb.append(c.toChar())
        i += 2
    }
    return sb.toString()
}

fun hex(v: Int): String = "0x" + Integer.toHexString(v).uppercase()

/** Lettore sequenziale dei dataset PTP. */
class Reader(private val b: ByteArray, var pos: Int = 0) {
    fun u8(): Int {
        val v = b[pos].toInt() and 0xFF
        pos += 1
        return v
    }

    fun u16(): Int {
        val v = le16(b, pos)
        pos += 2
        return v
    }

    fun u32(): Int {
        val v = le32(b, pos)
        pos += 4
        return v
    }

    /** Stringa PTP: 1 byte con il numero di caratteri (terminatore incluso) + UTF-16LE. */
    fun str(): String {
        if (pos >= b.size) return ""
        val n = u8()
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (pos + 1 >= b.size) break
            val c = u16()
            if (c != 0) sb.append(c.toChar())
        }
        return sb.toString()
    }

    fun u16Array(): IntArray {
        val n = u32()
        return IntArray(n) { u16() }
    }

    fun u32Array(): IntArray {
        val n = u32()
        return IntArray(n) { u32() }
    }
}
