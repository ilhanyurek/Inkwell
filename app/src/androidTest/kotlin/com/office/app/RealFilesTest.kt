package com.office.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.office.core.xlsx.XlsxReader
import com.office.core.zip.ZipPackage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens files produced by other tools (see scripts/make_fixtures.py) on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class RealFilesTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private fun open(name: String, mime: String, vararg expected: String, shot: String) = rule.explainFailure {
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(Fixtures.asset(name), mime)).use {
            expected.forEach { rule.waitForText(it) }
            rule.screenshot(shot)
        }
    }

    @Test fun wordReport() = open(
        "report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "Quarterly Report", "İzmir sales doubled", "City", "our site",
        shot = "20_real_docx",
    )

    @Test fun excelBudget() = open(
        "budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Kalem", "Defter", "Sales", "Notes", "3.50 ₺", "12.01.2025",
        shot = "21_real_xlsx",
    )

    @Test fun excelChartsFilterAndFreeze() = rule.explainFailure {
        val file = Fixtures.asset("budget.xlsx")
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(file, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).use {
            rule.waitForText("Defter")
            rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
            rule.onNodeWithTag("menu_charts").performClick()
            rule.onNodeWithTag("chart_0").assertExists()
            rule.waitForText("Units")
            rule.screenshot("25_xlsx_chart")
            rule.onNodeWithContentDescription(str(R.string.action_close)).performClick()

            // Column A is selected: hide the "Kalem" row.
            rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
            rule.onNodeWithTag("menu_filter").performClick()
            rule.onNodeWithTag("filter_value_Kalem").performClick()
            rule.onNodeWithTag("filter_apply").performClick()
            rule.onNodeWithTag("filter_bar").assertExists()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("Kalem").fetchSemanticsNodes().isEmpty() }
            rule.onAllNodesWithText("Defter").fetchSemanticsNodes().isNotEmpty().let { assert(it) }
            rule.onNodeWithText(str(R.string.sheet_filter_clear)).performClick()
            rule.waitForText("Kalem")

            rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
            rule.onNodeWithTag("menu_freeze").performClick()
            rule.onNodeWithContentDescription(str(R.string.action_save)).performClick()
            rule.waitUntil(15_000) {
                runCatching { XlsxReader.read(ZipPackage.read(file.inputStream())).sheets[0].frozenRows == 1 }.getOrDefault(false)
            }
        }
    }

    @Test fun powerPointDeck() = open(
        "deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "Inkwell Deck", str(R.string.slide_of, 1, 3),
        shot = "22_real_pptx",
    )

    @Test fun editSlideText() = rule.explainFailure {
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(Fixtures.asset("deck.pptx"), "application/vnd.openxmlformats-officedocument.presentationml.presentation")).use {
            rule.waitForText("Inkwell Deck")
            rule.onNodeWithContentDescription(str(R.string.slide_edit)).performClick()
            rule.onAllNodesWithText("Inkwell Deck")[0].performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement("Edited title")
            rule.onNodeWithText(str(R.string.action_ok)).performClick()
            rule.waitForText("Edited title")
            rule.onNodeWithContentDescription(str(R.string.action_save)).assertExists()
            rule.screenshot("24_pptx_edited")
        }
    }

    @Test fun pdfForm() = open(
        "form.pdf", "application/pdf",
        str(R.string.pdf_page_of, 1, 2),
        shot = "23_real_pdf",
    )
}
