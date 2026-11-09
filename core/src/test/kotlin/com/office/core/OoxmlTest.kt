package com.office.core

import com.office.core.docx.DocxReader
import com.office.core.docx.DocxWriter
import com.office.core.pptx.PptxReader
import com.office.core.pptx.SlideShape
import com.office.core.sheet.CellRef
import com.office.core.sheet.Sheet
import com.office.core.sheet.Workbook
import com.office.core.text.ListType
import com.office.core.text.MarkdownCodec
import com.office.core.text.Paragraph
import com.office.core.text.ParagraphStyle
import com.office.core.text.TextDocument
import com.office.core.text.TextRun
import com.office.core.xlsx.XlsxReader
import com.office.core.xlsx.XlsxWriter
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OoxmlTest {

    private val sample = TextDocument(
        listOf(
            Paragraph(listOf(TextRun("Rapor <2026> & özet")), ParagraphStyle.HEADING1),
            Paragraph(listOf(TextRun("Normal "), TextRun("kalın", bold = true), TextRun(" ve "), TextRun("italik", italic = true, underline = true))),
            Paragraph(listOf(TextRun("madde bir")), list = ListType.BULLET),
            Paragraph(listOf(TextRun("adım\tbir")), list = ListType.NUMBERED),
            Paragraph(emptyList()),
        ),
    )

    @Test fun docxRoundTrip() {
        val out = ByteArrayOutputStream()
        DocxWriter.write(sample, out)
        val read = DocxReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sample.paragraphs.map { it.normalized().runs }, read.paragraphs.map { it.runs })
        assertEquals(sample.paragraphs.map { it.style to it.list }, read.paragraphs.map { it.style to it.list })
    }

    @Test fun markdownRoundTrip() {
        val md = MarkdownCodec.write(sample)
        val read = MarkdownCodec.read(md)
        assertEquals(sample.paragraphs.map { it.text.replace("\t", "\t") }, read.paragraphs.map { it.text })
        assertTrue(read.paragraphs[1].runs.any { it.bold && it.text == "kalın" })
        assertEquals(ParagraphStyle.HEADING1, read.paragraphs[0].style)
        assertEquals(ListType.NUMBERED, read.paragraphs[3].list)
    }

    @Test fun xlsxRoundTrip() {
        val s1 = Sheet("Satışlar")
        s1[CellRef(0, 0)] = "Ürün"
        s1[CellRef(0, 1)] = "Adet"
        s1[CellRef(1, 0)] = "Kalem"
        s1[CellRef(1, 1)] = "12"
        s1[CellRef(2, 0)] = "'007"
        s1[CellRef(2, 1)] = "3.5"
        s1[CellRef(3, 1)] = "=SUM(B2:B3)"
        s1.columnWidths[0] = 20f
        val s2 = Sheet("Özet")
        s2[CellRef(0, 0)] = "=Satışlar!B4*2"
        val wb = Workbook(mutableListOf(s1, s2))

        val out = ByteArrayOutputStream()
        XlsxWriter.write(wb, out)
        val read = XlsxReader.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals(listOf("Satışlar", "Özet"), read.sheets.map { it.name })
        val r1 = read.sheets[0]
        assertEquals("Ürün", r1[CellRef(0, 0)])
        assertEquals("12", r1[CellRef(1, 1)])
        assertEquals("'007", r1[CellRef(2, 0)])
        assertEquals("=SUM(B2:B3)", r1[CellRef(3, 1)])
        assertEquals("15.5", r1.cachedValues[CellRef(3, 1)])
        assertEquals(20f, r1.columnWidths[0])
        assertEquals("=Satışlar!B4*2", read.sheets[1][CellRef(0, 0)])
    }

    @Test fun xlsxSharedStringsAndDates() {
        val pkg = ZipPackage(
            mapOf(
                "xl/workbook.xml" to """<workbook xmlns:r="r"><sheets><sheet name="A" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Type="x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="x/sharedStrings" Target="sharedStrings.xml"/><Relationship Id="rId3" Type="x/styles" Target="styles.xml"/></Relationships>""",
                "xl/sharedStrings.xml" to """<sst><si><t>Merhaba</t></si><si><r><t>Zengin </t></r><r><t>metin</t></r></si></sst>""",
                "xl/styles.xml" to """<styleSheet><cellXfs><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>""",
                "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" s="1"><v>45658</v></c><c r="D1" t="b"><v>1</v></c></row></sheetData></worksheet>""",
            ).mapValues { it.value.toByteArray() },
        )
        val s = XlsxReader.read(pkg).sheets[0]
        assertEquals("Merhaba", s[CellRef(0, 0)])
        assertEquals("Zengin metin", s[CellRef(0, 1)])
        assertEquals("45658", s[CellRef(0, 2)])
        assertEquals("01.01.2025", com.office.core.sheet.NumberFormat.format(45658.0, s.style(CellRef(0, 2)).numFmt, java.util.Locale.ROOT))
        assertEquals("TRUE", s[CellRef(0, 3)])
    }

    @Test fun pptxReadsTextImagesAndInheritedPlaceholders() {
        val pkg = ZipPackage(
            mapOf(
                "_rels/.rels" to """<Relationships><Relationship Id="r1" Type="x/officeDocument" Target="ppt/presentation.xml"/></Relationships>""",
                "ppt/presentation.xml" to """<p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>""",
                "ppt/_rels/presentation.xml.rels" to """<Relationships><Relationship Id="rId2" Type="x/slide" Target="slides/slide1.xml"/></Relationships>""",
                "ppt/slides/slide1.xml" to """<p:sld xmlns:p="p" xmlns:a="a" xmlns:r="r"><p:cSld><p:spTree>
                    <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:p><a:r><a:rPr b="1"/><a:t>Başlık</a:t></a:r></a:p></p:txBody></p:sp>
                    <p:pic><p:blipFill><a:blip r:embed="rId5"/></p:blipFill><p:spPr><a:xfrm><a:off x="10" y="20"/><a:ext cx="30" cy="40"/></a:xfrm></p:spPr></p:pic>
                    </p:spTree></p:cSld></p:sld>""",
                "ppt/slides/_rels/slide1.xml.rels" to """<Relationships><Relationship Id="rId1" Type="x/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId5" Type="x/image" Target="../media/image1.png"/></Relationships>""",
                "ppt/slideLayouts/slideLayout1.xml" to """<p:sldLayout xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="1" y="2"/><a:ext cx="3" cy="4"/></a:xfrm></p:spPr></p:sp></p:spTree></p:cSld></p:sldLayout>""",
                "ppt/media/image1.png" to "PNG",
            ).mapValues { it.value.toByteArray() },
        )
        val pres = PptxReader.read(pkg)
        assertEquals(1, pres.slides.size)
        val shapes = pres.slides[0].shapes
        val title = shapes.filterIsInstance<SlideShape.TextBox>().single()
        assertEquals("Başlık", title.paragraphs[0].text)
        assertTrue(title.isTitle)
        assertEquals(1L, title.frame.x)
        val pic = shapes.filterIsInstance<SlideShape.Picture>().single()
        assertEquals("ppt/media/image1.png", pic.contentPath)
        assertEquals(40L, pic.frame.height)
    }

    @Test fun fileKindDetection() {
        assertEquals(FileKind.DOCUMENT, FileKind.detect("a.DOCX", null))
        assertEquals(FileKind.CSV, FileKind.detect(null, "text/csv"))
        assertEquals(FileKind.TEXT, FileKind.detect("x", "text/html"))
        assertTrue(FileKind.isLegacyBinary("old.ppt"))
        assertEquals(FileKind.DOCUMENT, FileKind.detect("old.doc", null))
        assertEquals(FileKind.SPREADSHEET, FileKind.detect(null, "application/vnd.ms-excel"))
    }
}
