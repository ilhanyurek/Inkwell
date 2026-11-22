package com.office.core.image

import java.util.zip.Inflater

/**
 * Decodes baseline TIFF pictures, which Android cannot show by itself. Word documents often carry logos as
 * TIFF. Supports uncompressed, LZW, PackBits and Deflate data in strips or tiles, the horizontal predictor,
 * greyscale, RGB, palette and CMYK images with 1 to 16 bits per sample, and alpha.
 */
object Tiff {

    class Image(val width: Int, val height: Int, val argb: IntArray)

    fun isTiff(b: ByteArray): Boolean = b.size >= 8 &&
        ((b[0] == 0x49.toByte() && b[1] == 0x49.toByte() && b[2] == 42.toByte() && b[3] == 0.toByte()) ||
            (b[0] == 0x4D.toByte() && b[1] == 0x4D.toByte() && b[2] == 0.toByte() && b[3] == 42.toByte()))

    /** The first image of [bytes], or null when it is not a TIFF this decoder understands. */
    fun decode(bytes: ByteArray, maxPixels: Int = 16_000_000): Image? =
        if (!isTiff(bytes)) null else runCatching { read(bytes, maxPixels) }.getOrNull()

    private class Reader(val b: ByteArray, val le: Boolean) {
        fun u8(o: Int): Int = if (o in b.indices) b[o].toInt() and 0xFF else 0
        fun u16(o: Int): Int = if (le) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1)
        fun u32(o: Int): Long = if (le) {
            (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
        } else {
            (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        }
    }

    private fun read(bytes: ByteArray, maxPixels: Int): Image? {
        val r = Reader(bytes, bytes[0] == 0x49.toByte())
        val ifd = r.u32(4).toInt()
        if (ifd <= 0 || ifd >= bytes.size) return null
        val tags = HashMap<Int, LongArray>()
        val count = r.u16(ifd)
        for (i in 0 until count) {
            val e = ifd + 2 + i * 12
            val tag = r.u16(e)
            val type = r.u16(e + 2)
            val n = r.u32(e + 4).coerceAtMost(1_000_000L).toInt()
            val size = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9 -> 4; else -> continue }
            val at = if (n * size <= 4) e + 8 else r.u32(e + 8).toInt()
            tags[tag] = LongArray(n) { k ->
                when (size) {
                    1 -> r.u8(at + k).toLong()
                    2 -> r.u16(at + k * 2).toLong()
                    else -> r.u32(at + k * 4)
                }
            }
        }
        fun one(tag: Int, default: Long): Long = tags[tag]?.firstOrNull() ?: default

        val width = one(256, 0).toInt()
        val height = one(257, 0).toInt()
        if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels) return null
        val spp = one(277, 1).toInt().coerceIn(1, 16)
        val bits = one(258, 1).toInt()
        if (bits !in setOf(1, 2, 4, 8, 16)) return null
        if (tags[258]?.any { it.toInt() != bits } == true) return null
        if (tags[339]?.any { it != 1L } == true) return null // only unsigned integer samples
        val compression = one(259, 1).toInt()
        val photometric = one(262, if (spp >= 3) 2 else 1).toInt()
        val planar = one(284, 1).toInt()
        if (planar != 1 && spp > 1) return null
        val predictor = one(317, 1).toInt()
        if (predictor != 1 && predictor != 2) return null
        val reverseBits = one(266, 1) == 2L
        val colorChannels = when (photometric) { 0, 1, 3 -> 1; 2 -> 3; 5 -> 4; else -> return null }
        if (spp < colorChannels) return null
        val extra = tags[338]?.firstOrNull()?.toInt()
        val alphaKind = when {
            spp <= colorChannels -> 0
            extra == 1 || extra == 2 -> extra
            extra == null && spp == colorChannels + 1 && photometric != 3 -> 2
            else -> 0
        }
        val colorMap = tags[320]
        if (photometric == 3 && (colorMap == null || colorMap.size < 3 * (1 shl bits))) return null

        // Strips are tiles as wide as the image.
        val tiled = tags.containsKey(322) && tags.containsKey(324)
        val chunkW = if (tiled) one(322, 0).toInt() else width
        val chunkH = if (tiled) one(323, 0).toInt() else one(278, height.toLong()).coerceIn(1, height.toLong()).toInt()
        if (chunkW <= 0 || chunkH <= 0) return null
        val offsets = (if (tiled) tags[324] else tags[273]) ?: return null
        val counts = (if (tiled) tags[325] else tags[279]) ?: LongArray(offsets.size) { k ->
            ((if (k + 1 < offsets.size) offsets[k + 1] else bytes.size.toLong()) - offsets[k]).coerceAtLeast(0)
        }
        val across = if (tiled) (width + chunkW - 1) / chunkW else 1
        val rowBytes = (chunkW.toLong() * spp * bits + 7).div(8).toInt()

        val out = IntArray(width * height)
        val samples = IntArray(spp)
        val max = (1 shl bits) - 1
        for (k in offsets.indices) {
            val x0 = (k % across) * chunkW
            val y0 = (k / across) * chunkH
            if (y0 >= height) break
            val rows = if (tiled) chunkH else minOf(chunkH, height - y0)
            val start = offsets[k].toInt()
            val len = counts.getOrElse(k) { 0 }.toInt().coerceAtMost(bytes.size - start)
            if (start < 0 || len <= 0) continue
            var src = bytes.copyOfRange(start, start + len)
            if (reverseBits) src = ByteArray(src.size) { i -> reverse(src[i]) }
            val expected = rowBytes * rows
            val data = when (compression) {
                1 -> src.copyOf(expected)
                5 -> lzw(src, expected)
                32773 -> packBits(src, expected)
                8, 32946 -> inflate(src, expected)
                else -> return null
            }
            if (predictor == 2) unpredict(data, rows, rowBytes, chunkW, spp, bits, r.le)

            for (ry in 0 until rows) {
                val y = y0 + ry
                if (y >= height) break
                val rowOff = ry * rowBytes
                for (rx in 0 until chunkW) {
                    val x = x0 + rx
                    if (x >= width) break
                    for (s in 0 until spp) {
                        val idx = rx * spp + s
                        samples[s] = when (bits) {
                            8 -> data[rowOff + idx].toInt() and 0xFF
                            16 -> {
                                val o = rowOff + idx * 2
                                val a = data[o].toInt() and 0xFF
                                val b = data[o + 1].toInt() and 0xFF
                                if (r.le) a or (b shl 8) else (a shl 8) or b
                            }
                            else -> {
                                val bit = idx * bits
                                val byte = data[rowOff + bit / 8].toInt() and 0xFF
                                (byte shr (8 - bits - bit % 8)) and max
                            }
                        }
                    }
                    fun scaled(v: Int): Int = when (bits) { 8 -> v; 16 -> v shr 8; else -> v * 255 / max }
                    var red: Int
                    var green: Int
                    var blue: Int
                    when (photometric) {
                        0 -> { red = 255 - scaled(samples[0]); green = red; blue = red }
                        1 -> { red = scaled(samples[0]); green = red; blue = red }
                        3 -> {
                            val n = 1 shl bits
                            val i = samples[0].coerceIn(0, n - 1)
                            red = (colorMap!![i] shr 8).toInt()
                            green = (colorMap[n + i] shr 8).toInt()
                            blue = (colorMap[2 * n + i] shr 8).toInt()
                        }
                        2 -> { red = scaled(samples[0]); green = scaled(samples[1]); blue = scaled(samples[2]) }
                        else -> {
                            val c = scaled(samples[0]); val m = scaled(samples[1]); val yy = scaled(samples[2]); val kk = scaled(samples[3])
                            red = (255 - c) * (255 - kk) / 255
                            green = (255 - m) * (255 - kk) / 255
                            blue = (255 - yy) * (255 - kk) / 255
                        }
                    }
                    var alpha = 255
                    if (alphaKind != 0) {
                        alpha = scaled(samples[colorChannels])
                        if (alphaKind == 1 && alpha in 1..254) {
                            // Associated alpha: colours are stored premultiplied.
                            red = minOf(255, red * 255 / alpha)
                            green = minOf(255, green * 255 / alpha)
                            blue = minOf(255, blue * 255 / alpha)
                        }
                    }
                    out[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        return Image(width, height, out)
    }

    private fun reverse(b: Byte): Byte = (Integer.reverse(b.toInt() and 0xFF) ushr 24).toByte()

    /** Undoes horizontal differencing: each sample was stored as the difference to the one on its left. */
    private fun unpredict(data: ByteArray, rows: Int, rowBytes: Int, w: Int, spp: Int, bits: Int, le: Boolean) {
        for (ry in 0 until rows) {
            val o = ry * rowBytes
            when (bits) {
                8 -> for (i in spp until w * spp) {
                    val at = o + i
                    data[at] = (data[at] + data[at - spp]).toByte()
                }
                16 -> for (i in spp until w * spp) {
                    val at = o + i * 2
                    val prev = o + (i - spp) * 2
                    fun get(p: Int): Int {
                        val a = data[p].toInt() and 0xFF
                        val b = data[p + 1].toInt() and 0xFF
                        return if (le) a or (b shl 8) else (a shl 8) or b
                    }
                    val v = (get(at) + get(prev)) and 0xFFFF
                    if (le) { data[at] = v.toByte(); data[at + 1] = (v shr 8).toByte() } else { data[at] = (v shr 8).toByte(); data[at + 1] = v.toByte() }
                }
            }
        }
    }

    /** TIFF flavour of LZW: codes read most significant bit first, widths growing one code early. */
    internal fun lzw(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var outPos = 0
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val first = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until 256) { prefix[i] = -1; suffix[i] = i.toByte(); first[i] = i.toByte(); length[i] = 1 }
        var next = 258
        var codeLen = 9
        var acc = 0L
        var accBits = 0
        var pos = 0
        fun readCode(): Int {
            while (accBits < codeLen) {
                if (pos >= src.size) return 257
                acc = (acc shl 8) or (src[pos++].toLong() and 0xFF)
                accBits += 8
            }
            val v = ((acc shr (accBits - codeLen)) and ((1L shl codeLen) - 1)).toInt()
            accBits -= codeLen
            acc = acc and ((1L shl accBits) - 1)
            return v
        }
        fun emit(code: Int) {
            val l = length[code]
            var c = code
            var i = outPos + l - 1
            while (i >= outPos) {
                if (i < expected) out[i] = suffix[c]
                c = prefix[c]
                i--
            }
            outPos += l
        }
        fun add(old: Int, ch: Byte) {
            if (next >= 4096) return
            prefix[next] = old
            suffix[next] = ch
            first[next] = first[old]
            length[next] = length[old] + 1
            next++
            if (next >= (1 shl codeLen) - 1 && codeLen < 12) codeLen++
        }
        var old = -1
        while (outPos < expected) {
            val code = readCode()
            if (code == 257) break
            if (code == 256) {
                next = 258
                codeLen = 9
                old = -1
                continue
            }
            if (old < 0) {
                if (code > 255) break
                emit(code)
            } else if (code < next) {
                emit(code)
                add(old, first[code])
            } else if (code == next) {
                add(old, first[old])
                emit(code)
            } else {
                break
            }
            old = code
        }
        return out
    }

    private fun packBits(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var o = 0
        var i = 0
        while (i < src.size && o < expected) {
            val n = src[i++].toInt()
            when {
                n >= 0 -> {
                    val c = minOf(n + 1, src.size - i, expected - o)
                    System.arraycopy(src, i, out, o, c)
                    i += n + 1
                    o += c
                }
                n != -128 -> {
                    if (i >= src.size) break
                    val v = src[i++]
                    val c = minOf(1 - n, expected - o)
                    java.util.Arrays.fill(out, o, o + c, v)
                    o += c
                }
            }
        }
        return out
    }

    private fun inflate(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        val inf = Inflater()
        try {
            inf.setInput(src)
            var o = 0
            while (o < expected && !inf.finished()) {
                val n = inf.inflate(out, o, expected - o)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                o += n
            }
        } finally {
            inf.end()
        }
        return out
    }
}
