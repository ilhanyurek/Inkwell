package com.office.core.xlsx

import com.office.core.formula.FormulaEngine
import com.office.core.sheet.CellRef
import com.office.core.sheet.CellStyle
import com.office.core.sheet.HAlign
import com.office.core.sheet.NumberFormat
import com.office.core.sheet.Sheet
import com.office.core.sheet.Value
import com.office.core.sheet.Workbook
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlParser
import com.office.core.xml.XmlWriter
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
        val workbookPath = workbookPart(pkg)
        val workbookXml = pkg.xml(workbookPath) ?: throw IllegalArgumentException("Missing workbook part")
        val rels = pkg.relationships(workbookPath)
        val dir = workbookPath.substringBeforeLast('/', "")

        val shared = rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.target
            ?.let { pkg.xml(it) }?.let(::readSharedStrings) ?: emptyList()
        val styles = rels.values.firstOrNull { it.type.endsWith("/styles") }?.target
            ?.let { pkg.xml(it) }?.let(::readStyles) ?: emptyList()

        val sheets = ArrayList<Sheet>()
        for (s in workbookXml.child("sheets")?.children("sheet").orEmpty()) {
            val name = s.attr("name") ?: "Sheet${sheets.size + 1}"
            val rid = s.attr("r:id") ?: s.attr("id")
            val path = rid?.let { rels[it]?.target } ?: "$dir/worksheets/sheet${sheets.size + 1}.xml"
            val xml = pkg.xml(path) ?: continue
            sheets += readSheet(name, xml, shared, styles).also {
                it.sourcePath = path
                it.sourceRelId = rid
                val (rows, cols) = XlsxViews.frozen(xml.child("sheetViews"))
                it.frozenRows = rows
                it.frozenCols = cols
                it.charts += runCatching { XlsxCharts.read(pkg, path) }.getOrDefault(emptyList())
            }
        }
        if (sheets.isEmpty()) sheets += Sheet("Sheet1")
        return Workbook(sheets)
    }

    internal fun workbookPart(pkg: ZipPackage) =
        pkg.relationships("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "xl/workbook.xml"

    private fun readSharedStrings(root: XmlElement): List<String> =
        root.children("si").map { si ->
            val t = si.child("t")
            if (t != null && si.children("r").isEmpty()) t.text()
            else si.children("r").joinToString("") { it.child("t")?.text().orEmpty() }
        }

    private fun color(el: XmlElement?): Long? {
        val rgb = el?.attr("rgb") ?: return null
        val v = rgb.toLongOrNull(16) ?: return null
        // Excel ignores the alpha byte (openpyxl writes 00), so colours are always opaque.
        return 0xFF000000 or (v and 0xFFFFFF)
    }

    /** One [CellStyle] per cellXfs record. */
    private fun readStyles(root: XmlElement): List<CellStyle> {
        val custom = HashMap<Int, String>()
        root.child("numFmts")?.children("numFmt")?.forEach { f ->
            val id = f.attr("numFmtId")?.toIntOrNull() ?: return@forEach
            custom[id] = f.attr("formatCode").orEmpty()
        }
        val fonts = root.child("fonts")?.children("font").orEmpty()
        val defaultSize = fonts.firstOrNull()?.child("sz")?.attr("val")?.toFloatOrNull()
        val fills = root.child("fills")?.children("fill").orEmpty()
        val borders = root.child("borders")?.children("border").orEmpty()
        fun on(e: XmlElement?, name: String): Boolean {
            val c = e?.child(name) ?: return false
            val v = c.attr("val") ?: return true
            return v != "0" && v != "false" && v != "none"
        }
        return root.child("cellXfs")?.children("xf").orEmpty().mapIndexed { index, xf ->
            val font = xf.attr("fontId")?.toIntOrNull()?.let { fonts.getOrNull(it) }
            val fill = xf.attr("fillId")?.toIntOrNull()?.let { fills.getOrNull(it) }?.child("patternFill")
            val border = xf.attr("borderId")?.toIntOrNull()?.let { borders.getOrNull(it) }
            val numFmtId = xf.attr("numFmtId")?.toIntOrNull() ?: 0
            val code = custom[numFmtId] ?: NumberFormat.builtin(numFmtId)
            val align = xf.child("alignment")
            val size = font?.child("sz")?.attr("val")?.toFloatOrNull()
            CellStyle(
                bold = on(font, "b"),
                italic = on(font, "i"),
                underline = on(font, "u"),
                strike = on(font, "strike"),
                fontColor = color(font?.child("color")),
                fontSizePt = size?.takeIf { it != defaultSize },
                fill = if (fill?.attr("patternType") == "solid") color(fill.child("fgColor")) else null,
                border = border?.elements?.any { it.localName in setOf("left", "right", "top", "bottom") && it.attr("style") != null } ?: false,
                hAlign = when (align?.attr("horizontal")) {
                    "left" -> HAlign.LEFT
                    "center", "centerContinuous" -> HAlign.CENTER
                    "right" -> HAlign.RIGHT
                    else -> null
                },
                wrap = align?.attr("wrapText").let { it == "1" || it == "true" },
                numFmt = code?.takeIf { numFmtId != 0 && it != "General" },
                sourceXf = index,
            )
        }
    }

    private val MODELED = setOf("dimension", "cols", "sheetData")

    private fun readSheet(name: String, root: XmlElement, shared: List<String>, styles: List<CellStyle>): Sheet {
        val sheet = Sheet(name)
        root.elements.filter { it.localName !in MODELED }.forEach { sheet.extraXml += it.localName to XmlWriter.write(it) }
        root.child("cols")?.children("col")?.forEach { col ->
            val min = col.attr("min")?.toIntOrNull() ?: return@forEach
            val max = col.attr("max")?.toIntOrNull() ?: min
            val width = col.attr("width")?.toFloatOrNull() ?: return@forEach
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
                c.attr("s")?.toIntOrNull()?.let { styles.getOrNull(it) }?.let { st ->
                    if (!st.copy(sourceXf = null).isDefault) sheet.styles[ref] = st
                }
                val display: String? = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> c.child("is")?.let { isEl ->
                        isEl.child("t")?.text() ?: isEl.children("r").joinToString("") { it.child("t")?.text().orEmpty() }
                    }
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    "str", "e" -> v
                    else -> v?.let(::normalizeNumber)
                }
                val isText = type == "s" || type == "str" || type == "inlineStr"
                val formulaText = f?.text()?.takeIf { it.isNotBlank() }
                when {
                    formulaText != null -> {
                        sheet.cells[ref] = "=$formulaText"
                        display?.let { sheet.cachedValues[ref] = it }
                    }
                    display != null && display.isNotEmpty() ->
                        sheet.cells[ref] = if (isText) quoteIfNeeded(display) else display
                }
            }
        }
        return sheet
    }

    private fun quoteIfNeeded(s: String): String =
        if (s.startsWith("=") || s.startsWith("'") || FormulaEngine.parseNumber(s) != null) "'$s" else s

    private fun normalizeNumber(s: String): String = s.toDoubleOrNull()?.let { Value.formatNumber(it) } ?: s

    fun formatDate(serial: Double, date1904: Boolean = false): String {
        val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        val days = kotlin.math.floor(serial).toLong()
        val seconds = Math.round((serial - days) * 86400)
        val dt = base.plusDays(days).plusSeconds(seconds)
        return if (seconds == 0L) dt.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        else dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    fun dateToSerial(date: LocalDate): Double =
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date).toDouble()
}

object XlsxWriter {

    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WORKSHEET_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
    private const val WORKSHEET_CT = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"

    /**
     * Writes [workbook]. With [original] (the package it was read from) every part we do not
     * model — charts, drawings, shared strings, themes, defined names — is kept.
     */
    fun write(workbook: Workbook, output: OutputStream, original: ZipPackage? = null) {
        if (original != null && original.contains(XlsxReader.workbookPart(original))) writeInto(workbook, output, original)
        else writeNew(workbook, output)
    }

    // --- styles --------------------------------------------------------------------------------

    /** Collects fonts/fills/borders/number formats/cellXfs for styles that are new or edited. */
    private class StyleRegistry(fontBase: Int, fillBase: Int, borderBase: Int, val xfBase: Int, usedNumFmtIds: Set<Int>) {
        val fonts = LinkedHashMap<String, Int>()
        val fills = LinkedHashMap<String, Int>()
        val borders = LinkedHashMap<String, Int>()
        val numFmts = LinkedHashMap<String, Int>()
        val xfs = LinkedHashMap<String, Int>()
        private var nextFont = fontBase
        private var nextFill = fillBase
        private var nextBorder = borderBase
        private var nextNumFmt = maxOf(164, (usedNumFmtIds.maxOrNull() ?: 0) + 1)

        private fun argb(c: Long) = "%08X".format(c and 0xFFFFFFFFL)

        fun xfFor(st: CellStyle): Int {
            st.sourceXf?.let { return it }
            if (st.isDefault) return 0
            val font = buildString {
                append("<font>")
                if (st.bold) append("<b/>")
                if (st.italic) append("<i/>")
                if (st.strike) append("<strike/>")
                if (st.underline) append("<u/>")
                append("""<sz val="${st.fontSizePt ?: 11f}"/>""")
                st.fontColor?.let { append("""<color rgb="${argb(it)}"/>""") }
                append("""<name val="Calibri"/><family val="2"/></font>""")
            }
            val fontId = fonts.getOrPut(font) { nextFont++ }
            val fillId = st.fill?.let { c ->
                fills.getOrPut("""<fill><patternFill patternType="solid"><fgColor rgb="${argb(c)}"/><bgColor indexed="64"/></patternFill></fill>""") { nextFill++ }
            } ?: 0
            val borderId = if (st.border) {
                val side = """style="thin"><color rgb="FF999999"/>"""
                borders.getOrPut("""<border><left $side</left><right $side</right><top $side</top><bottom $side</bottom><diagonal/></border>""") { nextBorder++ }
            } else 0
            val numFmtId = st.numFmt?.let { code -> NumberFormat.builtinId(code) ?: numFmts.getOrPut(code) { nextNumFmt++ } } ?: 0
            val align = if (st.hAlign != null || st.wrap) {
                val h = st.hAlign?.let { """ horizontal="${it.name.lowercase()}"""" }.orEmpty()
                val w = if (st.wrap) """ wrapText="1"""" else ""
                "<alignment$h$w/>"
            } else ""
            val xf = """<xf numFmtId="$numFmtId" fontId="$fontId" fillId="$fillId" borderId="$borderId" xfId="0"""" +
                (if (numFmtId != 0) """ applyNumberFormat="1"""" else "") +
                (if (fontId != 0) """ applyFont="1"""" else "") +
                (if (fillId != 0) """ applyFill="1"""" else "") +
                (if (borderId != 0) """ applyBorder="1"""" else "") +
                (if (align.isNotEmpty()) """ applyAlignment="1">$align</xf>""" else "/>")
            return xfs.getOrPut(xf) { xfBase + xfs.size }
        }
    }

    // --- sheets ------------------------------------------------------------------------------------

    private val WORKSHEET_ORDER = listOf(
        "sheetPr", "dimension", "sheetViews", "sheetFormatPr", "cols", "sheetData", "sheetCalcPr", "sheetProtection",
        "protectedRanges", "scenarios", "autoFilter", "sortState", "dataConsolidate", "customSheetViews", "mergeCells",
        "phoneticPr", "conditionalFormatting", "dataValidations", "hyperlinks", "printOptions", "pageMargins", "pageSetup",
        "headerFooter", "rowBreaks", "colBreaks", "customProperties", "cellWatches", "ignoredErrors", "smartTags",
        "drawing", "legacyDrawing", "legacyDrawingHF", "picture", "oleObjects", "controls", "webPublishItems",
        "tableParts", "extLst",
    )

    private fun sheetXml(sheet: Sheet, engine: FormulaEngine, styles: StyleRegistry, rootAttrs: Map<String, String>?): String {
        val parts = ArrayList<Pair<String, String>>()
        val maxRow = sheet.usedMaxRow
        val maxCol = sheet.usedMaxCol
        if (maxRow >= 0) parts += "dimension" to """<dimension ref="A1:${CellRef(maxRow, maxOf(0, maxCol))}"/>"""
        if (sheet.columnWidths.isNotEmpty()) {
            parts += "cols" to buildString {
                append("<cols>")
                for ((c, w) in sheet.columnWidths.toSortedMap()) append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""")
                append("</cols>")
            }
        }
        parts += "sheetData" to buildString {
            append("<sheetData>")
            val refs = (sheet.cells.keys + sheet.styles.keys).toSortedSet()
            for ((row, inRow) in refs.groupBy { it.row }) {
                append("""<row r="${row + 1}">""")
                for (ref in inRow) appendCell(this, sheet, ref, engine, styles)
                append("</row>")
            }
            append("</sheetData>")
        }
        parts += sheet.extraXml.map { (k, v) -> if (k == "sheetViews") k to XlsxViews.withFrozen(v, sheet.frozenRows, sheet.frozenCols) else k to v }
        if (sheet.extraXml.none { it.first == "pageMargins" }) {
            parts += "pageMargins" to """<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>"""
        }
        if (sheet.extraXml.none { it.first == "sheetViews" }) parts += "sheetViews" to XlsxViews.withFrozen(null, sheet.frozenRows, sheet.frozenCols)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        sb.append("<worksheet")
        val attrs = rootAttrs ?: mapOf("xmlns" to MAIN, "xmlns:r" to REL)
        for ((k, v) in attrs) sb.append(' ').append(k).append("=\"").append(xmlEscape(v)).append('"')
        sb.append('>')
        parts.sortedBy { WORKSHEET_ORDER.indexOf(it.first).let { i -> if (i < 0) WORKSHEET_ORDER.size else i } }.forEach { sb.append(it.second) }
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun appendCell(sb: StringBuilder, sheet: Sheet, ref: CellRef, engine: FormulaEngine, styles: StyleRegistry) {
        val raw = sheet.cells[ref].orEmpty()
        val xf = sheet.styles[ref]?.let { styles.xfFor(it) } ?: 0
        val s = if (xf != 0) """ s="$xf"""" else ""
        if (raw.isEmpty()) {
            sb.append("""<c r="$ref"$s/>""")
            return
        }
        if (raw.length > 1 && raw[0] == '=') {
            val formula = xmlEscape(raw.substring(1))
            when (val v = engine.value(sheet, ref)) {
                is Value.Num -> sb.append("""<c r="$ref"$s><f>$formula</f><v>${v.v.toPlain()}</v></c>""")
                is Value.Bool -> sb.append("""<c r="$ref"$s t="b"><f>$formula</f><v>${if (v.v) 1 else 0}</v></c>""")
                is Value.Err -> sb.append("""<c r="$ref"$s t="e"><f>$formula</f><v>${xmlEscape(v.code)}</v></c>""")
                else -> sb.append("""<c r="$ref"$s t="str"><f>$formula</f><v>${xmlEscape(v.display())}</v></c>""")
            }
            return
        }
        when (val v = FormulaEngine.literal(raw)) {
            is Value.Num -> sb.append("""<c r="$ref"$s><v>${v.v.toPlain()}</v></c>""")
            is Value.Bool -> sb.append("""<c r="$ref"$s t="b"><v>${if (v.v) 1 else 0}</v></c>""")
            else -> {
                val text = if (raw.startsWith("'")) raw.substring(1) else raw
                sb.append("""<c r="$ref"$s t="inlineStr"><is><t xml:space="preserve">${xmlEscape(text)}</t></is></c>""")
            }
        }
    }

    private fun Double.toPlain(): String =
        if (this == Math.rint(this) && kotlin.math.abs(this) < 1e15) toLong().toString()
        else java.math.BigDecimal(this).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

    private fun sanitizeSheetName(name: String) = name.filter { it !in "[]:*?/\\" }.take(31).trim('\'')

    private fun uniqueNames(workbook: Workbook): List<String> {
        val used = HashSet<String>()
        return workbook.sheets.mapIndexed { i, s ->
            var name = sanitizeSheetName(s.name).ifEmpty { "Sheet${i + 1}" }
            while (!used.add(name.lowercase())) name = "${name.take(28)}_${i + 1}"
            name
        }
    }

    // --- new workbook ----------------------------------------------------------------------------------

    private fun writeNew(workbook: Workbook, output: OutputStream) {
        val engine = FormulaEngine(workbook)
        // Reset source indices: they refer to a package we are not writing into.
        val sheets = workbook.sheets.map { sh -> sh.copy().also { c -> c.styles.replaceAll { _, st -> st.copy(sourceXf = null) } } }
        val styles = StyleRegistry(1, 2, 1, 1, emptySet())
        val sheetXmls = sheets.map { sheetXml(it, engine, styles, null) }
        val names = uniqueNames(workbook)
        val entries = ArrayList<Pair<String, String>>()
        entries += "[Content_Types].xml" to buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            for (i in sheets.indices) append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="$WORKSHEET_CT"/>""")
            append("</Types>")
        }
        entries += "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        entries += "xl/workbook.xml" to buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="$MAIN" xmlns:r="$REL"><bookViews><workbookView/></bookViews><sheets>""")
            names.forEachIndexed { i, n -> append("""<sheet name="${xmlEscape(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
            append("""</sheets><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""")
        }
        entries += "xl/_rels/workbook.xml.rels" to buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            for (i in sheets.indices) append("""<Relationship Id="rId${i + 1}" Type="$WORKSHEET_TYPE" Target="worksheets/sheet${i + 1}.xml"/>""")
            append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        }
        entries += "xl/styles.xml" to newStylesXml(styles)
        sheetXmls.forEachIndexed { i, x -> entries += "xl/worksheets/sheet${i + 1}.xml" to x }
        ZipPackage.write(output, entries.map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) })
    }

    private fun newStylesXml(r: StyleRegistry): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="$MAIN">""")
        if (r.numFmts.isNotEmpty()) {
            append("""<numFmts count="${r.numFmts.size}">""")
            r.numFmts.forEach { (code, id) -> append("""<numFmt numFmtId="$id" formatCode="${xmlEscape(code)}"/>""") }
            append("</numFmts>")
        }
        append("""<fonts count="${1 + r.fonts.size}"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font>""")
        r.fonts.keys.forEach { append(it) }
        append("""</fonts><fills count="${2 + r.fills.size}"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""")
        r.fills.keys.forEach { append(it) }
        append("""</fills><borders count="${1 + r.borders.size}"><border><left/><right/><top/><bottom/><diagonal/></border>""")
        r.borders.keys.forEach { append(it) }
        append("""</borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")
        append("""<cellXfs count="${1 + r.xfs.size}"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""")
        r.xfs.keys.forEach { append(it) }
        append("""</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
    }

    // --- editing an existing package -----------------------------------------------------------------

    private fun writeInto(workbook: Workbook, output: OutputStream, pkg: ZipPackage) {
        val engine = FormulaEngine(workbook)
        val wbPath = XlsxReader.workbookPart(pkg)
        val wbDir = wbPath.substringBeforeLast('/', "")
        val wbRelsPath = (if (wbDir.isEmpty()) "" else "$wbDir/") + "_rels/" + wbPath.substringAfterLast('/') + ".rels"
        val rels = pkg.relationships(wbPath)
        val replaced = LinkedHashMap<String, ByteArray?>()   // null = delete
        var contentTypes = String(pkg.bytes("[Content_Types].xml") ?: ByteArray(0), Charsets.UTF_8)
        var relsText = String(pkg.bytes(wbRelsPath) ?: ByteArray(0), Charsets.UTF_8)

        // Styles: keep the original stylesheet and append records for new or edited styles.
        val stylesPath = rels.values.firstOrNull { it.type.endsWith("/styles") }?.target
        val stylesText = stylesPath?.let { pkg.bytes(it) }?.let { String(it, Charsets.UTF_8) }
        val stylesRoot = stylesText?.let { XmlParser.parse(it) }
        val registry = StyleRegistry(
            fontBase = stylesRoot?.child("fonts")?.children("font")?.size ?: 1,
            fillBase = stylesRoot?.child("fills")?.children("fill")?.size ?: 2,
            borderBase = stylesRoot?.child("borders")?.children("border")?.size ?: 1,
            xfBase = stylesRoot?.child("cellXfs")?.children("xf")?.size ?: 1,
            usedNumFmtIds = stylesRoot?.child("numFmts")?.children("numFmt")?.mapNotNull { it.attr("numFmtId")?.toIntOrNull() }?.toSet() ?: emptySet(),
        )

        // Worksheets: existing ones keep their part and relationship; new ones get fresh parts.
        val names = uniqueNames(workbook)
        val sheetEntries = ArrayList<Triple<String, String, Sheet>>() // relId, path, sheet
        var n = 1
        for (sheet in workbook.sheets) {
            val path = sheet.sourcePath?.takeIf { p -> pkg.contains(p) && sheet.sourceRelId?.let { rels[it] }?.target == p }
            if (path != null) {
                sheetEntries += Triple(sheet.sourceRelId!!, path, sheet)
            } else {
                var newPath: String
                do { newPath = (if (wbDir.isEmpty()) "" else "$wbDir/") + "worksheets/inkwell${n++}.xml" } while (pkg.contains(newPath))
                val relId = "rIdInkwell${n}"
                sheetEntries += Triple(relId, newPath, sheet)
                relsText = relsText.replace("</Relationships>", """<Relationship Id="$relId" Type="$WORKSHEET_TYPE" Target="${newPath.removePrefix("$wbDir/")}"/></Relationships>""")
                contentTypes = contentTypes.replace("</Types>", """<Override PartName="/$newPath" ContentType="$WORKSHEET_CT"/></Types>""")
            }
        }
        for ((_, path, sheet) in sheetEntries) {
            val rootAttrs = pkg.xml(path)?.attributes
            replaced[path] = sheetXml(sheet, engine, registry, rootAttrs).toByteArray(Charsets.UTF_8)
        }
        // Deleted sheets: drop their part and relationship.
        val keptRelIds = sheetEntries.map { it.first }.toSet()
        rels.values.filter { it.type == WORKSHEET_TYPE || it.type.endsWith("/worksheet") }.filter { it.id !in keptRelIds }.forEach { r ->
            replaced[r.target] = null
            relsText = relsText.replace(Regex("""<Relationship[^>]*Id="${Regex.escape(r.id)}"[^>]*/>"""), "")
            contentTypes = contentTypes.replace(Regex("""<Override[^>]*PartName="/${Regex.escape(r.target)}"[^>]*/>"""), "")
        }
        // The calculation chain lists formula cells; it is stale after edits and Excel rebuilds it.
        rels.values.firstOrNull { it.type.endsWith("/calcChain") }?.let { r ->
            replaced[r.target] = null
            relsText = relsText.replace(Regex("""<Relationship[^>]*Id="${Regex.escape(r.id)}"[^>]*/>"""), "")
            contentTypes = contentTypes.replace(Regex("""<Override[^>]*PartName="/${Regex.escape(r.target)}"[^>]*/>"""), "")
        }

        // Workbook: rewrite the sheet list and ask Excel to recalculate on open.
        var wbText = String(pkg.bytes(wbPath)!!, Charsets.UTF_8)
        val wbRoot = XmlParser.parse(wbText)
        val prefix = wbRoot.name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
        val relPrefix = wbRoot.attributes.entries.firstOrNull { it.key.startsWith("xmlns:") && it.value == REL }?.key?.removePrefix("xmlns:") ?: "r"
        val oldSheets = wbRoot.child("sheets")?.children("sheet")?.associateBy { it.attr("r:id") ?: it.attr("id") }.orEmpty()
        var nextId = (oldSheets.values.mapNotNull { it.attr("sheetId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val rootDeclaresRel = wbRoot.attributes.any { it.key.startsWith("xmlns:") && it.value == REL }
        val sheetsXml = buildString {
            append("<${prefix}sheets>")
            sheetEntries.forEachIndexed { i, (relId, _, _) ->
                val old = oldSheets[relId]
                if (old != null) {
                    // Keep every original attribute (state, namespace declarations…) and only rename.
                    append('<').append(old.name)
                    for ((k, v) in old.attributes) {
                        val value = if (k == "name") names[i] else v
                        append(' ').append(k).append("=\"").append(xmlEscape(value)).append('"')
                    }
                    append("/>")
                } else {
                    val ns = if (rootDeclaresRel) "" else """ xmlns:$relPrefix="$REL""""
                    append("""<${prefix}sheet$ns name="${xmlEscape(names[i])}" sheetId="${nextId++}" $relPrefix:id="$relId"/>""")
                }
            }
            append("</${prefix}sheets>")
        }
        wbText = wbText.replace(Regex("""<${Regex.escape(prefix)}sheets>.*?</${Regex.escape(prefix)}sheets>""", RegexOption.DOT_MATCHES_ALL), Regex.escapeReplacement(sheetsXml))
        wbText = if (Regex("""<${Regex.escape(prefix)}calcPr\b""").containsMatchIn(wbText)) {
            wbText.replace(Regex("""<${Regex.escape(prefix)}calcPr\b([^>]*?)(\s*fullCalcOnLoad="[^"]*")?(\s*/?>)""")) { m ->
                "<${prefix}calcPr${m.groupValues[1]} fullCalcOnLoad=\"1\"${m.groupValues[3]}"
            }
        } else {
            val calc = "<${prefix}calcPr fullCalcOnLoad=\"1\"/>"
            val after = listOf("definedNames", "externalReferences", "functionGroups", "sheets")
                .firstNotNullOfOrNull { tag -> Regex("""</${Regex.escape(prefix)}$tag>""").find(wbText) }
            if (after != null) wbText.substring(0, after.range.last + 1) + calc + wbText.substring(after.range.last + 1) else wbText
        }
        replaced[wbPath] = wbText.toByteArray(Charsets.UTF_8)

        // Append new style records.
        if (stylesPath != null && stylesText != null) {
            replaced[stylesPath] = appendStyles(stylesText, stylesRoot!!, registry).toByteArray(Charsets.UTF_8)
        }
        replaced[wbRelsPath] = relsText.toByteArray(Charsets.UTF_8)
        replaced["[Content_Types].xml"] = contentTypes.toByteArray(Charsets.UTF_8)

        val entries = ArrayList<Pair<String, ByteArray>>()
        for ((k, v) in pkg.entries) {
            if (replaced.containsKey(k)) replaced[k]?.let { entries += k to it } else entries += k to v
        }
        for ((k, v) in replaced) if (!pkg.contains(k) && v != null) entries += k to v
        ZipPackage.write(output, entries)
    }

    private fun appendStyles(text: String, root: XmlElement, r: StyleRegistry): String {
        val p = root.name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
        var out = text
        fun addTo(tag: String, items: Collection<String>, total: Int) {
            if (items.isEmpty()) return
            val prefixed = items.joinToString("") { prefixTags(it, p) }
            val close = "</$p$tag>"
            val idx = out.indexOf(close)
            if (idx >= 0) {
                out = out.substring(0, idx) + prefixed + out.substring(idx)
                out = out.replace(Regex("""<${Regex.escape(p)}$tag\b([^>]*?)count="\d+""""), "<$p$tag$1count=\"$total\"")
            }
        }
        if (r.numFmts.isNotEmpty()) {
            val fmts = r.numFmts.entries.joinToString("") { (code, id) -> """<${p}numFmt numFmtId="$id" formatCode="${xmlEscape(code)}"/>""" }
            val existing = root.child("numFmts")
            if (existing != null) {
                val idx = out.indexOf("</${p}numFmts>")
                out = out.substring(0, idx) + fmts + out.substring(idx)
                out = out.replace(Regex("""<${Regex.escape(p)}numFmts\b([^>]*?)count="\d+""""), "<${p}numFmts$1count=\"${existing.children("numFmt").size + r.numFmts.size}\"")
            } else {
                val fontsAt = out.indexOf("<${p}fonts")
                out = out.substring(0, fontsAt) + """<${p}numFmts count="${r.numFmts.size}">$fmts</${p}numFmts>""" + out.substring(fontsAt)
            }
        }
        addTo("fonts", r.fonts.keys, (root.child("fonts")?.children("font")?.size ?: 0) + r.fonts.size)
        addTo("fills", r.fills.keys, (root.child("fills")?.children("fill")?.size ?: 0) + r.fills.size)
        addTo("borders", r.borders.keys, (root.child("borders")?.children("border")?.size ?: 0) + r.borders.size)
        addTo("cellXfs", r.xfs.keys, (root.child("cellXfs")?.children("xf")?.size ?: 0) + r.xfs.size)
        return out
    }

    /** Adds the stylesheet's namespace prefix (if it uses one) to our unprefixed markup. */
    private fun prefixTags(xml: String, p: String): String =
        if (p.isEmpty()) xml else xml.replace(Regex("<(/?)([a-zA-Z])")) { "<${it.groupValues[1]}$p${it.groupValues[2]}" }
}
