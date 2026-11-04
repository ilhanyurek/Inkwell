package com.office.core

import com.office.core.docx.DocxReader
import com.office.core.docx.DocxWriter
import com.office.core.formula.FormulaEngine
import com.office.core.pptx.PptxReader
import com.office.core.pptx.SlideShape
import com.office.core.sheet.CellRef
import com.office.core.text.EmbedKind
import com.office.core.text.InlineAttr
import com.office.core.text.ListType
import com.office.core.text.ParagraphStyle
import com.office.core.text.RichText
import com.office.core.xlsx.XlsxReader
import com.office.core.xlsx.XlsxWriter
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads files produced by python-docx / openpyxl / python-pptx (see scripts/make_fixtures.py),
 * edits and saves them, and writes the results to build/fixture-out so CI can check that those
 * libraries can open our output.
 */
class FixtureTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

    private val outDir = File("build/fixture-out").apply { mkdirs() }

    @Test fun docxFromPythonDocx() {
        val pkg = ZipPackage.read(ByteArrayInputStream(fixture("report.docx")))
        val doc = DocxReader.read(pkg)
        val texts = doc.paragraphs.map { it.text }
        assertEquals(ParagraphStyle.HEADING1, doc.paragraphs.first { it.text == "Quarterly Report" }.style)
        assertEquals(ParagraphStyle.HEADING2, doc.paragraphs.first { it.text == "Highlights" }.style)
        assertEquals(ListType.BULLET, doc.paragraphs.first { it.text == "İzmir sales doubled" }.list)
        assertEquals(ListType.NUMBERED, doc.paragraphs.first { it.text == "Build" }.list)
        val growth = doc.paragraphs.first { it.text.startsWith("Revenue grew") }
        assertTrue(growth.runs.any { it.text == "strongly" && it.bold })
        assertTrue(growth.runs.any { it.text == "Q3" && it.italic && it.sizePt == 14f && it.extraXml!!.contains("C00000") })
        val kinds = doc.embeds.values.map { it.kind }
        assertTrue(EmbedKind.TABLE in kinds && EmbedKind.IMAGE in kinds && EmbedKind.LINK in kinds)
        assertEquals(listOf("City", "Q2", "Q3"), doc.embeds.values.first { it.kind == EmbedKind.TABLE }.tableRows[0])
        assertTrue(texts.any { it.contains("Centered closing line.") })

        // Edit: change a word, make it bold, add a bullet; save into the original package.
        var m = RichText.fromDocument(doc)
        val at = m.text.indexOf("Highlights")
        m = m.replace(at, at + "Highlights".length, "Key points", m.styleAt(at)).toggleInline(at, at + 3, InlineAttr.BOLD)
        val out = ByteArrayOutputStream()
        DocxWriter.write(m.toDocument(embeds = doc.embeds), out, pkg)
        File(outDir, "report-edited.docx").writeBytes(out.toByteArray())

        val reread = DocxReader.read(ZipPackage.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals(doc.embeds.size, reread.embeds.size)
        assertTrue(reread.paragraphs.any { it.text == "Key points" && it.style == ParagraphStyle.HEADING2 })
        val outPkg = ZipPackage.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(pkg.entries.keys, outPkg.entries.keys)
        pkg.entries.filterKeys { it != "word/document.xml" }.forEach { (k, v) -> assertTrue(k, v.contentEquals(outPkg.bytes(k)!!)) }
    }

    @Test fun xlsxFromOpenpyxl() {
        val wb = XlsxReader.read(ByteArrayInputStream(fixture("budget.xlsx")))
        assertEquals(listOf("Sales", "Notes"), wb.sheets.map { it.name })
        val sales = wb.sheets[0]
        val engine = FormulaEngine(wb)
        fun v(ref: String) = engine.display(sales, CellRef.parse(ref)!!)
        assertEquals("Kalem", v("A2"))
        assertEquals("420", v("D2"))
        assertEquals("400", v("B6"))
        assertEquals("1750", v("D6"))
        assertEquals("good", v("B8"))
        assertEquals("3500", engine.display(wb.sheets[1], CellRef(0, 1)))
        assertEquals("=not a formula", engine.display(wb.sheets[1], CellRef(1, 1)).removePrefix("'"))
        assertEquals(18f, sales.columnWidths[0])

        val out = ByteArrayOutputStream()
        XlsxWriter.write(wb, out)
        File(outDir, "budget-resaved.xlsx").writeBytes(out.toByteArray())
        val again = XlsxReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("1750", FormulaEngine(again).display(again.sheets[0], CellRef.parse("D6")!!))
    }

    @Test fun pptxFromPythonPptx() {
        val pres = PptxReader.read(ByteArrayInputStream(fixture("deck.pptx")))
        assertEquals(3, pres.slides.size)
        fun texts(i: Int) = pres.slides[i].shapes.filterIsInstance<SlideShape.TextBox>().flatMap { b -> b.paragraphs.map { it.text } }
        assertTrue("Inkwell Deck" in texts(0))
        assertTrue("Fixture subtitle" in texts(0))
        assertTrue(texts(1).containsAll(listOf("Agenda", "Introduction", "Numbers", "Next steps")))
        assertEquals("Speaker note for agenda", pres.slides[1].notes)
        assertTrue(pres.slides[2].shapes.any { it is SlideShape.Picture })
        assertTrue(texts(2).any { it.contains("Q2") && it.contains("Q3") })
        val box = pres.slides[2].shapes.filterIsInstance<SlideShape.TextBox>().first { b -> b.paragraphs.any { it.text == "Free text box" } }
        val run = box.paragraphs[0].runs[0]
        assertTrue(run.bold)
        assertEquals(24f, run.sizePt)
    }
}
