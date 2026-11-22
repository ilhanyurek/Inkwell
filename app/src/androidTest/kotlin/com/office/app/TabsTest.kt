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

/** Several files open as tabs; each keeps its unsaved text while another one is shown. */
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

            // Back to the first tab: the unsaved edit is still there and the tab shows it as changed.
            rule.onNodeWithTag("tab_$first").performClick()
            rule.waitForText("Edited")
            assertEquals(true, tabs.isDirty(first))

            // Closing the clean second tab leaves the first one.
            val second = tabs.tabs.first { it.id != first }.id
            rule.onNodeWithTag("tab_close_$second").performClick()
            rule.waitUntil(5_000) { tabs.tabs.size == 1 }
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("tab_$first")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
