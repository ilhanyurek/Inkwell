package com.office.core.docx

import com.office.core.text.Alignment
import com.office.core.text.EMBED_CHAR
import com.office.core.text.Embed
import com.office.core.text.EmbedKind
import com.office.core.text.EmbedMedia
import com.office.core.text.ListType
import com.office.core.text.Paragraph
import com.office.core.text.ParagraphStyle
import com.office.core.text.TextDocument
import com.office.core.text.TextRun
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlParser
import com.office.core.xml.XmlWriter
import com.office.core.xml.xmlEscape
import com.office.core.zip.ZipPackage
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads WordprocessingML into a [TextDocument]. Everything we cannot edit (tables, images, links,
 * fields, equations…) becomes an [Embed] carrying its original XML, and unmodelled paragraph/run
 * properties are kept as raw XML, so [DocxWriter] can write the document back without losing them.
 */
object DocxReader {

    fun read(input: InputStream): TextDocument = read(ZipPackage.read(input))

    fun read(pkg: ZipPackage): TextDocument {
        val mainPath = mainPart(pkg)
        val document = pkg.xml(mainPath) ?: throw IllegalArgumentException("Missing $mainPath")
        val ctx = Ctx(readHeadingStyles(pkg), readNumbering(pkg))
        val body = document.child("body") ?: return TextDocument.empty()

        val paragraphs = ArrayList<Paragraph>()
        for (el in body.elements) {
            when (el.localName) {
                "p" -> paragraphs += readParagraph(el, ctx)
                "sectPr" -> {} // copied from the original package on save
                "bookmarkStart", "bookmarkEnd", "proofErr" -> {}
                "tbl" -> paragraphs += blockParagraph(ctx, tableEmbed(el))
                else -> paragraphs += blockParagraph(ctx, Embed(EmbedKind.BLOCK, XmlWriter.write(el), block = true, label = el.text().trim().take(60)))
            }
        }
        if (paragraphs.isEmpty()) paragraphs += Paragraph(emptyList())
        return TextDocument(paragraphs, lossyImport = false, embeds = ctx.embeds)
    }

    internal fun mainPart(pkg: ZipPackage): String =
        pkg.relationships("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "word/document.xml"

    private class Ctx(
        val headingStyles: Map<String, ParagraphStyle>,
        val numbering: Map<String, ListType>,
    ) {
        val embeds = LinkedHashMap<Int, Embed>()
        fun add(e: Embed): Int {
            val id = embeds.size + 1
            embeds[id] = e
            return id
        }
    }

    private fun embedRun(ctx: Ctx, e: Embed) = TextRun(EMBED_CHAR.toString(), embedId = ctx.add(e))

    private fun blockParagraph(ctx: Ctx, e: Embed) = Paragraph(listOf(embedRun(ctx, e)))

    private fun tableEmbed(tbl: XmlElement): Embed = DocxTable.embed(tbl)

    private fun readParagraph(p: XmlElement, ctx: Ctx): Paragraph {
        val pPr = p.child("pPr")
        val styleId = pPr?.child("pStyle")?.attr("val")
        val mapped = styleId?.let { ctx.headingStyles[it] ?: headingFromId(it) }
        val style = mapped ?: ParagraphStyle.NORMAL
        val customStyle = if (mapped == null) styleId else null

        val numPr = pPr?.child("numPr")
        var list = ListType.NONE
        var numXml: String? = null
        if (numPr != null) {
            val numId = numPr.child("numId")?.attr("val")
            list = if (numId == null || numId == "0") ListType.NONE else ctx.numbering[numId] ?: ListType.BULLET
            numXml = XmlWriter.write(numPr)
        } else if (styleId != null && styleId.contains("List", ignoreCase = true) && customStyle != null) {
            // Numbering comes from the paragraph style; no explicit numPr is needed to keep it.
            list = if (styleId.contains("Number", ignoreCase = true)) ListType.NUMBERED else ListType.BULLET
            numXml = ""
        }
        val alignment = when (pPr?.child("jc")?.attr("val")) {
            "center" -> Alignment.CENTER
            "right", "end" -> Alignment.END
            "both", "distribute" -> Alignment.JUSTIFY
            else -> Alignment.START
        }
        val extra = pPr?.elements?.filter { it.localName !in MODELED_PPR }?.joinToString("") { XmlWriter.write(it) }?.ifEmpty { null }

        val runs = ArrayList<TextRun>()
        collectRuns(p, ctx, runs)
        return Paragraph(runs, style, list, alignment, extra, customStyle, numXml, list).normalized()
    }

    private fun collectRuns(container: XmlElement, ctx: Ctx, out: MutableList<TextRun>) {
        for (el in container.elements) {
            when (el.localName) {
                "pPr" -> {}
                "r" -> if (isEditableRun(el)) readRun(el)?.let { out += it }
                else out += embedRun(ctx, Embed(runKind(el), XmlWriter.write(el), label = el.descendants("t").joinToString("") { it.text() }))
                "ins", "smartTag", "dir", "bdo" -> collectRuns(el, ctx, out)
                "proofErr" -> {}
                "hyperlink" -> out += embedRun(ctx, Embed(EmbedKind.LINK, XmlWriter.write(el), label = el.descendants("t").joinToString("") { it.text() }))
                "fldSimple" -> out += embedRun(ctx, Embed(EmbedKind.FIELD, XmlWriter.write(el), label = el.text()))
                "oMath", "oMathPara" -> out += embedRun(ctx, Embed(EmbedKind.EQUATION, XmlWriter.write(el), label = el.text()))
                "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd", "permStart", "permEnd" ->
                    out += embedRun(ctx, Embed(EmbedKind.FIELD, XmlWriter.write(el), label = MARKER_LABEL))
                else -> out += embedRun(ctx, Embed(EmbedKind.OBJECT, XmlWriter.write(el), label = el.text().take(60)))
            }
        }
    }

    private fun runKind(r: XmlElement): EmbedKind = when {
        r.descendants("drawing").isNotEmpty() || r.descendants("pict").isNotEmpty() -> EmbedKind.IMAGE
        r.descendants("object").isNotEmpty() -> EmbedKind.OBJECT
        else -> EmbedKind.FIELD
    }

    private fun isEditableRun(r: XmlElement): Boolean = r.elements.all { c ->
        when (c.localName) {
            "rPr", "t", "tab", "ptab", "noBreakHyphen", "softHyphen", "sym", "lastRenderedPageBreak" -> true
            "br", "cr" -> c.attr("type").let { it == null || it == "textWrapping" }
            else -> false
        }
    }

    private fun readRun(r: XmlElement): TextRun? {
        val rPr = r.child("rPr")
        fun flag(name: String): Boolean {
            val e = rPr?.child(name) ?: return false
            val v = e.attr("val") ?: return true
            return v != "0" && v != "false" && v != "none"
        }
        val sb = StringBuilder()
        for (c in r.elements) {
            when (c.localName) {
                "t" -> sb.append(c.text())
                "tab", "ptab" -> sb.append('\t')
                "br", "cr" -> sb.append('\n')
                "noBreakHyphen" -> sb.append('‑')
                "softHyphen" -> sb.append('­')
                "sym" -> c.attr("char")?.toIntOrNull(16)?.let { sb.appendCodePoint(it) }
            }
        }
        if (sb.isEmpty()) return null
        val extra = rPr?.elements?.filter { it.localName !in MODELED_RPR }?.joinToString("") { XmlWriter.write(it) }?.ifEmpty { null }
        return TextRun(
            text = sb.toString(),
            bold = flag("b"),
            italic = flag("i"),
            underline = rPr?.child("u")?.let { it.attr("val") != "none" } ?: false,
            strike = flag("strike"),
            sizePt = rPr?.child("sz")?.attr("val")?.toFloatOrNull()?.div(2f),
            extraXml = extra,
        )
    }

    internal fun headingFromId(id: String): ParagraphStyle? {
        val lower = id.lowercase().replace(" ", "")
        return when {
            lower == "title" -> ParagraphStyle.TITLE
            lower == "heading1" || lower == "baslik1" || lower == "başlık1" -> ParagraphStyle.HEADING1
            lower == "heading2" || lower == "baslik2" || lower == "başlık2" -> ParagraphStyle.HEADING2
            lower == "heading3" || lower == "baslik3" || lower == "başlık3" -> ParagraphStyle.HEADING3
            lower == "quote" -> ParagraphStyle.QUOTE
            else -> null
        }
    }

    /** Maps styleId → our style using the style's name and outline level (locale independent). */
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
                name == "quote" -> ParagraphStyle.QUOTE
                name == "heading 1" || outline == 0 -> ParagraphStyle.HEADING1
                name == "heading 2" || outline == 1 -> ParagraphStyle.HEADING2
                name == "heading 3" || outline == 2 -> ParagraphStyle.HEADING3
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

    /** Label used for invisible markers (bookmarks, comment ranges); the editor hides these. */
    const val MARKER_LABEL = "\u0000marker"

    internal val MODELED_PPR = setOf("pStyle", "numPr", "jc")
    internal val MODELED_RPR = setOf("b", "i", "u", "strike", "sz")
}

object DocxWriter {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val OUR_BULLET = 90001
    private const val OUR_DECIMAL = 90002

    /**
     * Writes [doc]. When [original] is the package the document was read from, every other part
     * (images, headers, styles, comments…) is kept and only the main document part is replaced.
     */
    fun write(doc: TextDocument, output: OutputStream, original: ZipPackage? = null) {
        if (original == null) writeNew(doc, output) else writeInto(doc, output, original)
    }

    // --- new documents ---------------------------------------------------------------------

    private fun writeNew(doc: TextDocument, output: OutputStream) {
        val body = StringBuilder()
        for (p in doc.paragraphs) appendParagraph(body, p, doc, 1, 2)
        body.append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""")
        body.append("""<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$W" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$body</w:body></w:document>"""
        val media = usedMedia(doc)
        val entries = listOf(
            "[Content_Types].xml" to withMediaTypes(CONTENT_TYPES, media),
            "_rels/.rels" to ROOT_RELS,
            "word/document.xml" to document,
            "word/styles.xml" to stylesXml(),
            "word/numbering.xml" to numberingXml(1, 2),
            "word/_rels/document.xml.rels" to withMediaRels(DOCUMENT_RELS, media),
            "docProps/app.xml" to APP_PROPS,
        ).map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) } + media.map { "word/media/${it.fileName}" to it.bytes }
        ZipPackage.write(output, entries)
    }

    // --- editing an existing package ----------------------------------------------------------

    private fun writeInto(doc: TextDocument, output: OutputStream, pkg: ZipPackage) {
        val mainPath = DocxReader.mainPart(pkg)
        val root = pkg.xml(mainPath) ?: return writeNew(doc, output)
        val replaced = LinkedHashMap<String, ByteArray>()

        val needsOurLists = doc.paragraphs.any { it.list != ListType.NONE && !(it.list == it.numType && it.numXml != null) }
        if (needsOurLists) addOurNumbering(pkg, mainPath, replaced)
        ensureStyles(pkg, doc, replaced)
        addMedia(pkg, mainPath, usedMedia(doc), replaced)

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        sb.append('<').append(root.name)
        for ((k, v) in root.attributes) sb.append(' ').append(k).append("=\"").append(xmlEscape(v)).append('"')
        sb.append('>')
        for (c in root.elements) {
            if (c.localName != "body") { XmlWriter.append(sb, c); continue }
            sb.append('<').append(c.name).append('>')
            for (p in doc.paragraphs) appendParagraph(sb, p, doc, OUR_BULLET, OUR_DECIMAL)
            c.child("sectPr")?.let { XmlWriter.append(sb, it) }
            sb.append("</").append(c.name).append('>')
        }
        sb.append("</").append(root.name).append('>')
        replaced[mainPath] = sb.toString().toByteArray(Charsets.UTF_8)

        val entries = pkg.entries.map { (k, v) -> k to (replaced.remove(k) ?: v) } + replaced.map { (k, v) -> k to v }
        ZipPackage.write(output, entries)
    }

    private fun addOurNumbering(pkg: ZipPackage, mainPath: String, replaced: MutableMap<String, ByteArray>) {
        val dir = mainPath.substringBeforeLast('/', "")
        val rels = pkg.relationships(mainPath)
        val existing = rels.values.firstOrNull { it.type.endsWith("/numbering") }?.target
        if (existing != null && pkg.bytes(existing) != null) {
            val text = String(pkg.bytes(existing)!!, Charsets.UTF_8)
            val root = XmlParser.parse(text)
            val p = root.name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
            val abstracts = ourAbstractNums(p, OUR_BULLET, OUR_DECIMAL)
            val nums = ourNums(p, OUR_BULLET, OUR_DECIMAL)
            // Schema order: all abstractNum elements come before num elements.
            val firstNum = Regex("<${Regex.escape(p)}num[ >]").find(text)?.range?.first
            val close = text.lastIndexOf("</${root.name}>")
            val updated = if (firstNum != null) {
                text.substring(0, firstNum) + abstracts + text.substring(firstNum, close) + nums + text.substring(close)
            } else {
                text.substring(0, close) + abstracts + nums + text.substring(close)
            }
            replaced[existing] = updated.toByteArray(Charsets.UTF_8)
        } else {
            val path = if (dir.isEmpty()) "numbering.xml" else "$dir/numbering.xml"
            replaced[path] = numberingXml(OUR_BULLET, OUR_DECIMAL).toByteArray(Charsets.UTF_8)
            val relsPath = if (dir.isEmpty()) "_rels/${mainPath.substringAfterLast('/')}.rels" else "$dir/_rels/${mainPath.substringAfterLast('/')}.rels"
            val relsText = pkg.bytes(relsPath)?.let { String(it, Charsets.UTF_8) }
                ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
            val rel = """<Relationship Id="rIdInkwellNum" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>"""
            replaced[relsPath] = relsText.replace("</Relationships>", "$rel</Relationships>").toByteArray(Charsets.UTF_8)
            val ct = String(pkg.bytes("[Content_Types].xml") ?: CONTENT_TYPES.toByteArray(), Charsets.UTF_8)
            if (!ct.contains("/$path\"")) {
                val override = """<Override PartName="/$path" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>"""
                replaced["[Content_Types].xml"] = ct.replace("</Types>", "$override</Types>").toByteArray(Charsets.UTF_8)
            }
        }
    }

    /** Pictures inserted in this session that are still in the document. */
    private fun usedMedia(doc: TextDocument): List<EmbedMedia> =
        doc.paragraphs.flatMap { p -> p.runs.mapNotNull { r -> r.embedId?.let { doc.embeds[it]?.media } } }.distinctBy { it.relId }

    private fun withMediaRels(rels: String, media: List<EmbedMedia>): String {
        if (media.isEmpty()) return rels
        val add = media.joinToString("") {
            """<Relationship Id="${it.relId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${it.fileName}"/>"""
        }
        return rels.replace("</Relationships>", "$add</Relationships>")
    }

    private fun withMediaTypes(ct: String, media: List<EmbedMedia>): String {
        var out = ct
        for (m in media) {
            val ext = m.fileName.substringAfterLast('.').lowercase()
            if (Regex("Extension=\"$ext\"", RegexOption.IGNORE_CASE).containsMatchIn(out)) continue
            out = out.replace("</Types>", """<Default Extension="$ext" ContentType="${m.contentType}"/></Types>""")
        }
        return out
    }

    private fun addMedia(pkg: ZipPackage, mainPath: String, media: List<EmbedMedia>, replaced: MutableMap<String, ByteArray>) {
        if (media.isEmpty()) return
        val dir = mainPath.substringBeforeLast('/', "")
        val file = mainPath.substringAfterLast('/')
        val relsPath = if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
        val relsText = (replaced[relsPath] ?: pkg.bytes(relsPath))?.let { String(it, Charsets.UTF_8) }
            ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
        val existing = pkg.relationships(mainPath).keys
        replaced[relsPath] = withMediaRels(relsText, media.filter { it.relId !in existing }).toByteArray(Charsets.UTF_8)
        val ct = String(replaced["[Content_Types].xml"] ?: pkg.bytes("[Content_Types].xml") ?: CONTENT_TYPES.toByteArray(), Charsets.UTF_8)
        replaced["[Content_Types].xml"] = withMediaTypes(ct, media).toByteArray(Charsets.UTF_8)
        for (m in media) {
            val path = if (dir.isEmpty()) "media/${m.fileName}" else "$dir/media/${m.fileName}"
            replaced[path] = m.bytes
        }
    }

    /** Adds definitions for our heading/title/quote styles if the document uses them but lacks them. */
    private fun ensureStyles(pkg: ZipPackage, doc: TextDocument, replaced: MutableMap<String, ByteArray>) {
        val used = doc.paragraphs.mapNotNull { styleIdFor(it.style) }.toSet()
        if (used.isEmpty()) return
        val bytes = pkg.bytes("word/styles.xml") ?: return
        val text = String(bytes, Charsets.UTF_8)
        val root = XmlParser.parse(text)
        val present = root.children("style").mapNotNull { it.attr("styleId") }.toSet()
        val missing = used - present
        if (missing.isEmpty()) return
        val defs = missing.joinToString("") { styleDefinition(it) }
        val close = text.lastIndexOf("</${root.name}>")
        replaced["word/styles.xml"] = (text.substring(0, close) + defs + text.substring(close)).toByteArray(Charsets.UTF_8)
    }

    // --- paragraphs ------------------------------------------------------------------------------

    private fun styleIdFor(style: ParagraphStyle): String? = when (style) {
        ParagraphStyle.NORMAL -> null
        ParagraphStyle.TITLE -> "Title"
        ParagraphStyle.HEADING1 -> "Heading1"
        ParagraphStyle.HEADING2 -> "Heading2"
        ParagraphStyle.HEADING3 -> "Heading3"
        ParagraphStyle.QUOTE -> "Quote"
    }

    private fun appendParagraph(sb: StringBuilder, p: Paragraph, doc: TextDocument, bulletId: Int, decimalId: Int) {
        // Block embeds (tables…) are written in place; any text around them becomes its own paragraph.
        val pending = ArrayList<TextRun>()
        var wroteAny = false
        fun flushParagraph(force: Boolean) {
            if (pending.isEmpty() && !force) return
            sb.append("<w:p>")
            appendPPr(sb, p, bulletId, decimalId)
            for (r in pending) appendRun(sb, r, doc)
            sb.append("</w:p>")
            pending.clear()
            wroteAny = true
        }
        for (r in p.runs) {
            val embed = r.embedId?.let { doc.embeds[it] }
            if (embed != null && embed.block) {
                flushParagraph(force = false)
                sb.append(embed.xml)
                wroteAny = true
            } else {
                pending += r
            }
        }
        flushParagraph(force = !wroteAny)
    }

    private fun appendPPr(sb: StringBuilder, p: Paragraph, bulletId: Int, decimalId: Int) {
        val parts = ArrayList<Pair<String, String>>()
        val styleId = styleIdFor(p.style) ?: p.customStyleId
        if (styleId != null) parts += "pStyle" to """<w:pStyle w:val="${xmlEscape(styleId)}"/>"""
        when {
            p.list != ListType.NONE && p.list == p.numType && p.numXml != null ->
                if (p.numXml.isNotEmpty()) parts += "numPr" to p.numXml
            p.list != ListType.NONE -> {
                val id = if (p.list == ListType.BULLET) bulletId else decimalId
                parts += "numPr" to """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="$id"/></w:numPr>"""
            }
            p.numType != ListType.NONE && p.numXml != null ->
                // The list came from the style; switch it off explicitly.
                parts += "numPr" to """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="0"/></w:numPr>"""
        }
        when (p.alignment) {
            Alignment.START -> null
            Alignment.CENTER -> "center"
            Alignment.END -> "right"
            Alignment.JUSTIFY -> "both"
        }?.let { parts += "jc" to """<w:jc w:val="$it"/>""" }
        p.extraXml?.let { parts += splitFragments(it) }
        if (parts.isEmpty()) return
        sb.append("<w:pPr>")
        parts.sortedBy { PPR_ORDER.indexOf(it.first).let { i -> if (i < 0) PPR_ORDER.size else i } }.forEach { sb.append(it.second) }
        sb.append("</w:pPr>")
    }

    private fun appendRun(sb: StringBuilder, r: TextRun, doc: TextDocument) {
        r.embedId?.let { id ->
            doc.embeds[id]?.let { sb.append(it.xml) }
            return
        }
        sb.append("<w:r>")
        val parts = ArrayList<Pair<String, String>>()
        if (r.bold) parts += "b" to "<w:b/>"
        if (r.italic) parts += "i" to "<w:i/>"
        if (r.strike) parts += "strike" to "<w:strike/>"
        if (r.underline) parts += "u" to """<w:u w:val="single"/>"""
        r.sizePt?.let { parts += "sz" to """<w:sz w:val="${(it * 2).toInt()}"/>""" }
        r.extraXml?.let { parts += splitFragments(it) }
        if (parts.isNotEmpty()) {
            sb.append("<w:rPr>")
            parts.sortedBy { RPR_ORDER.indexOf(it.first).let { i -> if (i < 0) RPR_ORDER.size else i } }.forEach { sb.append(it.second) }
            sb.append("</w:rPr>")
        }
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
                EMBED_CHAR -> {}
                else -> buf.append(c)
            }
        }
        flush()
        sb.append("</w:r>")
    }

    /** Splits concatenated raw elements back into (localName, xml) pairs for schema-ordered output. */
    private fun splitFragments(xml: String): List<Pair<String, String>> {
        val wrapper = runCatching { XmlParser.parse("<x>$xml</x>") }.getOrNull() ?: return emptyList()
        return wrapper.elements.map { it.localName to XmlWriter.write(it) }
    }

    private val PPR_ORDER = listOf(
        "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr", "suppressLineNumbers",
        "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct",
        "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing",
        "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl",
        "divId", "cnfStyle", "rPr", "sectPr", "pPrChange",
    )

    private val RPR_ORDER = listOf(
        "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow",
        "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position",
        "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang",
        "eastAsianLayout", "specVanish", "oMath", "rPrChange",
    )

    // --- fixed parts -----------------------------------------------------------------------------

    private fun styleDefinition(id: String): String = when (id) {
        "Title" -> """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="160"/></w:pPr><w:rPr><w:sz w:val="52"/></w:rPr></w:style>"""
        "Heading1" -> heading("Heading1", "heading 1", 36, 0)
        "Heading2" -> heading("Heading2", "heading 2", 30, 1)
        "Heading3" -> heading("Heading3", "heading 3", 26, 2)
        "Quote" -> """<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:ind w:left="720"/></w:pPr><w:rPr><w:i/><w:color w:val="595959"/></w:rPr></w:style>"""
        else -> ""
    }

    private fun heading(id: String, name: String, size: Int, outline: Int) =
        """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>""" +
            """<w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="$outline"/></w:pPr>""" +
            """<w:rPr><w:b/><w:sz w:val="$size"/></w:rPr></w:style>"""

    private fun stylesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri" w:eastAsia="Calibri"/><w:sz w:val="22"/><w:lang w:val="tr-TR"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            listOf("Title", "Heading1", "Heading2", "Heading3", "Quote").joinToString("") { styleDefinition(it) } +
            "</w:styles>"

    private fun ourAbstractNums(p: String, bulletId: Int, decimalId: Int): String =
        """<${p}abstractNum ${p}abstractNumId="$bulletId"><${p}multiLevelType ${p}val="hybridMultilevel"/><${p}lvl ${p}ilvl="0"><${p}start ${p}val="1"/><${p}numFmt ${p}val="bullet"/><${p}lvlText ${p}val="•"/><${p}lvlJc ${p}val="left"/><${p}pPr><${p}ind ${p}left="720" ${p}hanging="360"/></${p}pPr></${p}lvl></${p}abstractNum>""" +
            """<${p}abstractNum ${p}abstractNumId="$decimalId"><${p}multiLevelType ${p}val="hybridMultilevel"/><${p}lvl ${p}ilvl="0"><${p}start ${p}val="1"/><${p}numFmt ${p}val="decimal"/><${p}lvlText ${p}val="%1."/><${p}lvlJc ${p}val="left"/><${p}pPr><${p}ind ${p}left="720" ${p}hanging="360"/></${p}pPr></${p}lvl></${p}abstractNum>"""

    private fun ourNums(p: String, bulletId: Int, decimalId: Int): String =
        """<${p}num ${p}numId="$bulletId"><${p}abstractNumId ${p}val="$bulletId"/></${p}num><${p}num ${p}numId="$decimalId"><${p}abstractNumId ${p}val="$decimalId"/></${p}num>"""

    private fun numberingXml(bulletId: Int, decimalId: Int): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering xmlns:w="$W">""" +
            ourAbstractNums("w:", bulletId, decimalId) + ourNums("w:", bulletId, decimalId) + "</w:numbering>"

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
</Relationships>"""

    private const val APP_PROPS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Inkwell</Application></Properties>"""
}
