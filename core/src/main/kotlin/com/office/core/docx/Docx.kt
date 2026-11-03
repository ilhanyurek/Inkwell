package com.office.core.docx

import com.office.core.text.Alignment
import com.office.core.text.ListType
import com.office.core.text.Paragraph
import com.office.core.text.ParagraphStyle
import com.office.core.text.TextDocument
import com.office.core.text.TextRun
import com.office.core.xml.XmlElement
import com.office.core.xml.xmlEscape
import com.office.core.zip.ZipPackage
import java.io.InputStream
import java.io.OutputStream

object DocxReader {

    fun read(input: InputStream): TextDocument = read(ZipPackage.read(input))

    fun read(pkg: ZipPackage): TextDocument {
        val mainPath = findMainPart(pkg)
        val document = pkg.xml(mainPath) ?: throw IllegalArgumentException("Missing $mainPath")
        val styles = readHeadingStyles(pkg)
        val numbering = readNumbering(pkg)
        val body = document.child("body") ?: return TextDocument.empty()

        val ctx = Ctx(styles, numbering)
        val paragraphs = ArrayList<Paragraph>()
        readBlocks(body, ctx, paragraphs)
        if (paragraphs.isEmpty()) paragraphs += Paragraph(emptyList())
        return TextDocument(paragraphs, lossyImport = ctx.lossy)
    }

    private class Ctx(
        val headingStyles: Map<String, ParagraphStyle>,
        val numbering: Map<String, ListType>,
    ) {
        var lossy = false
    }

    private fun findMainPart(pkg: ZipPackage): String {
        val rels = pkg.relationships("")
        val main = rels.values.firstOrNull { it.type.endsWith("/officeDocument") }?.target
        return main ?: "word/document.xml"
    }

    private fun readBlocks(container: XmlElement, ctx: Ctx, out: MutableList<Paragraph>) {
        for (el in container.elements) {
            when (el.localName) {
                "p" -> out += readParagraph(el, ctx)
                "tbl" -> {
                    ctx.lossy = true
                    for (row in el.children("tr")) {
                        val cells = row.children("tc").map { tc ->
                            val inner = ArrayList<Paragraph>()
                            readBlocks(tc, ctx, inner)
                            inner.joinToString(" ") { it.text }.trim()
                        }
                        out += Paragraph.plain(cells.joinToString("\t"))
                    }
                }
                "sdt" -> el.child("sdtContent")?.let { readBlocks(it, ctx, out) }
                "customXml", "ins", "smartTag" -> readBlocks(el, ctx, out)
            }
        }
    }

    private fun readParagraph(p: XmlElement, ctx: Ctx): Paragraph {
        val pPr = p.child("pPr")
        val styleId = pPr?.child("pStyle")?.attr("val")
        val style = styleId?.let { ctx.headingStyles[it] ?: headingFromId(it) } ?: ParagraphStyle.NORMAL
        val list = pPr?.child("numPr")?.let { numPr ->
            val numId = numPr.child("numId")?.attr("val")
            if (numId == null || numId == "0") ListType.NONE
            else ctx.numbering[numId] ?: ListType.BULLET
        } ?: if (styleId != null && styleId.contains("List", ignoreCase = true)) {
            if (styleId.contains("Number", ignoreCase = true)) ListType.NUMBERED else ListType.BULLET
        } else ListType.NONE
        val alignment = when (pPr?.child("jc")?.attr("val")) {
            "center" -> Alignment.CENTER
            "right", "end" -> Alignment.END
            "both", "distribute" -> Alignment.JUSTIFY
            else -> Alignment.START
        }
        val runs = ArrayList<TextRun>()
        collectRuns(p, ctx, runs)
        return Paragraph(runs, style, list, alignment).normalized()
    }

    private fun collectRuns(container: XmlElement, ctx: Ctx, out: MutableList<TextRun>) {
        for (el in container.elements) {
            when (el.localName) {
                "r" -> readRun(el, ctx, out)
                "hyperlink", "ins", "smartTag", "fldSimple", "customXml", "bdo", "dir" -> collectRuns(el, ctx, out)
                "sdt" -> el.child("sdtContent")?.let { collectRuns(it, ctx, out) }
                "oMath", "oMathPara" -> {
                    ctx.lossy = true
                    out += TextRun(el.text())
                }
            }
        }
    }

    private fun readRun(r: XmlElement, ctx: Ctx, out: MutableList<TextRun>) {
        val rPr = r.child("rPr")
        fun flag(name: String): Boolean {
            val e = rPr?.child(name) ?: return false
            val v = e.attr("val") ?: return true
            return v != "0" && v != "false" && v != "none"
        }
        val bold = flag("b")
        val italic = flag("i")
        val underline = rPr?.child("u")?.let { it.attr("val") != "none" } ?: false
        val strike = flag("strike") || flag("dstrike")
        val size = rPr?.child("sz")?.attr("val")?.toFloatOrNull()?.div(2f)
        val sb = StringBuilder()
        for (c in r.elements) {
            when (c.localName) {
                "t" -> sb.append(c.text())
                "tab", "ptab" -> sb.append('\t')
                "br", "cr" -> sb.append('\n')
                "noBreakHyphen" -> sb.append('‑')
                "softHyphen" -> sb.append('­')
                "sym" -> c.attr("char")?.toIntOrNull(16)?.let { sb.appendCodePoint(it) }
                "drawing", "pict", "object" -> {
                    ctx.lossy = true
                    sb.append('￼')
                }
            }
        }
        if (sb.isNotEmpty()) out += TextRun(sb.toString(), bold, italic, underline, strike, size)
    }

    private fun headingFromId(id: String): ParagraphStyle? {
        val lower = id.lowercase().replace(" ", "")
        return when {
            lower == "title" -> ParagraphStyle.TITLE
            lower == "heading1" || lower == "baslik1" || lower == "başlık1" -> ParagraphStyle.HEADING1
            lower == "heading2" || lower == "baslik2" || lower == "başlık2" -> ParagraphStyle.HEADING2
            lower.startsWith("heading") || lower.startsWith("baslik") || lower.startsWith("başlık") -> ParagraphStyle.HEADING3
            lower == "quote" || lower == "intensequote" -> ParagraphStyle.QUOTE
            else -> null
        }
    }

    /** Maps styleId → heading style using the style's name and outline level (locale independent). */
    private fun readHeadingStyles(pkg: ZipPackage): Map<String, ParagraphStyle> {
        val root = pkg.xml("word/styles.xml") ?: return emptyMap()
        val out = HashMap<String, ParagraphStyle>()
        for (s in root.children("style")) {
            if (s.attr("type") != "paragraph") continue
            val id = s.attr("styleId") ?: continue
            val name = s.child("name")?.attr("val")?.lowercase().orEmpty()
            val outline = s.path("pPr", "outlineLvl")?.attr("val")?.toIntOrNull()
            val style = when {
                name == "title" -> ParagraphStyle.TITLE
                name == "quote" || name == "intense quote" -> ParagraphStyle.QUOTE
                name == "heading 1" || outline == 0 -> ParagraphStyle.HEADING1
                name == "heading 2" || outline == 1 -> ParagraphStyle.HEADING2
                name.startsWith("heading ") || (outline != null && outline in 2..8) -> ParagraphStyle.HEADING3
                else -> headingFromId(id)
            }
            if (style != null) out[id] = style
        }
        return out
    }

    /** numId → list type, using the level-0 number format of the referenced abstract numbering. */
    private fun readNumbering(pkg: ZipPackage): Map<String, ListType> {
        val root = pkg.xml("word/numbering.xml") ?: return emptyMap()
        val abstractFormats = HashMap<String, ListType>()
        for (a in root.children("abstractNum")) {
            val id = a.attr("abstractNumId") ?: continue
            val lvl0 = a.children("lvl").firstOrNull { it.attr("ilvl") == "0" } ?: a.children("lvl").firstOrNull()
            val fmt = lvl0?.child("numFmt")?.attr("val")
            abstractFormats[id] = if (fmt == null || fmt == "bullet" || fmt == "none") ListType.BULLET else ListType.NUMBERED
        }
        val out = HashMap<String, ListType>()
        for (n in root.children("num")) {
            val numId = n.attr("numId") ?: continue
            val abs = n.child("abstractNumId")?.attr("val") ?: continue
            out[numId] = abstractFormats[abs] ?: ListType.BULLET
        }
        return out
    }
}

object DocxWriter {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val BULLET_NUM_ID = 1
    private const val DECIMAL_NUM_ID = 2

    fun write(doc: TextDocument, output: OutputStream) {
        val entries = listOf(
            "[Content_Types].xml" to contentTypes(),
            "_rels/.rels" to rootRels(),
            "word/document.xml" to document(doc),
            "word/styles.xml" to styles(),
            "word/numbering.xml" to numbering(),
            "word/_rels/document.xml.rels" to documentRels(),
            "docProps/app.xml" to appProps(),
        ).map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }
        ZipPackage.write(output, entries)
    }

    private fun document(doc: TextDocument): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="$W"><w:body>""")
        // Lists restart numbering after an interruption; we model that by allocating a fresh num per run of items.
        for (p in doc.paragraphs) {
            sb.append("<w:p>")
            val styleId = when (p.style) {
                ParagraphStyle.NORMAL -> null
                ParagraphStyle.TITLE -> "Title"
                ParagraphStyle.HEADING1 -> "Heading1"
                ParagraphStyle.HEADING2 -> "Heading2"
                ParagraphStyle.HEADING3 -> "Heading3"
                ParagraphStyle.QUOTE -> "Quote"
            }
            val jc = when (p.alignment) {
                Alignment.START -> null
                Alignment.CENTER -> "center"
                Alignment.END -> "right"
                Alignment.JUSTIFY -> "both"
            }
            if (styleId != null || p.list != ListType.NONE || jc != null) {
                sb.append("<w:pPr>")
                if (styleId != null) sb.append("""<w:pStyle w:val="$styleId"/>""")
                if (p.list != ListType.NONE) {
                    val numId = if (p.list == ListType.BULLET) BULLET_NUM_ID else DECIMAL_NUM_ID
                    sb.append("""<w:numPr><w:ilvl w:val="0"/><w:numId w:val="$numId"/></w:numPr>""")
                }
                if (jc != null) sb.append("""<w:jc w:val="$jc"/>""")
                sb.append("</w:pPr>")
            }
            for (r in p.runs) appendRun(sb, r)
            sb.append("</w:p>")
        }
        sb.append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""")
        sb.append("""<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun appendRun(sb: StringBuilder, r: TextRun) {
        sb.append("<w:r>")
        if (r.bold || r.italic || r.underline || r.strike || r.sizePt != null) {
            sb.append("<w:rPr>")
            if (r.bold) sb.append("<w:b/>")
            if (r.italic) sb.append("<w:i/>")
            if (r.strike) sb.append("<w:strike/>")
            if (r.underline) sb.append("""<w:u w:val="single"/>""")
            r.sizePt?.let { sb.append("""<w:sz w:val="${(it * 2).toInt()}"/>""") }
            sb.append("</w:rPr>")
        }
        // Split on tabs/newlines which have dedicated elements in WordprocessingML.
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                sb.append("""<w:t xml:space="preserve">""").append(xmlEscape(buf.toString())).append("</w:t>")
                buf.setLength(0)
            }
        }
        for (c in r.text) {
            when (c) {
                '\t' -> { flush(); sb.append("<w:tab/>") }
                '\n' -> { flush(); sb.append("<w:br/>") }
                '￼' -> {}
                else -> buf.append(c)
            }
        }
        flush()
        sb.append("</w:r>")
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private fun documentRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
</Relationships>"""

    private fun appProps() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Office</Application></Properties>"""

    private fun styles(): String {
        fun heading(id: String, name: String, size: Int, outline: Int) =
            """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>""" +
                """<w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="$outline"/></w:pPr>""" +
                """<w:rPr><w:b/><w:sz w:val="$size"/></w:rPr></w:style>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:styles xmlns:w="$W">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri" w:eastAsia="Calibri"/><w:sz w:val="22"/><w:lang w:val="tr-TR"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="160"/></w:pPr><w:rPr><w:sz w:val="52"/></w:rPr></w:style>""" +
            heading("Heading1", "heading 1", 36, 0) +
            heading("Heading2", "heading 2", 30, 1) +
            heading("Heading3", "heading 3", 26, 2) +
            """<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:ind w:left="720"/></w:pPr><w:rPr><w:i/><w:color w:val="595959"/></w:rPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="720"/></w:pPr></w:style>""" +
            "</w:styles>"
    }

    private fun numbering(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:numbering xmlns:w="$W">""" +
            """<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>""" +
            """<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>""" +
            """<w:num w:numId="$BULLET_NUM_ID"><w:abstractNumId w:val="0"/></w:num>""" +
            """<w:num w:numId="$DECIMAL_NUM_ID"><w:abstractNumId w:val="1"/></w:num>""" +
            "</w:numbering>"
}
