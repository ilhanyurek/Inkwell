package com.office.core.sheet

enum class ChartType { COLUMN, BAR, LINE, AREA, PIE, DOUGHNUT, SCATTER }

/** A range a chart reads from, e.g. `Budget!$B$2:$B$7`. */
data class ChartRef(val sheet: String?, val from: CellRef, val to: CellRef) {
    val cells: List<CellRef>
        get() = (minOf(from.row, to.row)..maxOf(from.row, to.row)).flatMap { r ->
            (minOf(from.col, to.col)..maxOf(from.col, to.col)).map { c -> CellRef(r, c) }
        }

    companion object {
        fun parse(formula: String?): ChartRef? {
            val f = formula?.trim()?.removePrefix("=")?.removePrefix("(")?.removeSuffix(")") ?: return null
            if (f.contains(',')) return null
            val bang = f.lastIndexOf('!')
            val sheet = if (bang > 0) f.substring(0, bang).removeSurrounding("'").replace("''", "'") else null
            val range = if (bang >= 0) f.substring(bang + 1) else f
            val parts = range.split(':')
            val from = CellRef.parse(parts[0]) ?: return null
            val to = if (parts.size > 1) CellRef.parse(parts[1]) ?: return null else from
            return ChartRef(sheet, from, to)
        }
    }
}

data class ChartSeries(
    val name: String,
    val nameRef: ChartRef?,
    val values: ChartRef?,
    /** Values cached in the file, used when the range cannot be read. */
    val cachedValues: List<Double?>,
)

data class ChartSpec(
    val type: ChartType,
    val title: String,
    val series: List<ChartSeries>,
    val categories: ChartRef?,
    val cachedCategories: List<String>,
    /** Top-left cell the chart is anchored to. */
    val anchor: CellRef?,
)
