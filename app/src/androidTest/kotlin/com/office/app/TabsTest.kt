package com.office.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Several files open at once: Back goes Home and keeps them open, the count in the editor's bar lists them,
 * and each keeps its unsaved text while another one or Home is shown.
 */
@RunWith(AndroidJUnit4::class)
class TabsTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private fun ComposeTestRule.waitForTag(tag: String) =
        waitUntil(10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun openFilesStayOpen() = rule.explainFailure {
        val tabs = (targetContext.applicationContext as OfficeApp).container.tabs
        // Open files live as long as the app process; start from none.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tabs.tabs.toList().forEach { tabs.close(it.id) }
        }
        ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(Fixtures.markdown(), "text/markdown")).use { scenario ->
            rule.waitForText("Notes")
            assertEquals(1, tabs.tabs.size)
            val first = tabs.tabs[0].id
            rule.onNode(hasSetTextAction()).performTextInput("Edited ")

            scenario.onActivity { it.startActivity(Fixtures.viewIntent(Fixtures.csv(), "text/csv")) }
            rule.waitForText("Ankara")
            rule.waitUntil(10_000) { tabs.tabs.size == 2 }
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("tab_count") and hasText("2")).fetchSemanticsNodes().isNotEmpty() }
            rule.screenshot("40_open_files")

            // The editor's back arrow goes Home; both files are still open.
            rule.onNodeWithContentDescription(str(R.string.action_back)).performClick()
            rule.waitForTag("open_tabs")
            assertEquals(2, tabs.tabs.size)
            rule.screenshot("41_home_with_open_files")

            // Back to the files, then to the note through the list: the unsaved edit is still there.
            rule.onNodeWithTag("open_tabs").performClick()
            rule.waitForTag("tab_count")
            rule.onNodeWithTag("tab_count").performClick()
            rule.waitForTag("tab_overview")
            rule.screenshot("42_open_files_list")
            rule.onNodeWithTag("tab_$first").performClick()
            rule.waitForText("Edited")
            assertEquals(true, tabs.isDirty(first))

            // Closing the clean file from the list leaves the note.
            val second = tabs.tabs.first { it.id != first }.id
            rule.onNodeWithTag("tab_count").performClick()
            rule.waitForTag("tab_close_$second")
            rule.onNodeWithTag("tab_close_$second").performClick()
            rule.waitUntil(5_000) { tabs.tabs.size == 1 }

            // Closing the edited note asks first: Cancel keeps it, Discard closes it and returns Home.
            rule.waitForTag("tab_close_$first")
            rule.onNodeWithTag("tab_close_$first").performClick()
            rule.waitForTag("unsaved_cancel")
            rule.onNodeWithTag("unsaved_cancel").performClick()
            rule.waitForIdle()
            assertEquals(1, tabs.tabs.size)
            rule.onNodeWithTag("tab_count").performClick()
            rule.waitForTag("tab_close_$first")
            rule.onNodeWithTag("tab_close_$first").performClick()
            rule.waitForTag("unsaved_discard")
            rule.onNodeWithTag("unsaved_discard").performClick()
            rule.waitUntil(5_000) { tabs.tabs.isEmpty() }
            rule.waitForTag("new_button")
        }
    }
}
