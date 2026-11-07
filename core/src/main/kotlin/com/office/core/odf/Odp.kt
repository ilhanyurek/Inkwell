package com.office.core.odf

import com.office.core.pptx.Frame
import com.office.core.pptx.Presentation
import com.office.core.pptx.Slide
import com.office.core.pptx.SlideParagraph
import com.office.core.pptx.SlideRun
import com.office.core.pptx.SlideShape
import com.office.core.text.Alignment
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlText
import com.office.core.zip.ZipPackage

/** Reads OpenDocument presentations into the same [Presentation] model as PPTX, for viewing. */
object OdpReader {

    fun read(pkg: ZipPackage): Presentation {
        val content = pkg.xml("content.xml") ?: throw IllegalArgumentException("Missing content.xml")
        val styles = OdfStyles(pkg, content)
        val stylesXml = pkg.xml("styles.xml")
        val pages = content.child("body")?.child("presentation")?.children("page").orEmpty()

        // Page size: master page → page layout.
        val layouts = stylesXml?.child("automatic-styles")?.children("page-layout").orEmpty().associateBy { it.attr("style:name") }
        val masters = stylesXml?.child("master-styles")?.children("master-page").orEmpty().associateBy { it.attr("style:name") }
        val firstMaster = pages.firstOrNull()?.attr("draw:master-page-name")?.let { masters[it] } ?: masters.values.firstOrNull()
        val props = firstMaster?.attr("style:page-layout-name")?.let { layouts[it] }?.child("page-layout-properties")
        val width = Odf.toEmu(props?.attr("fo:page-width")) ?: 10080000L
        val height = Odf.toEmu(props?.attr("fo:page-height")) ?: 5670000L

        val pageBackgrounds = HashMap<String, Long>()
        for (container in listOfNotNull(content.child("automatic-styles"), stylesXml?.child("automatic-styles"), stylesXml?.child("styles"))) {
            for (st in container.children("style")) {
                val dp = st.child("drawing-page-properties") ?: st.child("graphic-properties") ?: continue
                if (dp.attr("draw:fill") == "solid") parseColor(dp.attr("draw:fill-color"))?.let { pageBackgrounds[st.attr("style:name").orEmpty()] = it }
            }
        }

        val slides = pages.mapIndexed { i, page ->
            val shapes = ArrayList<SlideShape>()
            val master = page.attr("draw:master-page-name")?.let { masters[it] }
            // Master pages carry backgrounds and decorations (not placeholders).
            master?.let { m -> collect(m, pkg, styles, pageBackgrounds, shapes, masterPage = true) }
            collect(page, pkg, styles, pageBackgrounds, shapes, masterPage = false)
            val notes = page.child("notes")?.elements?.filter { it.attr("presentation:class") == "notes" }
                ?.joinToString("\n") { f -> f.descendants("p").joinToString("\n") { it.text() } }.orEmpty().trim()
            val bg = page.attr("draw:style-name")?.let { pageBackgrounds[it] }
                ?: master?.attr("draw:style-name")?.let { pageBackgrounds[it] }
            Slide(shapes, bg, notes, path = "page${i + 1}")
        }
        return Presentation(width, height, slides)
    }

    private fun parseColor(v: String?): Long? =
        v?.takeIf { it.startsWith("#") && it.length == 7 }?.substring(1)?.toLongOrNull(16)?.let { it or 0xFF000000L }

    private fun frame(el: XmlElement): Frame? {
        val w = Odf.toEmu(el.attr("svg:width")) ?: return null
        val h = Odf.toEmu(el.attr("svg:height")) ?: return null
        val x = Odf.toEmu(el.attr("svg:x")) ?: 0L
        val y = Odf.toEmu(el.attr("svg:y")) ?: 0L
        return Frame(x, y, w, h)
    }

    private fun collect(
        container: XmlElement,
        pkg: ZipPackage,
        styles: OdfStyles,
        backgrounds: Map<String, Long>,
        out: MutableList<SlideShape>,
        masterPage: Boolean,
    ) {
        for (el in container.elements) {
            val cls = el.attr("presentation:class")
            if (masterPage && cls != null) continue // placeholders on the master are only prompts
            when (el.localName) {
                "frame" -> {
                    val f = frame(el) ?: continue
                    val image = el.child("image")
                    val box = el.child("text-box")
                    when {
                        image != null && box == null -> {
                            val href = image.attr("xlink:href")?.removePrefix("./") ?: continue
                            val bytes = pkg.bytes(href) ?: continue
                            out += SlideShape.Picture(f, bytes, href)
                        }
                        box != null -> {
                            val paragraphs = paragraphs(box, styles, el.attr("presentation:style-name") ?: el.attr("draw:style-name"), cls)
                            if (paragraphs.any { it.text.isNotBlank() }) {
                                out += SlideShape.TextBox(f, paragraphs, isTitle = cls == "title", verticalCenter = cls == "title" || cls == "subtitle")
                            }
                        }
                        el.child("table") != null -> {
                            val rows = OdtReader.tableRows(el.child("table")!!)
                            out += SlideShape.TextBox(f, rows.map { SlideParagraph(listOf(SlideRun(it.joinToString("   |   "), sizePt = 14f))) })
                        }
                    }
                }
                "custom-shape", "rect", "ellipse", "polygon", "path" -> {
                    val f = frame(el) ?: continue
                    val graphic = el.attr("draw:style-name")
                    val fill = graphicFill(container, styles, graphic, backgrounds)
                    val paragraphs = paragraphs(el, styles, graphic, null)
                    if (paragraphs.any { it.text.isNotBlank() }) out += SlideShape.TextBox(f, paragraphs, fill = fill, verticalCenter = true)
                    else if (fill != null) out += SlideShape.Rect(f, fill)
                }
                "g" -> collect(el, pkg, styles, backgrounds, out, masterPage)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun graphicFill(container: XmlElement, styles: OdfStyles, name: String?, backgrounds: Map<String, Long>): Long? =
        name?.let { backgrounds[it] }

    private fun paragraphs(box: XmlElement, styles: OdfStyles, frameStyle: String?, cls: String?): List<SlideParagraph> {
        val base = styles.resolve(frameStyle)
        val defaultSize = base?.sizePt ?: when (cls) {
            "title" -> 40f
            "subtitle" -> 28f
            else -> 24f
        }
        val out = ArrayList<SlideParagraph>()
        fun walk(e: XmlElement, level: Int, bullet: Boolean) {
            for (c in e.elements) {
                when (c.localName) {
                    "p", "h" -> out += paragraph(c, styles, defaultSize, base?.bold == true, base?.color, level, bullet)
                    "list" -> c.elements.forEach { item -> walk(item, level + if (bullet || e.localName == "list-item") 1 else 0, true) }
                    "list-item", "list-header" -> walk(c, level, bullet)
                }
            }
        }
        walk(box, 0, cls == "outline")
        // A list nested in a list item starts one level deeper; the first list is level 0.
        val minLevel = out.minOfOrNull { it.level } ?: 0
        return out.map { it.copy(level = it.level - minLevel) }
    }

    private fun paragraph(p: XmlElement, styles: OdfStyles, defaultSize: Float, bold: Boolean, color: Long?, level: Int, bullet: Boolean): SlideParagraph {
        val ps = styles.resolve(p.attr("text:style-name"))
        val size = ps?.sizePt ?: defaultSize
        val runs = ArrayList<SlideRun>()
        fun walk(e: XmlElement, span: String?) {
            val st = styles.resolve(span)
            for (n in e.children) {
                when (n) {
                    is XmlText -> if (n.text.isNotEmpty()) runs += SlideRun(
                        n.text.replace(Regex("[\\t\\r\\n]+"), " "),
                        bold = st?.bold ?: ps?.bold ?: bold,
                        italic = st?.italic ?: ps?.italic ?: false,
                        underline = st?.underline ?: ps?.underline ?: false,
                        sizePt = st?.sizePt ?: size,
                        color = st?.color ?: ps?.color ?: color,
                    )
                    is XmlElement -> when (n.localName) {
                        "span", "a" -> walk(n, n.attr("text:style-name") ?: span)
                        "s" -> runs += SlideRun(" ".repeat((n.attr("text:c")?.toIntOrNull() ?: 1).coerceIn(1, 100)), sizePt = size)
                        "tab" -> runs += SlideRun("\t", sizePt = size)
                        "line-break" -> runs += SlideRun(" ", sizePt = size)
                        else -> walk(n, span)
                    }
                }
            }
        }
        walk(p, null)
        return SlideParagraph(runs, level = level, bullet = bullet, alignment = ps?.align ?: Alignment.START)
    }
}
