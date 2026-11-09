package com.office.core

import com.office.core.doc.DocReader
import com.office.core.docx.DocxReader
import com.office.core.docx.DocxWriter
import com.office.core.formula.FormulaEngine
import com.office.core.odf.OfficeReaders
import com.office.core.ole.Cfb
import com.office.core.sheet.CellRef
import com.office.core.text.Alignment
import com.office.core.text.EmbedKind
import com.office.core.text.ListType
import com.office.core.text.ParagraphStyle
import com.office.core.xls.XlsReader
import com.office.core.xlsx.XlsxReader
import com.office.core.xlsx.XlsxWriter
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Word and Excel 97-2003 files saved by LibreOffice from the python-made fixtures. */
class LegacyTest {

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

    private val outDir = File("build/fixture-out").apply { mkdirs() }

    @Test fun readsCompoundFileStreams() {
        assertTrue(Cfb.read(fixture("report.doc")).keys.containsAll(listOf("WordDocument", "1Table")))
        assertTrue(Cfb.read(fixture("budget.xls")).keys.any { it == "Workbook" || it == "Book" })
        assertTrue(OfficeReaders.isLegacy(fixture("report.doc")))
    }

    @Test fun readsDoc() {
        val doc = DocReader.read(fixture("report.doc"))
        val texts = doc.paragraphs.map { it.text }
        println(texts)
        assertEquals("Quarterly Report", texts[0])
        assertEquals(ParagraphStyle.HEADING1, doc.paragraphs[0].style)
        assertEquals(ParagraphStyle.HEADING2, doc.paragraphs.first { it.text == "Highlights" }.style)
        val revenue = doc.paragraphs.first { it.text.startsWith("Revenue grew") }
        assertTrue(revenue.runs.any { it.text == "strongly" && it.bold })
        assertTrue(revenue.runs.any { it.text == "Q3" && it.italic })
        assertEquals(ListType.BULLET, doc.paragraphs.first { it.text == "Ankara office opened" }.list)
        assertEquals(ListType.NUMBERED, doc.paragraphs.first { it.text == "Plan" }.list)
        assertEquals(Alignment.CENTER, doc.paragraphs.first { it.text == "Centered closing line." }.alignment)
        assertTrue(texts.contains("Visit our site for more."))
        val table = doc.embeds.values.single { it.kind == EmbedKind.TABLE }
        assertEquals(listOf("City", "Q2", "Q3"), table.tableRows[0])
        assertEquals(listOf("İzmir", "80", "160"), table.tableRows[2])

        // Saved as .docx, it reads back and python-docx can open it (scripts/validate_outputs.py).
        val out = ByteArrayOutputStream()
        DocxWriter.write(doc, out, null)
        File(outDir, "report-from-doc.docx").writeBytes(out.toByteArray())
        val back = DocxReader.read(ZipPackage.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals("Quarterly Report", back.paragraphs[0].text)
        assertEquals(listOf("City", "Q2", "Q3"), back.embeds.values.single { it.kind == EmbedKind.TABLE }.tableRows[0])
    }

    @Test fun readsXls() {
        val wb = XlsReader.read(fixture("budget.xls"))
        assertEquals(listOf("Sales", "Notes"), wb.sheets.map { it.name })
        val sales = wb.sheets[0]
        val engine = FormulaEngine(wb)
        fun v(ref: String) = engine.display(sales, CellRef.parse(ref)!!)
        assertEquals("Kalem", v("A2"))
        assertEquals("420", v("D2"))
        assertEquals("1750", v("D6"))
        assertEquals("good", v("B8"))
        assertEquals("3500", engine.display(wb.sheets[1], CellRef(0, 1)))
        assertEquals("=not a formula", engine.display(wb.sheets[1], CellRef(1, 1)).removePrefix("'"))
        assertTrue(sales.style(CellRef(0, 0)).bold)
        val dateFmt = sales.style(CellRef.parse("E2")!!).numFmt.orEmpty().lowercase()
        assertTrue(dateFmt, dateFmt.contains("yy") && dateFmt.contains("d"))
        assertEquals(18f, sales.columnWidths[0] ?: 0f, 1f)

        val out = ByteArrayOutputStream()
        XlsxWriter.write(wb, out, null)
        File(outDir, "budget-from-xls.xlsx").writeBytes(out.toByteArray())
        val back = XlsxReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("1750", FormulaEngine(back).display(back.sheets[0], CellRef.parse("D6")!!))
    }
}
