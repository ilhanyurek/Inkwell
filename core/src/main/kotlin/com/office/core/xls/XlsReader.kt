package com.office.core.xls

import com.office.core.doc.EncryptedFileException
import com.office.core.formula.FormulaEngine
import com.office.core.ole.Bin
import com.office.core.ole.Cfb
import com.office.core.sheet.CellRef
import com.office.core.sheet.CellStyle
import com.office.core.sheet.Sheet
import com.office.core.sheet.Value
import com.office.core.sheet.Workbook

/**
 * Reads Excel 97-2003 (.xls, BIFF8) workbooks: sheets, cell values, shared strings, number formats,
 * bold/italic fonts and column widths. Formulas are kept as their last calculated values.
 */
object XlsReader {

    private class Rec(val type: Int, val data: ByteArray, val offset: Int)

    fun read(bytes: ByteArray): Workbook {
        val streams = Cfb.read(bytes)
        val book = streams["Workbook"] ?: streams["Book"] ?: throw IllegalArgumentException("Not an Excel workbook")
        val records = records(book)

        // Globals: shared strings, fonts, formats, cell formats, sheet list.
        val sst = ArrayList<String>()
        val fonts = ArrayList<Pair<Boolean, Boolean>>() // bold, italic
        val formats = HashMap<Int, String>()
        val xfs = ArrayList<Pair<Int, Int>>() // font index, format id
        val sheetOffsets = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < records.size) {
            val r = records[i]
            when (r.type) {
                0x002F -> throw EncryptedFileException()
                0x00FC -> {
                    val parts = ArrayList<ByteArray>()
                    parts += r.data
                    var j = i + 1
                    while (j < records.size && records[j].type == 0x003C) { parts += records[j].data; j++ }
                    readSst(parts, sst)
                    i = j
                    continue
                }
                0x0031 -> {
                    val b = Bin(r.data)
                    fonts += ((b.u16(6) >= 700) to (b.u16(2) and 0x02 != 0))
                }
                0x041E -> {
                    val b = Bin(r.data)
                    formats[b.u16(0)] = Str(r.data, 2).readLong()
                }
                0x00E0 -> {
                    val b = Bin(r.data)
                    xfs += (b.u16(0) to b.u16(2))
                }
                0x0085 -> {
                    val b = Bin(r.data)
                    val hidden = b.u8(4) and 0x03
                    val type = b.u8(5)
                    if (type == 0 && hidden != 2) sheetOffsets += Str(r.data, 6).readShort() to b.i32(0)
                }
            }
            if (r.type == 0x000A && sheetOffsets.isNotEmpty()) break
            i++
        }

        fun styleOf(xf: Int): CellStyle? {
            val (font, fmt) = xfs.getOrNull(xf) ?: return null
            // Font index 4 does not exist in BIFF; indexes above it are shifted by one.
            val f = fonts.getOrNull(if (font >= 4) font - 1 else font)
            val code = formats[fmt] ?: BUILTIN[fmt]
            val style = CellStyle(bold = f?.first == true, italic = f?.second == true, numFmt = code)
            return style.takeIf { !it.isDefault }
        }

        val sheets = sheetOffsets.map { (name, offset) ->
            val sheet = Sheet(name)
            val start = records.indexOfFirst { it.offset == offset }
            if (start >= 0) readSheet(records, start, sheet, sst, ::styleOf)
            sheet
        }.toMutableList()
        if (sheets.isEmpty()) sheets += Sheet("Sheet1")
        return Workbook(sheets)
    }

    private val BUILTIN = mapOf(
        1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%",
        14 to "yyyy-mm-dd", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 22 to "yyyy-mm-dd hh:mm",
    )

    private fun records(book: ByteArray): List<Rec> {
        val b = Bin(book)
        val out = ArrayList<Rec>()
        var pos = 0
        while (pos + 4 <= book.size) {
            val type = b.u16(pos)
            val len = b.u16(pos + 2)
            val end = (pos + 4 + len).coerceAtMost(book.size)
            out += Rec(type, book.copyOfRange(pos + 4, end), pos)
            pos = end
        }
        return out
    }

    /** A BIFF8 unicode string reader over one record's data. */
    private class Str(val d: ByteArray, var pos: Int) {
        val b = Bin(d)
        fun readShort(): String { val n = b.u8(pos); pos += 1; return body(n) }
        fun readLong(): String { val n = b.u16(pos); pos += 2; return body(n) }
        private fun body(n: Int): String {
            val flags = b.u8(pos); pos += 1
            var runs = 0
            var ext = 0
            if (flags and 0x08 != 0) { runs = b.u16(pos); pos += 2 }
            if (flags and 0x04 != 0) { ext = b.i32(pos); pos += 4 }
            val wide = flags and 0x01 != 0
            val sb = StringBuilder()
            repeat(n) {
                if (wide) { sb.append(b.u16(pos).toChar()); pos += 2 } else { sb.append(b.u8(pos).toChar()); pos += 1 }
            }
            pos += runs * 4 + ext
            return sb.toString()
        }
    }

    /** Shared strings may be split across CONTINUE records, which restart the high-byte flag. */
    private fun readSst(parts: List<ByteArray>, out: MutableList<String>) {
        var part = 0
        var data = parts[0]
        var b = Bin(data)
        var pos = 8
        val count = b.i32(4)
        fun next(): Boolean {
            part++
            if (part >= parts.size) return false
            data = parts[part]; b = Bin(data); pos = 0
            return true
        }
        repeat(count.coerceIn(0, 5_000_000)) {
            if (pos >= data.size && !next()) return
            val n = b.u16(pos); pos += 2
            if (pos >= data.size && !next()) return
            var flags = b.u8(pos); pos += 1
            var runs = 0
            var ext = 0
            if (flags and 0x08 != 0) { runs = b.u16(pos); pos += 2 }
            if (flags and 0x04 != 0) { ext = b.i32(pos); pos += 4 }
            val sb = StringBuilder()
            while (sb.length < n) {
                if (pos >= data.size) {
                    if (!next()) break
                    flags = b.u8(pos); pos += 1
                }
                val wide = flags and 0x01 != 0
                if (wide) { sb.append(b.u16(pos).toChar()); pos += 2 } else { sb.append(b.u8(pos).toChar()); pos += 1 }
            }
            var skip = runs * 4 + ext
            while (skip > 0) {
                val here = minOf(skip, data.size - pos)
                pos += here
                skip -= here
                if (skip > 0 && !next()) break
            }
            out += sb.toString()
        }
    }

    private fun rk(v: Int): Double {
        val raw = if (v and 0x02 != 0) (v shr 2).toDouble()
        else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 0x01 != 0) raw / 100 else raw
    }

    private fun quote(s: String): String =
        if (s.startsWith("=") || s.startsWith("'") || FormulaEngine.parseNumber(s) != null) "'$s" else s

    private fun readSheet(records: List<Rec>, start: Int, sheet: Sheet, sst: List<String>, styleOf: (Int) -> CellStyle?) {
        fun put(row: Int, col: Int, xf: Int, value: String?) {
            if (row !in 0..1_048_575 || col !in 0..16_383) return
            val ref = CellRef(row, col)
            styleOf(xf)?.let { sheet.styles[ref] = it }
            if (!value.isNullOrEmpty()) sheet.cells[ref] = value
        }
        var i = start + 1
        var pendingString: CellRef? = null
        while (i < records.size) {
            val r = records[i]
            val b = Bin(r.data)
            when (r.type) {
                0x000A -> return
                0x00FD -> put(b.u16(0), b.u16(2), b.u16(4), sst.getOrNull(b.i32(6))?.let(::quote))
                0x0204 -> put(b.u16(0), b.u16(2), b.u16(4), quote(Str(r.data, 6).readLong()))
                0x0203 -> put(b.u16(0), b.u16(2), b.u16(4), Value.formatNumber(b.f64(6)))
                0x027E -> put(b.u16(0), b.u16(2), b.u16(4), Value.formatNumber(rk(b.i32(6))))
                0x00BD -> {
                    val row = b.u16(0)
                    val first = b.u16(2)
                    val n = (r.data.size - 6) / 6
                    for (k in 0 until n) put(row, first + k, b.u16(4 + k * 6), Value.formatNumber(rk(b.i32(6 + k * 6))))
                }
                0x0205 -> {
                    val v = b.u8(6)
                    val text = if (b.u8(7) == 0) (if (v != 0) "TRUE" else "FALSE") else ERRORS[v] ?: "#N/A"
                    put(b.u16(0), b.u16(2), b.u16(4), text)
                }
                0x0006 -> {
                    val row = b.u16(0)
                    val col = b.u16(2)
                    val xf = b.u16(4)
                    if (b.u16(12) == 0xFFFF) {
                        when (b.u8(6)) {
                            0 -> pendingString = CellRef(row, col).also { put(row, col, xf, null) }
                            1 -> put(row, col, xf, if (b.u8(8) != 0) "TRUE" else "FALSE")
                            2 -> put(row, col, xf, ERRORS[b.u8(8)] ?: "#N/A")
                            else -> put(row, col, xf, null)
                        }
                    } else {
                        put(row, col, xf, Value.formatNumber(b.f64(6)))
                    }
                }
                0x0207 -> pendingString?.let { ref ->
                    val s = Str(r.data, 0).readLong()
                    if (s.isNotEmpty()) sheet.cells[ref] = quote(s)
                    pendingString = null
                }
                0x007D -> {
                    val first = b.u16(0)
                    val last = b.u16(2)
                    val width = b.u16(4) / 256f
                    for (c in first..minOf(last, first + 255)) if (c < 256) sheet.columnWidths[c] = width
                }
            }
            i++
        }
    }

    private val ERRORS = mapOf(0x00 to "#NULL!", 0x07 to "#DIV/0!", 0x0F to "#VALUE!", 0x17 to "#REF!", 0x1D to "#NAME?", 0x24 to "#NUM!", 0x2A to "#N/A")
}
