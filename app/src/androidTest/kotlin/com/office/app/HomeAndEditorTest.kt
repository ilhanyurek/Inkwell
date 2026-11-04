package com.office.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
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

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsTitleAndNewButton() {
        rule.waitForText(str(R.string.home_title))
        rule.onNodeWithText(str(R.string.action_new)).assertExists()
        rule.screenshot("01_home")
    }

    @Test
    fun newDocumentTypeUndoRedo() {
        rule.onNodeWithText(str(R.string.action_new)).performClick()
        rule.waitForText(str(R.string.new_document))
        rule.screenshot("02_new_sheet")
        rule.onNodeWithText(str(R.string.new_document)).performClick()

        val editor = rule.onNode(hasSetTextAction())
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(str(R.string.action_undo)).assertIsNotEnabled()

        editor.performTextInput("Hello Inkwell")
        rule.waitForIdle()
        editor.assertTextEquals("Hello Inkwell")
        rule.onNodeWithContentDescription(str(R.string.format_bold)).performClick()
        rule.screenshot("03_document_typed")

        rule.onNodeWithContentDescription(str(R.string.action_undo)).assertIsEnabled().performClick()
        rule.waitForIdle()
        editor.assertTextEquals("")
        rule.onNodeWithContentDescription(str(R.string.action_redo)).assertIsEnabled().performClick()
        rule.waitForIdle()
        editor.assertTextEquals("Hello Inkwell")
    }

    @Test
    fun settingsOpen() {
        rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
        rule.onNodeWithText(str(R.string.settings_title)).performClick()
        rule.waitForText(str(R.string.settings_appearance))
        rule.screenshot("04_settings")
    }
}
