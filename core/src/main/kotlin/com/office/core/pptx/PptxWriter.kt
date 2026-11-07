package com.office.core.pptx

import com.office.core.zip.ZipPackage
import java.io.OutputStream

/**
 * Writes a new presentation whose slides each show one picture, scaled to fit and centred
 * (used for "PDF to PowerPoint"). The package is the smallest one PowerPoint accepts:
 * one master, one blank layout and one theme.
 */
object PptxWriter {

    /** A slide picture: PNG or JPEG bytes and its pixel size. */
    class Picture(val bytes: ByteArray, val widthPx: Int, val heightPx: Int, val jpeg: Boolean)

    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val ODR = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    /**
     * @param widthEmu slide width; height follows [heightEmu].
     */
    fun writePictures(pictures: List<Picture>, out: OutputStream, widthEmu: Long = 12192000L, heightEmu: Long = 6858000L) {
        require(pictures.isNotEmpty()) { "No slides" }
        val parts = ArrayList<Pair<String, String>>()
        val binary = ArrayList<Pair<String, ByteArray>>()

        val slideOverrides = StringBuilder()
        pictures.indices.forEach { i ->
            slideOverrides.append("""<Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        }
        parts += "[Content_Types].xml" to DECL + """<Types xmlns="$CT"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>$slideOverrides</Types>"""
        parts += "_rels/.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/officeDocument" Target="ppt/presentation.xml"/></Relationships>"""

        val sldIds = StringBuilder()
        val presRels = StringBuilder("""<Relationship Id="rId1" Type="$ODR/slideMaster" Target="slideMasters/slideMaster1.xml"/><Relationship Id="rId2" Type="$ODR/theme" Target="theme/theme1.xml"/>""")
        pictures.indices.forEach { i ->
            sldIds.append("""<p:sldId id="${256 + i}" r:id="rId${i + 3}"/>""")
            presRels.append("""<Relationship Id="rId${i + 3}" Type="$ODR/slide" Target="slides/slide${i + 1}.xml"/>""")
        }
        parts += "ppt/presentation.xml" to DECL + """<p:presentation xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$sldIds</p:sldIdLst><p:sldSz cx="$widthEmu" cy="$heightEmu"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""
        parts += "ppt/_rels/presentation.xml.rels" to DECL + """<Relationships xmlns="$REL">$presRels</Relationships>"""

        val emptyTree = """<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"""
        parts += "ppt/slideMasters/slideMaster1.xml" to DECL + """<p:sldMaster xmlns:a="$A" xmlns:r="$R" xmlns:p="$P">$emptyTree<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>"""
        parts += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$ODR/theme" Target="../theme/theme1.xml"/></Relationships>"""
        parts += "ppt/slideLayouts/slideLayout1.xml" to DECL + """<p:sldLayout xmlns:a="$A" xmlns:r="$R" xmlns:p="$P" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
        parts += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
        parts += "ppt/theme/theme1.xml" to DECL + THEME

        pictures.forEachIndexed { i, pic ->
            val ext = if (pic.jpeg) "jpeg" else "png"
            val media = "ppt/media/image${i + 1}.$ext"
            binary += media to pic.bytes
            // Fit the picture inside the slide, keeping its aspect ratio.
            val scale = minOf(widthEmu.toDouble() / pic.widthPx, heightEmu.toDouble() / pic.heightPx)
            val cx = (pic.widthPx * scale).toLong()
            val cy = (pic.heightPx * scale).toLong()
            val x = (widthEmu - cx) / 2
            val y = (heightEmu - cy) / 2
            parts += "ppt/slides/slide${i + 1}.xml" to DECL + """<p:sld xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/><p:pic><p:nvPicPr><p:cNvPr id="2" name="Page ${i + 1}"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
            parts += "ppt/slides/_rels/slide${i + 1}.xml.rels" to DECL + """<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$ODR/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$ODR/image" Target="../media/image${i + 1}.$ext"/></Relationships>"""
        }
        ZipPackage.write(out, parts.map { (k, v) -> k to v.toByteArray(Charsets.UTF_8) } + binary)
    }

    internal val THEME = """<a:theme xmlns:a="$A" name="Inkwell"><a:themeElements><a:clrScheme name="Inkwell"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F2937"/></a:dk2><a:lt2><a:srgbClr val="F3F4F6"/></a:lt2><a:accent1><a:srgbClr val="2F5BC4"/></a:accent1><a:accent2><a:srgbClr val="C0504D"/></a:accent2><a:accent3><a:srgbClr val="9BBB59"/></a:accent3><a:accent4><a:srgbClr val="8064A2"/></a:accent4><a:accent5><a:srgbClr val="4BACC6"/></a:accent5><a:accent6><a:srgbClr val="F79646"/></a:accent6><a:hlink><a:srgbClr val="0000FF"/></a:hlink><a:folHlink><a:srgbClr val="800080"/></a:folHlink></a:clrScheme><a:fontScheme name="Inkwell"><a:majorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="Inkwell"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="25400"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="38100"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""
}
