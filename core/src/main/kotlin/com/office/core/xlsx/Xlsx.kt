package com.office.core.xlsx

import com.office.core.formula.FormulaEngine
import com.office.core.sheet.CellRef
import com.office.core.sheet.Sheet
import com.office.core.sheet.Value
import com.office.core.sheet.Workbook
import com.office.core.xml.XmlElement
import com.office.core.xml.xmlEscape
import com.office.core.zip.ZipPackage
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object XlsxReader {

    fun read(input: InputStream): Workbook = read(ZipPackage.read(input))

    fun read(pkg: ZipPackage): Workbook {
        val workbookPath = pkg.relationships("").values
            .firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "xl/workbook.xml"
        val workbookXml = pkg.xml(workbookPath) ?: throw IllegalArgumentException("Missing workbook part")
        val rels = pkg.relationships(workbookPath)
        val dir = workbookPath.substringBeforeLast('/', "")

        val shared = rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.target
            ?.let { pkg.xml(it) }?.let(::readSharedStrings) ?: emptyList()
        val dateStyles = rels.values.firstOrNull { it.type.endsWith("/styles") }?.target
            ?.let { pkg.xml(it) }?.let(::readDateStyles) ?: emptySet()
        val date1904 = workbookXml.child("workbookPr")?.attr("date1904").let { it == "1" || it == "true" }

        val sheets = ArrayList<Sheet>()
        for (s in workbookXml.child("sheets")?.children("sheet").orEmpty()) {
            val name = s.attr("name") ?: "Sheet${sheets.size + 1}"
            val rid = s.attr("r:id") ?: s.attr("id")
            val path = rid?.let { rels[it]?.target } ?: "$dir/worksheets/sheet${sheets.size + 1}.xml"
            val xml = pkg.xml(path) ?: continue
            sheets += readSheet(name, xml, shared, dateStyles, date1904)
        }
        if (sheets.isEmpty()) sheets += Sheet("Sheet1")
        return Workbook(sheets)
    }

    private fun readSharedStrings(root: XmlElement): List<String> =
        root.children("si").map { si ->
            // Either a plain <t> or rich text runs <r><t/></r>; ignore phonetic runs.
            val t = si.child("t")
            if (t != null && si.children("r").isEmpty()) t.text()
            else si.children("r").joinToString("") { it.child("t")?.text().orEmpty() }
        }

    /** Indices into cellXfs whose number format is a date or time. */
    private fun readDateStyles(root: XmlElement): Set<Int> {
        val custom = HashMap<Int, String>()
        root.child("numFmts")?.children("numFmt")?.forEach { f ->
            val id = f.attr("numFmtId")?.toIntOrNull() ?: return@forEach
            custom[id] = f.attr("formatCode").orEmpty()
        }
        val out = HashSet<Int>()
        root.child("cellXfs")?.children("xf")?.forEachIndexed { i, xf ->
            val id = xf.attr("numFmtId")?.toIntOrNull() ?: return@forEachIndexed
            if (id in 14..22 || id in 45..47 || custom[id]?.let(::isDateFormat) == true) out += i
        }
        return out
    }

    private fun isDateFormat(code: String): Boolean {
        val stripped = code.replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\."), "").lowercase()
        return stripped.any { it == 'd' || it == 'y' } || (stripped.contains('h') && stripped.contains('s'))
    }

    private fun readSheet(
        name: String,
        root: XmlElement,
        shared: List<String>,
        dateStyles: Set<Int>,
        date1904: Boolean,
    ): Sheet {
        val sheet = Sheet(name)
        root.child("cols")?.children("col")?.forEach { col ->
            val min = col.attr("min")?.toIntOrNull() ?: return@forEach
            val max = col.attr("max")?.toIntOrNull() ?: min
            val width = col.attr("width")?.toFloatOrNull() ?: return@forEach
            // Whole-sheet column definitions (max=16384) would be wasteful; cap them.
            for (c in min..minOf(max, min + 255)) sheet.columnWidths[c - 1] = width
        }
        val sheetData = root.child("sheetData") ?: return sheet
        var rowIndex = -1
        for (row in sheetData.children("row")) {
            rowIndex = row.attr("r")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1)
            var colIndex = -1
            for (c in row.children("c")) {
                val ref = c.attr("r")?.let(CellRef::parse) ?: CellRef(rowIndex, colIndex + 1)
                colIndex = ref.col
                val type = c.attr("t")
                val v = c.child("v")?.text()
                val f = c.child("f")
                val style = c.attr("s")?.toIntOrNull()
                val display: String? = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> c.child("is")?.let { isEl ->
                        isEl.child("t")?.text() ?: isEl.children("r").joinToString("") { it.child("t")?.text().orEmpty() }
                    }
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    "str", "e" -> v
                    else -> v?.let { num ->
                        if (style != null && style in dateStyles) num.toDoubleOrNull()?.let { formatDate(it, date1904) } ?: num
                        else normalizeNumber(num)
                    }
                }
                val formulaText = f?.text()?.takeIf { it.isNotBlank() }
                when {
                    formulaText != null -> {
                        sheet.cells[ref] = "=$formulaText"
                        display?.let { sheet.cachedValues[ref] = it }
                    }
                    f != null && display != null -> {
                        // Shared-formula followers have an empty <f>; keep the cached value as a literal.
                        sheet.cells[ref] = if (type == "s" || type == "str" || type == "inlineStr") quoteIfNeeded(display) else display
                    }
                    display != null && display.isNotEmpty() ->
                        sheet.cells[ref] = if (type == "s" || type == "str" || type == "inlineStr") quoteIfNeeded(display) else display
                }
            }
        }
        return sheet
    }

    /** Strings that would be misread as numbers or formulas keep a leading apostrophe. */
    private fun quoteIfNeeded(s: String): String =
        if (s.startsWith("=") || s.startsWith("'") || FormulaEngine.parseNumber(s) != null) "'$s" else s

    private fun normalizeNumber(s: String): String {
        val d = s.toDoubleOrNull() ?: return s
        return Value.formatNumber(d)
    }

    fun formatDate(serial: Double, date1904: Boolean = false): String {
        val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        val days = kotlin.math.floor(serial).toLong()
        val seconds = Math.round((serial - days) * 86400)
        val dt = base.plusDays(days).plusSeconds(seconds)
        return if (seconds == 0L) dt.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        else if (days == 0L && !date1904) dt.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        else dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    fun dateToSerial(date: LocalDate): Double =
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date).toDouble()
}

object XlsxWriter {

    fun write(workbook: Workbook, output: OutputStream) {
        val engine = FormulaEngine(workbook)
        val entries = ArrayList<Pair<String, String>>()
        entries += "[Content_Types].xml" to contentTypes(workbook.sheets.size)
        entries += "_rels/.rels" to ROOT_RELS
        entries += "xl/workbook.xml" to workbookXml(workbook)
        entries += "xl/_rels/workbook.xml.rels" to workbookRels(workbook.sheets.size)
        entries += "xl/styles.xml" to STYLES
        workbook.sheets.forEachIndexed { i, sheet ->
            entries += "xl/worksheets/sheet${i + 1}.xml" to sheetXml(sheet, engine)
        }
        ZipPackage.write(output, entries.map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) })
    }

    private fun sheetXml(sheet: Sheet, engine: FormulaEngine): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        if (sheet.maxRow >= 0) sb.append("""<dimension ref="A1:${CellRef(sheet.maxRow, maxOf(0, sheet.maxCol))}"/>""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"/></sheetViews>""")
        sb.append("""<sheetFormatPr defaultRowHeight="15"/>""")
        if (sheet.columnWidths.isNotEmpty()) {
            sb.append("<cols>")
            for ((c, w) in sheet.columnWidths.toSortedMap()) {
                sb.append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        val byRow = sheet.cells.entries.groupBy { it.key.row }.toSortedMap()
        for ((row, cells) in byRow) {
            sb.append("""<row r="${row + 1}">""")
            for ((ref, raw) in cells.sortedBy { it.key.col }) {
                appendCell(sb, sheet, ref, raw, engine)
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun appendCell(sb: StringBuilder, sheet: Sheet, ref: CellRef, raw: String, engine: FormulaEngine) {
        if (raw.length > 1 && raw[0] == '=') {
            val formula = xmlEscape(raw.substring(1))
            when (val v = engine.value(sheet, ref)) {
                is Value.Num -> sb.append("""<c r="$ref"><f>$formula</f><v>${v.v.toBigDecimalString()}</v></c>""")
                is Value.Bool -> sb.append("""<c r="$ref" t="b"><f>$formula</f><v>${if (v.v) 1 else 0}</v></c>""")
                is Value.Err -> sb.append("""<c r="$ref" t="e"><f>$formula</f><v>${xmlEscape(v.code)}</v></c>""")
                else -> sb.append("""<c r="$ref" t="str"><f>$formula</f><v>${xmlEscape(v.display())}</v></c>""")
            }
            return
        }
        when (val v = FormulaEngine.literal(raw)) {
            is Value.Num -> sb.append("""<c r="$ref"><v>${v.v.toBigDecimalString()}</v></c>""")
            is Value.Bool -> sb.append("""<c r="$ref" t="b"><v>${if (v.v) 1 else 0}</v></c>""")
            else -> {
                val text = if (raw.startsWith("'")) raw.substring(1) else raw
                sb.append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(text)}</t></is></c>""")
            }
        }
    }

    private fun Double.toBigDecimalString(): String =
        if (this == Math.rint(this) && kotlin.math.abs(this) < 1e15) toLong().toString()
        else java.math.BigDecimal(this).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

    private fun workbookXml(workbook: Workbook): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("""<bookViews><workbookView/></bookViews><sheets>""")
        val used = HashSet<String>()
        workbook.sheets.forEachIndexed { i, s ->
            var name = sanitizeSheetName(s.name).ifEmpty { "Sheet${i + 1}" }
            while (!used.add(name.lowercase())) name = "${name.take(28)}_${i + 1}"
            sb.append("""<sheet name="${xmlEscape(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        sb.append("</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>")
        return sb.toString()
    }

    private fun sanitizeSheetName(name: String) =
        name.filter { it !in "[]:*?/\\" }.take(31).trim('\'')

    private fun workbookRels(count: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) {
            sb.append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        sb.append("""<Relationship Id="rId${count + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun contentTypes(count: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        sb.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..count) {
            sb.append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
