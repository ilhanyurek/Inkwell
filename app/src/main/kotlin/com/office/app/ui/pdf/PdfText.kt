package com.office.app.ui.pdf

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.contentstream.operator.text.BeginText
import com.tom_roush.pdfbox.contentstream.operator.text.EndText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveTextSetLeading
import com.tom_roush.pdfbox.contentstream.operator.text.NextLine
import com.tom_roush.pdfbox.contentstream.operator.text.SetCharSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.SetFontAndSize
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextHorizontalScaling
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextLeading
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRenderingMode
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRise
import com.tom_roush.pdfbox.contentstream.operator.text.SetWordSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.ShowText
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextAdjusted
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLine
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLineAndSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDType3CharProc
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

/** How a paragraph's text is drawn. */
data class PdfTextStyle(
    val sizePt: Float,
    val bold: Boolean,
    val italic: Boolean,
    /** RGB without alpha. */
    val rgb: Int,
    /** 0 left, 1 centre, 2 right. */
    val align: Int,
    /** 0 sans serif, 1 serif, 2 monospace. */
    val family: Int,
)

/**
 * A paragraph of text on a page, found from the positions of its glyphs. Coordinates without a prefix are PDF
 * user space (points, y up); [left]..[bottom] is the same box normalised like [PagePoint] for drawing on screen.
 */
data class PdfTextBlock(
    val page: Int,
    val index: Int,
    val text: String,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val firstBaseline: Float,
    val lineSpacing: Float,
    val lineCount: Int,
    val style: PdfTextStyle,
    val fontName: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    operator fun contains(p: PagePoint) = p.x in left..right && p.y in top..bottom
}

/**
 * Edits the text of a PDF in place: finds paragraphs, removes their glyphs from the page's content (so the
 * old words are really gone, not just covered) and draws the new text at the same spot.
 */
object PdfText {

    /** The app's own typeface (regular or bold), used when no system font can show the new text. */
    @Volatile var bundledFont: ((bold: Boolean) -> java.io.InputStream)? = null

    private class Glyph(
        val text: String,
        val x: Float,
        val baseline: Float,
        val width: Float,
        val size: Float,
        val font: PDFont?,
        val rgb: Int,
    )

    private class Line(val glyphs: MutableList<Glyph>) {
        val baseline get() = glyphs.first().baseline
        val size get() = glyphs.maxOf { it.size }
        val x0 get() = glyphs.minOf { it.x }
        val x1 get() = glyphs.maxOf { it.x + it.width }
        fun text(): String {
            val sb = StringBuilder()
            var prev: Glyph? = null
            for (g in glyphs.sortedBy { it.x }) {
                if (prev != null && g.text != " " && !sb.endsWith(" ")) {
                    val gap = g.x - (prev.x + prev.width)
                    if (gap > g.size * 0.18f) sb.append(' ')
                }
                sb.append(g.text)
                prev = g
            }
            return sb.toString().replace(Regex(" {2,}"), " ").trim()
        }
    }

    suspend fun blocks(file: File, pageIndex: Int): List<PdfTextBlock> = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { doc -> blocks(doc, pageIndex) }
    }

    /** The paragraphs of one page, top to bottom. */
    fun blocks(doc: PDDocument, pageIndex: Int): List<PdfTextBlock> {
        val page = doc.getPage(pageIndex)
        val crop = page.cropBox
        val glyphs = ArrayList<Glyph>()
        val stripper = object : PDFTextStripper() {
            init {
                // The text stripper does not follow colours by itself.
                addOperator(SetNonStrokingColor())
                addOperator(SetNonStrokingColorN())
                addOperator(SetNonStrokingColorSpace())
                addOperator(SetNonStrokingDeviceGrayColor())
                addOperator(SetNonStrokingDeviceRGBColor())
                addOperator(SetNonStrokingDeviceCMYKColor())
            }

            override fun processTextPosition(text: TextPosition) {
                val m = text.textMatrix
                val rgb = runCatching { graphicsState.nonStrokingColor.toRGB() }.getOrDefault(0)
                val size = text.fontSizeInPt.takeIf { it > 0f } ?: abs(m.scalingFactorY).coerceAtLeast(1f)
                glyphs += Glyph(
                    text.unicode,
                    m.translateX + crop.lowerLeftX,
                    m.translateY + crop.lowerLeftY,
                    text.widthDirAdj.coerceAtLeast(0f),
                    size,
                    text.font,
                    rgb and 0xFFFFFF,
                )
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        runCatching { stripper.getText(doc) }
        if (glyphs.isEmpty()) return emptyList()

        // Lines: glyphs on the same baseline, split where a wide gap separates columns.
        val sorted = glyphs.sortedWith(compareByDescending<Glyph> { it.baseline }.thenBy { it.x })
        val rows = ArrayList<MutableList<Glyph>>()
        for (g in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(row.first().baseline - g.baseline) <= maxOf(row.first().size, g.size) * 0.3f) row += g else rows += mutableListOf(g)
        }
        val lines = ArrayList<Line>()
        for (row in rows) {
            row.sortBy { it.x }
            var current = Line(mutableListOf(row.first()))
            for (g in row.drop(1)) {
                val last = current.glyphs.last()
                val gap = g.x - (last.x + last.width)
                if (gap > maxOf(last.size, g.size) * 3f) {
                    lines += current
                    current = Line(mutableListOf(g))
                } else current.glyphs += g
            }
            lines += current
        }
        lines.removeAll { it.text().isBlank() }

        // Paragraphs: a line joins the paragraph just above it (same column) when it is close below it, of a
        // similar size and weight, and overlaps it sideways.
        val groups = ArrayList<MutableList<Line>>()
        for (line in lines) {
            val target = groups.lastOrNull { g ->
                val prev = g.last()
                val drop = prev.baseline - line.baseline
                drop >= prev.size * 0.6f && drop <= prev.size * 1.8f &&
                    abs(line.size - prev.size) <= prev.size * 0.2f &&
                    minOf(prev.x1, line.x1) - maxOf(prev.x0, line.x0) > 0f &&
                    isBold(dominantFont(prev)) == isBold(dominantFont(line))
            }
            if (target != null) target += line else groups += mutableListOf(line)
        }

        return groups.mapIndexed { index, g ->
            val x0 = g.minOf { it.x0 }
            val x1 = g.maxOf { it.x1 }
            val size = g.map { it.size }.groupingBy { (it * 2).toInt() }.eachCount().maxByOrNull { it.value }?.key?.div(2f) ?: g.first().size
            val top = g.first().baseline + size * 0.95f
            val bottom = g.last().baseline - size * 0.3f
            val spacing = if (g.size > 1) (g.first().baseline - g.last().baseline) / (g.size - 1) else size * 1.2f
            val font = dominantFont(g.first())
            val name = font?.name.orEmpty()
            val rgb = g.flatMap { it.glyphs }.groupingBy { it.rgb }.eachCount().maxByOrNull { it.value }?.key ?: 0
            val text = buildString {
                g.forEachIndexed { i, line ->
                    val t = line.text()
                    if (i > 0) {
                        if (endsWith("-") && t.firstOrNull()?.isLowerCase() == true) deleteCharAt(length - 1) else append(' ')
                    }
                    append(t)
                }
            }
            val tl = PdfTools.fromUser(page, x0, top)
            val br = PdfTools.fromUser(page, x1, bottom)
            PdfTextBlock(
                page = pageIndex,
                index = index,
                text = text,
                x0 = x0, y0 = bottom, x1 = x1, y1 = top,
                firstBaseline = g.first().baseline,
                lineSpacing = spacing,
                lineCount = g.size,
                style = PdfTextStyle(
                    sizePt = size,
                    bold = isBold(font),
                    italic = isItalic(font),
                    rgb = rgb,
                    align = alignment(g, page),
                    family = family(name),
                ),
                fontName = name,
                left = minOf(tl.x, br.x).coerceIn(0f, 1f),
                top = minOf(tl.y, br.y).coerceIn(0f, 1f),
                right = maxOf(tl.x, br.x).coerceIn(0f, 1f),
                bottom = maxOf(tl.y, br.y).coerceIn(0f, 1f),
            )
        }
    }

    private fun dominantFont(line: Line): PDFont? =
        line.glyphs.groupingBy { it.font }.eachCount().maxByOrNull { it.value }?.key

    private fun isBold(font: PDFont?): Boolean {
        font ?: return false
        val n = font.name.orEmpty().lowercase()
        val d = font.fontDescriptor
        return listOf("bold", "black", "heavy", "semibold", "demi").any { it in n } ||
            d?.isForceBold == true || (d?.fontWeight ?: 0f) >= 600f
    }

    private fun isItalic(font: PDFont?): Boolean {
        font ?: return false
        val n = font.name.orEmpty().lowercase()
        return "italic" in n || "oblique" in n || font.fontDescriptor?.isItalic == true
    }

    private fun family(name: String): Int {
        val n = name.lowercase()
        return when {
            listOf("courier", "mono", "consol").any { it in n } -> 2
            listOf("times", "serif", "georgia", "garamond", "cambria", "book", "minion", "palatino", "roman", "century").any { it in n } &&
                "sans" !in n -> 1
            else -> 0
        }
    }

    private fun alignment(lines: List<Line>, page: PDPage): Int {
        val lefts = lines.map { it.x0 }
        val rights = lines.map { it.x1 }
        val size = lines.first().size
        fun spread(v: List<Float>) = v.max() - v.min()
        if (lines.size == 1) {
            val box = page.cropBox
            val center = (lines[0].x0 + lines[0].x1) / 2
            val pageCenter = box.lowerLeftX + box.width / 2
            return if (abs(center - pageCenter) < box.width * 0.03f && lines[0].x0 - box.lowerLeftX > box.width * 0.15f) 1 else 0
        }
        // The last line of a paragraph is often short; leave it out when judging the right edge.
        val body = if (lines.size > 2) rights.dropLast(1) else rights
        val leftAligned = spread(lefts.drop(1).ifEmpty { lefts }) < size * 0.5f
        val rightAligned = spread(body) < size * 0.5f
        val centers = lines.map { (it.x0 + it.x1) / 2 }
        return when {
            leftAligned -> 0
            rightAligned -> 2
            spread(centers) < size * 0.6f -> 1
            else -> 0
        }
    }

    // --- replacing a paragraph ------------------------------------------------------------------

    /**
     * Replaces [block]'s text by [newText] drawn with [style]. [background] is the page colour around the block,
     * used only where old glyphs cannot be taken out (they are then covered).
     */
    suspend fun replace(file: File, block: PdfTextBlock, newText: String, style: PdfTextStyle, background: Int) =
        withContext(Dispatchers.IO) {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                replace(doc, block, newText, style, background)
                val tmp = File(file.parentFile, file.name + ".tmp")
                doc.save(tmp)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }

    fun replace(doc: PDDocument, block: PdfTextBlock, newText: String, style: PdfTextStyle, background: Int) {
        val page = doc.getPage(block.page)
        val pad = block.style.sizePt * 0.15f
        val box = floatArrayOf(block.x0 - pad, block.y0 - pad, block.x1 + pad, block.y1 + pad)
        val remover = GlyphRemover(box)
        remover.processPage(page)
        val originalFont = remover.firstFont

        if (remover.removals.isNotEmpty()) rewrite(doc, page, remover.removals)

        val text = newText.trimEnd()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            if (remover.needsCover) {
                cs.setNonStrokingColor(((background shr 16) and 0xFF) / 255f, ((background shr 8) and 0xFF) / 255f, (background and 0xFF) / 255f)
                cs.addRect(block.x0 - pad, block.y0 - pad, block.x1 - block.x0 + pad * 2, block.y1 - block.y0 + pad * 2)
                cs.fill()
            }
            if (text.isEmpty()) return@use
            val chosen = pickFont(doc, originalFont, text, style, block.fontName)
            val size = style.sizePt
            val maxWidth = if (block.lineCount > 1) block.x1 - block.x0 else {
                val crop = page.cropBox
                // A single line may grow up to the right margin mirroring its left one.
                maxOf(block.x1 - block.x0, crop.upperRightX - (block.x0 - crop.lowerLeftX) - block.x0)
            }
            val shown = if (chosen.ascii) text.lines().joinToString("\n") { PdfTools.asciiFallback(it) } else text
            val lines = wrap(shown, chosen.font, size, maxWidth)
            val spacing = if (block.lineCount > 1) block.lineSpacing else size * 1.2f
            val r = ((style.rgb shr 16) and 0xFF) / 255f
            val g = ((style.rgb shr 8) and 0xFF) / 255f
            val b = (style.rgb and 0xFF) / 255f
            cs.beginText()
            cs.setFont(chosen.font, size)
            cs.setNonStrokingColor(r, g, b)
            if (chosen.fakeBold) {
                cs.setRenderingMode(RenderingMode.FILL_STROKE)
                cs.setStrokingColor(r, g, b)
                cs.setLineWidth(size * 0.035f)
            }
            lines.forEachIndexed { i, line ->
                val w = width(chosen.font, line, size)
                val x = when (style.align) {
                    1 -> (block.x0 + block.x1) / 2 - w / 2
                    2 -> block.x1 - w
                    else -> block.x0
                }
                val y = block.firstBaseline - i * spacing
                val shear = if (chosen.fakeItalic) 0.2f else 0f
                cs.setTextMatrix(Matrix(1f, 0f, shear, 1f, x, y))
                cs.showText(line)
            }
            cs.endText()
        }
    }

    private fun width(font: PDFont, s: String, size: Float): Float =
        runCatching { font.getStringWidth(s) / 1000f * size }.getOrDefault(s.length * size * 0.5f)

    /** Breaks [text] into lines no wider than [maxWidth]; the user's own line breaks are kept. */
    private fun wrap(text: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && width(font, candidate, size) > maxWidth) {
                    out += line
                    line = word
                } else line = candidate
            }
            out += line
        }
        return out
    }

    private class ChosenFont(val font: PDFont, val fakeBold: Boolean, val fakeItalic: Boolean, val ascii: Boolean = false)

    /** The page's own font when it can show every character, else the closest system font. */
    private fun pickFont(doc: PDDocument, original: PDFont?, text: String, style: PdfTextStyle, originalName: String): ChosenFont {
        val sameStyle = style.bold == isBold(original) && style.italic == isItalic(original)
        if (original != null && sameStyle && canShow(original, text)) return ChosenFont(original, false, false)
        // Standard fonts (Helvetica, Times, Courier) for text they can encode.
        val std = standard(style)
        if (canShow(std, text)) return ChosenFont(std, false, false)
        val dir = File("/system/fonts")
        fun load(vararg names: String): PDFont? {
            for (n in names) {
                val f = File(dir, n)
                if (f.exists()) runCatching { return PDType0Font.load(doc, f) }
            }
            return null
        }
        val bi = style.bold && style.italic
        val font: Pair<PDFont?, Pair<Boolean, Boolean>> = when (style.family) {
            1 -> when {
                bi -> load("NotoSerif-BoldItalic.ttf") to (false to false)
                style.bold -> load("NotoSerif-Bold.ttf") to (false to false)
                style.italic -> load("NotoSerif-Italic.ttf") to (false to false)
                else -> load("NotoSerif-Regular.ttf") to (false to false)
            }
            2 -> load("DroidSansMono.ttf", "CutiveMono.ttf") to (style.bold to style.italic)
            else -> when {
                bi -> (load("Roboto-BoldItalic.ttf", "SourceSansPro-BoldItalic.ttf")?.let { it to (false to false) })
                    ?: (load("Roboto-Bold.ttf", "SourceSansPro-Bold.ttf")?.let { it to (false to true) })
                    ?: (load("Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf") to (true to true))
                style.bold -> (load("Roboto-Bold.ttf", "SourceSansPro-Bold.ttf", "NotoSans-Bold.ttf")?.let { it to (false to false) })
                    ?: (load("Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf") to (true to false))
                style.italic -> (load("Roboto-Italic.ttf", "SourceSansPro-Italic.ttf", "NotoSans-Italic.ttf")?.let { it to (false to false) })
                    ?: (load("Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf") to (false to true))
                else -> load("Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf") to (false to false)
            }
        }
        val f = font.first
        if (f != null && canShow(f, text)) return ChosenFont(f, font.second.first, font.second.second)
        val any = PdfTools.loadFont(doc)
        if (canShow(any, text)) return ChosenFont(any, style.bold, style.italic)
        bundledFont?.let { open ->
            val own = runCatching { open(style.bold).use { PDType0Font.load(doc, it) } }.getOrNull()
            if (own != null && canShow(own, text)) return ChosenFont(own, false, style.italic)
        }
        return ChosenFont(standard(style), false, false, ascii = true)
    }

    private fun standard(style: PdfTextStyle): PDFont = when (style.family) {
        1 -> when {
            style.bold && style.italic -> PDType1Font.TIMES_BOLD_ITALIC
            style.bold -> PDType1Font.TIMES_BOLD
            style.italic -> PDType1Font.TIMES_ITALIC
            else -> PDType1Font.TIMES_ROMAN
        }
        2 -> when {
            style.bold && style.italic -> PDType1Font.COURIER_BOLD_OBLIQUE
            style.bold -> PDType1Font.COURIER_BOLD
            style.italic -> PDType1Font.COURIER_OBLIQUE
            else -> PDType1Font.COURIER
        }
        else -> when {
            style.bold && style.italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            style.bold -> PDType1Font.HELVETICA_BOLD
            style.italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> PDType1Font.HELVETICA
        }
    }

    /** True when [font] has a real glyph for every character of [text]. */
    private fun canShow(font: PDFont, text: String): Boolean {
        if (font is PDType3Font) return false
        val chars = text.replace("\n", "")
        val bytes = runCatching { font.encode(chars) }.getOrNull() ?: return false
        if (font is PDType1Font && !font.isEmbedded) return true // standard 14: the viewer has every glyph
        if (!font.isEmbedded) return false
        val input = ByteArrayInputStream(bytes)
        while (input.available() > 0) {
            val code = runCatching { font.readCode(input) }.getOrNull() ?: return false
            val gid = when (font) {
                is PDType0Font -> runCatching { font.codeToGID(code) }.getOrDefault(0)
                is PDTrueTypeFont -> runCatching { font.codeToGID(code) }.getOrDefault(0)
                else -> return false
            }
            if (gid == 0) return false
        }
        return true
    }

    // --- taking glyphs out of the content stream --------------------------------------------------

    /** Glyphs to remove from one string of a text operator, with the width each one advanced the text by. */
    private class Removal(val element: Int, val start: Int, val end: Int, val adjust: Float)

    /**
     * Walks the page like a renderer and records which glyphs fall inside [box] (x0, y0, x1, y1 in user space):
     * by top-level operator, string element and bytes.
     */
    private class GlyphRemover(private val box: FloatArray) : PDFStreamEngine() {
        val removals = HashMap<Int, MutableList<Removal>>()
        var needsCover = false
        var firstFont: PDFont? = null
        private var depth = 0
        private var opIndex = -1
        private var inArray = false
        private var element = -1
        private var bytes: ByteArray? = null
        private var cursor = 0

        init {
            listOf(
                BeginText(), EndText(), MoveText(), MoveTextSetLeading(), NextLine(), SetCharSpacing(), SetFontAndSize(),
                SetTextHorizontalScaling(), SetTextLeading(), SetTextRenderingMode(), SetTextRise(), SetWordSpacing(),
                ShowText(), ShowTextAdjusted(), ShowTextLine(), ShowTextLineAndSpace(), SetMatrix(), Concatenate(),
                Save(), Restore(), SetGraphicsStateParameters(), DrawObject(),
            ).forEach { addOperator(it) }
        }

        /** Operators like T*, ' and " run other operators themselves; only the ones in the stream count. */
        private var nested = 0

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (depth == 0 && nested == 0) opIndex++
            nested++
            try { super.processOperator(operator, operands) } finally { nested-- }
        }

        override fun showForm(form: PDFormXObject) {
            depth++
            try { super.showForm(form) } finally { depth-- }
        }

        override fun processType3Stream(charProc: PDType3CharProc, textRenderingMatrix: Matrix) {
            depth++
            try { super.processType3Stream(charProc, textRenderingMatrix) } finally { depth-- }
        }

        override fun showTextStrings(array: COSArray) {
            if (depth > 0) return super.showTextStrings(array)
            inArray = true
            element = -1
            try { super.showTextStrings(array) } finally { inArray = false }
        }

        override fun showText(string: ByteArray) {
            if (depth == 0) {
                element = if (inArray) element + 1 else -1
                bytes = string
                cursor = 0
            }
            super.showText(string)
        }

        override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            val unicode = runCatching { font.toUnicode(code) }.getOrNull()
            if (depth == 0) {
                val b = bytes
                val start = cursor
                if (b != null) cursor += codeLength(font, b, cursor)
                val x = textRenderingMatrix.translateX
                val y = textRenderingMatrix.translateY
                if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) {
                    if (firstFont == null && !unicode.isNullOrBlank()) firstFont = font
                    val ts = graphicsState.textState
                    val fontSize = ts.fontSize.takeIf { it != 0f } ?: 1f
                    val wordSpace = if (code == 32 && cursor - start == 1) ts.wordSpacing else 0f
                    val adjust = displacement.x * 1000f + (ts.characterSpacing + wordSpace) * 1000f / fontSize
                    if (b != null && cursor > start) {
                        removals.getOrPut(opIndex) { ArrayList() } += Removal(element, start, cursor, adjust)
                    } else needsCover = true
                    // Invisible text (OCR over a scanned image) sits over pixels that would still show.
                    if (ts.renderingMode == RenderingMode.NEITHER) needsCover = true
                }
            } else {
                val x = textRenderingMatrix.translateX
                val y = textRenderingMatrix.translateY
                if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) {
                    needsCover = true
                    if (firstFont == null && !unicode.isNullOrBlank()) firstFont = font
                }
            }
            super.showGlyph(textRenderingMatrix, font, code, displacement)
        }

        private fun codeLength(font: PDFont, b: ByteArray, at: Int): Int {
            if (at >= b.size) return 0
            val input = ByteArrayInputStream(b, at, b.size - at)
            val before = input.available()
            runCatching { font.readCode(input) }.onFailure { return 1 }
            return (before - input.available()).coerceAtLeast(1)
        }
    }

    /** Writes the page's content again with the recorded glyphs replaced by equal spacing. */
    private fun rewrite(doc: PDDocument, page: PDPage, removals: Map<Int, List<Removal>>) {
        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens = parser.tokens
        val out = ArrayList<Any>(tokens.size + 16)
        var opIndex = -1
        var operandStart = 0
        for (token in tokens) {
            if (token !is Operator) {
                out.add(token)
                continue
            }
            opIndex++
            val operands = out.subList(operandStart, out.size)
            val list = removals[opIndex]
            if (list != null) {
                when (token.name) {
                    "Tj" -> {
                        val s = operands.lastOrNull() as? COSString
                        if (s != null) {
                            operands[operands.size - 1] = adjusted(COSArray().apply { add(s) }, list, single = true)
                            out.add(Operator.getOperator("TJ"))
                        } else out.add(token)
                    }
                    "TJ" -> {
                        val a = operands.lastOrNull() as? COSArray
                        if (a != null) operands[operands.size - 1] = adjusted(a, list, single = false)
                        out.add(token)
                    }
                    "'" -> {
                        val s = operands.lastOrNull() as? COSString
                        if (s != null) {
                            val arr = adjusted(COSArray().apply { add(s) }, list, single = true)
                            operands.clear()
                            out.add(Operator.getOperator("T*"))
                            out.add(arr)
                            out.add(Operator.getOperator("TJ"))
                        } else out.add(token)
                    }
                    "\"" -> {
                        if (operands.size >= 3 && operands.last() is COSString) {
                            val aw = operands[operands.size - 3]
                            val ac = operands[operands.size - 2]
                            val s = operands.last() as COSString
                            val arr = adjusted(COSArray().apply { add(s) }, list, single = true)
                            operands.clear()
                            out.add(aw); out.add(Operator.getOperator("Tw"))
                            out.add(ac); out.add(Operator.getOperator("Tc"))
                            out.add(Operator.getOperator("T*"))
                            out.add(arr)
                            out.add(Operator.getOperator("TJ"))
                        } else out.add(token)
                    }
                    else -> out.add(token)
                }
            } else {
                out.add(token)
            }
            operandStart = out.size
        }
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { os -> ContentStreamWriter(os).writeTokens(out) }
        page.setContents(stream)
    }

    /** [array] with the removed byte ranges of its strings turned into spacing. [single] means a lone Tj string. */
    private fun adjusted(array: COSArray, list: List<Removal>, single: Boolean): COSArray {
        val result = COSArray()
        var stringIndex = -1
        for (i in 0 until array.size()) {
            val item = array.getObject(i)
            if (item !is COSString) {
                if (item is COSNumber) result.add(item) else result.add(array.get(i))
                continue
            }
            stringIndex++
            val mine = list.filter { if (single) it.element == -1 else it.element == stringIndex }.sortedBy { it.start }
            if (mine.isEmpty()) {
                result.add(item)
                continue
            }
            val b = item.bytes
            var pos = 0
            var pending = 0f
            for (r in mine) {
                if (r.start > pos) {
                    if (pending != 0f) { result.add(COSFloat(-pending)); pending = 0f }
                    result.add(COSString(b.copyOfRange(pos, r.start)))
                }
                pending += r.adjust
                pos = maxOf(pos, r.end)
            }
            if (pending != 0f) result.add(COSFloat(-pending))
            if (pos < b.size) result.add(COSString(b.copyOfRange(pos, b.size)))
        }
        return result
    }
}
