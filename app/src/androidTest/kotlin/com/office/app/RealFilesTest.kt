package com.office.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
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
        "Quarterly Report", "İzmir sales doubled", "${str(R.string.embed_table)} 3×3", "our site",
        shot = "20_real_docx",
    )

    @Test fun excelBudget() = open(
        "budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Kalem", "Defter", "Sales", "Notes",
        shot = "21_real_xlsx",
    )

    @Test fun powerPointDeck() = open(
        "deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "Inkwell Deck", str(R.string.slide_of, 1, 3),
        shot = "22_real_pptx",
    )

    @Test fun pdfForm() = open(
        "form.pdf", "application/pdf",
        str(R.string.pdf_page_of, 1, 2),
        shot = "23_real_pdf",
    )
}
