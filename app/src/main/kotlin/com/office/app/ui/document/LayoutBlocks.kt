package com.office.app.ui.document

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.office.core.docx.BorderLine
import com.office.core.docx.LayoutItem
import com.office.core.docx.TableLayout
import com.office.core.text.Alignment

/**
 * Tables, headers and footers measured for the screen and drawn on a canvas. Everything is measured up
 * front, so the text can leave exactly the space a table needs.
 */
internal class MeasuredBlock(val width: Float, val height: Float, val ops: List<DrawOp>, val text: String)

internal sealed class DrawOp {
    class Fill(val x: Float, val y: Float, val w: Float, val h: Float, val color: Color) : DrawOp()
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val color: Color) : DrawOp()
    class Text(val x: Float, val y: Float, val layout: TextLayoutResult) : DrawOp()
    class Picture(val x: Float, val y: Float, val w: Float, val h: Float, val image: ImageBitmap?) : DrawOp()
}

/** Shared settings for measuring: points to pixels, default text colour, page colour. */
internal class LayoutMeasure(
    val measurer: TextMeasurer,
    val density: Density,
    val baseSp: Float,
    val textColor: Color,
    val page: Color,
    val lineColor: Color,
) {
    /** Pixels per point: an 11pt body is drawn at the editor's base size. */
    val ptPx: Float = with(density) { (baseSp / 11f).sp.toPx() }

    private val images = HashMap<ByteArray, ImageBitmap?>()

    fun image(bytes: ByteArray?, maxPx: Int): ImageBitmap? {
        bytes ?: return null
        return images.getOrPut(bytes) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx.coerceAtLeast(64)) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }

    // --- items ----------------------------------------------------------------------------------

    fun items(items: List<LayoutItem>, width: Float, background: Color = page): MeasuredBlock {
        val ops = ArrayList<DrawOp>()
        var y = 0f
        val text = StringBuilder()
        for (item in items) {
            when (item) {
                is LayoutItem.Para -> {
                    y += (item.spaceBefore * ptPx).coerceAtMost(24 * ptPx)
                    val indent = (item.indentLeft * ptPx * 0.6f).coerceIn(0f, width / 3)
                    val layout = paragraph(item, (width - indent).coerceAtLeast(1f), background)
                    ops += DrawOp.Text(indent, y, layout)
                    y += layout.size.height
                    y += (item.spaceAfter * ptPx).coerceAtMost(24 * ptPx)
                    if (item.text.isNotBlank()) text.append(item.text).append('\n')
                }
                is LayoutItem.Table -> {
                    val t = table(item.layout, width)
                    val x = when (item.layout.alignment) {
                        Alignment.CENTER -> (width - t.width) / 2
                        Alignment.END -> width - t.width
                        else -> 0f
                    }.coerceAtLeast(0f)
                    t.ops.forEach { ops += it.shift(x, y) }
                    y += t.height
                    text.append(t.text).append('\n')
                }
                is LayoutItem.Picture -> {
                    var w = item.widthPt * ptPx
                    var h = item.heightPt * ptPx
                    if (w > width) { h *= width / w; w = width }
                    val x = when (item.alignment) {
                        Alignment.CENTER -> (width - w) / 2
                        Alignment.END -> width - w
                        else -> 0f
                    }
                    ops += DrawOp.Picture(x, y, w, h, image(item.bytes, w.toInt()))
                    y += h
                }
            }
        }
        return MeasuredBlock(width, y, ops, text.toString().trimEnd())
    }

    private fun paragraph(p: LayoutItem.Para, width: Float, background: Color): TextLayoutResult {
        val annotated: AnnotatedString = buildAnnotatedString {
            if (p.runs.isEmpty()) append("​")
            for (r in p.runs) withStyle(lookSpanStyle(r.look, baseSp, background)) { append(r.text) }
        }
        val emptySize = (p.emptySizePt / 11f * baseSp).coerceIn(7f, 40f).sp
        val style = TextStyle(
            color = textColor,
            fontSize = if (p.runs.isEmpty()) emptySize else baseSp.sp,
            textAlign = when (p.alignment) {
                Alignment.CENTER -> TextAlign.Center
                Alignment.END -> TextAlign.End
                Alignment.JUSTIFY -> TextAlign.Justify
                Alignment.START -> TextAlign.Start
            },
        )
        return measurer.measure(annotated, style, constraints = Constraints(minWidth = width.toInt(), maxWidth = width.toInt()))
    }

    // --- tables ---------------------------------------------------------------------------------

    fun table(t: TableLayout, maxWidth: Float): MeasuredBlock {
        val natural = t.widthPt * ptPx
        val scale = if (natural > maxWidth && natural > 0f) maxWidth / natural else 1f
        val cols = t.columnsPt.map { it * ptPx * scale }
        val xs = cols.runningFold(0f) { a, b -> a + b }
        val width = xs.last()
        val pad = (t.marginPt * ptPx * scale).coerceIn(2f, 14f)
        val vpad = pad * 0.4f
        val rowCount = t.rows.size

        // Measure every visible cell.
        class Cell(val ri: Int, val c: com.office.core.docx.LayoutCell, val x: Float, val w: Float, val content: MeasuredBlock)
        val cells = ArrayList<Cell>()
        for ((ri, row) in t.rows.withIndex()) {
            for (c in row.cells) {
                if (c.rowSpan == 0) continue
                val x = xs.getOrElse(c.col) { width }
                val w = (xs.getOrElse(c.col + c.span) { width } - x).coerceAtLeast(1f)
                val bg = c.fill?.let { Color(it) } ?: page
                cells += Cell(ri, c, x, w, items(c.items, (w - 2 * pad).coerceAtLeast(1f), bg))
            }
        }
        val heights = FloatArray(rowCount) { ri ->
            val row = t.rows[ri]
            val spec = row.heightPt?.let { it * ptPx }
            val content = cells.filter { it.ri == ri && it.c.rowSpan == 1 }.maxOfOrNull { it.content.height + 2 * vpad } ?: 0f
            when {
                spec != null && row.exact -> spec
                spec != null -> maxOf(spec, content)
                else -> maxOf(content, ptPx * 14f)
            }
        }
        // Vertically merged cells: make the rows they cover tall enough.
        for (cell in cells.filter { it.c.rowSpan > 1 }) {
            val end = (cell.ri + cell.c.rowSpan).coerceAtMost(rowCount)
            val have = (cell.ri until end).sumOf { heights[it].toDouble() }.toFloat()
            val need = cell.content.height + 2 * vpad
            if (need > have) heights[end - 1] += need - have
        }
        val ys = heights.toList().runningFold(0f) { a, b -> a + b }

        val ops = ArrayList<DrawOp>()
        val lines = ArrayList<DrawOp>()
        for (cell in cells) {
            val y = ys[cell.ri]
            val h = ys[(cell.ri + cell.c.rowSpan).coerceAtMost(rowCount)] - y
            cell.c.fill?.let { ops += DrawOp.Fill(cell.x, y, cell.w, h, Color(it)) }
            val free = h - 2 * vpad - cell.content.height
            val dy = vpad + when (cell.c.vAlign) {
                1 -> free / 2
                2 -> free
                else -> 0f
            }.coerceAtLeast(0f)
            cell.content.ops.forEach { ops += it.shift(cell.x + pad, y + dy) }
            fun edge(b: BorderLine?, x1: Float, y1: Float, x2: Float, y2: Float) {
                b ?: return
                lines += DrawOp.Line(x1, y1, x2, y2, (b.widthPt * ptPx).coerceIn(1f, 6f), b.color?.let { readableOn(Color(it), page) } ?: lineColor)
            }
            edge(cell.c.top, cell.x, y, cell.x + cell.w, y)
            edge(cell.c.bottom, cell.x, y + h, cell.x + cell.w, y + h)
            edge(cell.c.left, cell.x, y, cell.x, y + h)
            edge(cell.c.right, cell.x + cell.w, y, cell.x + cell.w, y + h)
        }
        return MeasuredBlock(width, ys.last() + 1f, ops + lines, t.text)
    }
}

private fun DrawOp.shift(dx: Float, dy: Float): DrawOp = when (this) {
    is DrawOp.Fill -> DrawOp.Fill(x + dx, y + dy, w, h, color)
    is DrawOp.Line -> DrawOp.Line(x1 + dx, y1 + dy, x2 + dx, y2 + dy, width, color)
    is DrawOp.Text -> DrawOp.Text(x + dx, y + dy, layout)
    is DrawOp.Picture -> DrawOp.Picture(x + dx, y + dy, w, h, image)
}

private fun DrawScope.draw(ops: List<DrawOp>, placeholder: Color) {
    for (op in ops) {
        when (op) {
            is DrawOp.Fill -> drawRect(op.color, Offset(op.x, op.y), Size(op.w, op.h))
            is DrawOp.Line -> drawLine(op.color, Offset(op.x1, op.y1), Offset(op.x2, op.y2), op.width)
            is DrawOp.Text -> drawText(op.layout, topLeft = Offset(op.x, op.y))
            is DrawOp.Picture -> {
                val img = op.image
                if (img != null) {
                    drawImage(img, dstOffset = IntOffset(op.x.toInt(), op.y.toInt()), dstSize = IntSize(op.w.toInt().coerceAtLeast(1), op.h.toInt().coerceAtLeast(1)))
                } else {
                    drawRect(placeholder, Offset(op.x, op.y), Size(op.w, op.h))
                }
            }
        }
    }
}

/** Draws a measured block; its text is exposed to accessibility services and tests. */
@Composable
internal fun MeasuredBlockView(block: MeasuredBlock, modifier: Modifier = Modifier, placeholder: Color = Color.LightGray, onClick: (() -> Unit)? = null) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val w: Dp = with(density) { block.width.toDp() }
    val h: Dp = with(density) { block.height.toDp() }
    Box(
        modifier
            .width(w)
            .height(h)
            .semantics { text = AnnotatedString(block.text) }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Canvas(Modifier.width(w).height(h)) { draw(block.ops, placeholder) }
    }
}

/** A page break: a dashed line across the text column. */
@Composable
internal fun PageBreakBlock(width: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.width(width).height(PAGE_BREAK_HEIGHT.dp)) {
        val y = size.height / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
    }
}

internal const val PAGE_BREAK_HEIGHT = 28

/** A header or footer: drawn full width with a thin rule on the side that faces the text. */
@Composable
internal fun HeaderFooterBlock(block: MeasuredBlock, rule: Color, ruleBelow: Boolean, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.fillMaxWidth()) {
        MeasuredBlockView(block)
        Canvas(Modifier.width(with(density) { block.width.toDp() }).height(with(density) { block.height.toDp() + 6.dp })) {
            val y = if (ruleBelow) size.height - 1f else 0f
            drawLine(rule, Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
}
