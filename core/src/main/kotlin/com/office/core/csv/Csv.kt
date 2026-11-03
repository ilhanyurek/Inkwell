package com.office.core.csv

import com.office.core.sheet.CellRef
import com.office.core.sheet.Sheet
import com.office.core.sheet.Workbook
import com.office.core.text.TextDecoding

/** RFC 4180 CSV with delimiter auto-detection (`,` `;` tab `|`). */
object Csv {

    data class Parsed(val rows: List<List<String>>, val delimiter: Char)

    fun parse(bytes: ByteArray, delimiter: Char? = null): Parsed = parse(TextDecoding.decode(bytes), delimiter)

    fun parse(text: String, delimiter: Char? = null): Parsed {
        val d = delimiter ?: detectDelimiter(text)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false
        var i = 0
        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldStarted = false
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i += 2; continue }
                    inQuotes = false
                } else field.append(c)
                i++
                continue
            }
            when (c) {
                '"' -> if (!fieldStarted && field.isEmpty()) { inQuotes = true; fieldStarted = true } else field.append(c)
                d -> endField()
                '\r' -> { endRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                '\n' -> endRow()
                else -> { field.append(c); fieldStarted = true }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldStarted) endRow()
        return Parsed(rows, d)
    }

    fun detectDelimiter(text: String): Char {
        val candidates = charArrayOf(',', ';', '\t', '|')
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.isEmpty()) return ','
        var best = ','
        var bestScore = 0.0
        for (d in candidates) {
            val counts = sample.map { line -> countOutsideQuotes(line, d) }
            val min = counts.minOrNull() ?: 0
            if (min == 0 && counts.all { it == 0 }) continue
            // Prefer delimiters that appear consistently across lines.
            val consistent = counts.count { it == counts[0] }.toDouble() / counts.size
            val score = counts.average() * consistent + if (min > 0) 1 else 0
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var n = 0
        var q = false
        for (c in line) {
            if (c == '"') q = !q
            else if (c == d && !q) n++
        }
        return n
    }

    fun write(rows: List<List<String>>, delimiter: Char = ','): String {
        val sb = StringBuilder()
        for ((ri, row) in rows.withIndex()) {
            if (ri > 0) sb.append("\r\n")
            row.forEachIndexed { ci, v ->
                if (ci > 0) sb.append(delimiter)
                val needsQuotes = v.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } ||
                    v.startsWith(' ') || v.endsWith(' ')
                if (needsQuotes) sb.append('"').append(v.replace("\"", "\"\"")).append('"') else sb.append(v)
            }
        }
        return sb.toString()
    }

    fun toWorkbook(parsed: Parsed, sheetName: String = "Sheet1"): Workbook {
        val sheet = Sheet(sheetName)
        parsed.rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, v -> if (v.isNotEmpty()) sheet.cells[CellRef(r, c)] = v }
        }
        return Workbook(mutableListOf(sheet))
    }

    /**
     * Serializes a sheet. When [values] is given (e.g. formula results) it is used for
     * each cell instead of the raw input, so formulas are flattened into plain values.
     */
    fun fromSheet(sheet: Sheet, values: ((CellRef) -> String)? = null): List<List<String>> {
        val maxRow = sheet.maxRow
        val maxCol = sheet.maxCol
        if (maxRow < 0) return emptyList()
        return (0..maxRow).map { r ->
            (0..maxCol).map { c ->
                val ref = CellRef(r, c)
                values?.invoke(ref) ?: sheet[ref]
            }.dropLastWhile { it.isEmpty() }
        }
    }
}
