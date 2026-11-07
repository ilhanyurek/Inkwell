package com.office.core

import com.office.core.docx.DocxImages
import com.office.core.docx.DocxReader
import com.office.core.docx.DocxTable
import com.office.core.docx.DocxWriter
import com.office.core.text.EMBED_CHAR
import com.office.core.text.EmbedKind
import com.office.core.text.RichText
import com.office.core.xml.XmlParser
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DocxTablesImagesTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9, 9)

    private val table = """<w:tbl xmlns:w="$w"><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>""" +
        """<w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:tcPr><w:shd w:fill="2F5BC4"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>Name</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Score</w:t></w:r></w:p></w:tc></w:tr>""" +
        """<w:tr><w:tc><w:p><w:r><w:t>Ada</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:rPr><w:color w:val="FF0000"/></w:rPr><w:t>90</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""

    @Test fun editKeepsFormattingOfChangedCells() {
        val rows = listOf(listOf("Name", "Score"), listOf("Ada", "95"))
        val out = DocxTable.edit(table, rows, listOf(0, 1), listOf(0, 1))
        assertEquals(rows, DocxTable.rows(out))
        assertTrue(out.contains("""<w:color w:val="FF0000"/>"""))
        assertTrue(out.contains("""<w:shd w:fill="2F5BC4"/>"""))
        assertTrue(out.contains("""<w:jc w:val="center"/>"""))
        assertTrue(!out.contains("90"))
    }

    @Test fun addAndRemoveRowsAndColumns() {
        val rows = listOf(listOf("Name", "Score", "Grade"), listOf("Ada", "90", "A"), listOf("Bo", "70", "C"))
        val out = DocxTable.edit(table, rows, listOf(0, 1, null), listOf(0, 1, null))
        assertEquals(rows, DocxTable.rows(out))
        val root = XmlParser.parse(out)
        assertEquals(3, root.path("tblGrid")!!.children("gridCol").size)
        assertEquals(1, root.descendants("tblHeader").size)

        val removed = DocxTable.edit(out, listOf(listOf("Score"), listOf("70")), listOf(0, 2), listOf(1))
        assertEquals(listOf(listOf("Score"), listOf("70")), DocxTable.rows(removed))
        assertEquals(1, XmlParser.parse(removed).path("tblGrid")!!.children("gridCol").size)
    }

    @Test fun multiLineCells() {
        val out = DocxTable.edit(table, listOf(listOf("Name", "Score"), listOf("Ada\nLovelace", "90")), listOf(0, 1), listOf(0, 1))
        val cell = XmlParser.parse(out).children("tr")[1].children("tc")[0]
        assertEquals(2, cell.children("p").size)
        assertEquals("Ada\nLovelace", DocxTable.rows(out)[1][0])
    }

    @Test fun createdTableAndImageSurviveSave() {
        val created = DocxTable.create(2, 3, listOf(listOf("a", "b", "c")))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("", "", "")), DocxTable.rows(created))

        val image = DocxImages.newImage(png, "png", 1200, 600)
        assertEquals(600L * DocxImages.EMU_PER_PX to 300L * DocxImages.EMU_PER_PX, DocxImages.extent(image))
        val embeds = mapOf(1 to DocxTable.embed(created), 2 to image)
        var rt = RichText.plain("Intro\nEnd")
        rt = rt.insertEmbedParagraph(5, 1).first
        val (withImage, at) = rt.insertEmbedParagraph(rt.text.length, 2)
        assertEquals(EMBED_CHAR, withImage.text[at])
        val doc = withImage.toDocument(embeds = embeds)

        for (original in listOf(null, newPackage())) {
            val out = ByteArrayOutputStream()
            DocxWriter.write(doc, out, original)
            val pkg = ZipPackage.read(ByteArrayInputStream(out.toByteArray()))
            val back = DocxReader.read(pkg)
            assertEquals(listOf(listOf("a", "b", "c"), listOf("", "", "")), back.embeds.values.first { it.kind == EmbedKind.TABLE }.tableRows)
            val img = back.embeds.values.first { it.kind == EmbedKind.IMAGE }
            assertArrayEquals(png, DocxImages.bytes(img, pkg))
            val ct = String(pkg.bytes("[Content_Types].xml")!!)
            assertTrue(ct.contains("""Extension="png""""))
            assertEquals(listOf("Intro", "", "End", "", ""), back.paragraphs.map { it.text.replace(EMBED_CHAR.toString(), "") })
        }
    }

    @Test fun existingImageBytesResolveThroughRelationships() {
        val pkg = newPackage(withImage = true)
        val doc = DocxReader.read(pkg)
        val img = doc.embeds.values.first { it.kind == EmbedKind.IMAGE }
        assertArrayEquals(png, DocxImages.bytes(img, pkg))
    }

    @Test fun replaceAndRemoveEmbedParagraphs() {
        val (rt, at) = RichText.plain("A\nB").insertEmbedParagraph(1, 7)
        assertEquals("A\n${EMBED_CHAR}\nB", rt.text)
        assertEquals(8, rt.withEmbedId(at, 8).styleAt(at).embedId)
        assertEquals("A\nB", rt.removeEmbedParagraph(at).text)
    }

    private fun newPackage(withImage: Boolean = false): ZipPackage {
        val body = if (withImage) """<w:p><w:r><w:drawing><wp:inline xmlns:wp="x"><a:blip xmlns:a="y" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:embed="rIdImg"/></wp:inline></w:drawing></w:r></w:p>"""
        else "<w:p/>"
        return ZipPackage(
            linkedMapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>""".toByteArray(),
                "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(),
                "word/document.xml" to """<w:document xmlns:w="$w"><w:body>$body<w:sectPr/></w:body></w:document>""".toByteArray(),
                "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdImg" Type="x/image" Target="media/image1.png"/></Relationships>""".toByteArray(),
                "word/media/image1.png" to png,
            ),
        )
    }
}
