package com.office.core.docx

import com.office.core.text.Alignment
import com.office.core.text.CharStyle
import com.office.core.text.EmbedKind
import com.office.core.text.ParaAttrs
import com.office.core.text.Paragraph
import com.office.core.text.RunLook
import com.office.core.text.TextDocument
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlParser
import com.office.core.zip.ZipPackage

/** A piece of formatted text inside a table cell, header or footer. */
data class LayoutRun(val text: String, val look: RunLook)

/** Content of a cell, header or footer, ready to draw. Lengths are in points. */
sealed class LayoutItem {
    data class Para(
        val runs: List<LayoutRun>,
        val alignment: Alignment,
        val indentLeft: Float = 0f,
        val spaceBefore: Float = 0f,
        val spaceAfter: Float = 0f,
        /** Font size of an empty paragraph, so it keeps its height. */
        val emptySizePt: Float = 11f,
    ) : LayoutItem() {
        val text: String get() = runs.joinToString("") { it.text }
    }

    data class Table(val layout: TableLayout) : LayoutItem()

    class Picture(val bytes: ByteArray?, val widthPt: Float, val heightPt: Float, val alignment: Alignment) : LayoutItem()
}

/** One side of a cell border; null when there is no line. */
data class BorderLine(val widthPt: Float, val color: Long?)

data class LayoutCell(
    /** First grid column and number of grid columns covered. */
    val col: Int,
    val span: Int,
    /** Number of rows covered (vertical merge); 0 for a cell hidden by a merge above it. */
    val rowSpan: Int,
    val fill: Long?,
    val top: BorderLine?,
    val bottom: BorderLine?,
    val left: BorderLine?,
    val right: BorderLine?,
    /** 0 top, 1 centre, 2 bottom. */
    val vAlign: Int,
    val items: List<LayoutItem>,
) {
    val text: String get() = items.joinToString("\n") { item ->
        when (item) {
            is LayoutItem.Para -> item.text
            is LayoutItem.Table -> item.layout.text
            is LayoutItem.Picture -> ""
        }
    }
}

data class LayoutRow(val heightPt: Float?, val exact: Boolean, val cells: List<LayoutCell>)

data class TableLayout(val columnsPt: List<Float>, val rows: List<LayoutRow>, val alignment: Alignment, val marginPt: Float) {
    val widthPt: Float get() = columnsPt.sum()
    val text: String get() = rows.joinToString("\n") { r -> r.cells.filter { it.rowSpan > 0 }.joinToString(" ") { it.text } }
}

/**
 * Turns WordprocessingML tables, headers and footers into [LayoutItem]s using the document's styles, so the
 * editor can draw them the way Word does: column widths, merged cells, borders, shading and formatted text.
 */
class DocxLayout(private val pkg: ZipPackage?, private val formatting: DocxFormatting = DocxFormatting(pkg)) {

    /** Layout of a `w:tbl` that belongs to [part] (pictures are resolved through that part's relationships). */
    fun table(xml: String, part: String? = null): TableLayout? =
        runCatching { table(XmlParser.parse(xml), part ?: pkg?.let { DocxReader.mainPart(it) }) }.getOrNull()

    /** Header and footer content of the document's first page, or null when there is none. */
    fun headerFooter(): Pair<List<LayoutItem>?, List<LayoutItem>?> {
        val p = pkg ?: return null to null
        val main = DocxReader.mainPart(p)
        val doc = runCatching { p.xml(main) }.getOrNull() ?: return null to null
        val body = doc.child("body") ?: return null to null
        // The first section's properties: in a paragraph's pPr, or the body's own sectPr when there is one section.
        val sectPr = body.elements.firstNotNullOfOrNull { it.path("pPr", "sectPr") } ?: body.child("sectPr") ?: return null to null
        val titlePage = sectPr.child("titlePg")?.let { it.attr("val").let { v -> v == null || v == "1" || v == "true" } } == true
        val rels = p.relationships(main)
        fun part(kind: String): List<LayoutItem>? {
            val refs = sectPr.children("${kind}Reference")
            val ref = (if (titlePage) refs.firstOrNull { it.attr("type") == "first" } else null)
                ?: refs.firstOrNull { it.attr("type") == "default" } ?: refs.firstOrNull()
                ?: return null
            val target = (ref.attr("r:id") ?: ref.attr("id"))?.let { rels[it]?.target } ?: return null
            val root = runCatching { p.xml(target) }.getOrNull() ?: return null
            return items(root, target).takeIf { list -> list.any { it !is LayoutItem.Para || it.text.isNotBlank() } }
        }
        return runCatching { part("header") }.getOrNull() to runCatching { part("footer") }.getOrNull()
    }

    private fun items(container: XmlElement, part: String?): List<LayoutItem> {
        val out = ArrayList<LayoutItem>()
        val paragraphs = ArrayList<XmlElement>()
        fun flush() {
            if (paragraphs.isEmpty()) return
            val wrapper = XmlElement(container.name, emptyMap(), paragraphs.toList())
            out += paragraphItems(DocxReader.readParagraphs(wrapper, pkg), part)
            paragraphs.clear()
        }
        fun walk(c: XmlElement) {
            for (el in c.elements) {
                when (el.localName) {
                    "p" -> paragraphs += el
                    "tbl" -> { flush(); table(el, part)?.let { out += LayoutItem.Table(it) } }
                    "sdt" -> el.child("sdtContent")?.let(::walk)
                    "customXml" -> walk(el)
                }
            }
        }
        walk(container)
        flush()
        return out
    }

    private fun paragraphItems(doc: TextDocument, part: String?): List<LayoutItem> {
        val out = ArrayList<LayoutItem>()
        for (p in doc.paragraphs) {
            val attrs = p.attrs()
            val look = formatting.paraLook(attrs)
            val alignment = if (p.alignment != Alignment.START) p.alignment else look.alignment ?: Alignment.START
            val runs = ArrayList<LayoutRun>()
            for (r in p.runs) {
                val embed = r.embedId?.let { doc.embeds[it] }
                if (embed == null) {
                    runs += LayoutRun(r.text, formatting.runLook(attrs, CharStyle.of(r)))
                    continue
                }
                when {
                    embed.wrapper != null || embed.label == DocxReader.MARKER_LABEL -> {}
                    embed.kind == EmbedKind.IMAGE -> {
                        // A picture in the text becomes its own item.
                        if (runs.isNotEmpty()) { out += para(runs, alignment, look, attrs); runs.clear() }
                        val (cx, cy) = DocxImages.extent(embed) ?: (914400L to 685800L)
                        out += LayoutItem.Picture(imageBytes(embed, part), cx / 12700f, cy / 12700f, alignment)
                    }
                    embed.kind == EmbedKind.TABLE -> table(embed.xml, part)?.let { out += LayoutItem.Table(it) }
                    else -> if (embed.label.isNotEmpty()) runs += LayoutRun(embed.label, formatting.runLook(attrs, CharStyle.PLAIN))
                }
            }
            val lastIsPicture = out.lastOrNull() is LayoutItem.Picture && runs.isEmpty() && p.runs.any { it.embedId != null }
            if (!lastIsPicture) out += para(runs, alignment, look, attrs)
        }
        return out
    }

    private fun para(runs: List<LayoutRun>, alignment: Alignment, look: com.office.core.text.ParaLook, attrs: ParaAttrs) = LayoutItem.Para(
        runs.toList(), alignment, look.indentLeft.coerceAtLeast(0f), look.spaceBefore, look.spaceAfter,
        formatting.runLook(attrs, CharStyle.PLAIN).sizePt ?: 11f,
    )

    private fun Paragraph.attrs() = ParaAttrs(style, list, alignment, extraXml, customStyleId, numXml, numType)

    private fun imageBytes(embed: com.office.core.text.Embed, part: String?): ByteArray? {
        val p = pkg ?: return null
        val root = runCatching { XmlParser.parse(embed.xml) }.getOrNull() ?: return null
        val relId = root.descendants("blip").firstNotNullOfOrNull { it.attr("r:embed") ?: it.attr("embed") }
            ?: root.descendants("imagedata").firstNotNullOfOrNull { it.attr("r:id") } ?: return null
        val rel = p.relationships(part ?: DocxReader.mainPart(p))[relId] ?: return null
        return if (rel.external) null else p.bytes(rel.target)
    }

    // --- tables -----------------------------------------------------------------------------------

    private fun line(e: XmlElement?): BorderLine? {
        e ?: return null
        val v = e.attr("val") ?: return null
        if (v == "nil" || v == "none") return null
        val width = (e.attr("sz")?.toFloatOrNull() ?: 4f) / 8f
        val color = e.attr("color")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { it or 0xFF000000L }
        return BorderLine(width.coerceIn(0.25f, 6f), color)
    }

    private fun table(tbl: XmlElement, part: String?): TableLayout? {
        val tblPr = tbl.child("tblPr")
        val styleId = tblPr?.child("tblStyle")?.attr("val")
        // Borders: the table style, then the table's own.
        val borders = HashMap<String, XmlElement>()
        formatting.tableStyleProperty(styleId, "tblBorders")?.elements?.forEach { borders[it.localName] = it }
        tblPr?.child("tblBorders")?.elements?.forEach { borders[it.localName] = it }
        fun b(name: String, alt: String? = null) = line(borders[name] ?: alt?.let { borders[it] })

        val grid = tbl.child("tblGrid")?.children("gridCol")?.map { (it.attr("w")?.toFloatOrNull() ?: 0f) / 20f }.orEmpty()
        val rowsXml = tbl.children("tr")
        if (rowsXml.isEmpty()) return null

        // Grid positions of every cell, to resolve vertical merges.
        class Raw(val tc: XmlElement, val col: Int, val span: Int, val vMerge: String?)
        val raw = rowsXml.map { tr ->
            var col = tr.path("trPr", "gridBefore")?.attr("val")?.toIntOrNull() ?: 0
            tr.children("tc").map { tc ->
                val span = tc.path("tcPr", "gridSpan")?.attr("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val vm = tc.path("tcPr", "vMerge")?.let { it.attr("val") ?: "continue" }
                Raw(tc, col, span, vm).also { col += span }
            }
        }
        val columnCount = maxOf(grid.size, raw.maxOf { r -> r.lastOrNull()?.let { it.col + it.span } ?: 0 })
        val columns = List(columnCount) { i ->
            grid.getOrNull(i)?.takeIf { it > 0f } ?: run {
                // No grid: take the width of a one-column cell in that position, else an even share.
                raw.firstNotNullOfOrNull { r -> r.firstOrNull { it.col == i && it.span == 1 } }
                    ?.tc?.path("tcPr", "tcW")?.takeIf { it.attr("type") == "dxa" }?.attr("w")?.toFloatOrNull()?.div(20f)
                    ?: 60f
            }
        }

        val rowCount = rowsXml.size
        val rows = rowsXml.mapIndexed { ri, tr ->
            val trH = tr.path("trPr", "trHeight")
            val height = trH?.attr("val")?.toFloatOrNull()?.div(20f)
            val cells = raw[ri].map { rc ->
                val tcPr = rc.tc.child("tcPr")
                val rowSpan = when (rc.vMerge) {
                    null -> 1
                    "restart" -> {
                        var n = 1
                        while (ri + n < rowCount && raw[ri + n].any { it.col == rc.col && it.vMerge == "continue" }) n++
                        n
                    }
                    else -> 0
                }
                val cb = HashMap<String, XmlElement>()
                tcPr?.child("tcBorders")?.elements?.forEach { cb[it.localName] = it }
                fun side(own: String, alt: String, outer: String, outerAlt: String, inner: String, isOuter: Boolean): BorderLine? =
                    if (cb.containsKey(own) || cb.containsKey(alt)) line(cb[own] ?: cb[alt]) else if (isOuter) b(outer, outerAlt) else b(inner)
                val lastCol = rc.col + rc.span >= columnCount
                LayoutCell(
                    col = rc.col,
                    span = rc.span,
                    rowSpan = rowSpan,
                    fill = tcPr?.child("shd")?.attr("fill")?.takeIf { it.length == 6 && it != "auto" }?.toLongOrNull(16)?.let { it or 0xFF000000L },
                    top = side("top", "top", "top", "top", "insideH", ri == 0),
                    bottom = side("bottom", "bottom", "bottom", "bottom", "insideH", ri + maxOf(rowSpan, 1) >= rowCount),
                    left = side("start", "left", "start", "left", "insideV", rc.col == 0),
                    right = side("end", "right", "end", "right", "insideV", lastCol),
                    vAlign = when (tcPr?.child("vAlign")?.attr("val")) { "center" -> 1; "bottom" -> 2; else -> 0 },
                    items = if (rowSpan == 0) emptyList() else items(rc.tc, part),
                )
            }
            LayoutRow(height, trH?.attr("hRule") == "exact", cells)
        }
        val jc = when (tblPr?.child("jc")?.attr("val")) {
            "center" -> Alignment.CENTER
            "right", "end" -> Alignment.END
            else -> Alignment.START
        }
        val margin = (tblPr?.path("tblCellMar", "left") ?: formatting.tableStyleProperty(styleId, "tblCellMar")?.child("left"))
            ?.attr("w")?.toFloatOrNull()?.div(20f) ?: 5.4f
        return TableLayout(columns, rows, jc, margin)
    }
}
