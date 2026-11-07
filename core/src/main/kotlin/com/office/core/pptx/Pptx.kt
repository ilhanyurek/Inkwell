package com.office.core.pptx

import com.office.core.text.Alignment
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlNode
import com.office.core.xml.XmlText
import com.office.core.xml.XmlWriter
import com.office.core.xml.withChildren
import com.office.core.zip.ZipPackage
import java.io.InputStream

/** Geometry in EMUs (914400 per inch). */
data class Frame(val x: Long, val y: Long, val width: Long, val height: Long)

data class SlideRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val sizePt: Float? = null,
    /** ARGB colour, or null to use the theme text colour. */
    val color: Long? = null,
)

data class SlideParagraph(
    val runs: List<SlideRun>,
    val level: Int = 0,
    val bullet: Boolean = false,
    val alignment: Alignment = Alignment.START,
) {
    val text get() = runs.joinToString("") { it.text }
}

sealed class SlideShape {
    abstract val frame: Frame

    data class TextBox(
        override val frame: Frame,
        val paragraphs: List<SlideParagraph>,
        val fill: Long? = null,
        val isTitle: Boolean = false,
        val verticalCenter: Boolean = false,
        /** `cNvPr/@id` of the shape, used to edit its text; null for text we cannot edit (tables). */
        val shapeId: String? = null,
    ) : SlideShape()

    data class Picture(override val frame: Frame, val image: ByteArray, val contentPath: String) : SlideShape() {
        override fun equals(other: Any?) = other is Picture && other.frame == frame && other.contentPath == contentPath
        override fun hashCode() = frame.hashCode() * 31 + contentPath.hashCode()
    }

    data class Rect(override val frame: Frame, val fill: Long) : SlideShape()
}

data class Slide(val shapes: List<SlideShape>, val background: Long?, val notes: String, val path: String = "")

data class Presentation(val width: Long, val height: Long, val slides: List<Slide>) {
    val aspectRatio: Float get() = if (height == 0L) 16f / 9f else width.toFloat() / height
}

object PptxReader {

    private const val DEFAULT_W = 12192000L
    private const val DEFAULT_H = 6858000L

    fun read(input: InputStream): Presentation = read(ZipPackage.read(input))

    fun read(pkg: ZipPackage): Presentation {
        val presPath = pkg.relationships("").values
            .firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "ppt/presentation.xml"
        val pres = pkg.xml(presPath) ?: throw IllegalArgumentException("Missing presentation part")
        val size = pres.child("sldSz")
        val width = size?.attr("cx")?.toLongOrNull() ?: DEFAULT_W
        val height = size?.attr("cy")?.toLongOrNull() ?: DEFAULT_H
        val rels = pkg.relationships(presPath)
        val theme = rels.values.firstOrNull { it.type.endsWith("/theme") }?.target?.let { pkg.xml(it) }
            ?: pkg.relationships(rels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.target ?: "")
                .values.firstOrNull { it.type.endsWith("/theme") }?.target?.let { pkg.xml(it) }
        val colors = theme?.let(::themeColors) ?: emptyMap()

        val slidePaths = pres.child("sldIdLst")?.children("sldId").orEmpty().mapNotNull { id ->
            (id.attr("r:id") ?: id.attr("id"))?.let { rels[it]?.target }
        }
        val slides = slidePaths.mapNotNull { path -> pkg.xml(path)?.let { readSlide(pkg, path, it, colors) } }
        return Presentation(width, height, slides)
    }

    private class Placeholder(val type: String?, val idx: String?, val frame: Frame?, val defaultSize: Float?)

    private fun readSlide(pkg: ZipPackage, path: String, root: XmlElement, colors: Map<String, Long>): Slide {
        val rels = pkg.relationships(path)
        val layoutPath = rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
        val layout = layoutPath?.let { pkg.xml(it) }
        val masterPath = layoutPath?.let { lp -> pkg.relationships(lp).values.firstOrNull { it.type.endsWith("/slideMaster") }?.target }
        val master = masterPath?.let { pkg.xml(it) }
        val inherited = (layout?.let(::placeholders).orEmpty()) + (master?.let(::placeholders).orEmpty())

        val shapes = ArrayList<SlideShape>()
        root.path("cSld", "spTree")?.let { collectShapes(pkg, path, rels, it, inherited, colors, shapes, 0L, 0L) }

        val background = backgroundOf(root, colors) ?: layout?.let { backgroundOf(it, colors) } ?: master?.let { backgroundOf(it, colors) }

        val notes = rels.values.firstOrNull { it.type.endsWith("/notesSlide") }?.target?.let { pkg.xml(it) }
            ?.descendants("sp")
            ?.filter { sp -> sp.path("nvSpPr", "nvPr", "ph")?.attr("type") == "body" }
            ?.joinToString("\n") { sp -> sp.descendants("p").joinToString("\n") { p -> p.descendants("t").joinToString("") { it.text() } } }
            .orEmpty()
        return Slide(shapes, background, notes.trim(), path)
    }

    private fun backgroundOf(root: XmlElement, colors: Map<String, Long>): Long? =
        root.path("cSld", "bg", "bgPr")?.child("solidFill")?.let { color(it, colors) }

    private fun placeholders(root: XmlElement): List<Placeholder> =
        root.path("cSld", "spTree")?.descendants("sp").orEmpty().mapNotNull { sp ->
            val ph = sp.path("nvSpPr", "nvPr", "ph") ?: return@mapNotNull null
            val size = sp.descendants("defRPr").firstOrNull()?.attr("sz")?.toFloatOrNull()?.div(100f)
            Placeholder(ph.attr("type"), ph.attr("idx"), frameOf(sp.child("spPr")), size)
        }

    private fun findPlaceholder(ph: XmlElement, inherited: List<Placeholder>): Placeholder? {
        val type = ph.attr("type")
        val idx = ph.attr("idx")
        return inherited.firstOrNull { idx != null && it.idx == idx && it.frame != null }
            ?: inherited.firstOrNull { it.type == (type ?: "body") && it.frame != null }
            ?: if (type == "ctrTitle") inherited.firstOrNull { it.type == "title" && it.frame != null } else null
            ?: if (type == "subTitle") inherited.firstOrNull { it.type == "body" && it.frame != null } else null
    }

    private fun collectShapes(
        pkg: ZipPackage,
        slidePath: String,
        rels: Map<String, ZipPackage.Relationship>,
        tree: XmlElement,
        inherited: List<Placeholder>,
        colors: Map<String, Long>,
        out: MutableList<SlideShape>,
        dx: Long,
        dy: Long,
    ) {
        for (el in tree.elements) {
            when (el.localName) {
                "sp" -> {
                    val ph = el.path("nvSpPr", "nvPr", "ph")
                    val placeholder = ph?.let { findPlaceholder(it, inherited) }
                    val frame = (frameOf(el.child("spPr")) ?: placeholder?.frame)?.offset(dx, dy) ?: continue
                    val phType = ph?.attr("type")
                    val isTitle = phType == "title" || phType == "ctrTitle"
                    val defaultSize = placeholder?.defaultSize ?: when {
                        isTitle -> 36f
                        phType == "subTitle" -> 20f
                        ph != null -> 20f
                        else -> 18f
                    }
                    val fill = el.child("spPr")?.child("solidFill")?.let { color(it, colors) }
                    val body = el.child("txBody")
                    val paragraphs = body?.children("p")?.map { readParagraph(it, colors, defaultSize, ph != null && !isTitle && phType != "subTitle" && phType != "ctrTitle") }.orEmpty()
                    val anchor = body?.child("bodyPr")?.attr("anchor") ?: if (isTitle) "ctr" else null
                    val hasText = paragraphs.any { it.text.isNotBlank() }
                    val id = el.path("nvSpPr", "cNvPr")?.attr("id")
                    // Empty placeholders are kept (invisible) so their text can be filled in.
                    if (hasText || (ph != null && body != null)) out += SlideShape.TextBox(frame, paragraphs, fill, isTitle, anchor == "ctr", id)
                    else if (fill != null) out += SlideShape.Rect(frame, fill)
                }
                "pic" -> {
                    val frame = frameOf(el.child("spPr"))?.offset(dx, dy) ?: continue
                    val rid = el.path("blipFill", "blip")?.let { it.attr("r:embed") ?: it.attr("embed") } ?: continue
                    val target = rels[rid]?.takeIf { !it.external }?.target ?: continue
                    val bytes = pkg.bytes(target) ?: continue
                    out += SlideShape.Picture(frame, bytes, target)
                }
                "grpSp" -> {
                    val xfrm = el.path("grpSpPr", "xfrm")
                    val off = xfrm?.child("off")
                    val chOff = xfrm?.child("chOff")
                    val ox = (off?.attr("x")?.toLongOrNull() ?: 0) - (chOff?.attr("x")?.toLongOrNull() ?: 0)
                    val oy = (off?.attr("y")?.toLongOrNull() ?: 0) - (chOff?.attr("y")?.toLongOrNull() ?: 0)
                    collectShapes(pkg, slidePath, rels, el, inherited, colors, out, dx + ox, dy + oy)
                }
                "graphicFrame" -> {
                    // Tables: flatten into a text box, one paragraph per row.
                    val xfrm = el.child("xfrm")
                    val frame = xfrm?.let { frameFromXfrm(it) }?.offset(dx, dy) ?: continue
                    val rows = el.descendants("tr")
                    if (rows.isNotEmpty()) {
                        val paragraphs = rows.map { tr ->
                            SlideParagraph(listOf(SlideRun(tr.children("tc").joinToString("   |   ") { tc -> tc.descendants("t").joinToString("") { it.text() } }, sizePt = 14f)))
                        }
                        out += SlideShape.TextBox(frame, paragraphs)
                    }
                }
                "AlternateContent" -> el.child("Fallback")?.let {
                    collectShapes(pkg, slidePath, rels, it, inherited, colors, out, dx, dy)
                }
            }
        }
    }

    private fun readParagraph(p: XmlElement, colors: Map<String, Long>, defaultSize: Float, bodyPlaceholder: Boolean): SlideParagraph {
        val pPr = p.child("pPr")
        val level = pPr?.attr("lvl")?.toIntOrNull() ?: 0
        val bullet = when {
            pPr?.child("buNone") != null -> false
            pPr?.child("buChar") != null || pPr?.child("buAutoNum") != null -> true
            else -> bodyPlaceholder
        }
        val alignment = when (pPr?.attr("algn")) {
            "ctr" -> Alignment.CENTER
            "r" -> Alignment.END
            "just" -> Alignment.JUSTIFY
            else -> Alignment.START
        }
        val endSize = p.child("endParaRPr")?.attr("sz")?.toFloatOrNull()?.div(100f)
        val runs = ArrayList<SlideRun>()
        for (el in p.elements) {
            when (el.localName) {
                "r", "fld" -> {
                    val rPr = el.child("rPr")
                    runs += SlideRun(
                        text = el.child("t")?.text().orEmpty(),
                        bold = rPr?.attr("b") == "1",
                        italic = rPr?.attr("i") == "1",
                        underline = rPr?.attr("u").let { it != null && it != "none" },
                        sizePt = rPr?.attr("sz")?.toFloatOrNull()?.div(100f) ?: endSize ?: defaultSize,
                        color = rPr?.child("solidFill")?.let { color(it, colors) },
                    )
                }
                "br" -> runs += SlideRun("\n", sizePt = defaultSize)
            }
        }
        return SlideParagraph(runs, level, bullet && runs.any { it.text.isNotBlank() }, alignment)
    }

    private fun frameOf(spPr: XmlElement?): Frame? = spPr?.child("xfrm")?.let(::frameFromXfrm)

    private fun frameFromXfrm(xfrm: XmlElement): Frame? {
        val off = xfrm.child("off") ?: return null
        val ext = xfrm.child("ext") ?: return null
        return Frame(
            off.attr("x")?.toLongOrNull() ?: 0,
            off.attr("y")?.toLongOrNull() ?: 0,
            ext.attr("cx")?.toLongOrNull() ?: 0,
            ext.attr("cy")?.toLongOrNull() ?: 0,
        )
    }

    private fun Frame.offset(dx: Long, dy: Long) = if (dx == 0L && dy == 0L) this else copy(x = x + dx, y = y + dy)

    private fun color(fill: XmlElement, theme: Map<String, Long>): Long? {
        fill.child("srgbClr")?.attr("val")?.toLongOrNull(16)?.let { return 0xFF000000 or it }
        fill.child("schemeClr")?.attr("val")?.let { name ->
            val key = when (name) {
                "tx1" -> "dk1"; "bg1" -> "lt1"; "tx2" -> "dk2"; "bg2" -> "lt2"
                else -> name
            }
            return theme[key]
        }
        return null
    }

    private fun themeColors(theme: XmlElement): Map<String, Long> {
        val scheme = theme.path("themeElements", "clrScheme") ?: return emptyMap()
        val out = HashMap<String, Long>()
        for (c in scheme.elements) {
            val rgb = c.child("srgbClr")?.attr("val") ?: c.child("sysClr")?.attr("lastClr") ?: continue
            rgb.toLongOrNull(16)?.let { out[c.localName] = 0xFF000000 or it }
        }
        return out
    }
}

/** Edits slide text in place, keeping every other part of the package untouched. */
object PptxEditor {

    /**
     * Replaces the text of shape [shapeId] on [slidePath] with [paragraphs]. Each new paragraph
     * reuses the paragraph and first-run formatting of the original paragraph at the same position
     * (or the last one), so the slide keeps its look.
     */
    fun replaceText(pkg: ZipPackage, slidePath: String, shapeId: String, paragraphs: List<String>): ZipPackage {
        val root = pkg.xml(slidePath) ?: return pkg
        var changed = false
        val updated = transform(root) { el ->
            if (el.localName != "sp" || el.path("nvSpPr", "cNvPr")?.attr("id") != shapeId) return@transform null
            val body = el.child("txBody") ?: return@transform null
            changed = true
            el.withChildren(el.children.map { if (it === body) newBody(body, paragraphs) else it })
        }
        if (!changed) return pkg
        val entries = LinkedHashMap(pkg.entries)
        entries[slidePath] = XmlWriter.write(updated, declaration = true).toByteArray(Charsets.UTF_8)
        return ZipPackage(entries)
    }

    private fun newBody(body: XmlElement, paragraphs: List<String>): XmlElement {
        val old = body.children("p")
        val aPrefix = old.firstOrNull()?.name?.substringBefore(':', "")?.let { if (it.isEmpty() || it == old.first().name) "" else "$it:" } ?: "a:"
        val kept = body.children.filter { it !is XmlElement || it.localName != "p" }
        val newParas = paragraphs.ifEmpty { listOf("") }.mapIndexed { i, text ->
            val template = old.getOrNull(minOf(i, old.lastIndex))
            val pPr = template?.child("pPr")
            val rPr = template?.children("r")?.firstOrNull()?.child("rPr") ?: template?.child("endParaRPr")?.let {
                XmlElement(it.name.replace("endParaRPr", "rPr"), it.attributes, it.children)
            }
            val end = template?.child("endParaRPr")
            val children = ArrayList<XmlNode>()
            pPr?.let { children += it }
            if (text.isNotEmpty()) {
                val run = ArrayList<XmlNode>()
                rPr?.let { run += it }
                run += XmlElement("${aPrefix}t", emptyMap(), listOf(XmlText(text)))
                children += XmlElement("${aPrefix}r", emptyMap(), run)
            }
            end?.let { children += it }
            XmlElement("${aPrefix}p", emptyMap(), children)
        }
        // Keep bodyPr/lstStyle first, then the new paragraphs.
        return body.withChildren(kept + newParas)
    }

    /** Rebuilds the tree, replacing any element for which [replace] returns a new element. */
    private fun transform(el: XmlElement, replace: (XmlElement) -> XmlElement?): XmlElement {
        replace(el)?.let { return it }
        var changed = false
        val kids = el.children.map { c ->
            if (c is XmlElement) transform(c, replace).also { if (it !== c) changed = true } else c
        }
        return if (changed) el.withChildren(kids) else el
    }
}
