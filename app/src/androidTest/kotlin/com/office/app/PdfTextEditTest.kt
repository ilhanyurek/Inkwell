package com.office.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.office.app.ui.pdf.PdfText
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The text of a PDF is edited in place: old glyphs are taken out of the page and the new text drawn there. */
@RunWith(AndroidJUnit4::class)
class PdfTextEditTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private fun samplePdf(): File = File(targetContext.cacheDir, "edit-sample.pdf").also { f ->
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 16f); cs.newLineAtOffset(72f, 760f); cs.showText("Quarterly Title"); cs.endText()
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 11f); cs.setLeading(14f); cs.newLineAtOffset(72f, 720f)
                cs.showText("The first line of a paragraph that goes on"); cs.newLine()
                cs.showText("and a second line closing it."); cs.endText()
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 11f); cs.newLineAtOffset(72f, 600f); cs.showText("Separate footer line"); cs.endText()
                // Kerned text with a wide gap: two separate pieces on one line.
                cs.beginText(); cs.setFont(PDType1Font.TIMES_ROMAN, 12f); cs.newLineAtOffset(72f, 500f)
                cs.showTextWithPositioning(arrayOf<Any>("Na", 20f, "me:", -6000f, "Va", -10f, "lue here")); cs.endText()
            }
            doc.save(f)
        }
    }

    private fun text(f: File) = PDDocument.load(f).use { PDFTextStripper().getText(it) }

    @Test
    fun findsParagraphsAndReplacesThemInPlace(): Unit = runBlocking {
        val f = samplePdf()
        val blocks = PdfText.blocks(f, 0)
        assertEquals(listOf("Quarterly Title", "The first line of a paragraph that goes on and a second line closing it.", "Separate footer line", "Name:", "Value here"), blocks.map { it.text })
        assertEquals(2, blocks[1].lineCount)
        assertEquals(11f, blocks[1].style.sizePt, 0.01f)
        assertTrue(blocks[0].style.bold)
        assertEquals(1, blocks[3].style.family) // Times is a serif

        val footerBefore = blocks[2]
        PdfText.replace(f, blocks[1], "Güncellenmiş paragraf: ışık, şeker ve çiçek ile bu satır sarmalıdır.", blocks[1].style, 0xFFFFFF)
        val after = text(f)
        assertFalse(after.contains("The first line"))
        assertFalse(after.contains("second line closing"))
        assertTrue(after.contains("Güncellenmiş paragraf: ışık, şeker"))

        val blocks2 = PdfText.blocks(f, 0)
        val footer = blocks2.first { it.text == "Separate footer line" }
        assertEquals(footerBefore.x0, footer.x0, 0.5f)
        assertEquals(footerBefore.firstBaseline, footer.firstBaseline, 0.5f)

        // Only the second piece of the kerned line changes; the first keeps its place.
        val value = blocks2.first { it.text == "Value here" }
        val name = blocks2.first { it.text == "Name:" }
        PdfText.replace(f, value, "Değer", value.style, 0xFFFFFF)
        val final = text(f)
        assertFalse(final.contains("Value here"))
        assertTrue(final.contains("Değer"))
        val name2 = PdfText.blocks(f, 0).first { it.text == "Name:" }
        assertEquals(name.x0, name2.x0, 0.5f)
    }

    @Test
    fun editsATitleOnThePage() = rule.explainFailure {
        val drafts = (targetContext.applicationContext as OfficeApp).container.drafts
        drafts.drafts.value.forEach { drafts.delete(it.key) }
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(Fixtures.asset("form.pdf"), "application/pdf")).use {
            rule.waitForText(str(R.string.pdf_page_of, 1, 2))
            rule.onNodeWithTag("pdf_tool_edit_text").performScrollTo().performClick()
            // The title "Application form (Inkwell fixture)" is the first paragraph of page 1.
            rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("pdf_block_0_0")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pdf_block_0_0").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("pdf_block_editor")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pdf_block_editor").performTextReplacement("Başvuru formu")
            rule.screenshot("32_pdf_edit_text")
            rule.onNodeWithTag("pdf_block_done").performClick()
            var seen = "no draft"
            val done = runCatching {
                rule.waitUntil(30_000) {
                    val draft = drafts.drafts.value.firstOrNull() ?: return@waitUntil false
                    runCatching {
                        PDDocument.load(draft.file).use { doc ->
                            val page1 = PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc)
                            seen = page1.replace("\n", " | ").take(200)
                            page1.contains("Başvuru formu") && !page1.contains("Application form")
                        }
                    }.getOrDefault(false)
                }
            }.isSuccess
            // Say what the draft held, so a failure on CI is readable from the log.
            assertTrue("page 1 of the draft: $seen", done)
            rule.screenshot("33_pdf_text_edited")
        }
    }
}
