package com.office.app

import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launcher shortcuts (res/xml/shortcuts.xml) open the right screen. */
@RunWith(AndroidJUnit4::class)
class ShortcutsTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private fun shortcut(target: String): Intent = Intent(MainActivity.SHORTCUT_ACTION).apply {
        setClassName(targetContext, MainActivity::class.java.name)
        putExtra("target", target)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    @Test
    fun newDocumentOpensTheEditor() = rule.explainFailure {
        ActivityScenario.launch<MainActivity>(shortcut("new_document")).use {
            rule.waitUntil(15_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun toolsOpensTheToolsTab() = rule.explainFailure {
        ActivityScenario.launch<MainActivity>(shortcut("tools")).use {
            rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("tool_MERGE")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun scanOpensTheScanTool() = rule.explainFailure {
        ActivityScenario.launch<MainActivity>(shortcut("scan")).use {
            rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("tool_camera")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
