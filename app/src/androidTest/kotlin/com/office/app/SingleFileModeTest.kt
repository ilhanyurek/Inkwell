package com.office.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** With "Keep several files open" off, one file is open at a time and leaving it closes it. */
@RunWith(AndroidJUnit4::class)
class SingleFileModeTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun oneFileAtATime() = rule.explainFailure {
        val container = (targetContext.applicationContext as OfficeApp).container
        val tabs = container.tabs
        runBlocking { container.settings.setKeepFilesOpen(false) }
        try {
            rule.waitUntil(5_000) { tabs.singleFile }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                tabs.tabs.toList().forEach { tabs.close(it.id) }
            }
            ActivityScenario.launch<MainActivity>(Fixtures.viewIntent(Fixtures.markdown(), "text/markdown")).use { scenario ->
                rule.waitForText("Notes")
                scenario.onActivity { it.startActivity(Fixtures.viewIntent(Fixtures.csv(), "text/csv")) }
                rule.waitForText("Ankara")
                rule.waitUntil(5_000) { tabs.tabs.size == 1 }
                // No list of open files in this mode.
                assertEquals(0, rule.onAllNodes(hasTestTag("tab_count")).fetchSemanticsNodes().size)

                // The back arrow closes the file and shows Home with nothing left open.
                rule.onNodeWithContentDescription(str(R.string.action_back)).performClick()
                rule.waitUntil(10_000) { tabs.tabs.isEmpty() }
                rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("new_button")).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(0, rule.onAllNodes(hasTestTag("open_tabs")).fetchSemanticsNodes().size)
            }
        } finally {
            runBlocking { container.settings.setKeepFilesOpen(true) }
        }
    }
}
