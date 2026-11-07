package com.office.core.pptx

import com.office.core.xml.XmlElement
import com.office.core.xml.XmlWriter
import com.office.core.xml.withChildren
import com.office.core.xml.xmlEscape
import com.office.core.zip.ZipPackage
import java.io.ByteArrayOutputStream

/**
 * Adds, duplicates, deletes and reorders slides in a presentation package, and builds new
 * presentations. Every operation returns a new [ZipPackage]; parts it does not touch stay as they are.
 */
object PptxSlides {

    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val ODR = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val SLIDE_CT = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    private const val DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private class Doc(val pkg: ZipPackage) {
        val presPath = pkg.relationships("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "ppt/presentation.xml"
        val presDir = presPath.substringBeforeLast('/', "")
        val presRelsPath = relsPath(presPath)
        val pres = pkg.xml(presPath) ?: throw IllegalArgumentException("Missing presentation part")
        val rels = pkg.relationships(presPath)
        val idList = pres.child("sldIdLst")
        val ids = idList?.children("sldId").orEmpty()
        val entries = LinkedHashMap(pkg.entries)

        fun slidePath(i: Int) = ids.getOrNull(i)?.let { (it.attr("r:id") ?: it.attr("id"))?.let { r -> rels[r]?.target } }

        fun put(path: String, text: String) { entries[path] = text.toByteArray(Charsets.UTF_8) }

        fun text(path: String) = entries[path]?.let { String(it, Charsets.UTF_8) }

        /** Writes presentation.xml with [newIds] as the slide list. */
        fun writeIds(newIds: List<XmlElement>) {
            val listName = idList?.name ?: (pres.name.substringBefore(':', "").let { if (it == pres.name) "" else "$it:" } + "sldIdLst")
            val newList = XmlElement(listName, idList?.attributes ?: emptyMap(), newIds)
            val kids = if (idList != null) pres.children.map { if (it === idList) newList else it }
            else {
                // sldIdLst follows sldMasterIdLst / notesMasterIdLst / handoutMasterIdLst.
                val at = pres.children.indexOfLast { it is XmlElement && it.localName in setOf("sldMasterIdLst", "notesMasterIdLst", "handoutMasterIdLst") } + 1
                pres.children.toMutableList().apply { add(at, newList) }
            }
            put(presPath, XmlWriter.write(pres.withChildren(kids), declaration = true))
        }

        fun addRel(id: String, type: String, target: String) {
            val t = text(presRelsPath) ?: """$DECL<Relationships xmlns="$REL"></Relationships>"""
            put(presRelsPath, t.replace("</Relationships>", """<Relationship Id="$id" Type="$type" Target="${xmlEscape(target)}"/></Relationships>"""))
        }

        fun removeRel(relsFile: String, id: String) {
            val t = text(relsFile) ?: return
            put(relsFile, t.replace(Regex("""<Relationship\b[^>]*\bId="${Regex.escape(id)}"[^>]*/>"""), ""))
        }

        fun addOverride(path: String, type: String) {
            val t = text("[Content_Types].xml") ?: return
            if (t.contains("PartName=\"/$path\"")) return
            put("[Content_Types].xml", t.replace("</Types>", """<Override PartName="/$path" ContentType="$type"/></Types>"""))
        }

        fun removeOverride(path: String) {
            val t = text("[Content_Types].xml") ?: return
            put("[Content_Types].xml", t.replace(Regex("""<Override\b[^>]*PartName="/${Regex.escape(path)}"[^>]*/>"""), ""))
        }

        fun newRelId(): String {
            val taken = rels.keys
            var n = 1
            while ("rIdInkwell$n" in taken) n++
            return "rIdInkwell$n"
        }

        fun newSlidePath(): String {
            var n = 1
            val dir = (if (presDir.isEmpty()) "" else "$presDir/") + "slides"
            while ("$dir/slide$n.xml" in entries) n++
            return "$dir/slide$n.xml"
        }

        fun newSlideId(): Long = (ids.mapNotNull { it.attr("id")?.toLongOrNull() }.maxOrNull() ?: 255L) + 1

        fun sldIdElement(id: Long, relId: String): XmlElement {
            val name = ids.firstOrNull()?.name ?: (pres.name.substringBefore(':', "").let { if (it == pres.name) "" else "$it:" } + "sldId")
            val relAttr = ids.firstOrNull()?.attributes?.keys?.firstOrNull { it.endsWith(":id") } ?: "r:id"
            return XmlElement(name, linkedMapOf("id" to id.toString(), relAttr to relId), emptyList())
        }

        fun result() = ZipPackage(entries)
    }

    private fun relsPath(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        val file = part.substringAfterLast('/')
        return if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
    }

    private fun relative(fromDir: String, target: String): String {
        val from = if (fromDir.isEmpty()) emptyList() else fromDir.split('/')
        val to = target.split('/')
        var common = 0
        while (common < from.size && common < to.size - 1 && from[common] == to[common]) common++
        return (List(from.size - common) { ".." } + to.drop(common)).joinToString("/")
    }

    fun count(pkg: ZipPackage): Int = Doc(pkg).ids.size

    /** Moves slide [from] to position [to]. */
    fun move(pkg: ZipPackage, from: Int, to: Int): ZipPackage {
        val d = Doc(pkg)
        if (from !in d.ids.indices || to !in d.ids.indices || from == to) return pkg
        val list = d.ids.toMutableList()
        list.add(to, list.removeAt(from))
        d.writeIds(list)
        return d.result()
    }

    /** Deletes slide [index] with its notes; the last remaining slide cannot be deleted. */
    fun delete(pkg: ZipPackage, index: Int): ZipPackage {
        val d = Doc(pkg)
        if (index !in d.ids.indices || d.ids.size <= 1) return pkg
        val el = d.ids[index]
        val relId = el.attr("r:id") ?: el.attr("id") ?: return pkg
        val path = d.rels[relId]?.target
        d.writeIds(d.ids.filterIndexed { i, _ -> i != index })
        d.removeRel(d.presRelsPath, relId)
        if (path != null) {
            val notes = pkg.relationships(path).values.filter { it.type.endsWith("/notesSlide") && !it.external }.map { it.target }
            for (n in notes) {
                d.entries.remove(n)
                d.entries.remove(relsPath(n))
                d.removeOverride(n)
            }
            d.entries.remove(path)
            d.entries.remove(relsPath(path))
            d.removeOverride(path)
        }
        return d.result()
    }

    /** Copies slide [index] (without its speaker notes) to just after it. */
    fun duplicate(pkg: ZipPackage, index: Int): ZipPackage {
        val d = Doc(pkg)
        val src = d.slidePath(index) ?: return pkg
        val newPath = d.newSlidePath()
        d.entries[newPath] = pkg.bytes(src) ?: return pkg
        pkg.bytes(relsPath(src))?.let { bytes ->
            val text = String(bytes, Charsets.UTF_8)
                .replace(Regex("""<Relationship\b[^>]*Type="[^"]*/notesSlide"[^>]*/>"""), "")
            // Both slides live in the same folder, so relative targets stay valid.
            d.put(relsPath(newPath), text)
        }
        return insert(d, newPath, index + 1)
    }

    /**
     * Adds an empty "Title and Content" slide after [afterIndex], using a matching layout of the
     * presentation when there is one.
     */
    fun addSlide(pkg: ZipPackage, afterIndex: Int): ZipPackage {
        val d = Doc(pkg)
        val size = d.pres.child("sldSz")
        val w = size?.attr("cx")?.toLongOrNull() ?: 12192000L
        val h = size?.attr("cy")?.toLongOrNull() ?: 6858000L
        val layout = pickLayout(pkg, d) ?: return pkg
        val newPath = d.newSlidePath()
        d.put(newPath, contentSlide(w, h, title = "", body = emptyList()))
        d.put(relsPath(newPath), """$DECL<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/slideLayout" Target="${xmlEscape(relative(newPath.substringBeforeLast('/'), layout))}"/></Relationships>""")
        return insert(d, newPath, afterIndex + 1)
    }

    private fun insert(d: Doc, newPath: String, at: Int): ZipPackage {
        val relId = d.newRelId()
        d.addRel(relId, "$ODR/slide", relative(d.presDir, newPath))
        d.addOverride(newPath, SLIDE_CT)
        val list = d.ids.toMutableList()
        list.add(at.coerceIn(0, list.size), d.sldIdElement(d.newSlideId(), relId))
        d.writeIds(list)
        return d.result()
    }

    /** A "Title and Content" layout (type obj), else the layout of the first slide, else any layout. */
    private fun pickLayout(pkg: ZipPackage, d: Doc): String? {
        val masters = d.rels.values.filter { it.type.endsWith("/slideMaster") }.map { it.target }
        val layouts = masters.flatMap { m -> pkg.relationships(m).values.filter { it.type.endsWith("/slideLayout") }.map { it.target } }
        layouts.firstOrNull { pkg.xml(it)?.attr("type") == "obj" }?.let { return it }
        d.slidePath(0)?.let { first ->
            pkg.relationships(first).values.firstOrNull { it.type.endsWith("/slideLayout") }?.target?.let { return it }
        }
        return layouts.firstOrNull()
    }

    private fun textBody(paragraphs: List<String>): String {
        val ps = paragraphs.ifEmpty { listOf("") }.joinToString("") { t ->
            val run = if (t.isEmpty()) "" else """<a:r><a:rPr lang="en-US" dirty="0"/><a:t>${xmlEscape(t)}</a:t></a:r>"""
            "<a:p>$run<a:endParaRPr lang=\"en-US\" dirty=\"0\"/></a:p>"
        }
        return "<p:txBody><a:bodyPr/><a:lstStyle/>$ps</p:txBody>"
    }

    private fun placeholder(id: Int, name: String, ph: String, x: Long, y: Long, cx: Long, cy: Long, paragraphs: List<String>) =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>""" +
            """<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm></p:spPr>${textBody(paragraphs)}</p:sp>"""

    private const val TREE_START = """<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""
    private const val TREE_END = """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"""

    private fun contentSlide(w: Long, h: Long, title: String, body: List<String>): String =
        """$DECL<p:sld xmlns:a="$A" xmlns:r="$R" xmlns:p="$P">$TREE_START""" +
            placeholder(2, "Title 1", """<p:ph type="title"/>""", w / 20, h / 20, w * 9 / 10, h * 15 / 100, listOf(title)) +
            placeholder(3, "Content Placeholder 2", """<p:ph idx="1"/>""", w / 20, h * 23 / 100, w * 9 / 10, h * 70 / 100, body) +
            """$TREE_END</p:sld>"""

    private fun titleSlide(w: Long, h: Long, title: String, subtitle: String): String =
        """$DECL<p:sld xmlns:a="$A" xmlns:r="$R" xmlns:p="$P">$TREE_START""" +
            placeholder(2, "Title 1", """<p:ph type="ctrTitle"/>""", w / 10, h * 30 / 100, w * 8 / 10, h * 22 / 100, listOf(title)) +
            placeholder(3, "Subtitle 2", """<p:ph type="subTitle" idx="1"/>""", w / 10, h * 54 / 100, w * 8 / 10, h * 14 / 100, listOf(subtitle)) +
            """$TREE_END</p:sld>"""

    /** A new 16:9 presentation with one title slide and the title and content layouts. */
    fun newPresentation(title: String, subtitle: String): ByteArray {
        val w = 12192000L
        val h = 6858000L
        val parts = ArrayList<Pair<String, String>>()
        parts += "[Content_Types].xml" to DECL + """<Types xmlns="$CT"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/>""" +
            """<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""" +
            """<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""" +
            """<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""" +
            """<Override PartName="/ppt/slideLayouts/slideLayout2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""" +
            """<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""" +
            """<Override PartName="/ppt/slides/slide1.xml" ContentType="$SLIDE_CT"/>""" +
            """<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>"""
        parts += "_rels/.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/officeDocument" Target="ppt/presentation.xml"/><Relationship Id="rId2" Type="$ODR/extended-properties" Target="docProps/app.xml"/></Relationships>"""
        parts += "docProps/app.xml" to DECL + """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Inkwell</Application></Properties>"""
        parts += "ppt/presentation.xml" to DECL + """<p:presentation xmlns:a="$A" xmlns:r="$R" xmlns:p="$P" saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId3"/></p:sldIdLst><p:sldSz cx="$w" cy="$h"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""
        parts += "ppt/_rels/presentation.xml.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/slideMaster" Target="slideMasters/slideMaster1.xml"/><Relationship Id="rId2" Type="$ODR/theme" Target="theme/theme1.xml"/><Relationship Id="rId3" Type="$ODR/slide" Target="slides/slide1.xml"/></Relationships>"""

        val masterShapes = placeholder(2, "Title Placeholder 1", """<p:ph type="title"/>""", w / 20, h / 20, w * 9 / 10, h * 15 / 100, emptyList()) +
            placeholder(3, "Text Placeholder 2", """<p:ph type="body" idx="1"/>""", w / 20, h * 23 / 100, w * 9 / 10, h * 70 / 100, emptyList())
        val txStyles = """<p:txStyles><p:titleStyle><a:lvl1pPr algn="l"><a:defRPr sz="4000" b="1"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mj-lt"/></a:defRPr></a:lvl1pPr></p:titleStyle>""" +
            """<p:bodyStyle><a:lvl1pPr marL="342900" indent="-342900"><a:buFont typeface="Arial"/><a:buChar char="•"/><a:defRPr sz="2400"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/></a:defRPr></a:lvl1pPr>""" +
            """<a:lvl2pPr marL="742950" indent="-285750"><a:buFont typeface="Arial"/><a:buChar char="–"/><a:defRPr sz="2000"/></a:lvl2pPr></p:bodyStyle><p:otherStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr></p:otherStyle></p:txStyles>"""
        parts += "ppt/slideMasters/slideMaster1.xml" to DECL + """<p:sldMaster xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>""" +
            TREE_START.removePrefix("<p:cSld>") + masterShapes + "</p:spTree></p:cSld>" +
            """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
            """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/><p:sldLayoutId id="2147483650" r:id="rId2"/></p:sldLayoutIdLst>$txStyles</p:sldMaster>"""
        parts += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$ODR/slideLayout" Target="../slideLayouts/slideLayout2.xml"/><Relationship Id="rId3" Type="$ODR/theme" Target="../theme/theme1.xml"/></Relationships>"""
        val titleLayoutShapes = placeholder(2, "Title 1", """<p:ph type="ctrTitle"/>""", w / 10, h * 30 / 100, w * 8 / 10, h * 22 / 100, emptyList()) +
            placeholder(3, "Subtitle 2", """<p:ph type="subTitle" idx="1"/>""", w / 10, h * 54 / 100, w * 8 / 10, h * 14 / 100, emptyList())
        parts += "ppt/slideLayouts/slideLayout1.xml" to DECL + """<p:sldLayout xmlns:a="$A" xmlns:r="$R" xmlns:p="$P" type="title" preserve="1">""" +
            TREE_START.replace("<p:cSld>", """<p:cSld name="Title Slide">""") + titleLayoutShapes + TREE_END + "</p:sldLayout>"
        val contentLayoutShapes = placeholder(2, "Title 1", """<p:ph type="title"/>""", w / 20, h / 20, w * 9 / 10, h * 15 / 100, emptyList()) +
            placeholder(3, "Content Placeholder 2", """<p:ph idx="1"/>""", w / 20, h * 23 / 100, w * 9 / 10, h * 70 / 100, emptyList())
        parts += "ppt/slideLayouts/slideLayout2.xml" to DECL + """<p:sldLayout xmlns:a="$A" xmlns:r="$R" xmlns:p="$P" type="obj" preserve="1">""" +
            TREE_START.replace("<p:cSld>", """<p:cSld name="Title and Content">""") + contentLayoutShapes + TREE_END + "</p:sldLayout>"
        for (i in 1..2) {
            parts += "ppt/slideLayouts/_rels/slideLayout$i.xml.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
        }
        parts += "ppt/theme/theme1.xml" to DECL + PptxWriter.THEME
        parts += "ppt/slides/slide1.xml" to titleSlide(w, h, title, subtitle)
        parts += "ppt/slides/_rels/slide1.xml.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
        val out = ByteArrayOutputStream()
        ZipPackage.write(out, parts.map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) })
        return out.toByteArray()
    }
}
