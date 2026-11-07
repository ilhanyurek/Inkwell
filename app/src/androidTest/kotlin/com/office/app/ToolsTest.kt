package com.office.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.office.app.tools.PdfOps
import com.office.app.ui.pdf.PagePoint
import com.office.app.ui.pdf.PdfTools
import com.office.app.ui.pdf.OutlineItem
import com.office.core.docx.DocxReader
import com.office.core.pptx.PptxReader
import com.office.core.util.TextDiff
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** Runs every Tools-tab operation on real files and checks the result with PDFBox. */
@RunWith(AndroidJUnit4::class)
class ToolsTest {

    private val dir = File(targetContext.cacheDir, "tools-test").apply { deleteRecursively(); mkdirs() }

    private fun out(name: String) = File(dir, name)
    private fun pages(f: File, password: String? = null) = (if (password == null) PDDocument.load(f) else PDDocument.load(f, password)).use { it.numberOfPages }
    private fun text(f: File) = PDDocument.load(f).use { PDFTextStripper().getText(it) }
    private fun form() = Fixtures.asset("form.pdf")

    /** A noisy photo-like picture, saved where the app's FileProvider can share it. */
    private fun image(name: String, w: Int, h: Int): android.net.Uri {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        val rnd = Random(w + h)
        for (y in 0 until h step 8) for (x in 0 until w step 8) {
            p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            c.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 8f, p)
        }
        bmp.setHasAlpha(false)
        val f = File(File(targetContext.cacheDir, "exports").apply { mkdirs() }, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Fixtures.uriFor(f)
    }

    @Test
    fun organizeReordersDuplicatesAndInsertsBlankPages() = runBlocking {
        val o = out("organized.pdf")
        PdfOps.organize(form(), listOf(PdfOps.PageSpec(1), PdfOps.PageSpec(0, 90), PdfOps.PageSpec(-1), PdfOps.PageSpec(1)), o)
        PDDocument.load(o).use { doc ->
            assertEquals(4, doc.numberOfPages)
            assertEquals(90, doc.getPage(1).rotation)
            val s = PDFTextStripper().apply { startPage = 1; endPage = 1 }
            assertTrue(s.getText(doc).contains("Ankara"))
            val blank = PDFTextStripper().apply { startPage = 3; endPage = 3 }
            assertTrue(blank.getText(doc).isBlank())
        }
    }

    @Test
    fun deleteRotateAndSplit() = runBlocking {
        val deleted = out("deleted.pdf")
        PdfOps.deletePages(form(), setOf(0), deleted)
        assertEquals(1, pages(deleted))
        val rotated = out("rotated.pdf")
        PdfOps.rotatePages(form(), null, 180, rotated)
        PDDocument.load(rotated).use { d -> assertTrue(d.pages.all { it.rotation == 180 }) }
        val parts = PdfOps.split(form(), 1, dir)
        assertEquals(2, parts.size)
        assertTrue(parts.all { pages(it) == 1 })
        val extracted = out("extracted.pdf")
        PdfOps.extractPages(form(), listOf(1), extracted)
        assertTrue(text(extracted).contains("Ankara"))
    }

    @Test
    fun watermarkAndPageNumbersAreText() = runBlocking {
        val w = out("watermarked.pdf")
        PdfOps.watermark(form(), "CONFIDENTIAL", 48f, 0.3f, 0f, 0xC62828, w)
        assertTrue(text(w).contains("CONFIDENTIAL"))
        val n = out("numbered.pdf")
        PdfOps.pageNumbers(form(), PdfOps.Position.BOTTOM_CENTER, PdfOps.NumberFormat.OF_TOTAL, 1, 11f, "Page", n)
        val t = text(n)
        assertTrue(t, t.contains("1 / 2") && t.contains("2 / 2"))
    }

    @Test
    fun protectThenUnlock() = runBlocking {
        val locked = out("locked.pdf")
        PdfOps.protect(form(), "s3cret", allowPrint = true, allowCopy = false, allowEdit = false, output = locked)
        try {
            PDDocument.load(locked).close()
            fail("Opened without a password")
        } catch (_: InvalidPasswordException) {
        }
        assertEquals(2, pages(locked, "s3cret"))
        val open = out("open.pdf")
        PdfOps.unlock(locked, "s3cret", open)
        PDDocument.load(open).use { assertFalse(it.isEncrypted) }
    }

    @Test
    fun metadataCropRepairAndFlatten() = runBlocking {
        val meta = out("meta.pdf")
        PdfOps.writeMetadata(form(), PdfOps.Metadata(title = "Inkwell title", author = "Ada"), meta)
        assertEquals("Inkwell title", PdfOps.readMetadata(meta).title)
        val cleared = out("cleared.pdf")
        PdfOps.writeMetadata(meta, PdfOps.Metadata(), cleared)
        assertEquals(PdfOps.Metadata(), PdfOps.readMetadata(cleared))

        val cropped = out("cropped.pdf")
        PdfOps.crop(form(), null, floatArrayOf(36f, 36f, 36f, 36f), cropped)
        PDDocument.load(cropped).use { d -> assertEquals(d.getPage(0).mediaBox.width - 72f, d.getPage(0).cropBox.width, 0.5f) }

        val repaired = out("repaired.pdf")
        PdfOps.repair(form(), repaired)
        assertEquals(2, pages(repaired))

        // A highlight and the form fields are burned into the page.
        val annotated = out("annotated.pdf").also { form().copyTo(it, overwrite = true) }
        PdfTools.highlight(annotated, 0, PagePoint(0.1f, 0.05f), PagePoint(0.6f, 0.1f), 0xFFEB3B)
        val flat = out("flat.pdf")
        PdfOps.flatten(annotated, flat)
        PDDocument.load(flat).use { d ->
            assertTrue(d.getPage(0).annotations.none { it.subtype == "Highlight" })
            assertTrue(d.documentCatalog.acroForm?.fields.isNullOrEmpty())
        }
    }

    @Test
    fun imagesToPdfCompressAndExtractImages() = runBlocking {
        val pdf = out("photos.pdf")
        PdfOps.imagesToPdf(targetContext, listOf(image("a.png", 1600, 1200), image("b.png", 900, 1400)), PdfOps.PageSize.A4, 20f, documentFilter = false, output = pdf)
        PDDocument.load(pdf).use { d ->
            assertEquals(2, d.numberOfPages)
            assertTrue("wide image gets a landscape page", d.getPage(0).mediaBox.width > d.getPage(0).mediaBox.height)
        }
        val small = out("small.pdf")
        assertTrue("strong compression shrinks photos", PdfOps.compress(pdf, 600, 0.4f, small))
        assertTrue(small.length() < pdf.length())
        assertEquals(2, pages(small))
        val names = ArrayList<String>()
        assertEquals(2, PdfOps.extractImages(pdf) { name, bytes -> names += name; assertTrue(bytes.isNotEmpty()) })
        val scan = out("scan.pdf")
        PdfOps.imagesToPdf(targetContext, listOf(image("c.png", 800, 1100)), PdfOps.PageSize.FIT, 0f, documentFilter = true, output = scan)
        assertEquals(1, pages(scan))
    }

    @Test
    fun convertsOfficeFilesToPdf() = runBlocking {
        val word = out("report.pdf")
        PdfOps.wordToPdf(Fixtures.asset("report.docx"), word)
        assertTrue(text(word).contains("Quarterly Report"))
        val sheet = out("budget.pdf")
        PdfOps.sheetToPdf(Fixtures.asset("budget.xlsx"), csv = false, output = sheet)
        assertTrue(text(sheet).contains("Kalem"))
        val csv = out("cities.pdf")
        PdfOps.sheetToPdf(Fixtures.csv(), csv = true, output = csv)
        assertTrue(text(csv).contains("Ankara"))
        val slides = out("deck.pdf")
        PdfOps.slidesToPdf(Fixtures.asset("deck.pptx"), slides)
        assertEquals(3, pages(slides))
        val md = out("notes.pdf")
        PdfOps.textToPdf(Fixtures.markdown(), markdown = true, output = md)
        assertTrue(text(md).contains("Notes"))
    }

    @Test
    fun convertsPdfToOtherFormats() = runBlocking {
        val docx = out("form.docx")
        PdfOps.pdfToWord(form(), docx, { "Page $it" })
        val doc = docx.inputStream().use { DocxReader.read(it) }
        assertTrue(doc.paragraphs.any { it.text.contains("Application form") })
        assertTrue(doc.paragraphs.any { it.text == "Page 2" })

        val pptx = out("form.pptx")
        PdfOps.pdfToPowerPoint(form(), pptx)
        assertEquals(2, pptx.inputStream().use { PptxReader.read(it) }.slides.size)

        assertTrue(PdfOps.pdfToText(form()).contains("Ankara"))

        val images = ArrayList<String>()
        PdfOps.pdfToImages(form(), 72, PdfOps.ImageFormat.PNG) { name, bytes -> images += name; assertTrue(bytes.size > 100) }
        assertEquals(listOf("form-1.png", "form-2.png"), images)
    }

    @Test
    fun compareFindsTheAddedText() = runBlocking {
        val w = out("compare-b.pdf")
        PdfOps.watermark(form(), "DRAFT COPY", 40f, 0.3f, 0f, 0x000000, w)
        val lines = PdfOps.compare(form(), w) { "Page $it" }
        assertTrue(lines.any { it.kind == TextDiff.Kind.ADDED && it.text.contains("DRAFT COPY") })
        assertTrue(PdfOps.compare(form(), form()) { "Page $it" }.all { it.kind == TextDiff.Kind.SAME })
    }

    @Test
    fun wordsAndOutlineForSelectionAndContents() = runBlocking {
        val words = PdfTools.words(form(), 0)
        val title = words.firstOrNull { it.text == "Application" }
        assertTrue("words: ${words.take(10)}", title != null)
        assertTrue(title!!.left < title.right && title.top < title.bottom && title.top < 0.3f)
        assertEquals("Application form", PdfTools.joinWords(words.take(2)))

        // A PDF with two bookmarks, one nested.
        val f = out("outline.pdf")
        PDDocument().use { doc ->
            repeat(3) { doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage()) }
            val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
            fun item(title: String, page: Int) = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
                this.title = title
                destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination().apply { this.page = doc.getPage(page) }
            }
            val chapter = item("Chapter 1", 0)
            chapter.addLast(item("Section 1.1", 2))
            outline.addLast(chapter)
            doc.documentCatalog.documentOutline = outline
            doc.save(f)
        }
        assertEquals(
            listOf(OutlineItem("Chapter 1", 0, 0), OutlineItem("Section 1.1", 1, 2)),
            PdfTools.outline(f),
        )
    }
}
