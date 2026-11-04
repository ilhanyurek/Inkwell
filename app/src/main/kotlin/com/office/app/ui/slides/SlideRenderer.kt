package com.office.app.ui.slides

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.office.core.pptx.Presentation
import com.office.core.pptx.Slide
import com.office.core.pptx.SlideShape
import com.office.core.text.Alignment
import java.io.OutputStream

/** Draws slides with the platform Canvas, for PDF export and thumbnails. */
object SlideRenderer {

    private const val EMU_PER_POINT = 12700f

    fun decode(pic: SlideShape.Picture, maxSide: Int = 1600): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pic.image, 0, pic.image.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeByteArray(pic.image, 0, pic.image.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Draws [slide] scaled so that one EMU is [scale] canvas units. */
    fun draw(canvas: Canvas, slide: Slide, pres: Presentation, scale: Float, images: (SlideShape.Picture) -> Bitmap? = { decode(it) }) {
        val bg = slide.background?.toInt() ?: Color.WHITE
        canvas.drawColor(bg)
        val darkBackground = Color.luminance(bg) < 0.4f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        for (shape in slide.shapes) {
            val f = shape.frame
            val rect = RectF(f.x * scale, f.y * scale, (f.x + f.width) * scale, (f.y + f.height) * scale)
            when (shape) {
                is SlideShape.Rect -> { fill.color = shape.fill.toInt(); canvas.drawRect(rect, fill) }
                is SlideShape.Picture -> images(shape)?.let { canvas.drawBitmap(it, null, rect, Paint(Paint.FILTER_BITMAP_FLAG)) }
                is SlideShape.TextBox -> drawText(canvas, shape, rect, scale, if (darkBackground) Color.rgb(245, 245, 245) else Color.rgb(27, 28, 30))
            }
        }
    }

    private fun drawText(canvas: Canvas, box: SlideShape.TextBox, rect: RectF, scale: Float, defaultColor: Int) {
        box.fill?.let { canvas.drawRect(rect, Paint().apply { color = it.toInt() }) }
        val ptToUnits = EMU_PER_POINT * scale
        val sb = SpannableStringBuilder()
        box.paragraphs.forEachIndexed { i, p ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            if (p.bullet) sb.append("•  ")
            for (r in p.runs) {
                val rs = sb.length
                sb.append(r.text)
                val re = sb.length
                if (re == rs) continue
                sb.setSpan(AbsoluteSizeSpan(((r.sizePt ?: 18f) * ptToUnits).toInt().coerceAtLeast(1)), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(r.color?.toInt() ?: defaultColor), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                when {
                    r.bold && r.italic -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    r.bold -> sb.setSpan(StyleSpan(Typeface.BOLD), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    r.italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (r.underline) sb.setSpan(UnderlineSpan(), rs, re, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sb.length == start) sb.append('​')
            val end = sb.length
            if (p.level > 0) sb.setSpan(LeadingMarginSpan.Standard((p.level * 18 * ptToUnits).toInt()), start, end, Spanned.SPAN_PARAGRAPH)
            val align = when (p.alignment) {
                Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                Alignment.END -> Layout.Alignment.ALIGN_OPPOSITE
                else -> null
            }
            align?.let { sb.setSpan(AlignmentSpan.Standard(it), start, end, Spanned.SPAN_PARAGRAPH) }
        }
        val inset = (rect.width() * 0.02f).coerceAtMost(8 * ptToUnits)
        val width = (rect.width() - 2 * inset).toInt().coerceAtLeast(1)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18 * ptToUnits; color = defaultColor }
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width).setIncludePad(false).build()
        canvas.save()
        val top = if (box.verticalCenter) rect.top + (rect.height() - layout.height) / 2 else rect.top + inset
        canvas.translate(rect.left + inset, top)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Writes every slide as one PDF page sized like the slide. */
    fun exportPdf(pres: Presentation, out: OutputStream) {
        val pdf = PdfDocument()
        val scale = 1f / EMU_PER_POINT
        val w = (pres.width * scale).toInt()
        val h = (pres.height * scale).toInt()
        val cache = HashMap<String, Bitmap?>()
        pres.slides.forEachIndexed { i, slide ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            draw(page.canvas, slide, pres, scale) { pic -> cache.getOrPut(pic.contentPath) { decode(pic) } }
            pdf.finishPage(page)
        }
        pdf.writeTo(out)
        pdf.close()
    }

    /** Renders one slide into a bitmap [widthPx] wide. */
    fun thumbnail(pres: Presentation, slide: Slide, widthPx: Int): Bitmap {
        val scale = widthPx.toFloat() / pres.width
        val bmp = Bitmap.createBitmap(widthPx, (pres.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), slide, pres, scale) { decode(it, 800) }
        return bmp
    }
}
