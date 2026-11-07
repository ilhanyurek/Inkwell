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
import com.office.core.text.RichText
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
        for (p in model.paras.indices) {
            val attrs = model.paras[p]
            val start = starts[p]
            val end = if (p + 1 < starts.size) starts[p + 1] - 1 else src.length
            val paraStartTrans = out.length

            val blockId = model.styles.getOrNull(start)?.embedId?.takeIf { end == start + 1 && it in blockHeights }
            if (blockId != null) {
                number = 0
                anchors += BlockAnchor(start, out.length, blockId)
                origToTrans[start] = out.length
                transToOrig += start
                out.append("\u200B")
                origToTrans[end] = out.length
                if (end < src.length) {
                    transToOrig += end
                    out.append('\n')
                }
                out.addStyle(
                    ParagraphStyle(
                        lineHeight = blockHeights.getValue(blockId),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None),
                    ),
                    paraStartTrans, out.length,
                )
                continue
            }

            number = if (attrs.list == ListType.NUMBERED) number + 1 else 0
            val marker = when (attrs.list) {
                ListType.BULLET -> "• "
                ListType.NUMBERED -> "$number. "
                ListType.NONE -> ""
            }
            if (marker.isNotEmpty()) {
                out.pushStyle(SpanStyle(color = accentColor))
                out.append(marker)
                out.pop()
                repeat(marker.length) { transToOrig += start }
            }

            // Characters of this paragraph, grouped into style runs.
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
                var j = i + 1
                while (j < end && model.styles[j] == st) j++
                val spanStart = out.length
                for (k in i until j) {
                    origToTrans[k] = out.length
                    transToOrig += k
                }
                out.append(src, i, j)
                charSpan(st)?.let { out.addStyle(it, spanStart, out.length) }
                i = j
            }
            origToTrans[end] = out.length
            if (end < src.length) {
                transToOrig += end
                out.append('\n')
            }
            val paraEndTrans = if (end < src.length) out.length - 1 else out.length

            headingSpan(attrs.style)?.let { out.addStyle(it, paraStartTrans, paraEndTrans) }
            // Paragraph styles must cover the newline that ends the paragraph to avoid overlaps.
            val paraStyleEnd = if (end < src.length) out.length else out.length
            if (paraStyleEnd > paraStartTrans) {
                out.addStyle(paragraphStyle(attrs.style, attrs.list != ListType.NONE, attrs.alignment), paraStartTrans, paraStyleEnd)
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
            fontSize = st.sizePt?.let { (it / 11f * baseSize.value).coerceIn(9f, 56f).sp } ?: TextUnit.Unspecified,
        )
    }

    private fun headingSpan(style: DocParagraphStyle): SpanStyle? {
        val factor = when (style) {
            DocParagraphStyle.TITLE -> 1.85f
            DocParagraphStyle.HEADING1 -> 1.5f
            DocParagraphStyle.HEADING2 -> 1.3f
            DocParagraphStyle.HEADING3 -> 1.15f
            DocParagraphStyle.QUOTE -> return SpanStyle(fontStyle = FontStyle.Italic, color = mutedColor)
            DocParagraphStyle.NORMAL -> return null
        }
        return SpanStyle(
            fontSize = (baseSize.value * factor).sp,
            fontWeight = if (style == DocParagraphStyle.TITLE) FontWeight.Normal else FontWeight.SemiBold,
            letterSpacing = if (style == DocParagraphStyle.TITLE) (-0.02).em else TextUnit.Unspecified,
        )
    }

    private fun paragraphStyle(style: DocParagraphStyle, list: Boolean, alignment: Alignment) = ParagraphStyle(
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
        lineHeight = when (style) {
            DocParagraphStyle.NORMAL, DocParagraphStyle.QUOTE -> 1.5.em
            else -> 1.25.em
        },
    )
}
