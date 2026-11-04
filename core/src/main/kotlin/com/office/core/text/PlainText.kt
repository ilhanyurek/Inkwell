package com.office.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextDecoding {
    /**
     * Decodes bytes using BOM detection, then strict UTF-8, falling back to
     * Windows-1254 (Turkish) which covers most legacy files our users will meet.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            val legacy = runCatching { Charset.forName("windows-1254") }.getOrDefault(Charsets.ISO_8859_1)
            String(bytes, legacy)
        }
    }

    fun normalizeNewlines(s: String) = s.replace("\r\n", "\n").replace('\r', '\n')
}

object PlainTextCodec {
    fun read(bytes: ByteArray): TextDocument {
        val text = TextDecoding.normalizeNewlines(TextDecoding.decode(bytes))
        return TextDocument(text.split('\n').map { Paragraph.plain(it) })
    }

    fun write(doc: TextDocument): ByteArray =
        doc.paragraphs.joinToString("\n") { p ->
            when (p.list) {
                ListType.BULLET -> "• " + doc.readableText(p)
                else -> doc.readableText(p)
            }
        }.toByteArray(Charsets.UTF_8)
}

/**
 * A deliberately small Markdown subset: headings, bullet/numbered lists, quotes,
 * **bold**, *italic*, ~~strike~~. Round-trips what the editor can produce.
 */
object MarkdownCodec {
    private val numbered = Regex("^\\d+[.)]\\s+")

    fun read(bytes: ByteArray): TextDocument {
        val text = TextDecoding.normalizeNewlines(TextDecoding.decode(bytes))
        return TextDocument(text.split('\n').map(::parseLine))
    }

    private fun parseLine(line: String): Paragraph {
        var body = line
        var style = ParagraphStyle.NORMAL
        var list = ListType.NONE
        when {
            body.startsWith("### ") -> { style = ParagraphStyle.HEADING3; body = body.substring(4) }
            body.startsWith("## ") -> { style = ParagraphStyle.HEADING2; body = body.substring(3) }
            body.startsWith("# ") -> { style = ParagraphStyle.HEADING1; body = body.substring(2) }
            body.startsWith("> ") -> { style = ParagraphStyle.QUOTE; body = body.substring(2) }
            body.startsWith("- ") || body.startsWith("* ") || body.startsWith("+ ") -> {
                list = ListType.BULLET; body = body.substring(2)
            }
            numbered.containsMatchIn(body) -> { list = ListType.NUMBERED; body = body.replaceFirst(numbered, "") }
        }
        return Paragraph(parseInline(body), style, list).normalized()
    }

    fun parseInline(s: String): List<TextRun> {
        val runs = ArrayList<TextRun>()
        var bold = false
        var italic = false
        var strike = false
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                runs += TextRun(buf.toString(), bold = bold, italic = italic, strike = strike)
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '\\' && i + 1 < s.length -> { buf.append(s[i + 1]); i += 2 }
                s.startsWith("**", i) && (bold || s.indexOf("**", i + 2) > 0) -> { flush(); bold = !bold; i += 2 }
                s.startsWith("~~", i) && (strike || s.indexOf("~~", i + 2) > 0) -> { flush(); strike = !strike; i += 2 }
                (s[i] == '*' || s[i] == '_') && (italic || s.indexOf(s[i], i + 1) > 0) &&
                    !(s[i] == '_' && !italic && i > 0 && s[i - 1].isLetterOrDigit()) -> {
                    flush(); italic = !italic; i += 1
                }
                else -> { buf.append(s[i]); i++ }
            }
        }
        flush()
        return runs
    }

    fun write(doc: TextDocument): ByteArray {
        var number = 0
        val lines = doc.paragraphs.map { p ->
            number = if (p.list == ListType.NUMBERED) number + 1 else 0
            val prefix = when {
                p.style == ParagraphStyle.HEADING1 || p.style == ParagraphStyle.TITLE -> "# "
                p.style == ParagraphStyle.HEADING2 -> "## "
                p.style == ParagraphStyle.HEADING3 -> "### "
                p.style == ParagraphStyle.QUOTE -> "> "
                p.list == ListType.BULLET -> "- "
                p.list == ListType.NUMBERED -> "$number. "
                else -> ""
            }
            prefix + p.runs.filter { it.embedId == null || doc.embeds[it.embedId]?.kind == com.office.core.text.EmbedKind.LINK }.joinToString("") { r ->
                var t = r.embedId?.let { doc.embeds[it]?.label } ?: r.text.replace("*", "\\*").replace("_", "\\_").replace("~", "\\~")
                if (t.isBlank()) return@joinToString t
                if (r.strike) t = "~~$t~~"
                if (r.italic) t = "*$t*"
                if (r.bold) t = "**$t**"
                t
            }
        }
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }
}
