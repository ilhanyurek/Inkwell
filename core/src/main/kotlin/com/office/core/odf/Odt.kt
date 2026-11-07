package com.office.core.odf

import com.office.core.docx.DocxReader
import com.office.core.text.Alignment
import com.office.core.text.EMBED_CHAR
import com.office.core.text.Embed
import com.office.core.text.EmbedKind
import com.office.core.text.ListType
import com.office.core.text.Paragraph
import com.office.core.text.ParagraphStyle
import com.office.core.text.TextDocument
import com.office.core.text.TextRun
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlNode
import com.office.core.xml.XmlText
import com.office.core.xml.XmlWriter
import com.office.core.xml.xmlEscape
import com.office.core.zip.ZipPackage
import java.io.OutputStream

/**
 * Reads an OpenDocument text file into a [TextDocument]. Like [DocxReader], content we do not edit
 * (tables, pictures, links, notes, fields) becomes an [Embed] holding its original XML, and each
 * paragraph remembers its original style so [OdtWriter] can keep the look of the document.
 */
object OdtReader {

    fun read(pkg: ZipPackage): TextDocument {
        val content = pkg.xml("content.xml") ?: throw IllegalArgumentException("Missing content.xml")
        val styles = OdfStyles(pkg, content)
        val body = content.child("body")?.child("text") ?: return TextDocument.empty()
        val ctx = Ctx(styles)
        for (el in body.elements) block(el, ctx, null)
        if (ctx.paragraphs.isEmpty()) ctx.paragraphs += Paragraph(emptyList())
        return TextDocument(ctx.paragraphs, lossyImport = false, embeds = ctx.embeds)
    }

    private class Ctx(val styles: OdfStyles) {
        val paragraphs = ArrayList<Paragraph>()
        val embeds = LinkedHashMap<Int, Embed>()
        fun add(e: Embed): Int = (embeds.size + 1).also { embeds[it] = e }
        fun embedRun(e: Embed) = TextRun(EMBED_CHAR.toString(), embedId = add(e))
    }

    /** List context: the list style that applies and the list type it gives. */
    private class ListCtx(val styleName: String?, val type: ListType)

    private fun block(el: XmlElement, ctx: Ctx, list: ListCtx?) {
        when (el.localName) {
            "p", "h" -> ctx.paragraphs += paragraph(el, ctx, list)
            "list" -> {
                val name = el.attr("text:style-name") ?: list?.styleName
                val type = name?.let { ctx.styles.numberedLists[it] }?.let { if (it) ListType.NUMBERED else ListType.BULLET } ?: list?.type ?: ListType.BULLET
                val inner = ListCtx(name, type)
                for (item in el.elements) {
                    if (item.localName != "list-item" && item.localName != "list-header") continue
                    for (c in item.elements) block(c, ctx, inner)
                }
            }
            "table" -> ctx.paragraphs += Paragraph(listOf(ctx.embedRun(tableEmbed(el))))
            "sequence-decls", "variable-decls", "user-field-decls", "soft-page-break", "tracked-changes", "forms" -> {}
            "section" -> el.elements.forEach { block(it, ctx, list) }
            else -> ctx.paragraphs += Paragraph(
                listOf(ctx.embedRun(Embed(EmbedKind.BLOCK, XmlWriter.write(el), block = true, label = el.text().trim().take(60)))),
            )
        }
    }

    fun tableRows(table: XmlElement): List<List<String>> {
        val rows = ArrayList<List<String>>()
        fun walk(e: XmlElement) {
            for (c in e.elements) {
                when (c.localName) {
                    "table-row" -> {
                        val cells = ArrayList<String>()
                        for (cell in c.elements) {
                            if (cell.localName != "table-cell" && cell.localName != "covered-table-cell") continue
                            val text = cell.children("p").joinToString("\n") { it.text() }.trim()
                            val repeat = (cell.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                            repeat(repeat) { cells += text }
                        }
                        val n = (c.attr("table:number-rows-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 1000)
                        repeat(n) { rows += cells.toList() }
                    }
                    "table-header-rows", "table-rows", "table-row-group" -> walk(c)
                }
            }
        }
        walk(table)
        return rows
    }

    private fun tableEmbed(table: XmlElement): Embed {
        val rows = tableRows(table)
        val cols = rows.maxOfOrNull { it.size } ?: 0
        return Embed(EmbedKind.TABLE, XmlWriter.write(table), block = true, label = "${rows.size}×$cols", tableRows = rows)
    }

    private fun paragraph(el: XmlElement, ctx: Ctx, list: ListCtx?): Paragraph {
        val styleName = el.attr("text:style-name")
        val chain = ctx.styles.chain(styleName)
        val resolved = ctx.styles.resolve(styleName)
        val outline = el.attr("text:outline-level")?.toIntOrNull()
        val style = when {
            chain.any { it == "Title" } -> ParagraphStyle.TITLE
            el.localName == "h" || outline != null -> when (outline ?: 1) {
                1 -> ParagraphStyle.HEADING1
                2 -> ParagraphStyle.HEADING2
                else -> ParagraphStyle.HEADING3
            }
            chain.any { it == "Quotations" || it == "Quote" } -> ParagraphStyle.QUOTE
            else -> ParagraphStyle.NORMAL
        }
        val alignment = resolved?.align ?: Alignment.START
        val runs = ArrayList<TextRun>()
        collect(el, ctx, null, runs)
        return Paragraph(
            runs = runs,
            style = style,
            list = list?.type ?: ListType.NONE,
            alignment = alignment,
            extraXml = OdtWriter.originalMarker(styleName, alignment, style),
            numXml = list?.styleName,
            numType = list?.type ?: ListType.NONE,
        ).normalized()
    }

    private fun collect(container: XmlElement, ctx: Ctx, span: String?, out: MutableList<TextRun>) {
        val st = ctx.styles.resolve(span)
        fun run(text: String) = TextRun(
            text,
            bold = st?.bold == true, italic = st?.italic == true, underline = st?.underline == true, strike = st?.strike == true,
            sizePt = st?.sizePt, extraXml = span,
        )
        for (n in container.children) {
            when (n) {
                is XmlText -> {
                    // Whitespace collapses in ODF; tabs and newlines in the source are layout only.
                    val t = n.text.replace(Regex("[\\t\\r\\n]+"), " ")
                    if (t.isNotEmpty()) out += run(t)
                }
                is XmlElement -> when (n.localName) {
                    "span" -> collect(n, ctx, n.attr("text:style-name") ?: span, out)
                    "s" -> out += run(" ".repeat((n.attr("text:c")?.toIntOrNull() ?: 1).coerceIn(1, 500)))
                    "tab" -> out += run("\t")
                    "soft-page-break", "change-start", "change-end", "change" -> {}
                    "a" -> out += ctx.embedRun(Embed(EmbedKind.LINK, XmlWriter.write(n), label = n.text()))
                    "frame" -> out += ctx.embedRun(
                        Embed(if (n.child("image") != null) EmbedKind.IMAGE else EmbedKind.OBJECT, XmlWriter.write(n), label = n.attr("draw:name").orEmpty()),
                    )
                    "bookmark", "bookmark-start", "bookmark-end", "reference-mark", "reference-mark-start", "reference-mark-end", "alphabetical-index-mark" ->
                        out += ctx.embedRun(Embed(EmbedKind.FIELD, XmlWriter.write(n), label = DocxReader.MARKER_LABEL))
                    "line-break" -> out += ctx.embedRun(Embed(EmbedKind.FIELD, XmlWriter.write(n), label = "↵"))
                    "note" -> out += ctx.embedRun(Embed(EmbedKind.FIELD, XmlWriter.write(n), label = n.child("note-citation")?.text().orEmpty().ifEmpty { "*" }))
                    else -> out += ctx.embedRun(Embed(EmbedKind.FIELD, XmlWriter.write(n), label = n.text().take(40)))
                }
            }
        }
    }
}

/** Writes a [TextDocument] as OpenDocument text, into the original package when there is one. */
object OdtWriter {

    private const val SEP = "\u0001"

    /** Remembers a paragraph's source style so an unchanged paragraph keeps it. */
    internal fun originalMarker(styleName: String?, alignment: Alignment, style: ParagraphStyle) =
        "odf$SEP${styleName.orEmpty()}$SEP${alignment.name}$SEP${style.name}"

    private class Marker(val styleName: String?, val alignment: Alignment, val style: ParagraphStyle)

    private fun marker(p: Paragraph): Marker? {
        val parts = p.extraXml?.split(SEP) ?: return null
        if (parts.size != 4 || parts[0] != "odf") return null
        return Marker(parts[1].ifEmpty { null }, Alignment.valueOf(parts[2]), ParagraphStyle.valueOf(parts[3]))
    }

    fun write(doc: TextDocument, output: OutputStream, original: ZipPackage? = null) {
        val content = original?.xml("content.xml")
        val styles = OdfStyles(original, content)
        val gen = Generated(styles, content)
        val body = StringBuilder()
        var i = 0
        val paras = doc.paragraphs
        while (i < paras.size) {
            val p = paras[i]
            if (p.list == ListType.NONE) {
                appendParagraph(body, p, doc, gen)
                i++
                continue
            }
            // Consecutive items of the same list type form one list.
            val listStyle = if (p.numXml != null && p.numType == p.list) p.numXml else if (p.list == ListType.NUMBERED) "IwListNumber" else "IwListBullet"
            gen.lists += listStyle
            body.append("""<text:list text:style-name="${xmlEscape(listStyle)}">""")
            while (i < paras.size && paras[i].list == p.list) {
                body.append("<text:list-item>")
                appendParagraph(body, paras[i], doc, gen)
                body.append("</text:list-item>")
                i++
            }
            body.append("</text:list>")
        }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        if (content != null) {
            sb.append('<').append(content.name)
            for ((k, v) in content.attributes) sb.append(' ').append(k).append("=\"").append(xmlEscape(v)).append('"')
            sb.append('>')
            var wroteAuto = false
            for (c in content.elements) {
                when (c.localName) {
                    "automatic-styles" -> {
                        sb.append('<').append(c.name).append('>')
                        c.children.forEach { XmlWriter.append(sb, it) }
                        sb.append(gen.styleXml())
                        sb.append("</").append(c.name).append('>')
                        wroteAuto = true
                    }
                    "body" -> {
                        if (!wroteAuto) sb.append("<office:automatic-styles>").append(gen.styleXml()).append("</office:automatic-styles>")
                        sb.append('<').append(c.name).append('>')
                        for (b in c.elements) {
                            if (b.localName != "text") { XmlWriter.append(sb, b); continue }
                            sb.append('<').append(b.name)
                            for ((k, v) in b.attributes) sb.append(' ').append(k).append("=\"").append(xmlEscape(v)).append('"')
                            sb.append('>')
                            b.elements.filter { it.localName.endsWith("-decls") || it.localName == "forms" }.forEach { XmlWriter.append(sb, it) }
                            sb.append(body)
                            sb.append("</").append(b.name).append('>')
                        }
                        sb.append("</").append(c.name).append('>')
                    }
                    else -> XmlWriter.append(sb, c)
                }
            }
            sb.append("</").append(content.name).append('>')
        } else {
            sb.append("""<office:document-content ${Odf.ROOT_NS} office:version="1.3">""")
            sb.append("<office:automatic-styles>").append(gen.styleXml()).append("</office:automatic-styles>")
            sb.append("<office:body><office:text>").append(body).append("</office:text></office:body></office:document-content>")
        }

        val entries = if (original != null) {
            original.entries.map { (k, v) -> k to if (k == "content.xml") sb.toString().toByteArray(Charsets.UTF_8) else v }
        } else {
            listOf(
                "mimetype" to Odf.ODT_MIME,
                "META-INF/manifest.xml" to Odf.manifest(Odf.ODT_MIME),
                "content.xml" to sb.toString(),
                "styles.xml" to STYLES,
                "meta.xml" to Odf.meta(),
            ).map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }
        }
        ZipPackage.write(output, entries)
    }

    /** Automatic styles created while writing: character formats, alignments and lists. */
    private class Generated(val styles: OdfStyles, content: XmlElement?) {
        val text = LinkedHashMap<String, String>()   // key → name
        val paragraph = LinkedHashMap<Pair<String, Alignment>, String>()
        val lists = LinkedHashSet<String>()
        private val taken: Set<String> = content?.child("automatic-styles")?.elements?.mapNotNull { it.attr("style:name") }?.toSet().orEmpty()

        private fun fresh(prefix: String, n: Int): String {
            var i = n
            while ("$prefix$i" in taken) i++
            return "$prefix$i"
        }

        fun textStyle(r: TextRun): String? {
            if (!r.bold && !r.italic && !r.underline && !r.strike && r.sizePt == null) return null
            val key = "${r.bold}|${r.italic}|${r.underline}|${r.strike}|${r.sizePt}"
            return text.getOrPut(key) { fresh("IwT", text.size + 1) }
        }

        fun paragraphStyle(parent: String, align: Alignment): String =
            paragraph.getOrPut(parent to align) { fresh("IwP", paragraph.size + 1) }

        fun styleXml(): String = buildString {
            for ((key, name) in text) {
                val (b, i, u, s, size) = key.split('|')
                append("""<style:style style:name="$name" style:family="text"><style:text-properties""")
                if (b == "true") append(""" fo:font-weight="bold" style:font-weight-asian="bold" style:font-weight-complex="bold"""")
                if (i == "true") append(""" fo:font-style="italic" style:font-style-asian="italic" style:font-style-complex="italic"""")
                if (u == "true") append(""" style:text-underline-style="solid" style:text-underline-width="auto" style:text-underline-color="font-color"""")
                if (s == "true") append(""" style:text-line-through-style="solid"""")
                if (size != "null") append(""" fo:font-size="${size}pt"""")
                append("/></style:style>")
            }
            for ((key, name) in paragraph) {
                val align = when (key.second) {
                    Alignment.START -> "start"
                    Alignment.CENTER -> "center"
                    Alignment.END -> "end"
                    Alignment.JUSTIFY -> "justify"
                }
                append("""<style:style style:name="$name" style:family="paragraph" style:parent-style-name="${xmlEscape(key.first)}"><style:paragraph-properties fo:text-align="$align"/></style:style>""")
            }
            if ("IwListBullet" in lists && "IwListBullet" !in styles.numberedLists) {
                append("""<text:list-style style:name="IwListBullet"><text:list-level-style-bullet text:level="1" text:bullet-char="•"><style:list-level-properties text:list-level-position-and-space-mode="label-alignment"><style:list-level-label-alignment text:label-followed-by="listtab" text:list-tab-stop-position="0.5in" fo:text-indent="-0.25in" fo:margin-left="0.5in"/></style:list-level-properties></text:list-level-style-bullet></text:list-style>""")
            }
            if ("IwListNumber" in lists && "IwListNumber" !in styles.numberedLists) {
                append("""<text:list-style style:name="IwListNumber"><text:list-level-style-number text:level="1" style:num-suffix="." style:num-format="1"><style:list-level-properties text:list-level-position-and-space-mode="label-alignment"><style:list-level-label-alignment text:label-followed-by="listtab" text:list-tab-stop-position="0.5in" fo:text-indent="-0.25in" fo:margin-left="0.5in"/></style:list-level-properties></text:list-level-style-number></text:list-style>""")
            }
        }
    }

    private fun styleFor(style: ParagraphStyle): String? = when (style) {
        ParagraphStyle.TITLE -> "Title"
        ParagraphStyle.HEADING1 -> "Heading_20_1"
        ParagraphStyle.HEADING2 -> "Heading_20_2"
        ParagraphStyle.HEADING3 -> "Heading_20_3"
        ParagraphStyle.QUOTE -> "Quotations"
        ParagraphStyle.NORMAL -> null
    }

    private fun appendParagraph(sb: StringBuilder, p: Paragraph, doc: TextDocument, gen: Generated) {
        // A table (or other block) standing alone is written as is, outside any paragraph.
        val only = p.runs.singleOrNull()?.embedId?.let { doc.embeds[it] }
        if (only != null && only.block) {
            sb.append(only.xml)
            return
        }
        val m = marker(p)
        val sameStyle = m != null && m.style == p.style
        val base = when {
            sameStyle && m?.styleName != null -> m.styleName
            else -> styleFor(p.style) ?: m?.styleName?.takeIf { m.style == ParagraphStyle.NORMAL } ?: "Standard"
        }
        val styleName = if (sameStyle && m?.alignment == p.alignment) base
        else if (p.alignment == Alignment.START && !sameStyle) base
        else {
            // An automatic style cannot be a parent: use the common style it derives from.
            val parent = gen.styles.styles[base]?.takeIf { it.automatic }?.parent ?: base
            gen.paragraphStyle(parent, p.alignment)
        }
        val heading = p.style in setOf(ParagraphStyle.HEADING1, ParagraphStyle.HEADING2, ParagraphStyle.HEADING3)
        val tag = if (heading) "text:h" else "text:p"
        sb.append('<').append(tag).append(""" text:style-name="${xmlEscape(styleName)}"""")
        if (heading) {
            val level = when (p.style) {
                ParagraphStyle.HEADING1 -> 1
                ParagraphStyle.HEADING2 -> 2
                else -> 3
            }
            sb.append(""" text:outline-level="$level"""")
        }
        sb.append('>')
        var atStart = true
        for (r in p.runs) {
            val embed = r.embedId?.let { doc.embeds[it] }
            if (embed != null) {
                sb.append(embed.xml)
                atStart = false
                continue
            }
            if (r.text.isEmpty()) continue
            val name = originalSpanIfUnchanged(r, gen.styles) ?: gen.textStyle(r)
            if (name != null) sb.append("""<text:span text:style-name="${xmlEscape(name)}">""")
            atStart = appendText(sb, r.text, atStart)
            if (name != null) sb.append("</text:span>")
        }
        sb.append("</").append(tag).append('>')
    }

    private fun originalSpanIfUnchanged(r: TextRun, styles: OdfStyles): String? {
        val name = r.extraXml ?: return null
        val st = styles.resolve(name) ?: return null
        val same = (st.bold == true) == r.bold && (st.italic == true) == r.italic && (st.underline == true) == r.underline &&
            (st.strike == true) == r.strike && st.sizePt == r.sizePt
        return if (same) name else null
    }

    /** Escapes text, writing runs of spaces and tabs the way ODF needs them. Returns whether the text ended at a line start. */
    private fun appendText(sb: StringBuilder, text: String, atStart: Boolean): Boolean {
        var i = 0
        var lineStart = atStart
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\t' -> { sb.append("<text:tab/>"); i++ }
                c == '\n' -> { sb.append("<text:line-break/>"); i++; lineStart = true; continue }
                c == ' ' -> {
                    var j = i
                    while (j < text.length && text[j] == ' ') j++
                    val n = j - i
                    if (lineStart || j == text.length) sb.append(if (n == 1) "<text:s/>" else """<text:s text:c="$n"/>""")
                    else {
                        sb.append(' ')
                        if (n > 1) sb.append(if (n == 2) "<text:s/>" else """<text:s text:c="${n - 1}"/>""")
                    }
                    i = j
                }
                c == EMBED_CHAR -> i++
                else -> { sb.append(xmlEscape(c.toString())); i++ }
            }
            lineStart = false
        }
        return lineStart
    }

    private val STYLES = """<?xml version="1.0" encoding="UTF-8"?><office:document-styles ${Odf.ROOT_NS} office:version="1.3"><office:styles>""" +
        """<style:default-style style:family="paragraph"><style:paragraph-properties fo:margin-bottom="0.08in" fo:line-height="115%"/><style:text-properties style:font-name="Liberation Sans" fo:font-family="'Liberation Sans'" fo:font-size="11pt"/></style:default-style>""" +
        """<style:style style:name="Standard" style:family="paragraph" style:class="text"/>""" +
        """<style:style style:name="Heading" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Standard" style:class="text"><style:paragraph-properties fo:margin-top="0.17in" fo:margin-bottom="0.08in" fo:keep-with-next="always"/><style:text-properties fo:font-weight="bold"/></style:style>""" +
        """<style:style style:name="Heading_20_1" style:display-name="Heading 1" style:family="paragraph" style:parent-style-name="Heading" style:default-outline-level="1" style:class="text"><style:text-properties fo:font-size="18pt"/></style:style>""" +
        """<style:style style:name="Heading_20_2" style:display-name="Heading 2" style:family="paragraph" style:parent-style-name="Heading" style:default-outline-level="2" style:class="text"><style:text-properties fo:font-size="15pt"/></style:style>""" +
        """<style:style style:name="Heading_20_3" style:display-name="Heading 3" style:family="paragraph" style:parent-style-name="Heading" style:default-outline-level="3" style:class="text"><style:text-properties fo:font-size="13pt"/></style:style>""" +
        """<style:style style:name="Title" style:family="paragraph" style:parent-style-name="Heading" style:class="chapter"><style:text-properties fo:font-size="26pt" fo:font-weight="normal"/></style:style>""" +
        """<style:style style:name="Quotations" style:family="paragraph" style:parent-style-name="Standard" style:class="html"><style:paragraph-properties fo:margin-left="0.4in" fo:margin-right="0.4in"/><style:text-properties fo:font-style="italic" fo:color="#595959"/></style:style>""" +
        """</office:styles><office:automatic-styles><style:page-layout style:name="pm1"><style:page-layout-properties fo:page-width="8.268in" fo:page-height="11.693in" fo:margin-top="1in" fo:margin-bottom="1in" fo:margin-left="1in" fo:margin-right="1in"/></style:page-layout></office:automatic-styles>""" +
        """<office:master-styles><style:master-page style:name="Standard" style:page-layout-name="pm1"/></office:master-styles></office:document-styles>"""
}
