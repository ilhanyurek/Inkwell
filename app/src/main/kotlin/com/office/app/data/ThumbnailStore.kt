package com.office.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.office.app.ui.slides.SlideRenderer
import com.office.core.FileKind
import com.office.core.csv.Csv
import com.office.core.docx.DocxReader
import com.office.core.formula.FormulaEngine
import com.office.core.pptx.PptxReader
import com.office.core.sheet.CellRef
import com.office.core.sheet.NumberFormat
import com.office.core.sheet.Value
import com.office.core.text.MarkdownCodec
import com.office.core.text.ParagraphStyle
import com.office.core.text.PlainTextCodec
import com.office.core.xlsx.XlsxReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** First-page previews for the home screen, cached on disk and in memory. */
class ThumbnailStore(private val context: Context) {

    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(24)
    private val permits = Semaphore(2)

    private fun key(file: RecentFile) =
        MessageDigest.getInstance("SHA-1").digest("${file.uri}|${file.size}|${file.lastOpened / 60000}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

    suspend fun get(file: RecentFile): Bitmap? {
        val k = key(file)
        memory.get(k)?.let { return it }
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                val cached = File(dir, "$k.png")
                val bmp = if (cached.exists()) BitmapFactory.decodeFile(cached.path)
                else runCatching { render(file) }.getOrNull()?.also { b ->
                    cached.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
                }
                bmp?.also { memory.put(k, it) }
            }
        }
    }

    private fun render(file: RecentFile): Bitmap? {
        if ((file.size ?: 0L) > MAX_BYTES) return null
        return when (file.kind) {
            FileKind.PDF -> pdf(file)
            FileKind.PRESENTATION -> {
                val pres = PptxReader.read(ByteArrayInputStream(bytes(file)))
                pres.slides.firstOrNull()?.let { SlideRenderer.thumbnail(pres, it, WIDTH) }
            }
            FileKind.SPREADSHEET, FileKind.CSV -> sheet(file)
            else -> document(file)
        }
    }

    private fun bytes(file: RecentFile): ByteArray =
        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: error("unreadable")

    private fun page(): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(WIDTH, (WIDTH * 1.414f).toInt(), Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp).apply { drawColor(Color.WHITE) }
    }

    private fun pdf(file: RecentFile): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(file.uri, "r") ?: return null
        return pfd.use {
            PdfRenderer(it).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { p ->
                    val bmp = Bitmap.createBitmap(WIDTH, (WIDTH.toFloat() * p.height / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    private fun document(file: RecentFile): Bitmap {
        val data = bytes(file)
        val doc = when (file.kind) {
            FileKind.DOCUMENT -> DocxReader.read(ByteArrayInputStream(data))
            FileKind.MARKDOWN -> MarkdownCodec.read(data)
            else -> PlainTextCodec.read(data)
        }
        val (bmp, canvas) = page()
        val margin = WIDTH * 0.09f
        var y = margin
        val width = (WIDTH - 2 * margin).toInt()
        for (p in doc.paragraphs.take(40)) {
            val text = doc.readableText(p)
            val size = when (p.style) {
                ParagraphStyle.TITLE -> 22f
                ParagraphStyle.HEADING1 -> 17f
                ParagraphStyle.HEADING2 -> 15f
                ParagraphStyle.HEADING3 -> 13f
                else -> 10f
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                color = Color.rgb(40, 40, 40)
                if (p.style != ParagraphStyle.NORMAL) typeface = Typeface.DEFAULT_BOLD
            }
            val layout = StaticLayout.Builder.obtain(if (text.isEmpty()) " " else text, 0, maxOf(1, text.length), paint, width).build()
            canvas.save(); canvas.translate(margin, y); layout.draw(canvas); canvas.restore()
            y += layout.height + size * 0.4f
            if (y > bmp.height - margin) break
        }
        return bmp
    }

    private fun sheet(file: RecentFile): Bitmap {
        val data = bytes(file)
        val wb = if (file.kind == FileKind.CSV) Csv.toWorkbook(Csv.parse(data)) else XlsxReader.read(ByteArrayInputStream(data))
        val sheet = wb.sheets.first()
        val engine = FormulaEngine(wb)
        val bmp = Bitmap.createBitmap(WIDTH, (WIDTH * 0.8f).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(Color.WHITE) }
        val cols = 4
        val rows = 12
        val cw = WIDTH / cols.toFloat()
        val rh = bmp.height / rows.toFloat()
        val grid = Paint().apply { color = Color.rgb(215, 215, 210); strokeWidth = 1f }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = rh * 0.5f; color = Color.rgb(40, 40, 40) }
        for (r in 0 until rows) for (c in 0 until cols) {
            val ref = CellRef(r, c)
            sheet.styles[ref]?.fill?.let { canvas.drawRect(c * cw, r * rh, (c + 1) * cw, (r + 1) * rh, Paint().apply { color = it.toInt() }) }
            val v = engine.value(sheet, ref)
            val s = sheet.styles[ref]?.numFmt?.let { f -> if (v is Value.Num) NumberFormat.format(v.v, f) else null } ?: v.display()
            if (s.isNotEmpty()) {
                text.isFakeBoldText = sheet.styles[ref]?.bold == true
                canvas.save()
                canvas.clipRect(c * cw, r * rh, (c + 1) * cw, (r + 1) * rh)
                canvas.drawText(s, c * cw + 4f, (r + 1) * rh - rh * 0.3f, text)
                canvas.restore()
            }
        }
        for (r in 0..rows) canvas.drawLine(0f, r * rh, WIDTH.toFloat(), r * rh, grid)
        for (c in 0..cols) canvas.drawLine(c * cw, 0f, c * cw, bmp.height.toFloat(), grid)
        return bmp
    }

    private companion object {
        const val WIDTH = 320
        const val MAX_BYTES = 40L * 1024 * 1024
    }
}
