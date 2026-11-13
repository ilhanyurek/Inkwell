package com.office.core.text

/** How a run of text really looks once styles and direct formatting are combined. */
data class RunLook(
    val font: String? = null,
    val sizePt: Float? = null,
    /** ARGB, or null for the automatic text colour. */
    val color: Long? = null,
    /** ARGB background behind the text (highlight or shading). */
    val highlight: Long? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** 1 superscript, -1 subscript, 0 normal. */
    val vertical: Int = 0,
    val caps: Boolean = false,
    val smallCaps: Boolean = false,
)

/** Paragraph layout once styles, list levels and direct formatting are combined. Lengths are in points. */
data class ParaLook(
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    /** Extra indent of the first line; negative for a hanging indent. */
    val firstLine: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    /** Line spacing as a multiple of single spacing, or null when [lineExactPt] applies. */
    val lineMultiple: Float? = null,
    val lineExactPt: Float? = null,
    /** Alignment coming from the style when the paragraph has none of its own. */
    val alignment: Alignment? = null,
    val pageBreakBefore: Boolean = false,
    /** No space between neighbouring paragraphs of the same style. */
    val contextualSpacing: Boolean = false,
    val styleId: String? = null,
)

/** The marker of a list paragraph, e.g. "2.1" or "•", and its nesting level. */
data class ListLabel(val text: String, val level: Int)

/** Resolves the look of text for the editor; implemented per file format. */
interface TextFormatting {
    fun runLook(paragraph: ParaAttrs, style: CharStyle): RunLook
    fun paraLook(paragraph: ParaAttrs): ParaLook
    /** One entry per paragraph: its list marker, or null when it has none or it cannot be computed. */
    fun listLabels(paragraphs: List<ParaAttrs>): List<ListLabel?>
}
