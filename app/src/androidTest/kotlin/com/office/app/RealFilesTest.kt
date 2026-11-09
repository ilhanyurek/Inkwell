package com.office.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.office.core.xlsx.XlsxReader
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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

    @Test fun addDuplicateMoveAndDeleteSlides() = rule.explainFailure {
        val file = Fixtures.asset("deck.pptx")
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(file, "application/vnd.openxmlformats-officedocument.presentationml.presentation")).use {
            rule.waitForText(str(R.string.slide_of, 1, 3))
            rule.onNodeWithTag("slide_thumb_0").performTouchInput { longClick() }
            rule.onNodeWithTag("slide_duplicate").performClick()
            rule.waitForText(str(R.string.slide_of, 2, 4))
            rule.onNodeWithTag("slide_thumb_1").performTouchInput { longClick() }
            rule.onNodeWithTag("slide_menu_add").performClick()
            rule.waitForText(str(R.string.slide_of, 3, 5))
            rule.onNodeWithTag("slide_thumb_0").performTouchInput { longClick() }
            rule.onNodeWithTag("slide_delete").performClick()
            rule.waitForText(str(R.string.slide_of, 1, 4))
            rule.screenshot("26_pptx_slides")
            rule.onNodeWithContentDescription(str(R.string.action_save)).assertExists()
        }
    }

    @Test fun openDocumentText() = open(
        "report.odt", "application/vnd.oasis.opendocument.text",
        "Quarterly Report", "İzmir sales doubled", "City", "Centered closing line.",
        shot = "27_real_odt",
    )

    @Test fun openDocumentSpreadsheet() = open(
        "budget.ods", "application/vnd.oasis.opendocument.spreadsheet",
        "Kalem", "Defter", "Sales", "Notes", "1750",
        shot = "28_real_ods",
    )

    @Test fun openDocumentPresentation() = open(
        "deck.odp", "application/vnd.oasis.opendocument.presentation",
        "Inkwell Deck", str(R.string.slide_of, 1, 3),
        shot = "29_real_odp",
    )

    @Test fun wordLegacy() = open(
        "report.doc", "application/msword",
        "Quarterly Report", "İzmir sales doubled", "City", str(R.string.legacy_banner),
        shot = "30_real_doc",
    )

    @Test fun excelLegacy() = open(
        "budget.xls", "application/vnd.ms-excel",
        "Kalem", "Defter", "Sales", "Notes", str(R.string.legacy_banner_sheet),
        shot = "31_real_xls",
    )

    @Test fun pdfForm() = open(
        "form.pdf", "application/pdf",
        str(R.string.pdf_page_of, 1, 2),
        shot = "23_real_pdf",
    )
}
