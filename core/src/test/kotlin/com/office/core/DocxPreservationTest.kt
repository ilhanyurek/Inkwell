package com.office.core

import com.office.core.docx.DocxReader
import com.office.core.docx.DocxWriter
import com.office.core.text.EmbedKind
import com.office.core.text.InlineAttr
import com.office.core.text.ListType
import com.office.core.text.ParagraphStyle
import com.office.core.text.RichText
import com.office.core.xml.XmlParser
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DocxPreservationTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    /** A document with things we do not model: a table, an image, a link, colours, spacing and a header. */
    private fun sourcePackage(): ZipPackage {
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$w" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Report</w:t></w:r></w:p>
<w:p><w:pPr><w:spacing w:after="240"/><w:jc w:val="center"/></w:pPr><w:bookmarkStart w:id="0" w:name="intro"/><w:r><w:rPr><w:color w:val="FF0000"/><w:b/></w:rPr><w:t>Red bold</w:t></w:r><w:bookmarkEnd w:id="0"/><w:r><w:t xml:space="preserve"> and </w:t></w:r><w:hyperlink r:id="rIdLink"><w:r><w:t>a link</w:t></w:r></w:hyperlink></w:p>
<w:p><w:r><w:drawing><wp:inline><a:graphic><a:graphicData><a:blip r:embed="rIdImg"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="7"/></w:numPr></w:pPr><w:r><w:t>Existing item</w:t></w:r></w:p>
<w:sectPr><w:headerReference w:type="default" r:id="rIdHdr"/><w:pgSz w:w="12240" w:h="15840"/></w:sectPr>
</w:body></w:document>"""
        val numbering = """<w:numbering xmlns:w="$w"><w:abstractNum w:abstractNumId="3"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum><w:num w:numId="7"><w:abstractNumId w:val="3"/></w:num></w:numbering>"""
        return ZipPackage(
            linkedMapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>""".toByteArray(),
                "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(),
                "word/document.xml" to document.toByteArray(),
                "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdImg" Type="x/image" Target="media/image1.png"/><Relationship Id="rIdLink" Type="x/hyperlink" Target="https://example.com" TargetMode="External"/><Relationship Id="rIdHdr" Type="x/header" Target="header1.xml"/><Relationship Id="rIdNum" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/></Relationships>""".toByteArray(),
                "word/numbering.xml" to numbering.toByteArray(),
                "word/media/image1.png" to png,
                "word/header1.xml" to """<w:hdr xmlns:w="$w"><w:p><w:r><w:t>Header</w:t></w:r></w:p></w:hdr>""".toByteArray(),
            ),
        )
    }

    private fun roundTrip(edit: (RichText) -> RichText): Pair<ZipPackage, String> {
        val src = sourcePackage()
        val doc = DocxReader.read(src)
        val edited = edit(RichText.fromDocument(doc)).toDocument(embeds = doc.embeds)
        val out = ByteArrayOutputStream()
        DocxWriter.write(edited, out, src)
        val pkg = ZipPackage.read(ByteArrayInputStream(out.toByteArray()))
        return pkg to String(pkg.bytes("word/document.xml")!!, Charsets.UTF_8)
    }

    @Test fun readsEmbedsForUnmodelledContent() {
        val doc = DocxReader.read(sourcePackage())
        val kinds = doc.embeds.values.map { it.kind }
        assertTrue(EmbedKind.TABLE in kinds)
        assertTrue(EmbedKind.IMAGE in kinds)
        assertTrue(EmbedKind.LINK in kinds)
        val table = doc.embeds.values.first { it.kind == EmbedKind.TABLE }
        assertEquals("2×2", table.label)
        assertEquals(listOf(listOf("A1", "B1"), listOf("A2", "B2")), table.tableRows)
        assertEquals(ListType.NUMBERED, doc.paragraphs.last().list)
    }

    @Test fun unchangedRoundTripKeepsEverything() {
        val (pkg, xml) = roundTrip { it }
        assertArrayEquals(png, pkg.bytes("word/media/image1.png"))
        assertTrue(pkg.bytes("word/header1.xml") != null)
        XmlParser.parse(xml) // still well-formed
        assertTrue(xml.contains("<w:tbl>"))
        assertTrue(xml.contains("""r:embed="rIdImg""""))
        assertTrue(xml.contains("""<w:hyperlink r:id="rIdLink">"""))
        assertTrue(xml.contains("""<w:color w:val="FF0000"/>"""))
        assertTrue(xml.contains("""<w:spacing w:after="240"/>"""))
        assertTrue(xml.contains("""<w:bookmarkStart w:id="0" w:name="intro"/>"""))
        assertTrue(xml.contains("""<w:numId w:val="7"/>"""))
        assertTrue(xml.contains("""<w:headerReference w:type="default" r:id="rIdHdr"/>"""))
        // Schema order inside pPr: spacing before jc.
        assertTrue(xml.indexOf("<w:spacing") < xml.indexOf("<w:jc"))
    }

    @Test fun editsApplyWhileEmbedsSurvive() {
        val (pkg, xml) = roundTrip { m ->
            val start = m.text.indexOf("Report")
            var e = m.replace(start, start + "Report".length, "Annual report", m.styleAt(start))
            e = e.toggleInline(0, 6, InlineAttr.ITALIC)
            // Turn the new last paragraph into a bullet list item (needs our numbering definitions).
            e = e.applyEdit(e.text + "\nNew bullet", e.styleAt(0))
            e.toggleList(e.text.length - 1, e.text.length - 1, ListType.BULLET)
        }
        assertTrue(xml.contains(">Annual<") && xml.contains("> report<"))
        assertTrue(xml.contains("<w:i/>"))
        assertTrue(xml.contains("<w:tbl>"))
        assertTrue(xml.contains("rIdImg"))
        assertTrue(xml.contains("""<w:numId w:val="90001"/>"""))
        val numbering = String(pkg.bytes("word/numbering.xml")!!, Charsets.UTF_8)
        XmlParser.parse(numbering)
        assertTrue(numbering.indexOf("""w:abstractNumId="90001"""") < numbering.indexOf("""<w:num w:numId="7">"""))
        val reread = DocxReader.read(pkg)
        assertEquals(ParagraphStyle.HEADING1, reread.paragraphs[0].style)
        assertEquals("Annual report", reread.paragraphs[0].text)
        assertEquals(ListType.BULLET, reread.paragraphs.last().list)
    }

    @Test fun deletingAnEmbedRemovesOnlyIt() {
        val (pkg, xml) = roundTrip { m ->
            val i = m.styles.indexOfFirst { s -> s.embedId != null && DocxReader.read(sourcePackage()).embeds[s.embedId]?.kind == EmbedKind.IMAGE }
            m.replace(i, i + 1, "", m.styleAt(0))
        }
        assertTrue(!xml.contains("rIdImg"))
        assertTrue(xml.contains("<w:tbl>"))
        assertArrayEquals(png, pkg.bytes("word/media/image1.png"))
    }

    @Test fun typingNextToEmbedDoesNotCopyIt() {
        val doc = DocxReader.read(sourcePackage())
        val m = RichText.fromDocument(doc)
        val linkEnd = m.styles.indexOfLast { it.embedId != null && doc.embeds[it.embedId]?.kind == EmbedKind.LINK } + 1
        val typed = m.applyEdit(m.text.substring(0, linkEnd) + "!" + m.text.substring(linkEnd), m.typingStyleAt(linkEnd))
        assertEquals(null, typed.styleAt(linkEnd).embedId)
    }
}
