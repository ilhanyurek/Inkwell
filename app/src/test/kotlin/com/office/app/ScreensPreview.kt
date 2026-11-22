package com.office.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.office.app.data.FileInfo
import com.office.app.data.ThemeMode
import com.office.core.FileKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens in each theme into app/build/screens/ so the design can be looked at without a
 * device. Not an assertion test: it only fails when a screen cannot be shown at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreensPreview {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<OfficeApp>()
    private val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }
    private val showcase = File(System.getProperty("user.dir"), "src/androidTest/assets/showcase")

    private var scenario: ActivityScenario<MainActivity>? = null

    /** Draws the whole window (dialogs and sheets included) into a PNG. */
    private fun shot(name: String) {
        rule.waitForIdle()
        scenario!!.onActivity { activity ->
            val roots = WindowManagerGlobalRoots.views()
            val decor = activity.window.decorView
            val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            decor.draw(canvas)
            // Popups (sheets, menus, dialogs) live in their own windows on top.
            roots.filter { it !== decor && it.isShown }.forEach { v ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                canvas.save()
                canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                v.draw(canvas)
                canvas.restore()
            }
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun sample(name: String): Uri {
        val dir = File(app.cacheDir, "samples").apply { mkdirs() }
        val f = File(dir, name)
        File(showcase, name).copyTo(f, overwrite = true)
        return Uri.fromFile(f)
    }

    private fun seed(theme: ThemeMode) = runBlocking {
        val c = app.container
        c.settings.setTheme(theme)
        // Data stores outlive a single test in this JVM.
        c.recents.files.first().forEach { c.recents.remove(it.uri) }
        for ((i, n) in listOf("Service Agreement.pdf", "Product Launch.pptx", "Budget 2026.xlsx", "Quarterly Report.docx").withIndex()) {
            val uri = sample(n)
            val f = File(uri.path!!)
            c.recents.record(FileInfo(uri, n, f.length(), null, FileKind.detect(n, null)))
            if (i == 1) c.recents.setPinned(uri, true)
        }
    }

    private fun ComposeTestRule.waitForTag(tag: String) =
        waitUntil(15_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeTestRule.waitForText(text: String) =
        waitUntil(15_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun homeScreens(prefix: String) {
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            rule.waitForTag("new_button")
            rule.waitForText("Quarterly Report")
            shot("${prefix}_1_home")
            rule.onNodeWithTag("tab_tools").performClick()
            rule.waitForTag("tool_SPLIT")
            shot("${prefix}_2_tools")
            rule.onAllNodesWithContentDescription(app.getString(R.string.action_more))[0].performClick()
            rule.onNodeWithText(app.getString(R.string.settings_title)).performClick()
            rule.waitForTag("theme_system")
            shot("${prefix}_3_settings")
        }
    }

    private fun documentScreens(prefix: String) {
        val doc = sample("Quarterly Report.docx")
        val sheet = sample("Budget 2026.xlsx")
        app.container.tabs.open(sheet)
        app.container.tabs.open(doc)
        val intent = Intent(Intent.ACTION_VIEW, doc).setClassName(app, MainActivity::class.java.name)
        ActivityScenario.launch<MainActivity>(intent).also { scenario = it }.use {
            rule.waitForTag("tab_count")
            rule.waitForText("Quarterly")
            Thread.sleep(500)
            shot("${prefix}_4_document_tabs")
            rule.onNodeWithTag("tab_count").performClick()
            rule.waitForTag("tab_overview")
            shot("${prefix}_5_tab_overview")
        }
    }

    @Test fun light() { seed(ThemeMode.LIGHT); homeScreens("light"); documentScreens("light") }

    @Test fun dark() { seed(ThemeMode.DARK); homeScreens("dark"); documentScreens("dark") }

    @Test fun black() { seed(ThemeMode.BLACK); homeScreens("black"); documentScreens("black") }
}

/** The root views of all windows of the app (activity, dialogs, popups). */
private object WindowManagerGlobalRoots {
    @Suppress("UNCHECKED_CAST")
    fun views(): List<android.view.View> = runCatching {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val global = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        (field.get(global) as List<android.view.View>).toList()
    }.getOrDefault(emptyList())
}
