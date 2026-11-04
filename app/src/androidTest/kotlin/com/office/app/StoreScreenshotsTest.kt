package com.office.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Produces the store screenshots (store_1 … store_6) from the realistic fixtures. */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val container get() = (targetContext.applicationContext as OfficeApp).container

    private fun clearDrafts() = container.drafts.drafts.value.forEach { container.drafts.delete(it.key) }

    private fun open(name: String, mime: String, block: () -> Unit) {
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(Fixtures.asset(name), mime)).use { block() }
    }

    @Test
    fun storeScreenshots() = rule.explainFailure {
        runBlocking { container.recents.clear() }
        clearDrafts()

        open("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document") {
            rule.waitForText("Quarterly Report")
            rule.waitForText("${str(R.string.embed_table)} 3×3")
            rule.screenshot("store_2_document")
        }
        open("budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") {
            rule.waitForText("Defter")
            rule.waitForText("3.50 ₺")
            rule.screenshot("store_3_spreadsheet")
        }
        open("form.pdf", "application/pdf") {
            rule.waitForText(str(R.string.pdf_page_of, 1, 2))
            rule.onNodeWithText(str(R.string.pdf_tool_highlight)).performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("pdf_page_0")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pdf_page_0").performTouchInput {
                swipe(Offset(width * 0.08f, height * 0.06f), Offset(width * 0.75f, height * 0.09f), durationMillis = 400)
            }
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithContentDescription(str(R.string.action_undo)).assertIsEnabled() }.isSuccess
            }
            rule.waitForIdle()
            rule.screenshot("store_4_pdf")
        }
        open("deck.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation") {
            rule.waitForText("Inkwell Deck")
            rule.waitForText(str(R.string.slide_of, 1, 3))
            rule.screenshot("store_5_presentation")
        }

        clearDrafts()
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitForText(str(R.string.home_title))
            clearDrafts()
            rule.onNodeWithContentDescription(str(R.string.home_grid_view)).performClick()
            rule.waitForText("report.docx")
            // Thumbnails are rendered in the background.
            Thread.sleep(4_000)
            rule.waitForIdle()
            rule.screenshot("store_1_home")

            // File cards have their own "more" buttons; the app bar's comes first.
            rule.onAllNodesWithContentDescription(str(R.string.action_more))[0].performClick()
            rule.onNodeWithText(str(R.string.settings_title)).performClick()
            rule.waitForText(str(R.string.settings_appearance))
            rule.screenshot("store_6_settings")
        }
    }
}
