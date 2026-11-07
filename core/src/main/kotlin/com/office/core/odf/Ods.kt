package com.office.core.odf

import com.office.core.formula.FormulaEngine
import com.office.core.sheet.CellRef
import com.office.core.sheet.CellStyle
import com.office.core.sheet.HAlign
import com.office.core.sheet.Sheet
import com.office.core.sheet.Value
import com.office.core.sheet.Workbook
import com.office.core.text.Alignment
import com.office.core.xlsx.XlsxReader
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlWriter
import com.office.core.xml.xmlEscape
import com.office.core.zip.ZipPackage
import java.io.OutputStream
import java.time.LocalDate

/** Converts formulas between OpenFormula (`of:=SUM([.A1:.B2])`) and the Excel style the app uses. */
object OdfFormula {

    /** `of:=SUM([.A1:.B2];[Sheet2.C3])` → `=SUM(A1:B2,Sheet2!C3)`. */
    fun toExcel(formula: String): String {
        val body = formula.substringAfter(":=", formula).removePrefix("=")
        val sb = StringBuilder("=")
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '"' -> {
                    val end = stringEnd(body, i)
                    sb.append(body, i, end)
                    i = end
                }
                c == '[' -> {
                    val close = body.indexOf(']', i)
                    if (close < 0) { sb.append(c); i++; continue }
                    sb.append(reference(body.substring(i + 1, close)))
                    i = close + 1
                }
                c == ';' -> { sb.append(','); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun stringEnd(s: String, start: Int): Int {
        var j = start + 1
        while (j < s.length) {
            if (s[j] == '"') {
                if (j + 1 < s.length && s[j + 1] == '"') { j += 2; continue }
                return j + 1
            }
            j++
        }
        return s.length
    }

    /** `.A1:.B2`, `$Sheet2.$A$1`, `'My sheet'.A1` → Excel references. */
    private fun reference(ref: String): String {
        val parts = ref.split(':')
        var sheet: String? = null
        val cells = parts.map { p ->
            val dot = p.lastIndexOf('.')
            if (dot > 0) {
                val s = p.substring(0, dot).removePrefix("$")
                if (sheet == null) sheet = s
            }
            p.substring(dot + 1)
        }
        val prefix = sheet?.let { s ->
            val name = s.removeSurrounding("'").replace("''", "'")
            if (name.all { it.isLetterOrDigit() || it == '_' }) "$name!" else "'${name.replace("'", "''")}'!"
        }.orEmpty()
        return prefix + cells.joinToString(":")
    }

    private val REF = Regex("""(?<![\w.$'])((?:'(?:[^']|'')+'|[A-Za-z_][\w]*)!)?(\$?[A-Za-z]{1,3}\$?\d{1,7}(?::\$?[A-Za-z]{1,3}\$?\d{1,7})?)(?![\w(!])""")

    /** `=SUM(A1:B2,Sheet2!C3)` → `of:=SUM([.A1:.B2];[$Sheet2.C3])`. */
    fun toOdf(formula: String): String {
        val body = formula.removePrefix("=")
        val sb = StringBuilder("of:=")
        var i = 0
        var segment = StringBuilder()
        fun flush() {
            sb.append(REF.replace(segment.toString()) { m ->
                val sheet = m.groupValues[1].removeSuffix("!").takeIf { it.isNotEmpty() }
                val cells = m.groupValues[2].split(':')
                val sheetPart = sheet?.let { s -> "$" + if (s.startsWith("'")) s else if (s.all { it.isLetterOrDigit() || it == '_' }) s else "'$s'" }.orEmpty()
                "[" + cells.mapIndexed { k, c -> (if (k == 0) sheetPart else "") + "." + c }.joinToString(":") + "]"
            }.replace(',', ';'))
            segment = StringBuilder()
        }
        while (i < body.length) {
            if (body[i] == '"') {
                flush()
                val end = stringEnd(body, i)
                sb.append(body, i, end)
                i = end
            } else {
                segment.append(body[i])
                i++
            }
        }
        flush()
        return sb.toString()
    }
}

/** Reads OpenDocument spreadsheets into the same [Workbook] model as XLSX files. */
object OdsReader {

    private const val MAX_REPEAT = 1000

    fun read(pkg: ZipPackage): Workbook {
        val content = pkg.xml("content.xml") ?: throw IllegalArgumentException("Missing content.xml")
        val styles = OdfStyles(pkg, content)
        val spreadsheet = content.child("body")?.child("spreadsheet") ?: return Workbook.empty()
        val sheets = spreadsheet.children("table").map { readTable(it, styles) }.toMutableList()
        if (sheets.isEmpty()) sheets += Sheet("Sheet1")
        return Workbook(sheets)
    }

    private fun cellStyle(styles: OdfStyles, name: String?): CellStyle? {
        val s = styles.resolve(name) ?: return null
        val style = CellStyle(
            bold = s.bold == true,
            italic = s.italic == true,
            underline = s.underline == true,
            strike = s.strike == true,
            fontColor = s.color?.takeIf { it != 0xFF000000L },
            fontSizePt = s.sizePt?.takeIf { it != 10f },
            fill = s.background,
            border = s.border == true,
            hAlign = when (s.align) {
                Alignment.CENTER -> HAlign.CENTER
                Alignment.END -> HAlign.RIGHT
                Alignment.START -> HAlign.LEFT
                else -> null
            },
            wrap = s.wrap == true,
            numFmt = s.dataStyle?.let { styles.dataFormats[it] },
        )
        return style.takeIf { !it.isDefault }
    }

    private fun readTable(table: XmlElement, styles: OdfStyles): Sheet {
        val sheet = Sheet(table.attr("table:name") ?: "Sheet")
        val columnStyles = ArrayList<String?>()
        var col = 0
        fun columns(e: XmlElement) {
            for (c in e.elements) {
                when (c.localName) {
                    "table-column" -> {
                        val n = (c.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                        val width = styles.styles[c.attr("table:style-name")]?.columnWidthEmu
                        repeat(minOf(n, 1024)) {
                            columnStyles += c.attr("table:default-cell-style-name")
                            // Excel width units are characters, about 0.2 cm each at the default font.
                            if (width != null && col < 256) sheet.columnWidths[col] = (width / 914400.0 * 2.54 / 0.2).toFloat().coerceIn(2f, 120f)
                            col++
                        }
                    }
                    "table-columns", "table-header-columns", "table-column-group" -> columns(c)
                }
            }
        }
        columns(table)

        var row = 0
        fun rows(e: XmlElement) {
            for (r in e.elements) {
                when (r.localName) {
                    "table-row" -> {
                        val repeat = (r.attr("table:number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                        val hasContent = r.elements.any { c -> c.attr("office:value-type") != null || c.attr("table:formula") != null || c.child("p") != null }
                        if (!hasContent) { row += repeat; continue }
                        repeat(minOf(repeat, MAX_REPEAT)) { readRow(r, row++, sheet, styles, columnStyles) }
                        if (repeat > MAX_REPEAT) row += repeat - MAX_REPEAT
                    }
                    "table-rows", "table-header-rows", "table-row-group" -> rows(r)
                }
            }
        }
        rows(table)
        return sheet
    }

    private fun readRow(r: XmlElement, row: Int, sheet: Sheet, styles: OdfStyles, columnStyles: List<String?>) {
        var col = 0
        for (c in r.elements) {
            if (c.localName != "table-cell" && c.localName != "covered-table-cell") continue
            val repeat = (c.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val type = c.attr("office:value-type")
            val formula = c.attr("table:formula")
            val text = c.children("p").joinToString("\n") { it.text() }
            val empty = type == null && formula == null && text.isEmpty()
            if (empty) { col += repeat; continue }
            val styleName = c.attr("table:style-name") ?: columnStyles.getOrNull(col)
            repeat(minOf(repeat, 256)) {
                val ref = CellRef(row, col++)
                cellStyle(styles, styleName)?.let { sheet.styles[ref] = it }
                val value: String? = when (type) {
                    "float", "currency" -> c.attr("office:value")?.toDoubleOrNull()?.let { Value.formatNumber(it) }
                    "percentage" -> c.attr("office:value")?.toDoubleOrNull()?.let { v ->
                        if (sheet.styles[ref]?.numFmt == null) sheet.styles[ref] = (sheet.styles[ref] ?: CellStyle()).copy(numFmt = "0%")
                        Value.formatNumber(v)
                    }
                    "date" -> c.attr("office:date-value")?.let { d ->
                        runCatching { LocalDate.parse(d.take(10)) }.getOrNull()?.let { date ->
                            if (sheet.styles[ref]?.numFmt == null) sheet.styles[ref] = (sheet.styles[ref] ?: CellStyle()).copy(numFmt = "yyyy-mm-dd")
                            Value.formatNumber(XlsxReader.dateToSerial(date))
                        }
                    }
                    "time" -> text
                    "boolean" -> if (c.attr("office:boolean-value") == "true") "TRUE" else "FALSE"
                    else -> text
                }
                when {
                    formula != null -> {
                        sheet.cells[ref] = OdfFormula.toExcel(formula)
                        value?.let { sheet.cachedValues[ref] = it }
                    }
                    value != null && value.isNotEmpty() -> sheet.cells[ref] =
                        if (type == "string" || type == null || type == "time") quoteIfNeeded(value) else value
                }
            }
            if (repeat > 256) col += repeat - 256
        }
    }

    private fun quoteIfNeeded(s: String): String =
        if (s.startsWith("=") || s.startsWith("'") || FormulaEngine.parseNumber(s) != null) "'$s" else s
}

/** Writes a [Workbook] as an OpenDocument spreadsheet. */
object OdsWriter {

    fun write(workbook: Workbook, output: OutputStream, original: ZipPackage? = null) {
        val engine = FormulaEngine(workbook)
        val auto = StringBuilder()
        val cellStyles = LinkedHashMap<CellStyle, String>()
        val columnStyles = LinkedHashMap<Float, String>()
        var needsDate = false
        var needsPercent = false

        fun styleName(st: CellStyle): String = cellStyles.getOrPut(st.copy(sourceXf = null)) { "IwCe${cellStyles.size + 1}" }
        fun columnStyle(w: Float): String = columnStyles.getOrPut(w) { "IwCo${columnStyles.size + 1}" }

        val body = StringBuilder()
        for (sheet in workbook.sheets) {
            body.append("""<table:table table:name="${xmlEscape(sheet.name)}">""")
            val maxCol = maxOf(sheet.usedMaxCol, sheet.columnWidths.keys.maxOrNull() ?: -1)
            for (c in 0..maxOf(0, maxCol)) {
                val w = sheet.columnWidths[c]
                if (w != null) body.append("""<table:table-column table:style-name="${columnStyle(w)}"/>""")
                else body.append("<table:table-column/>")
            }
            val refs = (sheet.cells.keys + sheet.styles.keys).toSortedSet()
            val byRow = refs.groupBy { it.row }
            var nextRow = 0
            for ((row, inRow) in byRow) {
                if (row > nextRow) body.append("""<table:table-row table:number-rows-repeated="${row - nextRow}"><table:table-cell/></table:table-row>""")
                body.append("<table:table-row>")
                var nextCol = 0
                for (ref in inRow) {
                    if (ref.col > nextCol) {
                        val gap = ref.col - nextCol
                        body.append(if (gap == 1) "<table:table-cell/>" else """<table:table-cell table:number-columns-repeated="$gap"/>""")
                    }
                    val st = sheet.styles[ref]
                    val fmt = st?.numFmt
                    val isDate = fmt != null && fmt.contains('d', ignoreCase = true) && fmt.contains('y', ignoreCase = true)
                    val isPercent = fmt != null && fmt.endsWith("%")
                    if (isDate) needsDate = true
                    if (isPercent) needsPercent = true
                    val styleAttr = st?.takeIf { !it.isDefault }?.let { """ table:style-name="${styleName(it)}"""" }.orEmpty()
                    appendCell(body, sheet, ref, engine, styleAttr, isDate, isPercent)
                    nextCol = ref.col + 1
                }
                body.append("</table:table-row>")
                nextRow = row + 1
            }
            if (byRow.isEmpty()) body.append("<table:table-row><table:table-cell/></table:table-row>")
            body.append("</table:table>")
        }

        if (needsDate) auto.append("""<number:date-style style:name="IwNDate"><number:year number:style="long"/><number:text>-</number:text><number:month number:style="long"/><number:text>-</number:text><number:day number:style="long"/></number:date-style>""")
        if (needsPercent) auto.append("""<number:percentage-style style:name="IwNPct"><number:number number:decimal-places="0" number:min-decimal-places="0" number:min-integer-digits="1"/><number:text>%</number:text></number:percentage-style>""")
        for ((w, name) in columnStyles) {
            auto.append("""<style:style style:name="$name" style:family="table-column"><style:table-column-properties style:column-width="${"%.3f".format(java.util.Locale.ROOT, w * 0.2)}cm"/></style:style>""")
        }
        for ((st, name) in cellStyles) auto.append(cellStyleXml(name, st))

        val contentXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><office:document-content ${Odf.ROOT_NS} office:version="1.3">""")
            // Fonts and other declarations from the original file stay valid.
            original?.xml("content.xml")?.let { c ->
                c.child("font-face-decls")?.let { append(XmlWriter.write(it)) }
            }
            append("<office:automatic-styles>").append(auto).append("</office:automatic-styles>")
            append("<office:body><office:spreadsheet>").append(body).append("</office:spreadsheet></office:body></office:document-content>")
        }
        val entries = if (original != null) {
            // settings.xml stores cursor and view positions for sheets that may no longer exist.
            original.entries.filterKeys { it != "settings.xml" }
                .map { (k, v) -> k to if (k == "content.xml") contentXml.toByteArray(Charsets.UTF_8) else v }
                .map { (k, v) -> k to if (k == "META-INF/manifest.xml") String(v, Charsets.UTF_8).replace(Regex("""<manifest:file-entry[^>]*full-path="settings.xml"[^>]*/>"""), "").toByteArray(Charsets.UTF_8) else v }
        } else {
            listOf(
                "mimetype" to Odf.ODS_MIME,
                "META-INF/manifest.xml" to Odf.manifest(Odf.ODS_MIME),
                "content.xml" to contentXml,
                "styles.xml" to STYLES,
                "meta.xml" to Odf.meta(),
            ).map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }
        }
        ZipPackage.write(output, entries)
    }

    private fun cellStyleXml(name: String, st: CellStyle): String = buildString {
        val fmt = st.numFmt
        val data = when {
            fmt != null && fmt.contains('d', true) && fmt.contains('y', true) -> """ style:data-style-name="IwNDate""""
            fmt != null && fmt.endsWith("%") -> """ style:data-style-name="IwNPct""""
            else -> ""
        }
        append("""<style:style style:name="$name" style:family="table-cell" style:parent-style-name="Default"$data>""")
        val cell = buildString {
            st.fill?.let { append(""" fo:background-color="#${"%06X".format(it and 0xFFFFFF)}"""") }
            if (st.border) append(""" fo:border="0.06pt solid #000000"""")
            if (st.wrap) append(""" fo:wrap-option="wrap"""")
        }
        if (cell.isNotEmpty()) append("<style:table-cell-properties$cell/>")
        st.hAlign?.let {
            val a = when (it) { HAlign.LEFT -> "start"; HAlign.CENTER -> "center"; HAlign.RIGHT -> "end" }
            append("""<style:paragraph-properties fo:text-align="$a"/>""")
        }
        val text = buildString {
            if (st.bold) append(""" fo:font-weight="bold"""")
            if (st.italic) append(""" fo:font-style="italic"""")
            if (st.underline) append(""" style:text-underline-style="solid" style:text-underline-width="auto" style:text-underline-color="font-color"""")
            if (st.strike) append(""" style:text-line-through-style="solid"""")
            st.fontColor?.let { append(""" fo:color="#${"%06X".format(it and 0xFFFFFF)}"""") }
            st.fontSizePt?.let { append(""" fo:font-size="${it}pt"""") }
        }
        if (text.isNotEmpty()) append("<style:text-properties$text/>")
        append("</style:style>")
    }

    private fun Double.plain(): String =
        if (this == Math.rint(this) && kotlin.math.abs(this) < 1e15) toLong().toString()
        else java.math.BigDecimal(this).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

    private fun dateOf(serial: Double): String = LocalDate.of(1899, 12, 30).plusDays(kotlin.math.floor(serial).toLong()).toString()

    private fun appendCell(sb: StringBuilder, sheet: Sheet, ref: CellRef, engine: FormulaEngine, style: String, isDate: Boolean, isPercent: Boolean) {
        val raw = sheet.cells[ref].orEmpty()
        fun number(v: Double, extra: String = "") = when {
            isDate -> """ office:value-type="date" office:date-value="${dateOf(v)}"$extra><text:p>${dateOf(v)}</text:p>"""
            isPercent -> """ office:value-type="percentage" office:value="${v.plain()}"$extra><text:p>${Value.formatNumber(v * 100)}%</text:p>"""
            else -> """ office:value-type="float" office:value="${v.plain()}"$extra><text:p>${Value.formatNumber(v)}</text:p>"""
        }
        if (raw.isEmpty()) {
            sb.append("<table:table-cell$style/>")
            return
        }
        if (raw.length > 1 && raw[0] == '=') {
            val f = """ table:formula="${xmlEscape(OdfFormula.toOdf(raw))}""""
            when (val v = engine.value(sheet, ref)) {
                is Value.Num -> sb.append("<table:table-cell$style$f").append(number(v.v)).append("</table:table-cell>")
                is Value.Bool -> sb.append("""<table:table-cell$style$f office:value-type="boolean" office:boolean-value="${v.v}"><text:p>${if (v.v) "TRUE" else "FALSE"}</text:p></table:table-cell>""")
                else -> sb.append("""<table:table-cell$style$f office:value-type="string" office:string-value="${xmlEscape(v.display())}"><text:p>${xmlEscape(v.display())}</text:p></table:table-cell>""")
            }
            return
        }
        when (val v = FormulaEngine.literal(raw)) {
            is Value.Num -> sb.append("<table:table-cell$style").append(number(v.v)).append("</table:table-cell>")
            is Value.Bool -> sb.append("""<table:table-cell$style office:value-type="boolean" office:boolean-value="${v.v}"><text:p>${if (v.v) "TRUE" else "FALSE"}</text:p></table:table-cell>""")
            else -> {
                val text = if (raw.startsWith("'")) raw.substring(1) else raw
                sb.append("""<table:table-cell$style office:value-type="string">""")
                text.split('\n').forEach { sb.append("<text:p>").append(xmlEscape(it)).append("</text:p>") }
                sb.append("</table:table-cell>")
            }
        }
    }

    private val STYLES = """<?xml version="1.0" encoding="UTF-8"?><office:document-styles ${Odf.ROOT_NS} office:version="1.3"><office:styles>""" +
        """<style:default-style style:family="table-cell"><style:text-properties style:font-name="Liberation Sans" fo:font-size="10pt"/></style:default-style>""" +
        """<style:style style:name="Default" style:family="table-cell"/></office:styles></office:document-styles>"""
}
