package com.office.core.sheet

/** Zero-based cell coordinate. */
data class CellRef(val row: Int, val col: Int) : Comparable<CellRef> {
    override fun compareTo(other: CellRef): Int =
        if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)

    override fun toString() = columnName(col) + (row + 1)

    companion object {
        private val pattern = Regex("^\\$?([A-Za-z]{1,3})\\$?(\\d{1,7})$")

        fun parse(a1: String): CellRef? {
            val m = pattern.matchEntire(a1.trim()) ?: return null
            val col = columnIndex(m.groupValues[1]) ?: return null
            val row = m.groupValues[2].toInt() - 1
            if (row < 0) return null
            return CellRef(row, col)
        }

        fun columnName(index: Int): String {
            var n = index + 1
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % 26
                sb.append('A' + rem)
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }

        fun columnIndex(name: String): Int? {
            if (name.isEmpty()) return null
            var n = 0
            for (c in name.uppercase()) {
                if (c !in 'A'..'Z') return null
                n = n * 26 + (c - 'A' + 1)
            }
            return n - 1
        }
    }
}

/**
 * A single sheet. Cells hold the raw user input: plain text, a number as typed,
 * or a formula starting with `=`.
 */
class Sheet(
    var name: String,
    val cells: MutableMap<CellRef, String> = HashMap(),
    /** Values cached by the producing application, used when we cannot evaluate a formula. */
    val cachedValues: MutableMap<CellRef, String> = HashMap(),
    /** Column widths in characters (Excel units). */
    val columnWidths: MutableMap<Int, Float> = HashMap(),
    val styles: MutableMap<CellRef, CellStyle> = HashMap(),
    /**
     * Worksheet elements we do not model (merged cells, charts, conditional formatting…) as
     * (local name, raw XML), written back unchanged.
     */
    val extraXml: MutableList<Pair<String, String>> = ArrayList(),
    /** Part path and relationship id of the worksheet this sheet was read from. */
    var sourcePath: String? = null,
    var sourceRelId: String? = null,
) {
    fun style(ref: CellRef): CellStyle = styles[ref] ?: CellStyle.DEFAULT

    fun setStyle(ref: CellRef, style: CellStyle) {
        if (style.isDefault) styles.remove(ref) else styles[ref] = style
    }
    operator fun get(ref: CellRef): String = cells[ref].orEmpty()

    operator fun set(ref: CellRef, value: String) {
        if (value.isEmpty()) cells.remove(ref) else cells[ref] = value
        cachedValues.remove(ref)
    }

    val maxRow: Int get() = cells.keys.maxOfOrNull { it.row } ?: -1
    val maxCol: Int get() = cells.keys.maxOfOrNull { it.col } ?: -1

    fun copy(): Sheet = Sheet(name, HashMap(cells), HashMap(cachedValues), HashMap(columnWidths), HashMap(styles), ArrayList(extraXml), sourcePath, sourceRelId)

    /** Largest row/column that has content or formatting. */
    val usedMaxRow: Int get() = maxOf(maxRow, styles.keys.maxOfOrNull { it.row } ?: -1)
    val usedMaxCol: Int get() = maxOf(maxCol, styles.keys.maxOfOrNull { it.col } ?: -1)

    /** Inserts an empty row before [index], shifting cells down. Formulas are not rewritten. */
    fun insertRow(index: Int) = shift { r -> if (r.row >= index) r.copy(row = r.row + 1) else r }

    fun deleteRow(index: Int) = shift { r ->
        when {
            r.row == index -> null
            r.row > index -> r.copy(row = r.row - 1)
            else -> r
        }
    }

    fun insertColumn(index: Int) {
        shift { r -> if (r.col >= index) r.copy(col = r.col + 1) else r }
        val widths = columnWidths.entries.associate { (c, w) -> (if (c >= index) c + 1 else c) to w }
        columnWidths.clear(); columnWidths.putAll(widths)
    }

    fun deleteColumn(index: Int) {
        shift { r ->
            when {
                r.col == index -> null
                r.col > index -> r.copy(col = r.col - 1)
                else -> r
            }
        }
        val widths = columnWidths.entries.mapNotNull { (c, w) ->
            when {
                c == index -> null
                c > index -> (c - 1) to w
                else -> c to w
            }
        }.toMap()
        columnWidths.clear(); columnWidths.putAll(widths)
    }

    private fun shift(transform: (CellRef) -> CellRef?) {
        val newCells = HashMap<CellRef, String>()
        for ((k, v) in cells) transform(k)?.let { newCells[it] = v }
        val newCached = HashMap<CellRef, String>()
        for ((k, v) in cachedValues) transform(k)?.let { newCached[it] = v }
        val newStyles = HashMap<CellRef, CellStyle>()
        for ((k, v) in styles) transform(k)?.let { newStyles[it] = v }
        cells.clear(); cells.putAll(newCells)
        cachedValues.clear(); cachedValues.putAll(newCached)
        styles.clear(); styles.putAll(newStyles)
    }

    /** Sorts rows [fromRow]..maxRow by the evaluated values of column [col]. */
    fun sortRows(col: Int, ascending: Boolean, fromRow: Int, evaluator: Evaluator) {
        val last = maxRow
        if (last <= fromRow) return
        val rows = (fromRow..last).map { r -> r to evaluator.value(this, CellRef(r, col)) }
        val sorted = rows.sortedWith { a, b ->
            val emptyA = a.second == Value.Empty
            val emptyB = b.second == Value.Empty
            when {
                emptyA != emptyB -> if (emptyA) 1 else -1
                ascending -> compareValues(a.second, b.second)
                else -> -compareValues(a.second, b.second)
            }
        }.map { it.first }
        val snapshot = HashMap(cells)
        val cachedSnapshot = HashMap(cachedValues)
        val styleSnapshot = HashMap(styles)
        cells.keys.removeAll { it.row >= fromRow && it.row <= last }
        cachedValues.keys.removeAll { it.row >= fromRow && it.row <= last }
        styles.keys.removeAll { it.row >= fromRow && it.row <= last }
        sorted.forEachIndexed { i, oldRow ->
            val newRow = fromRow + i
            snapshot.filterKeys { it.row == oldRow }.forEach { (k, v) -> cells[CellRef(newRow, k.col)] = v }
            cachedSnapshot.filterKeys { it.row == oldRow }.forEach { (k, v) -> cachedValues[CellRef(newRow, k.col)] = v }
            styleSnapshot.filterKeys { it.row == oldRow }.forEach { (k, v) -> styles[CellRef(newRow, k.col)] = v }
        }
    }

    private fun compareValues(a: Value, b: Value): Int {
        fun rank(v: Value) = when (v) {
            is Value.Num -> 0
            is Value.Text -> 1
            is Value.Bool -> 2
            is Value.Err -> 3
            Value.Empty -> 4
        }
        val ra = rank(a)
        val rb = rank(b)
        if (ra != rb) return ra - rb
        return when (a) {
            is Value.Num -> a.v.compareTo((b as Value.Num).v)
            is Value.Text -> a.v.compareTo((b as Value.Text).v, ignoreCase = true)
            is Value.Bool -> a.v.compareTo((b as Value.Bool).v)
            else -> 0
        }
    }
}

class Workbook(val sheets: MutableList<Sheet>) {
    fun sheet(name: String): Sheet? = sheets.firstOrNull { it.name.equals(name, ignoreCase = true) }

    companion object {
        fun empty(name: String = "Sheet1") = Workbook(mutableListOf(Sheet(name)))
    }
}

/** Evaluated cell value. */
sealed class Value {
    data class Num(val v: Double) : Value()
    data class Text(val v: String) : Value()
    data class Bool(val v: Boolean) : Value()
    data class Err(val code: String) : Value()
    object Empty : Value()

    fun display(): String = when (this) {
        is Num -> formatNumber(v)
        is Text -> v
        is Bool -> if (v) "TRUE" else "FALSE"
        is Err -> code
        Empty -> ""
    }

    companion object {
        fun formatNumber(d: Double): String {
            if (d.isNaN()) return "#NUM!"
            if (d.isInfinite()) return "#DIV/0!"
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
            val s = "%.10f".format(java.util.Locale.ROOT, d).trimEnd('0').trimEnd('.')
            return if (s == "-0") "0" else s
        }
    }
}

/** Implemented by the formula engine; lives here to keep [Sheet.sortRows] decoupled. */
interface Evaluator {
    fun value(sheet: Sheet, ref: CellRef): Value
}
