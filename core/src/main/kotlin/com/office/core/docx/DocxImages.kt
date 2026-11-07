package com.office.core.docx

import com.office.core.text.Embed
import com.office.core.text.EmbedKind
import com.office.core.text.EmbedMedia
import com.office.core.xml.XmlParser
import com.office.core.zip.ZipPackage
import kotlin.random.Random

/** Finds the picture bytes behind image embeds and builds embeds for newly inserted pictures. */
object DocxImages {

    const val EMU_PER_PX = 9525L

    /** The picture of an image embed: from the embed itself when newly inserted, else from [pkg]. */
    fun bytes(embed: Embed, pkg: ZipPackage?): ByteArray? {
        embed.media?.let { return it.bytes }
        if (pkg == null || embed.kind != EmbedKind.IMAGE) return null
        val root = runCatching { XmlParser.parse(embed.xml) }.getOrNull() ?: return null
        val relId = root.descendants("blip").firstNotNullOfOrNull { it.attr("r:embed") ?: it.attr("embed") }
            ?: root.descendants("imagedata").firstNotNullOfOrNull { it.attr("r:id") }
            ?: return null
        val rel = pkg.relationships(DocxReader.mainPart(pkg))[relId] ?: return null
        if (rel.external) return null
        return pkg.bytes(rel.target)
    }

    /** Display size in EMU from `wp:extent`, if present. */
    fun extent(embed: Embed): Pair<Long, Long>? {
        val root = runCatching { XmlParser.parse(embed.xml) }.getOrNull() ?: return null
        val ext = root.descendants("extent").firstOrNull() ?: return null
        val cx = ext.attr("cx")?.toLongOrNull() ?: return null
        val cy = ext.attr("cy")?.toLongOrNull() ?: return null
        return if (cx > 0 && cy > 0) cx to cy else null
    }

    /**
     * An inline picture run for [bytes] ([extension] like `png` or `jpeg`), shown [widthPx] × [heightPx]
     * pixels at 96 dpi but at most [maxWidthPx] wide.
     */
    fun newImage(bytes: ByteArray, extension: String, widthPx: Int, heightPx: Int, maxWidthPx: Int = 600): Embed {
        val tag = Random.nextLong(0x10000000L, 0xFFFFFFFFL).toString(16)
        val relId = "rIdInkwell$tag"
        val ext = extension.lowercase().let { if (it == "jpg") "jpeg" else it }
        val fileName = "inkwell_$tag.$ext"
        val scale = if (widthPx > maxWidthPx) maxWidthPx.toDouble() / widthPx else 1.0
        val cx = (widthPx * scale * EMU_PER_PX).toLong().coerceAtLeast(EMU_PER_PX)
        val cy = (heightPx * scale * EMU_PER_PX).toLong().coerceAtLeast(EMU_PER_PX)
        val docPrId = (tag.takeLast(6).toLong(16) % 1_000_000L) + 10_000
        val xml = """<w:r xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:drawing>""" +
            """<wp:inline distT="0" distB="0" distL="0" distR="0" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">""" +
            """<wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$docPrId" name="Picture $docPrId"/>""" +
            """<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            """<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="0" name="$fileName"/><pic:cNvPicPr/></pic:nvPicPr>""" +
            """<pic:blipFill><a:blip r:embed="$relId" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""" +
            """<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>""" +
            """</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"""
        return Embed(EmbedKind.IMAGE, xml, label = fileName, media = EmbedMedia(relId, fileName, bytes, contentType(ext)))
    }

    fun contentType(ext: String) = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }
}
