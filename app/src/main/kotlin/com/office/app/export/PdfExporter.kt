package com.office.app.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.office.core.formula.FormulaEngine
import com.office.core.sheet.CellRef
import com.office.core.sheet.Sheet
import com.office.core.text.Alignment
import com.office.core.text.EmbedKind
import com.office.core.text.ListType
import com.office.core.text.ParagraphStyle
import com.office.core.text.TextDocument
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/** Renders documents and sheets to A4 PDF using the platform's PdfDocument. */
object PdfExporter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 56f
    private const val BODY_PT = 11f

    fun document(doc: TextDocument, title: String, out: OutputStream) {
        val sb = SpannableStringBuilder()
        var number = 0
        doc.paragraphs.forEachIndexed { i, p ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            number = if (p.list == ListType.NUMBERED) number + 1 else 0
            when (p.list) {
                ListType.BULLET -> sb.append("•  ")
                ListType.NUMBERED -> sb.append("$number.  ")
                ListType.NONE -> {}
            }
            for (r in p.runs) {
                val rs = sb.length
                val embed = r.embedId?.let { doc.embeds[it] }
                sb.append(
                    when {
                        embed == null -> r.text
                        embed.kind == EmbedKind.LINK -> embed.label
                        embed.kind == EmbedKind.TABLE -> embed.tableRows.joinToString("\n", prefix = "\n") { it.joinToString("   ") }
                        else -> ""
                    },
                )
                val re = sb.length
                if (re == rs) continue
                if (r.bold && r.italic) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else if (r.bold) sb.setSpan(StyleSpan(Typeface.BOLD), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else if (r.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.underline) sb.setSpan(UnderlineSpan(), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.strike) sb.setSpan(StrikethroughSpan(), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                r.sizePt?.let { sb.setSpan(AbsoluteSizeSpan(it.toInt()), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            val end = sb.length
            // Paragraph spans need a non-empty range; give empty paragraphs a zero-width space.
            if (end == start) sb.append('​')
            val pe = sb.length
            val size = when (p.style) {
                ParagraphStyle.TITLE -> 26
                ParagraphStyle.HEADING1 -> 18
                ParagraphStyle.HEADING2 -> 15
                ParagraphStyle.HEADING3 -> 13
                else -> null
            }
            if (size != null) {
                sb.setSpan(AbsoluteSizeSpan(size), start, pe, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (p.style != ParagraphStyle.TITLE) sb.setSpan(StyleSpan(Typeface.BOLD), start, pe, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (p.style == ParagraphStyle.QUOTE) {
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, pe, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.rgb(90, 90, 90)), start, pe, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(LeadingMarginSpan.Standard(18), start, pe, Spanned.SPAN_PARAGRAPH)
            }
            if (p.list != ListType.NONE) sb.setSpan(LeadingMarginSpan.Standard(8, 22), start, pe, Spanned.SPAN_PARAGRAPH)
            val align = when (p.alignment) {
                Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                Alignment.END -> Layout.Alignment.ALIGN_OPPOSITE
                else -> null
            }
            align?.let { sb.setSpan(AlignmentSpan.Standard(it), start, pe, Spanned.SPAN_PARAGRAPH) }
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BODY_PT
            color = Color.rgb(27, 28, 30)
        }
        val width = (PAGE_W - 2 * MARGIN).toInt()
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(false)
            .build()
        paginate(layout, title, out)
    }

    private fun paginate(layout: StaticLayout, title: String, out: OutputStream) {
        val pdf = PdfDocument()
        val usable = PAGE_H - 2 * MARGIN
        var line = 0
        var pageNo = 1
        do {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= usable) last++
            val bottom = layout.getLineBottom(last)
            val c = page.canvas
            c.save()
            c.translate(MARGIN, MARGIN - top)
            c.clipRect(0f, top.toFloat(), layout.width.toFloat(), bottom.toFloat())
            layout.draw(c)
            c.restore()
            drawFooter(c, pageNo)
            pdf.finishPage(page)
            line = last + 1
            pageNo++
        } while (line < layout.lineCount)
        pdf.writeTo(out)
        pdf.close()
    }

    private fun drawFooter(c: android.graphics.Canvas, pageNo: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            color = Color.rgb(140, 140, 140)
            textAlign = Paint.Align.CENTER
        }
        c.drawText(pageNo.toString(), PAGE_W / 2f, PAGE_H - MARGIN / 2, p)
    }

    /** Draws the used range of a sheet as a gridded table, splitting across pages by rows and columns. */
    fun sheet(sheet: Sheet, engine: FormulaEngine, out: OutputStream) {
        val pdf = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.rgb(27, 28, 30) }
        val header = Paint(text).apply { color = Color.rgb(110, 110, 110); typeface = Typeface.DEFAULT_BOLD }
        val grid = Paint().apply { color = Color.rgb(205, 205, 200); strokeWidth = 0.5f }
        val rowH = 15f
        val headerW = 28f
        val maxRow = sheet.maxRow
        val maxCol = sheet.maxCol
        if (maxRow < 0) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            pdf.finishPage(page)
            pdf.writeTo(out); pdf.close(); return
        }
        val values = Array(maxRow + 1) { r ->
            Array(maxCol + 1) { c ->
                val ref = CellRef(r, c)
                val v = engine.value(sheet, ref)
                val fmt = sheet.styles[ref]?.numFmt
                if (v is com.office.core.sheet.Value.Num && fmt != null) com.office.core.sheet.NumberFormat.format(v.v, fmt) else v.display()
            }
        }
        val colW = FloatArray(maxCol + 1) { c ->
            val widest = (0..maxRow).maxOf { r -> text.measureText(values[r][c]) }
            (widest + 8f).coerceIn(36f, 180f)
        }
        val usableW = PAGE_W - 2 * MARGIN - headerW
        val usableH = PAGE_H - 2 * MARGIN - rowH
        val rowsPerPage = (usableH / rowH).toInt()

        // Group columns into page-wide bands.
        val bands = ArrayList<IntRange>()
        var bandStart = 0
        var acc = 0f
        for (c in 0..maxCol) {
            if (acc + colW[c] > usableW && c > bandStart) {
                bands += bandStart until c
                bandStart = c
                acc = 0f
            }
            acc += colW[c]
        }
        bands += bandStart..maxCol

        var pageNo = 1
        for (band in bands) {
            var rowStart = 0
            while (rowStart <= maxRow) {
                val rowEnd = minOf(maxRow, rowStart + rowsPerPage - 1)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                val cv = page.canvas
                var x = MARGIN + headerW
                // Column headers
                for (c in band) {
                    cv.drawText(CellRef.columnName(c), x + 3f, MARGIN + rowH - 4f, header)
                    x += colW[c]
                }
                for (r in rowStart..rowEnd) {
                    val y = MARGIN + rowH * (r - rowStart + 1)
                    cv.drawText((r + 1).toString(), MARGIN + 2f, y + rowH - 4f, header)
                    x = MARGIN + headerW
                    for (c in band) {
                        val v = values[r][c]
                        if (v.isNotEmpty()) {
                            cv.save()
                            cv.clipRect(x, y, x + colW[c] - 1f, y + rowH)
                            val numeric = FormulaEngine.parseNumber(v) != null
                            val tx = if (numeric) x + colW[c] - 3f - text.measureText(v) else x + 3f
                            cv.drawText(v, tx, y + rowH - 4f, text)
                            cv.restore()
                        }
                        x += colW[c]
                    }
                }
                // Grid lines
                val gridBottom = MARGIN + rowH * (rowEnd - rowStart + 2)
                val gridRight = MARGIN + headerW + band.sumOf { colW[it].toDouble() }.toFloat()
                for (i in 0..(rowEnd - rowStart + 2)) {
                    val y = MARGIN + rowH * i
                    cv.drawLine(MARGIN + headerW, y, gridRight, y, grid)
                }
                x = MARGIN + headerW
                cv.drawLine(x, MARGIN, x, gridBottom, grid)
                for (c in band) {
                    x += colW[c]
                    cv.drawLine(x, MARGIN, x, gridBottom, grid)
                }
                drawFooter(cv, pageNo)
                pdf.finishPage(page)
                pageNo++
                rowStart = rowEnd + 1
            }
        }
        pdf.writeTo(out)
        pdf.close()
    }
}

/** Sends an existing PDF file to the system print dialog. */
fun printPdf(context: Context, file: File, jobName: String) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(file.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
    manager.print(jobName, adapter, null)
}
