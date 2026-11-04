package com.office.app

import androidx.compose.ui.test.and
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
            rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
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
}
