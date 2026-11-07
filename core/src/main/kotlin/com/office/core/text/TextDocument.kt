package com.office.core.text

/** A run of text sharing the same character formatting. */
data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Font size in points, or null for the paragraph default. */
    val sizePt: Float? = null,
    /** When set, this run is a single [EMBED_CHAR] standing for [TextDocument.embeds]`[embedId]`. */
    val embedId: Int? = null,
    /** Run properties we do not model (colour, font, highlight…), kept verbatim for round-tripping. */
    val extraXml: String? = null,
) {
    fun sameStyle(other: TextRun) =
        bold == other.bold && italic == other.italic && underline == other.underline &&
            strike == other.strike && sizePt == other.sizePt && extraXml == other.extraXml &&
            embedId == null && other.embedId == null
}

/** Object replacement character used in text for embedded content. */
const val EMBED_CHAR = '\uFFFC'

enum class EmbedKind { IMAGE, TABLE, EQUATION, LINK, FIELD, OBJECT, BLOCK }

/**
 * Content we keep but do not edit: images, tables, equations, links, fields… [xml] is the
 * original markup, written back unchanged. [block] embeds stand for whole body blocks (tables).
 */
data class Embed(
    val kind: EmbedKind,
    val xml: String,
    val block: Boolean = false,
    /** Display text, e.g. the link text; for tables "rows×cols". */
    val label: String = "",
    /** Plain-text rows of a table, for previews. */
    val tableRows: List<List<String>> = emptyList(),
    /** Picture data for an image inserted in this session, written as a new package part on save. */
    val media: EmbedMedia? = null,
)

/** A picture part to add to the package: [relId] is referenced from the embed's XML. */
class EmbedMedia(val relId: String, val fileName: String, val bytes: ByteArray, val contentType: String)

enum class ParagraphStyle { NORMAL, TITLE, HEADING1, HEADING2, HEADING3, QUOTE }

enum class ListType { NONE, BULLET, NUMBERED }

enum class Alignment { START, CENTER, END, JUSTIFY }

data class Paragraph(
    val runs: List<TextRun>,
    val style: ParagraphStyle = ParagraphStyle.NORMAL,
    val list: ListType = ListType.NONE,
    val alignment: Alignment = Alignment.START,
    /** Paragraph properties we do not model (spacing, indents…), kept verbatim. */
    val extraXml: String? = null,
    /** A source style id that is not one of our styles, kept when the paragraph stays NORMAL. */
    val customStyleId: String? = null,
    /** Original list numbering markup and the list type it represents, reused while the type is unchanged. */
    val numXml: String? = null,
    val numType: ListType = ListType.NONE,
) {
    val text: String get() = runs.joinToString("") { it.text }

    /** Merges adjacent runs with identical formatting and drops empty runs. */
    fun normalized(): Paragraph {
        val merged = ArrayList<TextRun>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.sameStyle(r)) merged[merged.size - 1] = last.copy(text = last.text + r.text)
            else merged += r
        }
        return copy(runs = merged)
    }

    companion object {
        fun plain(text: String, style: ParagraphStyle = ParagraphStyle.NORMAL) =
            Paragraph(if (text.isEmpty()) emptyList() else listOf(TextRun(text)), style)
    }
}

data class TextDocument(
    val paragraphs: List<Paragraph>,
    /** Set when the source contained content we could not represent. */
    val lossyImport: Boolean = false,
    val embeds: Map<Int, Embed> = emptyMap(),
) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.text }

    /** Text with embeds replaced by readable placeholders, for plain-text and PDF output. */
    fun readableText(p: Paragraph): String = p.runs.joinToString("") { r ->
        r.embedId?.let { id -> embeds[id]?.let { e -> if (e.label.isNotEmpty() && e.kind == EmbedKind.LINK) e.label else "" } ?: "" } ?: r.text
    }

    val wordCount: Int
        get() = paragraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }

    val characterCount: Int get() = paragraphs.sumOf { it.text.length }

    companion object {
        fun empty() = TextDocument(listOf(Paragraph(emptyList())))
    }
}
