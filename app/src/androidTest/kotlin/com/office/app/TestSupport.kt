package com.office.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.printToString
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.office.core.docx.DocxWriter
import com.office.core.sheet.CellRef
import com.office.core.sheet.Sheet
import com.office.core.sheet.Workbook
import com.office.core.text.Paragraph
import com.office.core.text.ParagraphStyle
import com.office.core.text.TextDocument
import com.office.core.text.TextRun
import com.office.core.xlsx.XlsxWriter
import com.office.core.zip.ZipPackage
import java.io.File

val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

fun str(id: Int, vararg args: Any): String = targetContext.getString(id, *args)

/** Writes test documents into the cache folder exposed by the app's FileProvider. */
object Fixtures {
    private val dir: File get() = File(targetContext.cacheDir, "exports").apply { mkdirs() }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.files", file)

    fun viewIntent(file: File, mime: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setClassName(targetContext, MainActivity::class.java.name)
        setDataAndType(uriFor(file), mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun docx(): File = File(dir, "report.docx").also { f ->
        val doc = TextDocument(
            listOf(
                Paragraph(listOf(TextRun("Quarterly report")), ParagraphStyle.HEADING1),
                Paragraph(listOf(TextRun("Inkwell "), TextRun("test", bold = true), TextRun(" document."))),
            ),
        )
        f.outputStream().use { DocxWriter.write(doc, it) }
    }

    fun xlsx(): File = File(dir, "budget.xlsx").also { f ->
        val s = Sheet("Budget")
        s[CellRef(0, 0)] = "Rent"; s[CellRef(0, 1)] = "30"
        s[CellRef(1, 0)] = "Food"; s[CellRef(1, 1)] = "12"
        s[CellRef(2, 0)] = "Total"; s[CellRef(2, 1)] = "=SUM(B1:B2)"
        f.outputStream().use { XlsxWriter.write(Workbook(mutableListOf(s)), it) }
    }

    fun csv(): File = File(dir, "cities.csv").also { it.writeText("city;population\nAnkara;5700000\nİzmir;4400000\n") }

    fun markdown(): File = File(dir, "notes.md").also { it.writeText("# Notes\n\n- first item\n- **bold** item\n") }

    fun pdf(): File = File(dir, "letter.pdf").also { f ->
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = Color.BLACK }
        for (n in 1..2) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            page.canvas.drawText("Inkwell PDF page $n", 72f, 100f, paint)
            pdf.finishPage(page)
        }
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    /** A DOCX with a table and an image, like files made in Word. */
    fun richDocx(): File = File(dir, "rich.docx").also { f ->
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val parts = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdImg" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/></Relationships>""",
            "word/media/image1.png" to "PNGDATA",
            "word/document.xml" to """<w:document xmlns:w="$w" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body><w:p><w:r><w:t>Intro line</w:t></w:r></w:p><w:p><w:r><w:drawing><a:blip r:embed="rIdImg"/></w:drawing></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B2</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>Closing line</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""",
        )
        f.outputStream().use { ZipPackage.write(it, parts.map { (k, v) -> k to v.toByteArray() }) }
    }

    fun pptx(): File = File(dir, "deck.pptx").also { f ->
        val parts = mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>""",
            "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="838200" y="365125"/><a:ext cx="10515600" cy="1325563"/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:t>Hello slides</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>""",
        )
        f.outputStream().use { ZipPackage.write(it, parts.map { (k, v) -> k to v.toByteArray() }) }
    }
}

fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 15_000) {
    waitUntil(timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
}

/** Saves a screenshot of the first window; CI collects these as an artifact. */
fun SemanticsNodeInteractionsProvider.screenshot(name: String) {
    runCatching {
        val bitmap = onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        TestStorage().openOutputFile("screenshots/$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Runs [block]; on failure, rethrows with the whole UI tree so CI logs show what was on screen. */
fun SemanticsNodeInteractionsProvider.explainFailure(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        val tree = runCatching { onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n") { onAllNodes(isRoot())[it].printToString(maxDepth = 40) } }.getOrDefault("")
        throw AssertionError("${e.message}\n--- UI tree ---\n$tree", e)
    }
}
