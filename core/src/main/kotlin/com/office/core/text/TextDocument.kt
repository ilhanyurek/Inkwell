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
) {
    fun sameStyle(other: TextRun) =
        bold == other.bold && italic == other.italic && underline == other.underline &&
            strike == other.strike && sizePt == other.sizePt
}

enum class ParagraphStyle { NORMAL, TITLE, HEADING1, HEADING2, HEADING3, QUOTE }

enum class ListType { NONE, BULLET, NUMBERED }

enum class Alignment { START, CENTER, END, JUSTIFY }

data class Paragraph(
    val runs: List<TextRun>,
    val style: ParagraphStyle = ParagraphStyle.NORMAL,
    val list: ListType = ListType.NONE,
    val alignment: Alignment = Alignment.START,
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
    /** Set when the source contained content we could not represent (images, complex tables…). */
    val lossyImport: Boolean = false,
) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.text }

    val wordCount: Int
        get() = paragraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }

    val characterCount: Int get() = paragraphs.sumOf { it.text.length }

    companion object {
        fun empty() = TextDocument(listOf(Paragraph(emptyList())))
    }
}
