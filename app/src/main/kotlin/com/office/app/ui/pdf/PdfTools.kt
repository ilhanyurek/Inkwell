package com.office.app.ui.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A point on a rendered page, normalised to 0..1 from the top-left of what the user sees. */
data class PagePoint(val x: Float, val y: Float)

/** PDF operations backed by PDFBox. All functions run on the IO dispatcher. */
object PdfTools {

    suspend fun merge(context: Context, inputs: List<Uri>, output: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val merger = PDFMergerUtility()
            val streams = inputs.map { context.contentResolver.openInputStream(it) ?: error("Cannot read $it") }
            try {
                streams.forEach { merger.addSource(it) }
                val out = context.contentResolver.openOutputStream(output, "wt") ?: error("Cannot write")
                out.use {
                    merger.destinationStream = it
                    merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
                }
            } finally {
                streams.forEach { runCatching { it.close() } }
            }
        }
    }

    /** Opens [file] (optionally with a password), applies [block] and saves back to [file]. */
    private suspend fun edit(file: File, password: String?, block: (PDDocument) -> Unit) = withContext(Dispatchers.IO) {
        val doc = if (password != null) PDDocument.load(file, password) else PDDocument.load(file)
        doc.use {
            if (it.isEncrypted) it.isAllSecurityToBeRemoved = true
            block(it)
            val tmp = File(file.parentFile, file.name + ".tmp")
            it.save(tmp)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Writes a decrypted copy of a password-protected PDF. Throws if the password is wrong. */
    suspend fun decrypt(source: File, password: String, target: File) = withContext(Dispatchers.IO) {
        PDDocument.load(source, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(target)
        }
    }

    suspend fun rotate(file: File, pageIndex: Int, degrees: Int) = edit(file, null) { doc ->
        val page = doc.getPage(pageIndex)
        page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
    }

    suspend fun deletePage(file: File, pageIndex: Int) = edit(file, null) { doc ->
        if (doc.numberOfPages > 1) doc.removePage(pageIndex)
    }

    /** Copies pages [from]..[to] (0-based, inclusive) into a new PDF written to [output]. */
    suspend fun extract(context: Context, file: File, from: Int, to: Int, output: Uri) = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { src ->
            PDDocument().use { dst ->
                for (i in from..to) {
                    val imported = dst.importPage(src.getPage(i))
                    imported.rotation = src.getPage(i).rotation
                }
                val out = context.contentResolver.openOutputStream(output, "wt") ?: error("Cannot write")
                out.use { dst.save(it) }
            }
        }
    }

    /** Paints a translucent highlight rectangle between two corners into the page content. */
    suspend fun highlight(file: File, pageIndex: Int, a: PagePoint, b: PagePoint, rgb: Int) = edit(file, null) { doc ->
        val page = doc.getPage(pageIndex)
        val p1 = toUserSpace(page, a)
        val p2 = toUserSpace(page, b)
        val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.35f }
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.setGraphicsStateParameters(gs)
            cs.setNonStrokingColor(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
            cs.addRect(minOf(p1.first, p2.first), minOf(p1.second, p2.second), kotlin.math.abs(p1.first - p2.first), kotlin.math.abs(p1.second - p2.second))
            cs.fill()
        }
    }

    /** Writes [text] onto the page with its top-left at [at]. */
    suspend fun addText(file: File, pageIndex: Int, at: PagePoint, text: String, sizePt: Float, rgb: Int) = edit(file, null) { doc ->
        val page = doc.getPage(pageIndex)
        val font = loadFont(doc)
        val (x, y) = toUserSpace(page, at)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.beginText()
            cs.setFont(font, sizePt)
            cs.setNonStrokingColor(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
            cs.setLeading(sizePt * 1.25f)
            cs.newLineAtOffset(x, y - sizePt)
            text.split('\n').forEachIndexed { i, line ->
                if (i > 0) cs.newLine()
                cs.showText(if (font is PDType1Font) asciiFallback(line) else line)
            }
            cs.endText()
        }
    }

    /** Text of every page, used for search. */
    suspend fun pageTexts(file: File): List<String> = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            (1..doc.numberOfPages).map { n ->
                stripper.startPage = n
                stripper.endPage = n
                runCatching { stripper.getText(doc) }.getOrDefault("")
            }
        }
    }

    private fun loadFont(doc: PDDocument): PDFont {
        val candidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) runCatching { return PDType0Font.load(doc, f) }
        }
        return PDType1Font.HELVETICA
    }

    /** Helvetica only covers WinAnsi; map Turkish letters to their closest ASCII forms. */
    private fun asciiFallback(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                'ı' -> 'i'; 'İ' -> 'I'; 'ş' -> 's'; 'Ş' -> 'S'; 'ğ' -> 'g'; 'Ğ' -> 'G'
                else -> if (c.code < 256) c else '?'
            },
        )
    }

    /** Converts a point on the displayed (rotated) page to PDF user space. */
    private fun toUserSpace(page: PDPage, p: PagePoint): Pair<Float, Float> {
        val box: PDRectangle = page.cropBox
        val (u, v) = when (((page.rotation % 360) + 360) % 360) {
            90 -> p.y to 1f - p.x
            180 -> 1f - p.x to 1f - p.y
            270 -> 1f - p.y to p.x
            else -> p.x to p.y
        }
        return (box.lowerLeftX + u * box.width) to (box.upperRightY - v * box.height)
    }
}
