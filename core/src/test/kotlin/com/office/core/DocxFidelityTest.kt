package com.office.core

import com.office.core.docx.DocxFormatting
import com.office.core.docx.DocxReader
import com.office.core.docx.DocxWriter
import com.office.core.text.Alignment
import com.office.core.text.CharStyle
import com.office.core.text.InlineAttr
import com.office.core.text.ParagraphStyle
import com.office.core.text.RichText
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlParser
import com.office.core.xml.XmlText
import com.office.core.xml.XmlWriter
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** The editor must show a document's real formatting and must not change what it did not edit. */
class DocxFidelityTest {

    private fun fixture(name: String) = ZipPackage.read(ByteArrayInputStream(javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }))

    private val outDir = File("build/fixture-out").apply { mkdirs() }

    // --- no-edit round trip -----------------------------------------------------------------

    /** A paragraph or other body element in a form that ignores differences Word also ignores. */
    private fun canon(el: XmlElement): String {
        if (el.localName == "sdt") {
            return "SDT[" + el.elements.filter { it.localName != "sdtContent" }.joinToString("") { XmlWriter.write(it) } + "]{" +
                el.child("sdtContent")?.elements.orEmpty().joinToString("|") { canon(it) } + "}"
        }
        if (el.localName != "p") return XmlWriter.write(el)
        val sb = StringBuilder("P[")
        el.child("pPr")?.let { sb.append(props(it)) }
        sb.append("]")
        var lastProps: String? = null
        fun walk(container: XmlElement) {
            for (c in container.elements) {
                when (c.localName) {
                    "pPr", "proofErr", "bookmarkStart", "bookmarkEnd" -> {}
                    "sdt" -> { sb.append("|SDT[").append(c.elements.filter { it.localName != "sdtContent" }.joinToString("") { XmlWriter.write(it) }).append("]{"); lastProps = null; c.child("sdtContent")?.let { walk(it) }; sb.append("}"); lastProps = null }
                    "r" -> {
                        val text = c.elements.filter { it.localName != "rPr" && it.localName != "lastRenderedPageBreak" }.joinToString("") {
                            if (it.localName == "t") it.text() else XmlWriter.write(it)
                        }
                        if (text.isEmpty()) continue
                        val p = c.child("rPr")?.let(::props).orEmpty()
                        if (p == lastProps) sb.append(text) else sb.append("|R{").append(p).append("}").append(text)
                        lastProps = p
                    }
                    else -> { sb.append("|").append(XmlWriter.write(c)); lastProps = null }
                }
            }
        }
        walk(el)
        return sb.toString()
    }

    private fun props(pr: XmlElement): String = pr.elements
        .filter { it.localName != "rPr" || it.elements.isNotEmpty() }
        .map { e -> e.localName + e.attributes.filterKeys { !it.contains("rsid") }.toSortedMap() + e.children.joinToString("") { if (it is XmlElement) XmlWriter.write(it) else (it as XmlText).text } }
        .sorted().joinToString(";")

    private fun body(pkg: ZipPackage): List<String> =
        pkg.xml("word/document.xml")!!.child("body")!!.elements
            .filter { it.localName !in setOf("bookmarkStart", "bookmarkEnd", "proofErr") }
            .map(::canon)

    @Test fun savingWithoutEditsChangesNothing() {
        for (name in listOf("styled.docx", "report.docx", "form.docx")) {
            val src = fixture(name)
            val doc = DocxReader.read(src)
            val viaEditor = RichText.fromDocument(doc).toDocument(embeds = doc.embeds)
            val out = ByteArrayOutputStream()
            DocxWriter.write(viaEditor, out, src)
            File(outDir, name.replace(".docx", "-roundtrip.docx")).writeBytes(out.toByteArray())
            val back = ZipPackage.read(ByteArrayInputStream(out.toByteArray()))
            val before = body(src)
            val after = body(back)
            assertEquals("$name: number of body elements", before.size, after.size)
            for (i in before.indices) assertEquals("$name: element $i", before[i], after[i])
            // No style definitions are added or changed when nothing was edited.
            assertEquals(String(src.bytes("word/styles.xml")!!), String(back.bytes("word/styles.xml")!!))
        }
    }

    @Test fun editsKeepOffTogglesAndUnderlineKinds() {
        val src = fixture("styled.docx")
        val doc = DocxReader.read(src)
        var rt = RichText.fromDocument(doc)
        val at = rt.text.indexOf("Plain, ")
        rt = rt.replace(at, at, "Edited. ", rt.styleAt(at))
        val out = ByteArrayOutputStream()
        DocxWriter.write(rt.toDocument(embeds = doc.embeds), out, src)
        val xml = String(ZipPackage.read(ByteArrayInputStream(out.toByteArray())).bytes("word/document.xml")!!)
        assertTrue(xml.contains("""<w:b w:val="0"/>"""))
        assertTrue(xml.contains("""<w:u w:val="double"/>"""))
        assertTrue(xml.contains("""<w:pStyle w:val="Title"/>"""))
        assertTrue(xml.contains("Edited. "))

        // Turning the underline off removes the double underline instead of writing it back.
        val u = rt.text.indexOf("double")
        val off = rt.toggleInline(u, u + 6, InlineAttr.UNDERLINE)
        val out2 = ByteArrayOutputStream()
        DocxWriter.write(off.toDocument(embeds = doc.embeds), out2, src)
        assertFalse(String(ZipPackage.read(ByteArrayInputStream(out2.toByteArray())).bytes("word/document.xml")!!).contains("double\"/>"))
    }

    // --- effective formatting -----------------------------------------------------------------

    @Test fun resolvesRunAndParagraphLooks() {
        val src = fixture("styled.docx")
        val rt = RichText.fromDocument(DocxReader.read(src))
        val f = DocxFormatting(src)
        fun look(word: String): com.office.core.text.RunLook {
            val i = rt.text.indexOf(word)
            return f.runLook(rt.paras[rt.lineIndexAt(i)], rt.styleAt(i))
        }
        val body = look("Plain")
        assertEquals(11f, body.sizePt)
        assertFalse(body.bold)
        assertEquals(0xFFC00000L, look("red").color)
        assertEquals(0xFFFFFF00L, look("highlighted").highlight)
        assertEquals("Times New Roman", look("Times 14").font)
        assertEquals(14f, look("Times 14").sizePt)
        assertTrue(look("double").underline)
        assertEquals(1, look("2 and").vertical)
        assertEquals("Courier New", look("mono").font)

        // Heading 1 of python-docx's template: bold, 14pt, theme font and colour; "light" switches bold off.
        val heading = look("Heading with")
        assertTrue(heading.bold)
        assertEquals(14f, heading.sizePt)
        assertTrue(heading.color != null)
        assertFalse(look("light").bold)
        assertTrue(look("Styled Title").sizePt!! > 20f)

        val indented = f.paraLook(rt.paras[rt.lineIndexAt(rt.text.indexOf("Indented paragraph"))])
        assertEquals(42.5f, indented.indentLeft, 0.1f)
        assertEquals(21.25f, indented.firstLine, 0.1f)
        assertEquals(18f, indented.spaceBefore, 0.01f)
        assertEquals(12f, indented.spaceAfter, 0.01f)
        assertEquals(1.5f, indented.lineMultiple!!, 0.01f)
        val child = f.paraLook(rt.paras[rt.lineIndexAt(rt.text.indexOf("First child"))])
        assertEquals(39.6f, child.indentLeft, 0.1f)
        assertEquals(-21.6f, child.firstLine, 0.1f)
        assertEquals(Alignment.CENTER, f.paraLook(rt.paras[0]).alignment ?: Alignment.CENTER)
    }

    @Test fun computesListLabels() {
        val src = fixture("styled.docx")
        val rt = RichText.fromDocument(DocxReader.read(src))
        val labels = DocxFormatting(src).listLabels(rt.paras)
        fun label(text: String) = labels[rt.lineIndexAt(rt.text.indexOf(text))]
        assertEquals("1.", label("First")!!.text)
        assertEquals("1.1.", label("First child")!!.text)
        assertEquals("1.2.", label("Second child")!!.text)
        assertEquals("a)", label("Deep a")!!.text)
        assertEquals("b)", label("Deep b")!!.text)
        assertEquals(2, label("Deep b")!!.level)
        assertEquals("2.", label("Second\n")!!.text)
        assertEquals("2.1.", label("Second first child")!!.text)
        assertEquals("•", label("Bullet from a style")!!.text)
        assertEquals(null, label("Plain, "))
        assertEquals("iv", com.office.core.docx.DocxNumbering.format(4, "lowerRoman"))
        assertEquals("AA", com.office.core.docx.DocxNumbering.format(27, "upperLetter"))
    }

    @Test fun headingPickedInEditorUsesTheDocumentsOwnStyle() {
        val src = fixture("styled.docx")
        val doc = DocxReader.read(src)
        var rt = RichText.fromDocument(doc)
        val at = rt.text.indexOf("Right aligned line")
        rt = rt.setParagraphStyle(at, at, ParagraphStyle.HEADING2)
        val f = DocxFormatting(src)
        assertEquals("Heading2", f.styleIdOf(ParagraphStyle.HEADING2, null))
        assertTrue(f.runLook(rt.paras[rt.lineIndexAt(at)], CharStyle.PLAIN).bold)
    }

    // --- forms: content controls, tables, header ------------------------------------------------

    @Test fun contentControlsAreUnwrappedAndKept() {
        val src = fixture("form.docx")
        val doc = DocxReader.read(src)
        val text = doc.paragraphs.joinToString("\n") { it.text }
        // Text in block and inline content controls is ordinary, editable text now.
        assertTrue(text.contains("Çalışmada incelenecek gerçekçi koşullar"))
        val tableXml = doc.embeds.values.filter { it.kind == com.office.core.text.EmbedKind.TABLE }.map { it.xml }
        assertTrue(tableXml.any { it.contains("Seramik Bal Peteği") })

        // An edit inside the control keeps the control around it.
        var rt = RichText.fromDocument(doc)
        val at = rt.text.indexOf("Çalışmada incelenecek")
        rt = rt.replace(at, at, "Yeni: ", rt.styleAt(at))
        val out = ByteArrayOutputStream()
        DocxWriter.write(rt.toDocument(embeds = doc.embeds), out, src)
        val xml = String(ZipPackage.read(ByteArrayInputStream(out.toByteArray())).bytes("word/document.xml")!!)
        XmlParser.parse(xml)
        val sdt = Regex("<w:sdt>.*?</w:sdt>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.value }.first { "incelenecek" in it }
        assertTrue(sdt.contains("Yeni: Çalışmada"))

        // Deleting the whole paragraph (and so one half of the control) still writes a valid file.
        val start = rt.lineRange(rt.lineIndexAt(at))
        val cut = rt.replace(start.first, start.last + 2, "", CharStyle.PLAIN)
        val out2 = ByteArrayOutputStream()
        DocxWriter.write(cut.toDocument(embeds = doc.embeds), out2, src)
        XmlParser.parse(String(ZipPackage.read(ByteArrayInputStream(out2.toByteArray())).bytes("word/document.xml")!!))
    }

    @Test fun tableLayoutsAndHeader() {
        val src = fixture("form.docx")
        val doc = DocxReader.read(src)
        val layout = com.office.core.docx.DocxLayout(src)
        val tables = doc.embeds.values.filter { it.kind == com.office.core.text.EmbedKind.TABLE }.map { layout.table(it.xml)!! }

        val students = tables.first { it.text.contains("Öğrenci No") }
        assertEquals(7, students.columnsPt.size)
        assertEquals(15f, students.columnsPt[3], 0.1f)
        val head = students.rows[0].cells[0]
        assertTrue(head.top != null && head.left != null)
        val label = (head.items[0] as com.office.core.docx.LayoutItem.Para)
        assertTrue(label.runs.all { it.look.bold })
        assertEquals(Alignment.CENTER, label.alignment)
        assertEquals("Times New Roman", label.runs[0].look.font)

        val study = tables.first { it.text.contains("Çalışmanın Adı") }
        val adi = study.rows[0].cells[0].items[0] as com.office.core.docx.LayoutItem.Para
        assertEquals("Çalışmanın Adı: Seramik Bal Peteği Yapıların Tasarımı ve Optimizasyonu", adi.text)
        assertTrue(adi.runs.first().look.bold && !adi.runs.last().look.bold)

        val approval = tables.first { it.text.contains("UYGUNDUR") }
        assertEquals(2, approval.rows[0].cells[0].rowSpan)
        assertEquals(0, approval.rows[1].cells[0].rowSpan)
        assertEquals(3, approval.rows[2].cells[0].span)

        val checks = tables.first { it.text.contains("Ekonomi") }
        assertTrue(checks.text.contains("☒") && checks.text.contains("☐"))

        val (header, footer) = layout.headerFooter()
        val headerTable = (header!!.first { it is com.office.core.docx.LayoutItem.Table } as com.office.core.docx.LayoutItem.Table).layout
        assertTrue(headerTable.text.contains("TASARIM ÇALIŞMASI ÖNERİ FORMU"))
        val pictures = headerTable.rows[0].cells.flatMap { it.items }.filterIsInstance<com.office.core.docx.LayoutItem.Picture>()
        assertEquals(2, pictures.size)
        assertTrue(pictures.all { it.bytes != null && it.widthPt > 30f })
        assertEquals(null, footer)
    }
}
