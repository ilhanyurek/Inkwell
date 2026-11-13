package com.office.core.docx

import com.office.core.text.Alignment
import com.office.core.text.CharStyle
import com.office.core.text.ListLabel
import com.office.core.text.ListType
import com.office.core.text.ParaAttrs
import com.office.core.text.ParaLook
import com.office.core.text.ParagraphStyle
import com.office.core.text.RunLook
import com.office.core.text.TextFormatting
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlParser
import com.office.core.zip.ZipPackage

/**
 * Works out how text in a Word document really looks: document defaults, the paragraph style and the
 * styles it is based on, the list level, the character style and finally the direct formatting.
 * The model keeps unmodelled properties as raw XML; this class reads them for display only.
 */
class DocxFormatting(pkg: ZipPackage?) : TextFormatting {

    private class Style(val id: String, val type: String, val basedOn: String?, val pPr: XmlElement?, val rPr: XmlElement?)

    private val styles = HashMap<String, Style>()
    private var defaultParagraphStyle: String? = null
    private var defaultRPr: XmlElement? = null
    private var defaultPPr: XmlElement? = null
    private val themeFonts = HashMap<String, String>()
    private val themeColors = HashMap<String, Long>()
    /** styleId → our heading/title/quote style, and the document's own id for each of ours. */
    private val mapped = HashMap<String, ParagraphStyle>()
    private val ownIds = HashMap<ParagraphStyle, String>()
    val numbering = DocxNumbering(pkg)

    init {
        val root = runCatching { pkg?.xml("word/styles.xml") }.getOrNull()
        if (root != null) {
            defaultRPr = root.path("docDefaults", "rPrDefault", "rPr")
            defaultPPr = root.path("docDefaults", "pPrDefault", "pPr")
            for (s in root.children("style")) {
                val id = s.attr("styleId") ?: continue
                val type = s.attr("type") ?: "paragraph"
                styles[id] = Style(id, type, s.child("basedOn")?.attr("val"), s.child("pPr"), s.child("rPr"))
                if (type == "paragraph" && s.attr("default").let { it == "1" || it == "true" }) defaultParagraphStyle = id
                if (type != "paragraph") continue
                val name = s.child("name")?.attr("val")?.lowercase().orEmpty()
                val outline = s.path("pPr", "outlineLvl")?.attr("val")?.toIntOrNull()
                val style = when {
                    name == "title" -> ParagraphStyle.TITLE
                    name == "quote" -> ParagraphStyle.QUOTE
                    name == "heading 1" || outline == 0 -> ParagraphStyle.HEADING1
                    name == "heading 2" || outline == 1 -> ParagraphStyle.HEADING2
                    name == "heading 3" || outline == 2 -> ParagraphStyle.HEADING3
                    else -> DocxReader.headingFromId(id)
                }
                if (style != null) {
                    mapped[id] = style
                    // Prefer the style Word itself calls "heading N" over others that share its outline level.
                    val exact = name == "title" || name == "quote" || name.startsWith("heading ")
                    if (exact || style !in ownIds) ownIds[style] = id
                }
            }
        }
        val theme = runCatching {
            pkg?.entries?.keys?.firstOrNull { it.startsWith("word/theme/") && it.endsWith(".xml") }?.let { pkg.xml(it) }
        }.getOrNull()
        theme?.path("themeElements", "fontScheme")?.let { fs ->
            fs.path("majorFont", "latin")?.attr("typeface")?.let { themeFonts["major"] = it }
            fs.path("minorFont", "latin")?.attr("typeface")?.let { themeFonts["minor"] = it }
        }
        theme?.path("themeElements", "clrScheme")?.elements?.forEach { c ->
            val v = c.child("srgbClr")?.attr("val") ?: c.child("sysClr")?.attr("lastClr")
            v?.toLongOrNull(16)?.let { themeColors[c.localName] = it or 0xFF000000L }
        }
    }

    /** The paragraph style our model's [style] maps to in this document, as [DocxWriter] writes it. */
    fun styleIdOf(style: ParagraphStyle, customStyleId: String?): String? {
        val source = customStyleId?.let { mapped[it] ?: DocxReader.headingFromId(it) } ?: ParagraphStyle.NORMAL
        if (customStyleId != null && source == style) return customStyleId
        return ownIds[style] ?: builtinId(style)
    }

    fun mappedStyle(styleId: String): ParagraphStyle? = mapped[styleId] ?: DocxReader.headingFromId(styleId)

    fun hasStyle(id: String) = id in styles

    private fun chain(id: String?): List<Style> {
        val out = ArrayList<Style>()
        var cur = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val s = styles[cur] ?: break
            out += s
            cur = s.basedOn
        }
        return out.asReversed()
    }

    private fun overlay(into: MutableMap<String, XmlElement>, el: XmlElement?) {
        el?.elements?.forEach { into[it.localName] = it }
    }

    private val fragmentCache = HashMap<String, List<XmlElement>>()

    private fun fragments(xml: String?): List<XmlElement> {
        if (xml.isNullOrEmpty()) return emptyList()
        return fragmentCache.getOrPut(xml) {
            // Fragments use the w: prefix without declaring it; the parser does not need the declaration.
            runCatching { XmlParser.parse("<x>$xml</x>").elements }.getOrDefault(emptyList())
        }
    }

    private fun on(e: XmlElement?): Boolean? {
        e ?: return null
        val v = e.attr("val") ?: return true
        return v != "0" && v != "false" && v != "none"
    }

    private fun twips(v: String?): Float? = v?.toFloatOrNull()?.div(20f)

    // --- paragraphs -----------------------------------------------------------------------------

    private val paraCache = HashMap<ParaAttrs, ParaLook>()

    override fun paraLook(paragraph: ParaAttrs): ParaLook = paraCache.getOrPut(paragraph) {
        val styleId = styleIdOf(paragraph.style, paragraph.customStyleId) ?: defaultParagraphStyle
        val props = LinkedHashMap<String, XmlElement>()
        overlay(props, defaultPPr)
        val styleChain = chain(styleId)
        styleChain.forEach { overlay(props, it.pPr) }
        val styleAlign = alignmentOf(props["jc"])
        // Indents of the list level sit between the style and the paragraph's own properties.
        numberingOf(paragraph, styleChain)?.let { (numId, ilvl) -> numbering.level(numId, ilvl)?.pPr?.let { overlay(props, it) } }
        fragments(paragraph.extraXml).forEach { props[it.localName] = it }

        val ind = props["ind"]
        val left = twips(ind?.attr("start") ?: ind?.attr("left")) ?: 0f
        val right = twips(ind?.attr("end") ?: ind?.attr("right")) ?: 0f
        val first = twips(ind?.attr("hanging"))?.let { -it } ?: twips(ind?.attr("firstLine")) ?: 0f
        val sp = props["spacing"]
        val line = sp?.attr("line")?.toFloatOrNull()
        val rule = sp?.attr("lineRule") ?: "auto"
        ParaLook(
            indentLeft = left,
            indentRight = right,
            firstLine = first,
            spaceBefore = if (sp?.attr("beforeAutospacing").let { it == "1" || it == "true" }) 14f else twips(sp?.attr("before")) ?: 0f,
            spaceAfter = if (sp?.attr("afterAutospacing").let { it == "1" || it == "true" }) 14f else twips(sp?.attr("after")) ?: 0f,
            lineMultiple = if (line != null && rule == "auto") line / 240f else null,
            lineExactPt = if (line != null && rule != "auto") line / 20f else null,
            alignment = styleAlign,
            pageBreakBefore = on(props["pageBreakBefore"]) == true,
            contextualSpacing = on(props["contextualSpacing"]) == true,
            styleId = styleId,
        )
    }

    private fun alignmentOf(jc: XmlElement?): Alignment? = when (jc?.attr("val")) {
        null -> null
        "center" -> Alignment.CENTER
        "right", "end" -> Alignment.END
        "both", "distribute" -> Alignment.JUSTIFY
        else -> Alignment.START
    }

    /** numId and level of a list paragraph: from its own numPr, or from its style when the list comes from there. */
    private fun numberingOf(paragraph: ParaAttrs, styleChain: List<Style>): Pair<String, Int>? {
        if (paragraph.list == ListType.NONE || paragraph.list != paragraph.numType || paragraph.numXml == null) return null
        if (paragraph.numXml.isNotEmpty()) {
            val own = DocxNumbering.parseNumPr(paragraph.numXml)
            if (own != null) return own
        }
        var numId: String? = null
        var ilvl: Int? = null
        for (s in styleChain) {
            val n = s.pPr?.child("numPr") ?: continue
            n.child("numId")?.attr("val")?.let { numId = it }
            n.child("ilvl")?.attr("val")?.toIntOrNull()?.let { ilvl = it }
        }
        val id = numId?.takeIf { it != "0" } ?: return null
        return id to (ilvl ?: 0)
    }

    override fun listLabels(paragraphs: List<ParaAttrs>): List<ListLabel?> =
        numbering.labels(paragraphs.map { p ->
            val styleId = styleIdOf(p.style, p.customStyleId) ?: defaultParagraphStyle
            numberingOf(p, chain(styleId))
        })

    // --- runs -----------------------------------------------------------------------------------

    private val runCache = HashMap<Pair<String?, CharStyle>, RunLook>()

    override fun runLook(paragraph: ParaAttrs, style: CharStyle): RunLook {
        val styleId = styleIdOf(paragraph.style, paragraph.customStyleId) ?: defaultParagraphStyle
        return runCache.getOrPut(styleId to style) { computeRun(styleId, paragraph.style, style) }
    }

    private fun computeRun(styleId: String?, paraStyle: ParagraphStyle, st: CharStyle): RunLook {
        val props = LinkedHashMap<String, XmlElement>()
        overlay(props, defaultRPr)
        val known = styleId != null && styleId in styles
        chain(styleId).forEach { overlay(props, it.rPr) }
        val direct = fragments(st.extraXml)
        direct.firstOrNull { it.localName == "rStyle" }?.attr("val")?.let { rs -> chain(rs).forEach { overlay(props, it.rPr) } }
        direct.forEach { props[it.localName] = it }

        // A style the document does not define yet (a heading picked in the editor) uses our own definition.
        val builtin = if (!known) BUILTIN[paraStyle] else null
        val fonts = props["rFonts"]
        val font = fonts?.attr("ascii") ?: fonts?.attr("hAnsi")
            ?: (fonts?.attr("asciiTheme") ?: fonts?.attr("hAnsiTheme"))?.let { themeFonts[if (it.startsWith("major")) "major" else "minor"] }
        val vert = when (props["vertAlign"]?.attr("val")) {
            "superscript" -> 1
            "subscript" -> -1
            else -> 0
        }
        return RunLook(
            font = font,
            sizePt = st.sizePt ?: props["sz"]?.attr("val")?.toFloatOrNull()?.div(2f) ?: builtin?.first,
            color = color(props["color"]),
            highlight = HIGHLIGHTS[props["highlight"]?.attr("val")] ?: shade(props["shd"]),
            bold = st.bold || on(props["b"]) == true || (builtin?.second == true && on(props["b"]) != false),
            italic = st.italic || on(props["i"]) == true,
            underline = st.underline || (props["u"]?.let { on(it) } == true && direct.none { it.localName == "u" }),
            strike = st.strike || on(props["strike"]) == true || on(props["dstrike"]) == true,
            vertical = vert,
            caps = on(props["caps"]) == true,
            smallCaps = on(props["smallCaps"]) == true,
        )
    }

    private fun color(e: XmlElement?): Long? {
        e ?: return null
        val v = e.attr("val")
        v?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { return it or 0xFF000000L }
        val theme = e.attr("themeColor") ?: return null
        val key = when (theme) {
            "text1", "dark1" -> "dk1"
            "text2", "dark2" -> "dk2"
            "background1", "light1" -> "lt1"
            "background2", "light2" -> "lt2"
            "hyperlink" -> "hlink"
            "followedHyperlink" -> "folHlink"
            else -> theme
        }
        return themeColors[key]
    }

    private fun shade(e: XmlElement?): Long? =
        e?.attr("fill")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { it or 0xFF000000L }

    companion object {
        /** Size and boldness of the styles the editor adds to documents that lack them (see DocxWriter). */
        private val BUILTIN = mapOf(
            ParagraphStyle.TITLE to (26f to false),
            ParagraphStyle.HEADING1 to (18f to true),
            ParagraphStyle.HEADING2 to (15f to true),
            ParagraphStyle.HEADING3 to (13f to true),
        )

        fun builtinId(style: ParagraphStyle): String? = when (style) {
            ParagraphStyle.TITLE -> "Title"
            ParagraphStyle.HEADING1 -> "Heading1"
            ParagraphStyle.HEADING2 -> "Heading2"
            ParagraphStyle.HEADING3 -> "Heading3"
            ParagraphStyle.QUOTE -> "Quote"
            ParagraphStyle.NORMAL -> null
        }

        private val HIGHLIGHTS = mapOf(
            "yellow" to 0xFFFFFF00L, "green" to 0xFF00FF00L, "cyan" to 0xFF00FFFFL, "magenta" to 0xFFFF00FFL,
            "blue" to 0xFF0000FFL, "red" to 0xFFFF0000L, "darkBlue" to 0xFF000080L, "darkCyan" to 0xFF008080L,
            "darkGreen" to 0xFF008000L, "darkMagenta" to 0xFF800080L, "darkRed" to 0xFF800000L,
            "darkYellow" to 0xFF808000L, "darkGray" to 0xFF808080L, "lightGray" to 0xFFC0C0C0L, "black" to 0xFF000000L,
        )
    }
}
