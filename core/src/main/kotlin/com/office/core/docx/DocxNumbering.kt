package com.office.core.docx

import com.office.core.text.ListLabel
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlParser
import com.office.core.zip.ZipPackage

/** List definitions from `word/numbering.xml` and the labels ("1.", "a)", "•") they produce. */
class DocxNumbering(pkg: ZipPackage?) {

    class Level(
        val start: Int,
        val format: String,
        val text: String,
        val restart: Int?,
        val pPr: XmlElement?,
        val font: String?,
    )

    private class Num(val abstractId: String, val startOverrides: Map<Int, Int>, val levelOverrides: Map<Int, Level>)

    private val abstracts = HashMap<String, Map<Int, Level>>()
    private val nums = HashMap<String, Num>()

    init {
        val root = runCatching { pkg?.xml(numberingPath(pkg)) }.getOrNull()
        if (root != null) {
            for (a in root.children("abstractNum")) {
                val id = a.attr("abstractNumId") ?: continue
                abstracts[id] = a.children("lvl").mapNotNull { l -> l.attr("ilvl")?.toIntOrNull()?.let { it to level(l) } }.toMap()
            }
            for (n in root.children("num")) {
                val id = n.attr("numId") ?: continue
                val abs = n.child("abstractNumId")?.attr("val") ?: continue
                val starts = HashMap<Int, Int>()
                val levels = HashMap<Int, Level>()
                for (o in n.children("lvlOverride")) {
                    val ilvl = o.attr("ilvl")?.toIntOrNull() ?: continue
                    o.child("startOverride")?.attr("val")?.toIntOrNull()?.let { starts[ilvl] = it }
                    o.child("lvl")?.let { levels[ilvl] = level(it) }
                }
                nums[id] = Num(abs, starts, levels)
            }
        }
    }

    private fun numberingPath(pkg: ZipPackage): String {
        val main = DocxReader.mainPart(pkg)
        return pkg.relationships(main).values.firstOrNull { it.type.endsWith("/numbering") }?.target ?: "word/numbering.xml"
    }

    private fun level(l: XmlElement) = Level(
        start = l.child("start")?.attr("val")?.toIntOrNull() ?: 1,
        format = l.child("numFmt")?.attr("val") ?: "decimal",
        text = l.child("lvlText")?.attr("val").orEmpty(),
        restart = l.child("lvlRestart")?.attr("val")?.toIntOrNull(),
        pPr = l.child("pPr"),
        font = l.path("rPr", "rFonts")?.let { it.attr("ascii") ?: it.attr("hAnsi") },
    )

    /** The level definition a paragraph with this numId/ilvl uses, for its indents. */
    fun level(numId: String, ilvl: Int): Level? {
        val num = nums[numId] ?: return null
        return num.levelOverrides[ilvl] ?: abstracts[num.abstractId]?.get(ilvl)
    }

    /** Labels for a sequence of (numId, ilvl) pairs in document order; null entries are not list items. */
    fun labels(items: List<Pair<String, Int>?>): List<ListLabel?> {
        val counters = HashMap<String, IntArray>()   // per abstract list; 0 = not started
        val seenNums = HashSet<String>()
        return items.map { item ->
            val (numId, ilvlRaw) = item ?: return@map null
            val num = nums[numId] ?: return@map null
            val ilvl = ilvlRaw.coerceIn(0, 8)
            val lvl = level(numId, ilvl) ?: return@map null
            val c = counters.getOrPut(num.abstractId) { IntArray(9) }
            if (seenNums.add(numId)) for ((l, s) in num.startOverrides) if (l in 0..8) c[l] = s - 1
            c[ilvl] = if (c[ilvl] == 0 && num.startOverrides[ilvl] == null) lvl.start else c[ilvl] + 1
            for (deeper in ilvl + 1..8) {
                val restart = level(numId, deeper)?.restart
                if (restart == null || (restart != 0 && ilvl < restart)) c[deeper] = 0
            }
            val text = if (lvl.format == "bullet") bullet(lvl) else {
                Regex("%(\\d)").replace(lvl.text) { m ->
                    val k = m.groupValues[1].toInt() - 1
                    val def = level(numId, k)
                    val value = if (c[k] == 0) def?.start ?: 1 else c[k]
                    format(value, def?.format ?: "decimal")
                }
            }
            ListLabel(text, ilvl)
        }
    }

    private fun bullet(lvl: Level): String {
        val t = lvl.text
        if (t.isEmpty()) return "•"
        val ch = t[0]
        val font = lvl.font.orEmpty().lowercase()
        return when {
            ch == '' || ch == '·' -> "•"
            ch == '' -> "▪"
            ch == '' -> "➢"
            ch == '' -> "✓"
            ch == '' -> "◆"
            ch == '' -> "❖"
            ch == 'o' && font.contains("courier") -> "○"
            ch in ''..'' -> "•"
            font.contains("wingdings") || font.contains("symbol") -> "•"
            else -> t
        }
    }

    companion object {
        fun format(n: Int, fmt: String): String = when (fmt) {
            "lowerLetter" -> letters(n)
            "upperLetter" -> letters(n).uppercase()
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "decimalZero" -> if (n < 10) "0$n" else n.toString()
            "none" -> ""
            else -> n.toString()
        }

        private fun letters(n: Int): String {
            if (n <= 0) return n.toString()
            val letter = 'a' + (n - 1) % 26
            return letter.toString().repeat((n - 1) / 26 + 1)
        }

        private fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            val sb = StringBuilder()
            var rest = n
            for (i in values.indices) while (rest >= values[i]) { sb.append(symbols[i]); rest -= values[i] }
            return sb.toString()
        }

        /** numId and ilvl of a `w:numPr` fragment. */
        fun parseNumPr(xml: String?): Pair<String, Int>? {
            if (xml.isNullOrEmpty()) return null
            val el = runCatching { XmlParser.parse(xml) }.getOrNull() ?: return null
            val numId = el.child("numId")?.attr("val") ?: return null
            if (numId == "0") return null
            return numId to (el.child("ilvl")?.attr("val")?.toIntOrNull() ?: 0)
        }
    }
}
