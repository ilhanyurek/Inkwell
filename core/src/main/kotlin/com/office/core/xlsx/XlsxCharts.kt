package com.office.core.xlsx

import com.office.core.sheet.CellRef
import com.office.core.sheet.ChartRef
import com.office.core.sheet.ChartSeries
import com.office.core.sheet.ChartSpec
import com.office.core.sheet.ChartType
import com.office.core.xml.XmlElement
import com.office.core.zip.ZipPackage

/** Reads the charts drawn on a worksheet (worksheet → drawing → chart parts). */
object XlsxCharts {

    fun read(pkg: ZipPackage, sheetPath: String): List<ChartSpec> {
        val sheetRels = pkg.relationships(sheetPath)
        val drawings = sheetRels.values.filter { it.type.endsWith("/drawing") && !it.external }
        val out = ArrayList<ChartSpec>()
        for (d in drawings) {
            val drawing = runCatching { pkg.xml(d.target) }.getOrNull() ?: continue
            val drawingRels = pkg.relationships(d.target)
            for (anchor in drawing.elements) {
                val chartEl = anchor.descendants("chart").firstOrNull { it.attr("r:id") != null || it.attr("id") != null } ?: continue
                val relId = chartEl.attr("r:id") ?: chartEl.attr("id") ?: continue
                val target = drawingRels[relId]?.target ?: continue
                val chart = runCatching { pkg.xml(target) }.getOrNull() ?: continue
                val from = anchor.child("from")
                val cell = from?.let {
                    val c = it.child("col")?.text()?.trim()?.toIntOrNull()
                    val r = it.child("row")?.text()?.trim()?.toIntOrNull()
                    if (c != null && r != null) CellRef(r, c) else null
                }
                runCatching { parseChart(chart, cell) }.getOrNull()?.let { out += it }
            }
        }
        return out
    }

    fun parseChart(root: XmlElement, anchor: CellRef?): ChartSpec? {
        val chart = root.child("chart") ?: return null
        val plot = chart.child("plotArea") ?: return null
        val group = plot.elements.firstOrNull { it.localName in TYPES } ?: return null
        val type = when (group.localName) {
            "barChart", "bar3DChart" -> if (group.child("barDir")?.attr("val") == "bar") ChartType.BAR else ChartType.COLUMN
            "lineChart", "line3DChart" -> ChartType.LINE
            "areaChart", "area3DChart" -> ChartType.AREA
            "pieChart", "pie3DChart", "ofPieChart" -> ChartType.PIE
            "doughnutChart" -> ChartType.DOUGHNUT
            else -> ChartType.SCATTER
        }
        val series = group.children("ser").map { ser ->
            val tx = ser.child("tx")
            val nameRef = tx?.descendants("f")?.firstOrNull()?.text()
            val name = tx?.descendants("v")?.firstOrNull()?.text() ?: tx?.descendants("t")?.joinToString("") { it.text() } ?: ""
            val vals = ser.child("val") ?: ser.child("yVal")
            ChartSeries(
                name = name,
                nameRef = ChartRef.parse(nameRef),
                values = ChartRef.parse(vals?.descendants("f")?.firstOrNull()?.text()),
                cachedValues = cached(vals).map { it?.toDoubleOrNull() },
            )
        }
        val cat = group.children("ser").firstOrNull()?.let { it.child("cat") ?: it.child("xVal") }
        val title = chart.child("title")?.let { t ->
            t.descendants("t").joinToString("") { it.text() }.ifEmpty { t.descendants("v").firstOrNull()?.text().orEmpty() }
        } ?: if (chart.child("autoTitleDeleted")?.attr("val") == "1" || series.size != 1) "" else series[0].name
        return ChartSpec(
            type = type,
            title = title,
            series = series,
            categories = ChartRef.parse(cat?.descendants("f")?.firstOrNull()?.text()),
            cachedCategories = cached(cat).map { it.orEmpty() },
            anchor = anchor,
        )
    }

    /** Points of a `numCache`/`strCache`, by index; missing points are null. */
    private fun cached(el: XmlElement?): List<String?> {
        val cache = el?.descendants("numCache")?.firstOrNull() ?: el?.descendants("strCache")?.firstOrNull()
            ?: el?.descendants("numLit")?.firstOrNull() ?: el?.descendants("strLit")?.firstOrNull() ?: return emptyList()
        val count = cache.child("ptCount")?.attr("val")?.toIntOrNull()
        val pts = cache.children("pt").associate { (it.attr("idx")?.toIntOrNull() ?: 0) to it.child("v")?.text() }
        val n = count ?: ((pts.keys.maxOrNull() ?: -1) + 1)
        return List(n) { pts[it] }
    }

    private val TYPES = setOf(
        "barChart", "bar3DChart", "lineChart", "line3DChart", "areaChart", "area3DChart",
        "pieChart", "pie3DChart", "ofPieChart", "doughnutChart", "scatterChart",
    )
}
