package com.office.core.ole

/**
 * Reads Compound File Binary containers (the "OLE" files behind .doc and .xls): a small FAT file
 * system inside one file. Only reading is supported.
 */
object Cfb {

    private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private const val END_OF_CHAIN = -2
    private const val FREE = -1
    private const val MAX_STREAM = 256 * 1024 * 1024

    fun isCfb(bytes: ByteArray): Boolean = bytes.size >= 512 && (0 until 8).all { bytes[it] == MAGIC[it] }

    /** All streams by name; streams inside storages are named `storage/stream`. */
    fun read(bytes: ByteArray): Map<String, ByteArray> {
        require(isCfb(bytes)) { "Not a compound file" }
        val b = Bin(bytes)
        val sectorShift = b.u16(30)
        require(sectorShift == 9 || sectorShift == 12) { "Bad sector size" }
        val sectorSize = 1 shl sectorShift
        val miniShift = b.u16(32)
        val miniSize = 1 shl miniShift
        val numFatSectors = b.i32(44)
        val firstDirSector = b.i32(48)
        val miniCutoff = b.i32(56)
        val firstMiniFat = b.i32(60)
        val numMiniFat = b.i32(64)
        var firstDifat = b.i32(68)
        val numDifat = b.i32(72)
        val sectorCount = (bytes.size - 512) / sectorSize + 1

        fun offset(sector: Int) = 512 + sector.toLong() * sectorSize

        // DIFAT: the list of FAT sectors.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) b.i32(76 + i * 4).let { if (it >= 0) fatSectors += it }
        var guard = 0
        while (firstDifat >= 0 && guard++ < numDifat.coerceAtLeast(0) + 1 && guard < 100_000) {
            val off = offset(firstDifat)
            if (off + sectorSize > bytes.size) break
            for (i in 0 until sectorSize / 4 - 1) b.i32((off + i * 4).toInt()).let { if (it >= 0) fatSectors += it }
            firstDifat = b.i32((off + sectorSize - 4).toInt())
        }
        if (numFatSectors in 1 until fatSectors.size) fatSectors.subList(numFatSectors, fatSectors.size).clear()

        val fat = IntArray(fatSectors.size * (sectorSize / 4)) { FREE }
        fatSectors.forEachIndexed { i, s ->
            val off = offset(s)
            if (off + sectorSize <= bytes.size) {
                for (j in 0 until sectorSize / 4) fat[i * (sectorSize / 4) + j] = b.i32((off + j * 4).toInt())
            }
        }

        fun chain(start: Int, table: IntArray, limit: Int): List<Int> {
            val out = ArrayList<Int>()
            var s = start
            val seen = HashSet<Int>()
            while (s >= 0 && s < table.size && seen.add(s) && out.size < limit) {
                out += s
                s = table[s]
            }
            if (s >= 0 && s >= table.size && s != END_OF_CHAIN && out.size < limit && seen.add(s)) out += s
            return out
        }

        fun readChain(start: Int, size: Long): ByteArray {
            val sectors = chain(start, fat, sectorCount + 1)
            val total = if (size >= 0) size.coerceAtMost(MAX_STREAM.toLong()).toInt() else sectors.size * sectorSize
            val out = ByteArray(total)
            var pos = 0
            for (s in sectors) {
                if (pos >= total) break
                val off = offset(s)
                val n = minOf(sectorSize, total - pos, (bytes.size - off).toInt().coerceAtLeast(0))
                if (n <= 0) break
                System.arraycopy(bytes, off.toInt(), out, pos, n)
                pos += n
            }
            return if (pos == total) out else out.copyOf(pos)
        }

        // Directory entries (128 bytes each).
        val dir = readChain(firstDirSector, -1)
        val d = Bin(dir)
        class Entry(val name: String, val type: Int, val left: Int, val right: Int, val child: Int, val start: Int, val size: Long)
        val entries = (0 until dir.size / 128).map { i ->
            val o = i * 128
            val nameLen = d.u16(o + 64).coerceIn(0, 64)
            val name = String(dir, o, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            Entry(name, dir[o + 66].toInt(), d.i32(o + 68), d.i32(o + 72), d.i32(o + 76), d.i32(o + 116), d.u32(o + 120))
        }
        val root = entries.firstOrNull { it.type == 5 } ?: return emptyMap()

        val miniStream = readChain(root.start, root.size)
        val miniFatBytes = if (numMiniFat > 0 && firstMiniFat >= 0) readChain(firstMiniFat, numMiniFat.toLong() * sectorSize) else ByteArray(0)
        val miniFat = IntArray(miniFatBytes.size / 4) { Bin(miniFatBytes).i32(it * 4) }

        fun readMini(start: Int, size: Long): ByteArray {
            val sectors = chain(start, miniFat, miniFat.size + 1)
            val total = size.coerceAtMost(MAX_STREAM.toLong()).toInt()
            val out = ByteArray(total)
            var pos = 0
            for (s in sectors) {
                if (pos >= total) break
                val off = s * miniSize
                val n = minOf(miniSize, total - pos, (miniStream.size - off).coerceAtLeast(0))
                if (n <= 0) break
                System.arraycopy(miniStream, off, out, pos, n)
                pos += n
            }
            return if (pos == total) out else out.copyOf(pos)
        }

        val result = LinkedHashMap<String, ByteArray>()
        val visited = HashSet<Int>()
        fun walk(index: Int, prefix: String) {
            if (index < 0 || index >= entries.size || !visited.add(index)) return
            val e = entries[index]
            walk(e.left, prefix)
            when (e.type) {
                2 -> result[prefix + e.name] = if (e.size < miniCutoff) readMini(e.start, e.size) else readChain(e.start, e.size)
                1 -> walk(e.child, prefix + e.name + "/")
            }
            walk(e.right, prefix)
        }
        walk(root.child, "")
        return result
    }
}

/** Little-endian reads with bounds that return 0 past the end instead of throwing. */
internal class Bin(val b: ByteArray) {
    fun u8(o: Int): Int = if (o in b.indices) b[o].toInt() and 0xFF else 0
    fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
    fun i16(o: Int): Int = u16(o).toShort().toInt()
    fun i32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
    fun u32(o: Int): Long = i32(o).toLong() and 0xFFFFFFFFL
    fun f64(o: Int): Double = java.lang.Double.longBitsToDouble((u32(o) or (u32(o + 4) shl 32)))
}
