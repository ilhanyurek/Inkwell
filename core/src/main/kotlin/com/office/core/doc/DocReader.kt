package com.office.core.doc

import com.office.core.docx.DocxTable
import com.office.core.ole.Bin
import com.office.core.ole.Cfb
import com.office.core.text.Alignment
import com.office.core.text.EMBED_CHAR
import com.office.core.text.Embed
import com.office.core.text.ListType
import com.office.core.text.Paragraph
import com.office.core.text.ParagraphStyle
import com.office.core.text.TextDocument
import com.office.core.text.TextRun

/** A legacy file is password protected; the app shows its own message for this. */
class EncryptedFileException : Exception("The file is password protected")

/**
 * Reads Word 97-2003 (.doc) files: text from the piece table, character formatting, paragraph styles,
 * alignment, lists and tables. Pictures, headers, footnotes and fields other than their shown text are
 * not kept, so the result is marked lossy.
 */
object DocReader {

    fun read(bytes: ByteArray): TextDocument {
        val streams = Cfb.read(bytes)
        val word = streams["WordDocument"] ?: throw IllegalArgumentException("Not a Word document")
        val w = Bin(word)
        require(w.u16(0) == 0xA5EC) { "Not a Word document" }
        require(w.u16(2) >= 101) { "Word 6 and older files are not supported" }
        val flags = w.u16(0x0A)
        if (flags and 0x0100 != 0) throw EncryptedFileException()
        val table = streams[if (flags and 0x0200 != 0) "1Table" else "0Table"] ?: throw IllegalArgumentException("Missing table stream")
        val t = Bin(table)

        val ccpText = w.i32(0x4C)
        fun fcLcb(off: Int) = w.i32(off) to w.u32(off + 4).toInt()
        val (fcClx, lcbClx) = fcLcb(0x01A2)

        // Text with the file position of every character, from the piece table.
        val text = StringBuilder()
        val fcs = ArrayList<Int>()
        readPieces(t, word, fcClx, lcbClx, ccpText, text, fcs)

        val chpx = runCatching { readChpx(t, w, fcLcb(0x00FA)) }.getOrDefault(emptyList())
        val papx = runCatching { readPapx(t, w, fcLcb(0x0102)) }.getOrDefault(emptyList())
        val styles = runCatching { readStyles(t, fcLcb(0x00A2)) }.getOrDefault(emptyMap())
        val lists = runCatching { readLists(t, fcLcb(0x02E2), fcLcb(0x02EA)) }.getOrDefault(emptyMap())

        return build(text, fcs, chpx, papx, styles, lists)
    }

    // --- text ------------------------------------------------------------------------------------

    private fun readPieces(t: Bin, word: ByteArray, fcClx: Int, lcbClx: Int, ccpText: Int, text: StringBuilder, fcs: MutableList<Int>) {
        var pos = fcClx
        val end = fcClx + lcbClx
        var guard = 0
        while (pos < end && t.u8(pos) == 0x01 && guard++ < 10_000) pos += 3 + t.i16(pos + 1).coerceAtLeast(0)
        require(t.u8(pos) == 0x02) { "Missing piece table" }
        val lcb = t.i32(pos + 1)
        val plc = pos + 5
        val n = (lcb - 4) / 12
        require(n in 1..1_000_000) { "Bad piece table" }
        val cps = IntArray(n + 1) { t.i32(plc + it * 4) }
        val wb = Bin(word)
        for (i in 0 until n) {
            val cpStart = cps[i]
            if (cpStart >= ccpText) break
            val cpEnd = minOf(cps[i + 1], ccpText)
            val pcd = plc + (n + 1) * 4 + i * 8
            val fcRaw = t.i32(pcd + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = if (compressed) (fcRaw and 0x3FFFFFFF) / 2 else fcRaw
            for (k in 0 until cpEnd - cpStart) {
                if (compressed) {
                    val o = fc + k
                    text.append(cp1252(wb.u8(o)))
                    fcs += o
                } else {
                    val o = fc + k * 2
                    text.append(wb.u16(o).toChar())
                    fcs += o
                }
            }
        }
    }

    private val CP1252_80 = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"

    private fun cp1252(b: Int): Char = if (b in 0x80..0x9F) CP1252_80[b - 0x80] else b.toChar()

    // --- formatting tables -----------------------------------------------------------------------

    private class Range(val start: Int, val end: Int, val sprms: ByteArray, val istd: Int = 0)

    private fun bteEntries(t: Bin, plc: Pair<Int, Int>): List<Int> {
        val (fc, lcb) = plc
        if (lcb <= 4) return emptyList()
        val n = (lcb - 4) / 8
        return (0 until n).map { t.i32(fc + (n + 1) * 4 + it * 4) and 0x3FFFFF }
    }

    private fun readChpx(t: Bin, w: Bin, plc: Pair<Int, Int>): List<Range> {
        val out = ArrayList<Range>()
        for (pn in bteEntries(t, plc)) {
            val page = pn * 512
            val crun = w.u8(page + 511)
            for (i in 0 until crun) {
                val start = w.i32(page + i * 4)
                val end = w.i32(page + (i + 1) * 4)
                val off = w.u8(page + (crun + 1) * 4 + i) * 2
                val sprms = if (off == 0) ByteArray(0) else {
                    val cb = w.u8(page + off)
                    w.b.copyOfRange((page + off + 1).coerceAtMost(w.b.size), (page + off + 1 + cb).coerceAtMost(w.b.size))
                }
                out += Range(start, end, sprms)
            }
        }
        return out.sortedBy { it.start }
    }

    private fun readPapx(t: Bin, w: Bin, plc: Pair<Int, Int>): List<Range> {
        val out = ArrayList<Range>()
        for (pn in bteEntries(t, plc)) {
            val page = pn * 512
            val cpara = w.u8(page + 511)
            for (i in 0 until cpara) {
                val start = w.i32(page + i * 4)
                val end = w.i32(page + (i + 1) * 4)
                val off = w.u8(page + (cpara + 1) * 4 + i * 13) * 2
                if (off == 0) { out += Range(start, end, ByteArray(0)); continue }
                var p = page + off
                var cb = w.u8(p)
                val size = if (cb == 0) { p++; cb = w.u8(p); cb * 2 } else cb * 2 - 1
                val istd = w.u16(p + 1)
                val sprms = w.b.copyOfRange((p + 3).coerceAtMost(w.b.size), (p + 1 + size).coerceIn(p + 3, w.b.size).coerceAtMost(w.b.size))
                out += Range(start, end, sprms, istd)
            }
        }
        return out.sortedBy { it.start }
    }

    private fun find(ranges: List<Range>, fc: Int): Range? {
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = ranges[mid]
            when {
                fc < r.start -> hi = mid - 1
                fc >= r.end -> lo = mid + 1
                else -> return r
            }
        }
        return null
    }

    /** Calls [f] with (sprm, operand offset, operand bytes) for each property in [grpprl]. */
    private fun sprms(grpprl: ByteArray, f: (Int, Bin, Int) -> Unit) {
        val b = Bin(grpprl)
        var i = 0
        while (i + 2 <= grpprl.size) {
            val sprm = b.u16(i)
            i += 2
            val len = when (sprm ushr 13) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                7 -> 3
                else -> if (sprm == 0xD608 || sprm == 0xC615) b.u16(i) + 1 else b.u8(i) + 1
            }
            if (i + len > grpprl.size) break
            f(sprm, b, i)
            i += len
        }
    }

    private class StyleInfo(val name: String, val sti: Int, val sprms: ByteArray)

    private fun readStyles(t: Bin, plc: Pair<Int, Int>): Map<Int, StyleInfo> {
        val (fc, lcb) = plc
        if (lcb <= 2) return emptyMap()
        val cbStshi = t.u16(fc)
        val stshi = fc + 2
        val cstd = t.u16(stshi)
        val cbBase = t.u16(stshi + 2)
        var pos = stshi + cbStshi
        val out = HashMap<Int, StyleInfo>()
        for (istd in 0 until cstd) {
            val cbStd = t.u16(pos)
            val std = pos + 2
            if (cbStd > 0) {
                val sti = t.u16(std) and 0x0FFF
                val name = std + cbBase
                val cch = t.u16(name).coerceIn(0, 200)
                val chars = String(CharArray(cch) { t.u16(name + 2 + it * 2).toChar() })
                // UPX after the name: for paragraph styles the first is the paragraph properties (istd + grpprl).
                var upx = name + 2 + (cch + 1) * 2
                if ((upx - std) % 2 == 1) upx++
                val cbUpx = t.u16(upx)
                val stk = t.u16(std + 2) and 0x0F
                val sprms = if (stk == 1 && cbUpx >= 2) t.b.copyOfRange((upx + 4).coerceAtMost(t.b.size), (upx + 2 + cbUpx).coerceAtMost(t.b.size)) else ByteArray(0)
                out[istd] = StyleInfo(chars, sti, sprms)
            }
            pos = std + cbStd
        }
        return out
    }

    /** ilfo (1-based) → list type of level 0. */
    private fun readLists(t: Bin, lst: Pair<Int, Int>, lfo: Pair<Int, Int>): Map<Int, ListType> {
        val (fcLst, lcbLst) = lst
        val (fcLfo, lcbLfo) = lfo
        if (lcbLst < 2 || lcbLfo < 4) return emptyMap()
        val cLst = t.i16(fcLst)
        val lsidToType = HashMap<Int, ListType>()
        var lvl = fcLst + 2 + cLst * 28
        for (i in 0 until cLst) {
            val lstf = fcLst + 2 + i * 28
            val lsid = t.i32(lstf)
            val simple = t.u8(lstf + 26) and 0x01 != 0
            val levels = if (simple) 1 else 9
            for (l in 0 until levels) {
                if (l == 0) lsidToType[lsid] = if (t.u8(lvl + 4) == 23) ListType.BULLET else ListType.NUMBERED
                val cbChpx = t.u8(lvl + 24)
                val cbPapx = t.u8(lvl + 25)
                val xst = lvl + 28 + cbPapx + cbChpx
                lvl = xst + 2 + t.u16(xst) * 2
            }
        }
        val count = t.i32(fcLfo)
        val out = HashMap<Int, ListType>()
        for (i in 0 until count.coerceIn(0, 10_000)) {
            val lsid = t.i32(fcLfo + 4 + i * 16)
            lsidToType[lsid]?.let { out[i + 1] = it }
        }
        return out
    }

    // --- building the document --------------------------------------------------------------------

    private class Pap(
        var istd: Int = 0, var align: Alignment? = null, var ilfo: Int = 0,
        var inTable: Boolean = false, var rowEnd: Boolean = false,
    )

    private fun pap(r: Range?, styles: Map<Int, StyleInfo>): Pap {
        val p = Pap(istd = r?.istd ?: 0)
        fun apply(grpprl: ByteArray) = sprms(grpprl) { sprm, b, o ->
            when (sprm) {
                0x2403, 0x2461 -> p.align = when (b.u8(o)) { 1 -> Alignment.CENTER; 2 -> Alignment.END; 3, 4 -> Alignment.JUSTIFY; else -> Alignment.START }
                0x460B -> p.ilfo = b.i16(o)
                0x2416 -> p.inTable = b.u8(o) != 0
                0x2417 -> p.rowEnd = b.u8(o) != 0
                0x6649 -> if (b.i32(o) > 0) p.inTable = true
                0x244B -> if (b.u8(o) != 0) { p.inTable = true }
                0x244C -> if (b.u8(o) != 0) p.rowEnd = true
            }
        }
        styles[p.istd]?.let { apply(it.sprms) }
        r?.let { apply(it.sprms) }
        return p
    }

    private fun styleOf(info: StyleInfo?): ParagraphStyle {
        val name = info?.name?.lowercase()?.trim().orEmpty()
        return when {
            info?.sti == 1 || name == "heading 1" -> ParagraphStyle.HEADING1
            info?.sti == 2 || name == "heading 2" -> ParagraphStyle.HEADING2
            info?.sti in 3..9 || Regex("heading [3-9]").matches(name) -> ParagraphStyle.HEADING3
            info?.sti == 62 || name == "title" -> ParagraphStyle.TITLE
            name == "quote" || name == "intense quote" -> ParagraphStyle.QUOTE
            else -> ParagraphStyle.NORMAL
        }
    }

    private fun runStyle(r: Range?): TextRun {
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var size: Float? = null
        if (r != null) sprms(r.sprms) { sprm, b, o ->
            fun on() = b.u8(o).let { it == 1 || it == 0x81 }
            when (sprm) {
                0x0835 -> bold = on()
                0x0836 -> italic = on()
                0x0837 -> strike = on()
                0x2A3E -> underline = b.u8(o) != 0
                0x4A43 -> size = b.u16(o) / 2f
            }
        }
        return TextRun("", bold, italic, underline, strike, size?.takeIf { it != 11f && it != 12f && it != 10f })
    }

    private fun build(
        text: CharSequence,
        fcs: List<Int>,
        chpx: List<Range>,
        papx: List<Range>,
        styles: Map<Int, StyleInfo>,
        lists: Map<Int, ListType>,
    ): TextDocument {
        val paragraphs = ArrayList<Paragraph>()
        val embeds = LinkedHashMap<Int, Embed>()
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()

        fun flushTable() {
            if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); cell.setLength(0); rows += row; row = ArrayList() }
            if (rows.isEmpty()) return
            val cols = rows.maxOf { it.size }.coerceAtLeast(1)
            val embed = DocxTable.embed(DocxTable.create(rows.size, cols, rows))
            val id = embeds.size + 1
            embeds[id] = embed
            paragraphs += Paragraph(listOf(TextRun(EMBED_CHAR.toString(), embedId = id)))
            rows.clear()
        }

        var fieldDepth = 0
        val fieldShowing = ArrayList<Boolean>()
        var start = 0
        var i = 0
        while (i <= text.length) {
            val c = if (i < text.length) text[i] else '\r'
            val isEnd = c == '\r' || c == '\u0007' || c == '\u000C' || c == '\u000B'
            if (!isEnd) { i++; continue }
            val markFc = if (i < fcs.size) fcs[i] else -1
            val p = pap(find(papx, markFc), styles)

            // Character runs of this paragraph, without field codes and control characters.
            val runs = ArrayList<TextRun>()
            val plain = StringBuilder()
            for (k in start until minOf(i, text.length)) {
                val ch = text[k]
                when (ch) {
                    '\u0013' -> { fieldDepth++; fieldShowing += false; continue }
                    '\u0014' -> { if (fieldShowing.isNotEmpty()) fieldShowing[fieldShowing.size - 1] = true; continue }
                    '\u0015' -> { if (fieldDepth > 0) { fieldDepth--; fieldShowing.removeAt(fieldShowing.size - 1) }; continue }
                }
                if (fieldShowing.any { !it }) continue
                val out = when {
                    ch == '\u001E' -> '-'
                    ch == '\t' -> '\t'
                    ch < ' ' || ch == '\u001F' -> null
                    else -> ch
                } ?: continue
                plain.append(out)
                val st = runStyle(find(chpx, fcs[k]))
                val last = runs.lastOrNull()
                if (last != null && last.sameStyle(st)) runs[runs.size - 1] = last.copy(text = last.text + out)
                else runs += st.copy(text = out.toString())
            }

            if (p.inTable && c == '\u0007' && p.rowEnd) {
                if (cell.isNotEmpty()) { row += cell.toString(); cell.setLength(0) }
                rows += row
                row = ArrayList()
            } else if (p.inTable) {
                if (cell.isNotEmpty()) cell.append('\n')
                cell.append(plain.toString().trim())
                if (c == '\u0007') { row += cell.toString(); cell.setLength(0) }
            } else {
                flushTable()
                val list = if (p.ilfo > 0) lists[p.ilfo] ?: ListType.BULLET else ListType.NONE
                val style = styleOf(styles[p.istd])
                paragraphs += Paragraph(runs, style = style, list = list, alignment = p.align ?: Alignment.START).normalized()
            }
            start = i + 1
            i++
            if (i > text.length) break
        }
        flushTable()
        // Word ends the main text with a paragraph mark; drop the empty paragraph it leaves behind.
        while (paragraphs.size > 1 && paragraphs.last().runs.isEmpty()) paragraphs.removeAt(paragraphs.size - 1)
        if (paragraphs.isEmpty()) paragraphs += Paragraph(emptyList())
        return TextDocument(paragraphs, lossyImport = true, embeds = embeds)
    }
}
