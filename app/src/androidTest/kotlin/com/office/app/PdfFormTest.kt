package com.office.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfFormTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun fillsAFormFieldIntoThePdf() = rule.explainFailure {
        val drafts = (targetContext.applicationContext as OfficeApp).container.drafts
        drafts.drafts.value.forEach { drafts.delete(it.key) }
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(Fixtures.asset("form.pdf"), "application/pdf")).use {
            rule.waitForText(str(R.string.pdf_page_of, 1, 2))
            // Form fields are read in the background after the PDF opens.
            rule.waitUntil(20_000) {
                rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
                val shown = rule.onAllNodesWithText(str(R.string.pdf_fill_form)).fetchSemanticsNodes().isNotEmpty()
                if (!shown) pressBack()
                shown
            }
            rule.onNodeWithText(str(R.string.pdf_fill_form)).performClick()
            rule.onNode(hasSetTextAction() and hasText("full_name")).performTextInput("Ada Lovelace")
            rule.screenshot("30_pdf_form")
            rule.onNodeWithText(str(R.string.action_ok)).performClick()
            // The edited PDF is autosaved as a draft; read the field back with PDFBox.
            rule.waitUntil(20_000) {
                val draft = drafts.drafts.value.firstOrNull() ?: return@waitUntil false
                runCatching {
                    PDDocument.load(draft.file).use { it.documentCatalog.acroForm.getField("full_name").valueAsString == "Ada Lovelace" }
                }.getOrDefault(false)
            }
        }
    }

    @Test
    fun redactionRemovesTheTextUnderneath() = rule.explainFailure {
        val drafts = (targetContext.applicationContext as OfficeApp).container.drafts
        drafts.drafts.value.forEach { drafts.delete(it.key) }
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(Fixtures.asset("form.pdf"), "application/pdf")).use {
            rule.waitForText(str(R.string.pdf_page_of, 1, 2))
            rule.onNodeWithText(str(R.string.pdf_tool_redact)).performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("pdf_page_0")).fetchSemanticsNodes().isNotEmpty() }
            // The title line "Application form (Inkwell fixture)" sits near the top of page 1.
            rule.onNode(androidx.compose.ui.test.hasTestTag("pdf_page_0")).performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * 0.05f, height * 0.03f), androidx.compose.ui.geometry.Offset(width * 0.95f, height * 0.12f), 400)
            }
            rule.onNodeWithText(str(R.string.pdf_redact_apply)).performClick()
            rule.onNodeWithText(str(R.string.pdf_tool_redact)).performClick()
            rule.waitUntil(30_000) {
                val draft = drafts.drafts.value.firstOrNull() ?: return@waitUntil false
                runCatching {
                    PDDocument.load(draft.file).use { doc ->
                        val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = 1; endPage = 1 }
                        val page1 = stripper.getText(doc)
                        val all = com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
                        !page1.contains("Application form") && all.contains("Ankara")
                    }
                }.getOrDefault(false)
            }
            rule.screenshot("31_pdf_redacted")
        }
    }
}
