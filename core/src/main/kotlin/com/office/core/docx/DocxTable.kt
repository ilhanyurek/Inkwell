package com.office.core.docx

import com.office.core.text.Embed
import com.office.core.text.EmbedKind
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlNode
import com.office.core.xml.XmlParser
import com.office.core.xml.XmlText
import com.office.core.xml.XmlWriter
import com.office.core.xml.withChildren

/**
 * Edits the cell text and shape of a `w:tbl` while keeping its formatting. Cells whose text did not
 * change are written back untouched; changed cells keep their cell, paragraph and first run properties.
 */
object DocxTable {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** Cell text per row; paragraphs inside a cell are joined with a newline. */
    fun rows(tbl: XmlElement): List<List<String>> = tbl.children("tr").map { tr -> tr.children("tc").map { cellText(it) } }

    fun rows(xml: String): List<List<String>> = rows(XmlParser.parse(xml))

    fun embed(xml: String): Embed = embed(XmlParser.parse(xml), xml)

    internal fun embed(tbl: XmlElement, xml: String = XmlWriter.write(tbl)): Embed {
        val rows = rows(tbl)
        val cols = rows.maxOfOrNull { it.size } ?: 0
        return Embed(EmbedKind.TABLE, xml, block = true, label = "${rows.size}×$cols", tableRows = rows)
    }

    private fun cellText(tc: XmlElement): String =
        tc.children("p").joinToString("\n") { p -> p.descendants("t").joinToString("") { it.text() } }.trim()

    /**
     * Rewrites [xml] so it holds [newRows]. [rowMap] and [colMap] give, for each new row/column, the
     * index of the original one it comes from, or null for an inserted one.
     */
    fun edit(xml: String, newRows: List<List<String>>, rowMap: List<Int?>, colMap: List<Int?>): String {
        require(rowMap.size == newRows.size)
        val tbl = XmlParser.parse(xml)
        val p = tbl.name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
        val origRows = tbl.children("tr")
        if (origRows.isEmpty() || newRows.isEmpty() || colMap.isEmpty()) return xml
        val origCols = origRows.maxOf { it.children("tc").size }
        val shapeChanged = colMap.size != origCols || colMap.withIndex().any { (i, c) -> c != i }

        val grid = tbl.child("tblGrid")
        val origWidths = grid?.children("gridCol")?.map { it.attr("w")?.toIntOrNull() ?: 0 }.orEmpty()
        val total = origWidths.sum().takeIf { it > 0 } ?: 9000
        val newWidth = total / colMap.size

        val trs = newRows.mapIndexed { i, texts ->
            val src = rowMap[i]?.let { origRows.getOrNull(it) }
            val baseRow = src ?: origRows.last()
            val cells = baseRow.children("tc")
            val tcs = colMap.mapIndexed { j, c ->
                val origCell = c?.let { cells.getOrNull(it) }
                val text = texts.getOrElse(j) { "" }
                val tc = when {
                    origCell != null && src != null && cellText(origCell) == text -> origCell
                    origCell != null -> setText(origCell, text, p, clearMerge = src == null)
                    else -> setText(cells.lastOrNull() ?: emptyCell(p), text, p, clearMerge = true)
                }
                if (shapeChanged) withWidth(tc, newWidth, p) else tc
            }
            val others = baseRow.children.filter { it !is XmlElement || it.localName != "tc" }
                .map { if (src == null && it is XmlElement && it.localName == "trPr") stripChildren(it, "tblHeader") else it }
                .filterIsInstance<XmlElement>()
            baseRow.withChildren(others + tcs)
        }

        val children = ArrayList<XmlNode>()
        var rowsPlaced = false
        for (c in tbl.children) {
            if (c !is XmlElement) continue
            when (c.localName) {
                "tr" -> if (!rowsPlaced) { children += trs; rowsPlaced = true }
                "tblGrid" -> children += if (shapeChanged) gridOf(c.name, p, colMap.size, newWidth) else c
                else -> children += c
            }
        }
        return XmlWriter.write(tbl.withChildren(children))
    }

    // --- single steps used when a table is edited in place -------------------------------------

    /** True when every row has the same cells and nothing is merged, so columns can be added or removed. */
    fun isRegular(xml: String): Boolean = runCatching {
        val tbl = XmlParser.parse(xml)
        val rows = tbl.children("tr")
        val counts = rows.map { it.children("tc").size }.distinct()
        counts.size == 1 && rows.none { tr ->
            tr.children("tc").any { tc ->
                val pr = tc.child("tcPr")
                (pr?.child("gridSpan")?.attr("val")?.toIntOrNull() ?: 1) > 1 || pr?.child("vMerge") != null || pr?.child("hMerge") != null
            }
        }
    }.getOrDefault(false)

    /** [xml] with the text of cell [tc] of row [row] replaced; every other cell is left as it is. */
    fun setCellText(xml: String, row: Int, tc: Int, text: String): String {
        val tbl = XmlParser.parse(xml)
        val p = prefix(tbl)
        var r = -1
        var changed = false
        val children = tbl.children.map { node ->
            if (node !is XmlElement || node.localName != "tr") return@map node
            r++
            if (r != row) return@map node
            var c = -1
            node.withChildren(
                node.children.map { cell ->
                    if (cell !is XmlElement || cell.localName != "tc") return@map cell
                    c++
                    if (c != tc || cellText(cell) == text) cell else { changed = true; setText(cell, text, p, clearMerge = false) }
                },
            )
        }
        return if (changed) XmlWriter.write(tbl.withChildren(children)) else xml
    }

    /**
     * [xml] with an empty row inserted at index [at], shaped like row [like] (same cells and widths, no
     * text, no vertical merge, not a repeated header).
     */
    fun insertRow(xml: String, at: Int, like: Int): String {
        val tbl = XmlParser.parse(xml)
        val p = prefix(tbl)
        val rows = tbl.children("tr")
        if (rows.isEmpty()) return xml
        val model = rows[like.coerceIn(0, rows.lastIndex)]
        val blank = model.withChildren(
            model.children.mapNotNull { n ->
                when {
                    n !is XmlElement -> n
                    n.localName == "trPr" -> stripChildren(n, "tblHeader")
                    n.localName == "tc" -> {
                        val cleared = setText(n, "", p, clearMerge = false)
                        val pr = cleared.child("tcPr")
                        if (pr == null) cleared else cleared.withChildren(cleared.children.map { if (it === pr) stripChildren(pr, "vMerge") else it })
                    }
                    else -> n
                }
            },
        )
        val target = at.coerceIn(0, rows.size)
        val out = ArrayList<XmlNode>()
        var r = 0
        var placed = false
        for (n in tbl.children) {
            if (n is XmlElement && n.localName == "tr") {
                if (r == target && !placed) { out += blank; placed = true }
                r++
            }
            out += n
        }
        if (!placed) {
            // After the last row (before anything that follows the rows).
            val lastRow = out.indexOfLast { it is XmlElement && it.localName == "tr" }
            out.add(lastRow + 1, blank)
        }
        return XmlWriter.write(tbl.withChildren(out))
    }

    /** [xml] without row [row]; a table keeps at least one row. */
    fun deleteRow(xml: String, row: Int): String {
        val tbl = XmlParser.parse(xml)
        if (tbl.children("tr").size <= 1) return xml
        var r = -1
        val out = tbl.children.filter { n -> !(n is XmlElement && n.localName == "tr" && ++r == row) }
        return XmlWriter.write(tbl.withChildren(out))
    }

    /** [xml] with an empty column inserted before column [at] (regular tables only; widths are shared out again). */
    fun insertColumn(xml: String, at: Int): String {
        if (!isRegular(xml)) return xml
        val rows = rows(xml)
        val cols = rows.maxOfOrNull { it.size } ?: return xml
        val target = at.coerceIn(0, cols)
        val colMap = (0 until cols).map { it as Int? }.toMutableList().apply { add(target, null) }
        val newRows = rows.map { r -> r.toMutableList().apply { while (size < cols) add(""); add(target, "") } }
        return edit(xml, newRows, rows.indices.map { it }, colMap)
    }

    /** [xml] without column [col] (regular tables only); a table keeps at least one column. */
    fun deleteColumn(xml: String, col: Int): String {
        if (!isRegular(xml)) return xml
        val rows = rows(xml)
        val cols = rows.maxOfOrNull { it.size } ?: return xml
        if (cols <= 1 || col !in 0 until cols) return xml
        val colMap = (0 until cols).filter { it != col }.map { it as Int? }
        val newRows = rows.map { r -> r.filterIndexed { i, _ -> i != col } }
        return edit(xml, newRows, rows.indices.map { it }, colMap)
    }

    private fun prefix(tbl: XmlElement) = tbl.name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }

    /** A new bordered table with [rows] × [cols] cells, sized to a typical page width. */
    fun create(rows: Int, cols: Int, texts: List<List<String>> = emptyList()): String {
        val width = 9000 / cols
        val border = { side: String -> """<w:$side w:val="single" w:sz="4" w:space="0" w:color="A0A6B0"/>""" }
        val sb = StringBuilder()
        sb.append("""<w:tbl xmlns:w="$W"><w:tblPr><w:tblW w:w="${width * cols}" w:type="dxa"/><w:tblBorders>""")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { sb.append(border(it)) }
        sb.append("""</w:tblBorders><w:tblLayout w:type="fixed"/><w:tblCellMar><w:left w:w="108" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar><w:tblLook w:val="04A0" w:firstRow="1" w:lastRow="0" w:firstColumn="1" w:lastColumn="0" w:noHBand="0" w:noVBand="1"/></w:tblPr>""")
        sb.append("<w:tblGrid>")
        repeat(cols) { sb.append("""<w:gridCol w:w="$width"/>""") }
        sb.append("</w:tblGrid>")
        for (r in 0 until rows) {
            sb.append("<w:tr>")
            for (c in 0 until cols) {
                sb.append("""<w:tc><w:tcPr><w:tcW w:w="$width" w:type="dxa"/></w:tcPr>""")
                val text = texts.getOrNull(r)?.getOrNull(c).orEmpty()
                text.split('\n').forEach { line ->
                    sb.append("<w:p>")
                    if (r == 0) sb.append("<w:pPr><w:rPr><w:b/></w:rPr></w:pPr>")
                    if (line.isNotEmpty()) {
                        sb.append("<w:r>")
                        if (r == 0) sb.append("<w:rPr><w:b/></w:rPr>")
                        sb.append("""<w:t xml:space="preserve">""").append(com.office.core.xml.xmlEscape(line)).append("</w:t></w:r>")
                    }
                    sb.append("</w:p>")
                }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun el(name: String, attrs: Map<String, String> = emptyMap(), children: List<XmlNode> = emptyList()) =
        XmlElement(name, attrs, children)

    private fun emptyCell(p: String) = el("${p}tc", children = listOf(el("${p}p")))

    private fun stripChildren(e: XmlElement, vararg names: String) =
        e.withChildren(e.children.filter { it !is XmlElement || it.localName !in names })

    private fun setText(tc: XmlElement, text: String, p: String, clearMerge: Boolean): XmlElement {
        val tcPr = tc.child("tcPr")?.let { if (clearMerge) stripChildren(it, "gridSpan", "vMerge", "hMerge") else it }
        val firstP = tc.child("p")
        val pPr = firstP?.child("pPr")
        val rPr = firstP?.descendants("r")?.firstOrNull()?.child("rPr")
        val others = tc.elements.filter { it.localName != "tcPr" && it.localName != "p" }
        val paragraphs = text.split('\n').map { line ->
            val pc = ArrayList<XmlNode>()
            pPr?.let { pc += it }
            if (line.isNotEmpty()) {
                val rc = ArrayList<XmlNode>()
                rPr?.let { rc += it }
                rc += el("${p}t", mapOf("xml:space" to "preserve"), listOf(XmlText(line)))
                pc += el("${p}r", children = rc)
            }
            el("${p}p", children = pc)
        }
        return tc.withChildren(listOfNotNull(tcPr) + others + paragraphs)
    }

    private fun withWidth(tc: XmlElement, width: Int, p: String): XmlElement {
        val tcW = el("${p}tcW", mapOf("${p}w" to width.toString(), "${p}type" to "dxa"))
        val tcPr = tc.child("tcPr")
        val newPr = if (tcPr == null) el("${p}tcPr", children = listOf(tcW))
        else {
            val kids = tcPr.children.filter { it !is XmlElement || it.localName != "tcW" }
            val idx = kids.indexOfFirst { it is XmlElement && it.localName == "cnfStyle" } + 1
            tcPr.withChildren(kids.toMutableList().apply { add(idx, tcW) })
        }
        val rest = tc.children.filter { it !is XmlElement || it.localName != "tcPr" }
        return tc.withChildren(listOf(newPr) + rest)
    }

    private fun gridOf(name: String, p: String, cols: Int, width: Int) =
        el(name, children = List(cols) { el("${p}gridCol", mapOf("${p}w" to width.toString())) })
}
