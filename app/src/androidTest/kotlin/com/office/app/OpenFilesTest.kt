package com.office.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithTag
import com.office.core.docx.DocxReader
import com.office.core.text.EmbedKind
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens each supported format through an "Open with" intent and checks it renders. */
@RunWith(AndroidJUnit4::class)
class OpenFilesTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private fun open(file: File, mime: String): ActivityScenario<MainActivity> =
        ActivityScenario.launch(Fixtures.viewIntent(file, mime))

    @Test
    fun opensDocx() {
        open(Fixtures.docx(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document").use {
            rule.waitForText("Quarterly report")
            rule.waitForText("Inkwell test document.")
            rule.screenshot("10_docx")
        }
    }

    @Test
    fun docxKeepsTableAndImageAfterEditing() = rule.explainFailure {
        val file = Fixtures.richDocx()
        open(file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document").use {
            rule.waitForText("Intro line")
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("doc_table_0").fetchSemanticsNodes().isNotEmpty() }
            rule.waitForText("B2")
            rule.onNodeWithTag("doc_image_0").assertExists()
            rule.screenshot("17_docx_embeds")
            rule.onNode(hasSetTextAction()).performTextInput("Edited ")
            rule.onNodeWithContentDescription(str(R.string.action_save)).performClick()
            rule.waitUntil(15_000) {
                runCatching {
                    val xml = String(ZipPackage.read(file.inputStream()).bytes("word/document.xml")!!)
                    xml.contains("Edited") && xml.contains("<w:tbl>") && xml.contains("rIdImg")
                }.getOrDefault(false)
            }
            val pkg = ZipPackage.read(file.inputStream())
            assertEquals("PNGDATA", String(pkg.bytes("word/media/image1.png")!!))
        }
    }

    @Test
    fun editsAndInsertsDocxTables() = rule.explainFailure {
        val file = Fixtures.richDocx()
        open(file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document").use {
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("doc_table_0").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("doc_table_0").performClick()
            rule.onNodeWithTag("table_cell_1_1").performTextReplacement("Z9")
            rule.onNodeWithTag("table_add_row").performClick()
            rule.onNodeWithTag("table_cell_2_0").performTextInput("A3")
            rule.onNodeWithTag("table_save").performClick()
            rule.waitForText("Z9")
            rule.waitForText("A3")

            rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
            rule.onNodeWithTag("menu_insert_table").performClick()
            rule.onNodeWithTag("insert_table_confirm").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("doc_table_1").fetchSemanticsNodes().isNotEmpty() }
            rule.screenshot("18_docx_tables")

            rule.onNodeWithContentDescription(str(R.string.action_save)).performClick()
            rule.waitUntil(15_000) {
                runCatching {
                    val xml = String(ZipPackage.read(file.inputStream()).bytes("word/document.xml")!!)
                    xml.contains("Z9") && xml.contains("A3") && Regex("<w:tbl[ >]").findAll(xml).count() == 2
                }.getOrDefault(false)
            }
            val doc = DocxReader.read(ZipPackage.read(file.inputStream()))
            val tables = doc.embeds.values.filter { it.kind == EmbedKind.TABLE }.map { it.tableRows }
            assertEquals(2, tables.size)
            assertEquals(listOf(listOf("A1", "B1"), listOf("A2", "Z9"), listOf("A3", "")), tables.first { it[0][0] == "A1" })
            assertEquals(List(3) { List(3) { "" } }, tables.first { it[0][0] != "A1" })
        }
    }

    @Test
    fun opensXlsxAndEvaluatesFormula() {
        open(Fixtures.xlsx(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").use {
            rule.waitForText("Total")
            rule.waitForText("42")
            rule.onNodeWithText("Budget").assertExists()
            rule.screenshot("11_xlsx")
        }
    }

    @Test
    fun opensCsvWithSemicolons() {
        open(Fixtures.csv(), "text/csv").use {
            rule.waitForText("Ankara")
            rule.waitForText("İzmir")
            rule.screenshot("12_csv")
        }
    }

    @Test
    fun opensMarkdown() {
        open(Fixtures.markdown(), "text/markdown").use {
            rule.waitForText("Notes")
            rule.waitForText("bold item")
            rule.screenshot("13_markdown")
        }
    }

    @Test
    fun opensPptx() {
        open(Fixtures.pptx(), "application/vnd.openxmlformats-officedocument.presentationml.presentation").use {
            rule.waitForText(str(R.string.slide_of, 1, 1))
            rule.waitForText("Hello slides")
            rule.screenshot("14_pptx")
        }
    }

    @Test
    fun opensPdfHighlightsAndUndoes() {
        open(Fixtures.pdf(), "application/pdf").use {
            rule.waitForText(str(R.string.pdf_page_of, 1, 2))
            rule.screenshot("15_pdf")
            rule.onNodeWithText(str(R.string.pdf_tool_highlight)).performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("pdf_page_0")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pdf_page_0").performTouchInput {
                swipe(Offset(width * 0.1f, height * 0.1f), Offset(width * 0.6f, height * 0.15f), durationMillis = 400)
            }
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithContentDescription(str(R.string.action_undo)).assertIsEnabled() }.isSuccess
            }
            rule.screenshot("16_pdf_highlighted")
            rule.onNodeWithContentDescription(str(R.string.action_undo)).performClick()
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithContentDescription(str(R.string.action_redo)).assertIsEnabled() }.isSuccess
            }
            // Redo brings the highlight back; it is a real annotation that can be deleted.
            rule.onNodeWithContentDescription(str(R.string.action_redo)).performClick()
            rule.waitUntil(15_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("pdf_annotations_1")).fetchSemanticsNodes().isNotEmpty() }
            // Open the menu (again, if a re-render closed it) until it lists the annotation.
            rule.waitUntil(15_000) {
                val menuOpen = rule.onAllNodesWithText(str(R.string.pdf_go_to_page)).fetchSemanticsNodes().isNotEmpty()
                if (!menuOpen) rule.onNodeWithContentDescription(str(R.string.action_more)).performClick()
                rule.onAllNodesWithText(str(R.string.pdf_annotations_count, 1)).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(str(R.string.pdf_annotations_count, 1)).performClick()
            rule.onNodeWithText(str(R.string.action_delete)).performClick()
            rule.onNodeWithText(str(R.string.action_ok)).performClick()
            rule.waitUntil(15_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("pdf_annotations_0")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun longPressSelectsAndCopiesPdfText() = rule.explainFailure {
        open(Fixtures.asset("form.pdf"), "application/pdf").use {
            rule.waitForText(str(R.string.pdf_page_of, 1, 2))
            rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("pdf_page_0")).fetchSemanticsNodes().isNotEmpty() }
            // "Application form" sits at the top of page 1.
            rule.onNodeWithTag("pdf_page_0").performTouchInput {
                down(Offset(width * 0.2f, height * 0.068f))
                advanceEventTime(1_000)
                moveTo(Offset(width * 0.45f, height * 0.068f))
                up()
            }
            rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("pdf_copy")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pdf_copy").performClick()
            fun clip(): String {
                var text = ""
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val cm = targetContext.getSystemService(android.content.ClipboardManager::class.java)
                    text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                }
                return text
            }
            rule.waitUntil(10_000) { clip().contains("Application") }
        }
    }
}
