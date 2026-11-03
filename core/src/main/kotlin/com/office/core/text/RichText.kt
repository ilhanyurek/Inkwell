package com.office.core.text

data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float? = null,
) {
    companion object {
        val PLAIN = CharStyle()
        fun of(run: TextRun) = CharStyle(run.bold, run.italic, run.underline, run.strike, run.sizePt)
    }
}

enum class InlineAttr { BOLD, ITALIC, UNDERLINE, STRIKE }

data class ParaAttrs(
    val style: ParagraphStyle = ParagraphStyle.NORMAL,
    val list: ListType = ListType.NONE,
    val alignment: Alignment = Alignment.START,
)

/**
 * Immutable editing model behind the document editor: one string where paragraphs are
 * separated by `\n`, a style per character, and attributes per paragraph.
 *
 * The UI hands every keystroke's new text to [applyEdit], which diffs it against the
 * current text so formatting survives edits made by any IME.
 */
class RichText private constructor(
    val text: String,
    val styles: List<CharStyle>,
    val paras: List<ParaAttrs>,
) {
    init {
        require(styles.size == text.length) { "styles must match text length" }
        require(paras.size == text.count { it == '\n' } + 1) { "one ParaAttrs per paragraph" }
    }

    val paragraphCount get() = paras.size

    fun lineIndexAt(offset: Int): Int {
        var n = 0
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') n++
        return n
    }

    /** Start offsets of each paragraph. */
    fun lineStarts(): IntArray {
        val out = IntArray(paras.size)
        var k = 1
        for (i in text.indices) if (text[i] == '\n') out[k++] = i + 1
        return out
    }

    fun lineRange(index: Int): IntRange {
        val starts = lineStarts()
        val start = starts[index]
        val end = if (index + 1 < starts.size) starts[index + 1] - 1 else text.length
        return start until end
    }

    fun styleAt(offset: Int): CharStyle = styles.getOrNull(offset) ?: CharStyle.PLAIN

    /** Style a character typed at [cursor] would get: that of the preceding character on the same line. */
    fun typingStyleAt(cursor: Int): CharStyle {
        if (cursor > 0 && cursor <= text.length && text[cursor - 1] != '\n') return styles[cursor - 1]
        if (cursor < text.length && text[cursor] != '\n') return styles[cursor]
        return CharStyle.PLAIN
    }

    /** Replaces [start, end) with [insert]. Inserted characters take [insertStyle]. */
    fun replace(start: Int, end: Int, insert: String, insertStyle: CharStyle): RichText {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val firstLine = lineIndexAt(s)
        val removedBreaks = (s until e).count { text[it] == '\n' }
        val addedBreaks = insert.count { it == '\n' }

        val newText = text.substring(0, s) + insert + text.substring(e)
        val newStyles = ArrayList<CharStyle>(newText.length)
        newStyles.addAll(styles.subList(0, s))
        repeat(insert.length) { i -> newStyles.add(if (insert[i] == '\n') CharStyle.PLAIN else insertStyle) }
        newStyles.addAll(styles.subList(e, styles.size))

        val newParas = ArrayList<ParaAttrs>(paras.size - removedBreaks + addedBreaks)
        newParas.addAll(paras.subList(0, firstLine + 1))
        val base = paras[firstLine]
        // A paragraph split off a heading continues as body text; lists continue as lists.
        val continuation = if (base.style == ParagraphStyle.NORMAL || base.style == ParagraphStyle.QUOTE) base
        else base.copy(style = ParagraphStyle.NORMAL)
        repeat(addedBreaks) { newParas.add(continuation) }
        newParas.addAll(paras.subList(firstLine + 1 + removedBreaks, paras.size))
        return RichText(newText, newStyles, newParas)
    }

    /**
     * Applies a whole-text change coming from a text field. Returns the new model, or
     * this model unchanged when the edit was interpreted as a command (e.g. Enter on an
     * empty list item ends the list instead of adding a line).
     */
    fun applyEdit(newText: String, typingStyle: CharStyle): RichText {
        if (newText == text) return this
        var prefix = 0
        val maxPrefix = minOf(text.length, newText.length)
        while (prefix < maxPrefix && text[prefix] == newText[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(text.length, newText.length) - prefix
        while (suffix < maxSuffix && text[text.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        val removedEnd = text.length - suffix
        val inserted = newText.substring(prefix, newText.length - suffix)

        if (inserted == "\n" && removedEnd == prefix) {
            val line = lineIndexAt(prefix)
            val range = lineRange(line)
            if (paras[line].list != ListType.NONE && range.isEmpty()) {
                return withParas(line..line) { it.copy(list = ListType.NONE) }
            }
        }
        // Backspace at the start of a list item first removes the bullet.
        if (inserted.isEmpty() && removedEnd - prefix == 1 && text[prefix] == '\n') {
            val next = lineIndexAt(prefix) + 1
            if (next < paras.size && paras[next].list != ListType.NONE && lineRange(next).first == prefix + 1 && newText.length == text.length - 1) {
                // Only treat it as "remove bullet" when the cursor was at the start of that list line.
                return withParas(next..next) { it.copy(list = ListType.NONE) }
            }
        }
        return replace(prefix, removedEnd, inserted, typingStyle)
    }

    fun toggleInline(start: Int, end: Int, attr: InlineAttr): RichText {
        if (start >= end) return this
        val range = start until end
        val allOn = range.filter { text[it] != '\n' }.all { styles[it].has(attr) }
        return mapStyles(range) { it.with(attr, !allOn) }
    }

    fun setSize(start: Int, end: Int, sizePt: Float?): RichText =
        if (start >= end) this else mapStyles(start until end) { it.copy(sizePt = sizePt) }

    fun clearFormatting(start: Int, end: Int): RichText =
        if (start >= end) this else mapStyles(start until end) { CharStyle.PLAIN }

    private fun mapStyles(range: IntRange, f: (CharStyle) -> CharStyle): RichText {
        val newStyles = styles.toMutableList()
        for (i in range) if (text[i] != '\n') newStyles[i] = f(newStyles[i])
        return RichText(text, newStyles, paras)
    }

    /** Paragraph indices touched by the selection [start, end]. */
    fun paragraphsIn(start: Int, end: Int): IntRange = lineIndexAt(start)..lineIndexAt(maxOf(start, end))

    fun withParas(lines: IntRange, f: (ParaAttrs) -> ParaAttrs): RichText {
        val newParas = paras.toMutableList()
        for (i in lines) if (i in newParas.indices) newParas[i] = f(newParas[i])
        return RichText(text, styles, newParas)
    }

    fun setParagraphStyle(start: Int, end: Int, style: ParagraphStyle): RichText {
        val lines = paragraphsIn(start, end)
        val allSame = lines.all { paras[it].style == style }
        val target = if (allSame) ParagraphStyle.NORMAL else style
        return withParas(lines) { it.copy(style = target, list = if (target == ParagraphStyle.NORMAL) it.list else ListType.NONE) }
    }

    fun toggleList(start: Int, end: Int, type: ListType): RichText {
        val lines = paragraphsIn(start, end)
        val allSame = lines.all { paras[it].list == type }
        return withParas(lines) {
            it.copy(list = if (allSame) ListType.NONE else type, style = if (allSame) it.style else ParagraphStyle.NORMAL)
        }
    }

    fun setAlignment(start: Int, end: Int, alignment: Alignment): RichText =
        withParas(paragraphsIn(start, end)) { it.copy(alignment = alignment) }

    fun findAll(query: String, ignoreCase: Boolean = true): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var i = text.indexOf(query, 0, ignoreCase)
        while (i >= 0) {
            out += i until i + query.length
            i = text.indexOf(query, i + query.length, ignoreCase)
        }
        return out
    }

    fun replaceAll(query: String, replacement: String, ignoreCase: Boolean = true): Pair<RichText, Int> {
        val hits = findAll(query, ignoreCase)
        var model = this
        for (h in hits.asReversed()) model = model.replace(h.first, h.last + 1, replacement, model.styleAt(h.first))
        return model to hits.size
    }

    fun toDocument(lossy: Boolean = false): TextDocument {
        val starts = lineStarts()
        val paragraphs = paras.indices.map { i ->
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] - 1 else text.length
            val runs = ArrayList<TextRun>()
            var runStart = start
            for (k in start + 1..end) {
                if (k == end || styles[k] != styles[runStart]) {
                    if (k > runStart) {
                        val st = styles[runStart]
                        runs += TextRun(text.substring(runStart, k), st.bold, st.italic, st.underline, st.strike, st.sizePt)
                    }
                    runStart = k
                }
            }
            Paragraph(runs, paras[i].style, paras[i].list, paras[i].alignment).normalized()
        }
        return TextDocument(paragraphs, lossy)
    }

    override fun equals(other: Any?) =
        other is RichText && other.text == text && other.styles == styles && other.paras == paras

    override fun hashCode() = text.hashCode()

    companion object {
        fun empty() = RichText("", emptyList(), listOf(ParaAttrs()))

        fun plain(text: String) = RichText(text, List(text.length) { CharStyle.PLAIN }, List(text.count { it == '\n' } + 1) { ParaAttrs() })

        fun fromDocument(doc: TextDocument): RichText {
            if (doc.paragraphs.isEmpty()) return empty()
            val sb = StringBuilder()
            val styles = ArrayList<CharStyle>()
            val paras = ArrayList<ParaAttrs>()
            doc.paragraphs.forEachIndexed { i, p ->
                if (i > 0) {
                    sb.append('\n'); styles.add(CharStyle.PLAIN)
                }
                for (r in p.runs) {
                    // Line breaks inside a run become paragraph breaks in the editor.
                    val st = CharStyle.of(r)
                    for (ch in r.text) {
                        if (ch == '\n') {
                            sb.append('\n'); styles.add(CharStyle.PLAIN)
                            paras.add(ParaAttrs(p.style, p.list, p.alignment))
                        } else {
                            sb.append(ch); styles.add(st)
                        }
                    }
                }
                paras.add(ParaAttrs(p.style, p.list, p.alignment))
            }
            return RichText(sb.toString(), styles, paras)
        }
    }
}

fun CharStyle.has(attr: InlineAttr) = when (attr) {
    InlineAttr.BOLD -> bold
    InlineAttr.ITALIC -> italic
    InlineAttr.UNDERLINE -> underline
    InlineAttr.STRIKE -> strike
}

fun CharStyle.with(attr: InlineAttr, on: Boolean) = when (attr) {
    InlineAttr.BOLD -> copy(bold = on)
    InlineAttr.ITALIC -> copy(italic = on)
    InlineAttr.UNDERLINE -> copy(underline = on)
    InlineAttr.STRIKE -> copy(strike = on)
}
