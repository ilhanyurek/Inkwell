package com.office.app.ui.document

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.office.core.text.Alignment
import com.office.core.text.CharStyle
import com.office.core.text.Embed
import com.office.core.text.EmbedKind
import com.office.core.text.ListType
import com.office.core.text.ParaAttrs
import com.office.core.text.ParaLook
import com.office.core.text.RichText
import com.office.core.text.RunLook
import com.office.core.text.TextFormatting
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import com.office.core.text.ParagraphStyle as DocParagraphStyle

/**
 * Renders a [RichText] model inside a plain text field: character styles become spans,
 * paragraph attributes become paragraph styles, and list markers are inserted visually
 * (they are not part of the editable text).
 */
class RichTextTransformation(
    private val model: RichText,
    private val baseSize: TextUnit,
    private val mutedColor: Color,
    private val accentColor: Color,
    private val embeds: Map<Int, Embed> = emptyMap(),
    /** Text shown in place of an embed; an empty string hides it (bookmarks and similar markers). */
    private val embedLabel: (Embed) -> String = { "" },
    private val chipBackground: Color = Color.Transparent,
    /**
     * Embeds drawn over the text (tables, pictures) with the space to leave for them. A paragraph
     * holding only such an embed becomes one empty line of that height.
     */
    private val blockHeights: Map<Int, TextUnit> = emptyMap(),
    /** Resolves the document's own fonts, colours, indents, spacing and list numbers; null for plain rendering. */
    private val formatting: TextFormatting? = null,
    /** Page background, used to keep document colours readable in the dark theme. */
    private val background: Color = Color.Unspecified,
) : VisualTransformation {

    /** Where a block embed sits: its offset in the model and in the transformed text. */
    data class BlockAnchor(val offset: Int, val transformedOffset: Int, val embedId: Int)

    /** Filled by [filter]; read after layout to place the block views. */
    var blockAnchors: List<BlockAnchor> = emptyList()
        private set

    override fun filter(text: AnnotatedString): TransformedText {
        if (text.text != model.text) return TransformedText(text, OffsetMapping.Identity)

        val src = model.text
        val out = AnnotatedString.Builder(src.length + 16)
        val origToTrans = IntArray(src.length + 1)
        val transToOrig = ArrayList<Int>(src.length + 16)

        val starts = model.lineStarts()
        val anchors = ArrayList<BlockAnchor>()
        var number = 0
        val labels = formatting?.let { f -> runCatching { f.listLabels(model.paras) }.getOrNull() }
        var previousLook: ParaLook? = null
        for (p in model.paras.indices) {
            val attrs = model.paras[p]
            val start = starts[p]
            val end = if (p + 1 < starts.size) starts[p + 1] - 1 else src.length
            // A paragraph that holds one drawn block (table, picture, page break) and nothing else visible.
            val block = blockIn(start, end)
            val isBlock = block != null
            val look = if (isBlock) null else formatting?.let { f -> runCatching { f.paraLook(attrs) }.getOrNull() }

            // Space between paragraphs: an empty, very small line of that height, mapped to the paragraph start.
            if (look != null && p > 0) {
                val collapse = look.contextualSpacing && previousLook?.contextualSpacing == true && look.styleId == previousLook?.styleId
                val gapPt = if (collapse) 0f else (previousLook?.spaceAfter ?: 0f) + look.spaceBefore
                if (gapPt >= 3f) {
                    val gapStart = out.length
                    out.append("\u200B\n")
                    transToOrig += start
                    transToOrig += start
                    out.addStyle(SpanStyle(fontSize = 1.sp), gapStart, out.length)
                    out.addStyle(
                        ParagraphStyle(
                            lineHeight = (gapPt.coerceAtMost(48f) * baseSize.value / 11f).sp,
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None),
                        ),
                        gapStart, out.length,
                    )
                }
            }
            previousLook = look
            val paraStartTrans = out.length

            if (block != null) {
                val (blockOffset, blockId) = block
                number = 0
                anchors += BlockAnchor(blockOffset, out.length, blockId)
                // One invisible character per model character keeps the caret mapping one-to-one.
                for (k in start until end) {
                    origToTrans[k] = out.length
                    transToOrig += k
                    out.append("\u200B")
                }
                origToTrans[end] = out.length
                if (end < src.length) {
                    transToOrig += end
                    out.append('\n')
                }
                // The newline ending a styled paragraph adds an empty line of the paragraph's line height; for
                // a block that would be a second table- or picture-high gap, so that line keeps only text height.
                // A block at the very end has no such line and keeps its full height.
                out.addStyle(
                    ParagraphStyle(
                        lineHeight = blockHeights.getValue(blockId),
                        lineHeightStyle = LineHeightStyle(
                            LineHeightStyle.Alignment.Top,
                            if (end < src.length) LineHeightStyle.Trim.LastLineBottom else LineHeightStyle.Trim.None,
                        ),
                    ),
                    paraStartTrans, out.length,
                )
                continue
            }

            number = if (attrs.list == ListType.NUMBERED) number + 1 else 0
            val label = labels?.getOrNull(p)
            val marker = when {
                attrs.list == ListType.NONE -> ""
                label != null -> if (label.text.isEmpty()) "" else label.text + "\u2002"
                attrs.list == ListType.BULLET -> "\u2022\u2002"
                else -> "$number.\u2002"
            }
            if (marker.isNotEmpty()) {
                out.pushStyle(SpanStyle(color = accentColor))
                out.append(marker)
                out.pop()
                repeat(marker.length) { transToOrig += start }
            }

            // Characters of this paragraph, grouped into style runs.
            var maxFontSp = baseSize.value * headingFactor(attrs.style)
            var runs = 0
            var i = start
            while (i < end) {
                val st = model.styles[i]
                if (st.embedId != null) {
                    val embed = embeds[st.embedId]
                    val label = embed?.let(embedLabel).orEmpty().ifEmpty { "\u200B" }
                    val chipStart = out.length
                    origToTrans[i] = chipStart
                    out.append(label)
                    repeat(label.length) { transToOrig += i }
                    if (embed != null && label != "\u200B") out.addStyle(embedSpan(embed), chipStart, out.length)
                    i++
                    continue
                }
                val runLook = formatting?.let { f -> runCatching { f.runLook(attrs, st) }.getOrNull() }
                if (runLook != null) {
                    val sp = lookFontSp(runLook)
                    if (runs == 0 || sp > maxFontSp) maxFontSp = sp
                    runs++
                } else {
                    runFontSp(st)?.let { if (it > maxFontSp) maxFontSp = it }
                }
                var j = i + 1
                while (j < end && model.styles[j] == st) j++
                val spanStart = out.length
                for (k in i until j) {
                    origToTrans[k] = spanStart + (k - i)
                    transToOrig += k
                }
                out.append(src, i, j)
                (if (runLook != null) lookSpan(runLook) else charSpan(st))?.let { out.addStyle(it, spanStart, out.length) }
                i = j
            }
            origToTrans[end] = out.length
            if (end < src.length) {
                transToOrig += end
                out.append('\n')
            }
            val paraEndTrans = if (end < src.length) out.length - 1 else out.length

            if (look == null) headingSpan(attrs.style)?.let { out.addStyle(it, paraStartTrans, paraEndTrans) }
            else if (runs == 0) maxFontSp = lookFontSp(formatting!!.runLook(attrs, CharStyle.PLAIN))
            // Paragraph styles must cover the newline that ends the paragraph to avoid overlaps.
            val paraStyleEnd = if (end < src.length) out.length else out.length
            if (paraStyleEnd > paraStartTrans) {
                // Line height follows the largest text in the paragraph; an em value would be relative to
                // the body size and make the lines of a wrapped heading overlap.
                val multiplier = if (attrs.style == DocParagraphStyle.NORMAL || attrs.style == DocParagraphStyle.QUOTE) 1.5f else 1.25f
                out.addStyle(
                    if (look != null) lookParagraphStyle(look, attrs, maxFontSp, label != null)
                    else paragraphStyle(attrs.style, attrs.list != ListType.NONE, attrs.alignment, (maxFontSp * multiplier).sp),
                    paraStartTrans, paraStyleEnd,
                )
            }
        }
        transToOrig += src.length
        blockAnchors = anchors

        val transformed = out.toAnnotatedString()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                origToTrans[offset.coerceIn(0, src.length)].coerceIn(0, transformed.length)

            override fun transformedToOriginal(offset: Int): Int =
                transToOrig[offset.coerceIn(0, transToOrig.size - 1)].coerceIn(0, src.length)
        }
        return TransformedText(transformed, mapping)
    }

    private fun embedSpan(e: Embed): SpanStyle = if (e.kind == EmbedKind.LINK) {
        SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline)
    } else {
        SpanStyle(color = accentColor, background = chipBackground, fontSize = (baseSize.value * 0.85f).sp)
    }

    private fun charSpan(st: CharStyle): SpanStyle? {
        if (st == CharStyle.PLAIN) return null
        val decorations = buildList {
            if (st.underline) add(TextDecoration.Underline)
            if (st.strike) add(TextDecoration.LineThrough)
        }
        return SpanStyle(
            fontWeight = if (st.bold) FontWeight.Bold else null,
            fontStyle = if (st.italic) FontStyle.Italic else null,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            fontSize = runFontSp(st)?.sp ?: TextUnit.Unspecified,
        )
    }

    /** Offset and id of the single drawn block in a paragraph whose other characters are invisible markers. */
    private fun blockIn(start: Int, end: Int): Pair<Int, Int>? {
        if (end <= start) return null
        var found: Pair<Int, Int>? = null
        for (k in start until end) {
            val id = model.styles[k].embedId ?: return null
            if (id in blockHeights) {
                if (found != null) return null
                found = k to id
            } else {
                val e = embeds[id] ?: return null
                if (e.wrapper == null && embedLabel(e).isNotEmpty()) return null
            }
        }
        return found
    }

    // --- rendering with the document's own formatting ------------------------------------------

    private fun lookFontSp(look: RunLook): Float = lookFontSp(look, baseSize.value)

    private fun lookSpan(look: RunLook): SpanStyle = lookSpanStyle(look, baseSize.value, background)

    private fun lookParagraphStyle(look: ParaLook, attrs: ParaAttrs, maxFontSp: Float, hasLabel: Boolean): ParagraphStyle {
        // Indents are scaled down: the screen is much narrower than a page.
        val k = baseSize.value / 11f * 0.6f
        var left = (look.indentLeft * k).coerceIn(0f, 140f)
        var first = (left + look.firstLine * k).coerceIn(0f, 160f)
        if (attrs.list != ListType.NONE && !hasLabel && left == 0f && first == 0f) {
            first = baseSize.value * 0.4f
            left = baseSize.value * 1.6f
        }
        val line = when {
            look.lineExactPt != null -> maxOf(look.lineExactPt!! / 11f * baseSize.value, maxFontSp)
            else -> maxFontSp * 1.2f * (look.lineMultiple ?: 1f).coerceIn(0.9f, 3f)
        }
        val alignment = if (attrs.alignment != Alignment.START) attrs.alignment else look.alignment ?: Alignment.START
        return ParagraphStyle(
            textAlign = when (alignment) {
                Alignment.START -> TextAlign.Start
                Alignment.CENTER -> TextAlign.Center
                Alignment.END -> TextAlign.End
                Alignment.JUSTIFY -> TextAlign.Justify
            },
            textIndent = if (left == 0f && first == 0f) null else TextIndent(firstLine = first.sp, restLine = left.sp),
            lineHeight = line.sp,
        )
    }

    /** Font size in sp of a run with an explicit size, or null for the paragraph's own size. */
    private fun runFontSp(st: CharStyle): Float? = st.sizePt?.let { (it / 11f * baseSize.value).coerceIn(9f, 56f) }

    private fun headingFactor(style: DocParagraphStyle): Float = when (style) {
        DocParagraphStyle.TITLE -> 1.85f
        DocParagraphStyle.HEADING1 -> 1.5f
        DocParagraphStyle.HEADING2 -> 1.3f
        DocParagraphStyle.HEADING3 -> 1.15f
        DocParagraphStyle.QUOTE, DocParagraphStyle.NORMAL -> 1f
    }

    private fun headingSpan(style: DocParagraphStyle): SpanStyle? {
        if (style == DocParagraphStyle.QUOTE) return SpanStyle(fontStyle = FontStyle.Italic, color = mutedColor)
        if (style == DocParagraphStyle.NORMAL) return null
        return SpanStyle(
            fontSize = (baseSize.value * headingFactor(style)).sp,
            fontWeight = if (style == DocParagraphStyle.TITLE) FontWeight.Normal else FontWeight.SemiBold,
            letterSpacing = if (style == DocParagraphStyle.TITLE) (-0.02).em else TextUnit.Unspecified,
        )
    }

    private fun paragraphStyle(style: DocParagraphStyle, list: Boolean, alignment: Alignment, lineHeight: TextUnit) = ParagraphStyle(
        textAlign = when (alignment) {
            Alignment.START -> TextAlign.Start
            Alignment.CENTER -> TextAlign.Center
            Alignment.END -> TextAlign.End
            Alignment.JUSTIFY -> TextAlign.Justify
        },
        textIndent = when {
            list -> TextIndent(firstLine = 0.4.em, restLine = 1.6.em)
            style == DocParagraphStyle.QUOTE -> TextIndent(firstLine = 1.2.em, restLine = 1.2.em)
            else -> null
        },
        lineHeight = lineHeight,
    )
}
