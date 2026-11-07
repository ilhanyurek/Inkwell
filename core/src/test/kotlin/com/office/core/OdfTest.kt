package com.office.core

import com.office.core.formula.FormulaEngine
import com.office.core.odf.OdfFormula
import com.office.core.odf.OdpReader
import com.office.core.odf.OdsReader
import com.office.core.odf.OdsWriter
import com.office.core.odf.OdtReader
import com.office.core.odf.OdtWriter
import com.office.core.pptx.SlideShape
import com.office.core.sheet.CellRef
import com.office.core.text.Alignment
import com.office.core.text.EMBED_CHAR
import com.office.core.text.EmbedKind
import com.office.core.text.InlineAttr
import com.office.core.text.ListType
import com.office.core.text.ParagraphStyle
import com.office.core.text.RichText
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** OpenDocument files made by LibreOffice from the python-made fixtures. */
class OdfTest {

    private fun fixture(name: String) = ZipPackage.read(ByteArrayInputStream(javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }))

    private val outDir = File("build/fixture-out").apply { mkdirs() }

    private fun texts(doc: com.office.core.text.TextDocument) = doc.paragraphs.map { it.text.replace(EMBED_CHAR.toString(), "") }

    @Test fun readsOdt() {
        val doc = OdtReader.read(fixture("report.odt"))
        val texts = texts(doc)
        assertEquals("Quarterly Report", texts[0])
        assertEquals(ParagraphStyle.HEADING1, doc.paragraphs[0].style)
        assertEquals(ParagraphStyle.HEADING2, doc.paragraphs.first { it.text == "Highlights" }.style)
        assertEquals(ListType.BULLET, doc.paragraphs.first { it.text == "Ankara office opened" }.list)
        assertEquals(ListType.NUMBERED, doc.paragraphs.first { it.text == "Plan" }.list)
        val revenue = doc.paragraphs.first { it.text.startsWith("Revenue grew") }
        assertTrue(revenue.runs.any { it.text == "strongly" && it.bold })
        assertTrue(revenue.runs.any { it.text == "Q3" && it.italic && it.sizePt == 14f })
        assertEquals(Alignment.CENTER, doc.paragraphs.first { it.text == "Centered closing line." }.alignment)
        val table = doc.embeds.values.first { it.kind == EmbedKind.TABLE }
        assertEquals(listOf("City", "Q2", "Q3"), table.tableRows[0])
        assertTrue(doc.embeds.values.any { it.kind == EmbedKind.IMAGE })
        assertTrue(doc.embeds.values.any { it.kind == EmbedKind.LINK && it.label == "our site" })
        assertTrue(doc.paragraphs.any { doc.readableText(it) == "Visit our site for more." })
    }

    @Test fun odtRoundTripKeepsContentAndEdits() {
        val src = fixture("report.odt")
        val doc = OdtReader.read(src)
        var rt = RichText.fromDocument(doc)
        val at = rt.text.indexOf("Details below.")
        rt = rt.replace(at, at, "Edited  twice. ", rt.styleAt(at))
        val bold = rt.text.indexOf("Edited")
        rt = rt.toggleInline(bold, bold + 6, InlineAttr.BOLD)
        val closing = rt.text.indexOf("Centered closing line.")
        rt = rt.setAlignment(closing, closing, Alignment.END)
        val out = ByteArrayOutputStream()
        OdtWriter.write(rt.toDocument(embeds = doc.embeds), out, src)
        File(outDir, "report-edited.odt").writeBytes(out.toByteArray())

        val pkg = ZipPackage.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("mimetype", pkg.entries.keys.first())
        val back = OdtReader.read(pkg)
        val revenue = back.paragraphs.first { it.text.startsWith("Revenue grew") }
        assertTrue(revenue.text.contains("Edited  twice. Details below."))
        assertTrue(revenue.runs.any { it.text == "Edited" && it.bold })
        assertTrue(revenue.runs.any { it.text == "Q3" && it.italic })
        assertEquals(Alignment.END, back.paragraphs.first { it.text == "Centered closing line." }.alignment)
        assertEquals(texts(doc).size, texts(back).size)
        assertEquals(ListType.NUMBERED, back.paragraphs.first { it.text == "Plan" }.list)
        assertTrue(pkg.entries.keys.any { it.startsWith("Pictures/") })
    }

    @Test fun newOdt() {
        var rt = RichText.plain("Title\nFirst item\nSecond item\nPlain  text")
        rt = rt.setParagraphStyle(0, 0, ParagraphStyle.HEADING1)
        rt = rt.toggleList(6, 20, ListType.BULLET)
        val out = ByteArrayOutputStream()
        OdtWriter.write(rt.toDocument(), out, null)
        File(outDir, "new.odt").writeBytes(out.toByteArray())
        val back = OdtReader.read(ZipPackage.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals(listOf("Title", "First item", "Second item", "Plain  text"), texts(back))
        assertEquals(ParagraphStyle.HEADING1, back.paragraphs[0].style)
        assertEquals(ListType.BULLET, back.paragraphs[2].list)
    }

    @Test fun formulasConvertBothWays() {
        assertEquals("=SUM(A1:B3)*Notes!C4", OdfFormula.toExcel("of:=SUM([.A1:.B3])*[\$Notes.C4]"))
        assertEquals("=IF(D6>1000,\"a;b\",'My sheet'!A1)", OdfFormula.toExcel("of:=IF([.D6]>1000;\"a;b\";[\$'My sheet'.A1])"))
        assertEquals("of:=SUM([.A1:.B3]);[\$Notes.C4]".replace(");", ")*"), OdfFormula.toOdf("=SUM(A1:B3)*Notes!C4"))
        assertEquals("of:=LOG10([.A1];\"x,y\")", OdfFormula.toOdf("=LOG10(A1,\"x,y\")"))
        assertEquals("of:=[\$'My sheet'.A1:.B2]", OdfFormula.toOdf("='My sheet'!A1:B2"))
    }

    @Test fun readsAndWritesOds() {
        val src = fixture("budget.ods")
        val wb = OdsReader.read(src)
        assertEquals(listOf("Sales", "Notes"), wb.sheets.map { it.name })
        val sales = wb.sheets[0]
        val engine = FormulaEngine(wb)
        fun v(ref: String) = engine.display(sales, CellRef.parse(ref)!!)
        assertEquals("Kalem", v("A2"))
        assertEquals("420", v("D2"))
        assertEquals("1750", v("D6"))
        assertEquals("good", v("B8"))
        assertEquals("3500", engine.display(wb.sheets[1], CellRef(0, 1)))
        assertTrue(sales.style(CellRef(0, 0)).bold)
        assertEquals("=SUM(B2:B4)", sales[CellRef.parse("B6")!!])

        sales[CellRef.parse("B2")!!] = "130"
        sales.setStyle(CellRef.parse("A3")!!, sales.style(CellRef.parse("A3")!!).copy(italic = true))
        val out = ByteArrayOutputStream()
        OdsWriter.write(wb, out, src)
        File(outDir, "budget-edited.ods").writeBytes(out.toByteArray())
        val again = OdsReader.read(ZipPackage.read(ByteArrayInputStream(out.toByteArray())))
        val e2 = FormulaEngine(again)
        assertEquals("1785", e2.display(again.sheets[0], CellRef.parse("D6")!!))
        assertEquals("3570", e2.display(again.sheets[1], CellRef(0, 1)))
        assertTrue(again.sheets[0].style(CellRef.parse("A3")!!).italic)
        assertTrue(again.sheets[0].style(CellRef(0, 0)).bold)
        assertEquals(sales.columnWidths[0] ?: 0f, again.sheets[0].columnWidths[0] ?: 0f, 0.5f)
    }

    @Test fun readsOdp() {
        val pres = OdpReader.read(fixture("deck.odp"))
        assertEquals(3, pres.slides.size)
        val titles = pres.slides.map { s -> s.shapes.filterIsInstance<SlideShape.TextBox>().firstOrNull { it.isTitle }?.paragraphs?.joinToString { it.text } }
        assertEquals("Inkwell Deck", titles[0])
        assertEquals("Agenda", titles[1])
        val agenda = pres.slides[1].shapes.filterIsInstance<SlideShape.TextBox>().first { !it.isTitle }
        assertEquals(listOf("Introduction", "Numbers", "Next steps"), agenda.paragraphs.take(3).map { it.text })
        assertTrue(agenda.paragraphs.first().bullet)
        assertEquals("Speaker note for agenda", pres.slides[1].notes)
        assertTrue(pres.aspectRatio > 1.3f)
    }
}
