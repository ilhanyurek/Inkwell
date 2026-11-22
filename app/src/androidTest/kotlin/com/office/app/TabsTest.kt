package com.office.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Several files open as tabs; each keeps its unsaved text while another one or Home is shown. */
@RunWith(AndroidJUnit4::class)
class TabsTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun twoFilesAsTabs() = rule.explainFailure {
        val tabs = (targetContext.applicationContext as OfficeApp).container.tabs
        // Tabs live as long as the app process; start from none.
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
            rule.screenshot("40_tabs")

            // Home on top of the open files and back again: the unsaved edit is kept.
            rule.onNodeWithTag("tab_home").performClick()
            rule.waitForTag("open_tabs")
            rule.screenshot("41_home_over_tabs")
            rule.onNodeWithTag("open_tabs").performClick()
            rule.waitForTag("tab_$first")
            rule.onNodeWithTag("tab_$first").performClick()
            rule.waitForText("Edited")
            assertEquals(true, tabs.isDirty(first))

            // The overview lists every open file.
            rule.onNodeWithTag("tab_count").performClick()
            rule.waitForTag("tab_overview")
            rule.screenshot("42_tab_overview")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("tab_overview")).fetchSemanticsNodes().isEmpty() }

            // Closing the clean second tab leaves the first one.
            val second = tabs.tabs.first { it.id != first }.id
            rule.onNodeWithTag("tab_close_$second").performClick()
            rule.waitUntil(5_000) { tabs.tabs.size == 1 }

            // Closing the edited tab asks first: Cancel keeps it, Discard closes it and returns Home.
            rule.onNodeWithTag("tab_close_$first").performClick()
            rule.waitForTag("unsaved_cancel")
            rule.onNodeWithTag("unsaved_cancel").performClick()
            rule.waitForIdle()
            assertEquals(1, tabs.tabs.size)
            rule.onNodeWithTag("tab_close_$first").performClick()
            rule.waitForTag("unsaved_discard")
            rule.onNodeWithTag("unsaved_discard").performClick()
            rule.waitUntil(5_000) { tabs.tabs.isEmpty() }
            rule.waitForTag("new_button")
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(tag: String) =
    waitUntil(10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
