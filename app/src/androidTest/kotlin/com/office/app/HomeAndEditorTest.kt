package com.office.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeAndEditorTest {

    private fun editableText(node: SemanticsNodeInteraction): String =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsTitleAndNewButton() = rule.explainFailure {
        rule.waitForText(str(R.string.home_title))
        rule.onNodeWithTag("new_button").assertExists()
        rule.screenshot("01_home")
    }

    @Test
    fun newDocumentTypeUndoRedo() = rule.explainFailure {
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("new_button")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("new_button").performClick()
        rule.waitForText(str(R.string.new_document))
        rule.screenshot("02_new_sheet")
        rule.onNodeWithText(str(R.string.new_document)).performClick()

        val editor = rule.onNode(hasSetTextAction())
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(str(R.string.action_undo)).assertIsNotEnabled()

        editor.performTextInput("Hello Inkwell")
        rule.waitForIdle()
        assertEquals("Hello Inkwell", editableText(editor))
        rule.onNodeWithContentDescription(str(R.string.format_bold)).performClick()
        rule.screenshot("03_document_typed")

        rule.onNodeWithContentDescription(str(R.string.action_undo)).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals("", editableText(editor))
        rule.onNodeWithContentDescription(str(R.string.action_redo)).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals("Hello Inkwell", editableText(editor))
    }

    @Test
    fun typingCreatesDraftAndDiscardRemovesIt() = rule.explainFailure {
        val drafts = (targetContext.applicationContext as OfficeApp).container.drafts
        drafts.drafts.value.forEach { drafts.delete(it.key) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("new_button")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("new_button").performClick()
        rule.onNodeWithText(str(R.string.new_note)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("Draft text")
        // Autosave runs a few seconds after the last change.
        rule.waitUntil(15_000) { drafts.drafts.value.isNotEmpty() }
        rule.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        rule.onNodeWithText(str(R.string.action_discard)).performClick()
        rule.waitUntil(10_000) { drafts.drafts.value.isEmpty() }
    }

    @Test
    fun settingsOpen() {
        rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
        rule.onNodeWithText(str(R.string.settings_title)).performClick()
        rule.waitForText(str(R.string.settings_appearance))
        rule.screenshot("04_settings")
    }
}
