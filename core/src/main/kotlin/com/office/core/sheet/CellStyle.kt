package com.office.core.sheet

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

enum class HAlign { LEFT, CENTER, RIGHT }

/** Visual formatting of a cell. Colours are ARGB. */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val fontColor: Long? = null,
    val fontSizePt: Float? = null,
    val fill: Long? = null,
    /** Thin border on all four sides. */
    val border: Boolean = false,
    val hAlign: HAlign? = null,
    val wrap: Boolean = false,
    /** Excel number format code, e.g. `#,##0.00` or `dd.mm.yyyy`; null means General. */
    val numFmt: String? = null,
    /**
     * Index of the cellXfs record this style was read from. Kept while the style is unchanged so
     * saving reuses the original record (with any properties we do not model).
     */
    val sourceXf: Int? = null,
) {
    val isDefault: Boolean get() = this == DEFAULT

    /** The same style with [sourceXf] cleared, for when the user changes it. */
    fun edited(): CellStyle = copy(sourceXf = null)

    companion object {
        val DEFAULT = CellStyle()
    }
}

/** Renders numbers with a useful subset of Excel number format codes. */
object NumberFormat {

    /** Formats shown in the format picker. */
    val PRESETS = listOf(
        "General" to null,
        "0" to "0",
        "0.00" to "0.00",
        "1,234" to "#,##0",
        "1,234.56" to "#,##0.00",
        "%" to "0%",
        "%.00" to "0.00%",
        "₺" to "#,##0.00 \"₺\"",
        "$" to "\"$\"#,##0.00",
        "€" to "#,##0.00 \"€\"",
        "Date" to "dd.mm.yyyy",
        "ISO date" to "yyyy-mm-dd",
        "Time" to "hh:mm",
        "Text" to "@",
    )

    private val BUILTIN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%",
        11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??", 14 to "dd.mm.yyyy", 15 to "d-mmm-yy", 16 to "d-mmm",
        17 to "mmm-yy", 18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "hh:mm", 21 to "hh:mm:ss",
        22 to "dd.mm.yyyy hh:mm", 37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)",
        40 to "#,##0.00;[Red](#,##0.00)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@",
    )

    fun builtin(id: Int): String? = BUILTIN[id]

    /** Ids of formats Excel has built in, so we do not redeclare them. */
    fun builtinId(code: String): Int? = BUILTIN.entries.firstOrNull { it.value == code }?.key

    private fun stripLiterals(code: String) = code.replace(Regex("\"[^\"]*\"|\\\\.|\\[[^]]*]|_.|\\*."), "")

    fun isDate(code: String?): Boolean {
        if (code == null) return false
        val s = stripLiterals(code).lowercase()
        return s.any { it == 'd' || it == 'y' } || ((s.contains('h') || s.contains('s')) && s.contains(':')) ||
            s.contains("am/pm")
    }

    fun format(value: Double, code: String?, locale: Locale = Locale.getDefault()): String {
        if (code == null || code.equals("General", true) || code == "@") return Value.formatNumber(value)
        if (value.isNaN() || value.isInfinite()) return Value.formatNumber(value)
        val sections = splitSections(code)
        val section = when {
            value < 0 && sections.size > 1 -> sections[1]
            value == 0.0 && sections.size > 2 -> sections[2]
            else -> sections[0]
        }
        val v = if (value < 0 && sections.size > 1) -value else value
        return runCatching {
            if (isDate(section)) formatDate(v, section, locale) else formatNumber(v, section, locale)
        }.getOrElse { Value.formatNumber(value) }
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (c in code) {
            if (c == '"') q = !q
            if (c == ';' && !q) { out += sb.toString(); sb.setLength(0) } else sb.append(c)
        }
        out += sb.toString()
        return out
    }

    private fun formatNumber(value: Double, section: String, locale: Locale): String {
        // Separate literal text from the numeric pattern.
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        val pattern = StringBuilder()
        var i = 0
        var seenDigit = false
        var percent = false
        var scientific = false
        while (i < section.length) {
            val c = section[i]
            val target = if (seenDigit) suffix else prefix
            when {
                c == '"' -> {
                    val end = section.indexOf('"', i + 1).let { if (it < 0) section.length else it }
                    target.append(section, i + 1, end)
                    i = end + 1
                    continue
                }
                c == '[' -> {
                    val end = section.indexOf(']', i).let { if (it < 0) section.length - 1 else it }
                    val inner = section.substring(i + 1, end)
                    // Currency tags look like [$₺-41F]; colours and conditions are dropped.
                    if (inner.startsWith("$")) target.append(inner.substring(1).substringBefore('-'))
                    i = end + 1
                    continue
                }
                c == '\\' && i + 1 < section.length -> { target.append(section[i + 1]); i += 2; continue }
                c == '_' || c == '*' -> { if (c == '_') target.append(' '); i += 2; continue }
                c == '0' || c == '#' || c == '?' || c == ',' || c == '.' -> {
                    if (c != ',' || seenDigit || section.indexOfAny(charArrayOf('0', '#'), i) >= 0) {
                        pattern.append(if (c == '?') '#' else c)
                        if (c == '0' || c == '#' || c == '?') seenDigit = true
                    }
                }
                c == '%' -> { percent = true; target.append('%') }
                (c == 'E' || c == 'e') && seenDigit -> { scientific = true; pattern.append('E'); if (i + 1 < section.length && (section[i + 1] == '+' || section[i + 1] == '-')) i++ }
                else -> target.append(c)
            }
            i++
        }
        var v = if (percent) value * 100 else value
        var pat = pattern.toString().trimEnd(',')
        // Trailing commas scale by 1000 each.
        val scale = pattern.length - pattern.toString().trimEnd(',').length
        repeat(scale) { v /= 1000 }
        if (pat.isEmpty()) pat = "0"
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val formatted = if (scientific) {
            DecimalFormat(pat.replace("E", "E0").replace("E00", "E0"), symbols).format(v)
        } else {
            DecimalFormat(pat, symbols).apply { roundingMode = RoundingMode.HALF_UP }.format(v)
        }
        return prefix.toString() + formatted + suffix.toString()
    }

    private fun formatDate(serial: Double, section: String, locale: Locale): String {
        val base = LocalDateTime.of(1899, 12, 30, 0, 0)
        val days = floor(serial).toLong()
        val secs = ((serial - days) * 86400).roundToLong()
        val dt = base.plusDays(days).plusSeconds(secs)
        val hasAmPm = section.contains("AM/PM", ignoreCase = true) || section.contains("A/P", ignoreCase = true)
        val sb = StringBuilder()
        var i = 0
        val lower = section.lowercase()
        fun run(ch: Char): Int { var n = 0; while (i + n < lower.length && lower[i + n] == ch) n++; return n }
        var lastWasHour = false
        while (i < section.length) {
            val c = lower[i]
            when {
                section[i] == '"' -> {
                    val end = section.indexOf('"', i + 1).let { if (it < 0) section.length else it }
                    sb.append('\'').append(section.substring(i + 1, end).replace("'", "''")).append('\'')
                    i = end + 1
                    continue
                }
                section[i] == '[' -> { // elapsed hours [h] and colour tags
                    val end = section.indexOf(']', i).let { if (it < 0) section.length - 1 else it }
                    val inner = section.substring(i + 1, end).lowercase()
                    if (inner.startsWith("h")) sb.append(abs(floor(serial * 24).toLong()))
                    i = end + 1
                    continue
                }
                section[i] == '\\' && i + 1 < section.length -> { sb.append('\'').append(section[i + 1]).append('\''); i += 2; continue }
                lower.startsWith("am/pm", i) -> { sb.append('a'); i += 5; continue }
                lower.startsWith("a/p", i) -> { sb.append('a'); i += 3; continue }
                c == 'y' -> { val n = run('y'); sb.append(if (n <= 2) "yy" else "yyyy"); i += n; lastWasHour = false; continue }
                c == 'd' -> { val n = run('d'); sb.append(when (n) { 1 -> "d"; 2 -> "dd"; 3 -> "EEE"; else -> "EEEE" }); i += n; lastWasHour = false; continue }
                c == 'h' -> { val n = run('h'); sb.append(if (hasAmPm) (if (n == 1) "h" else "hh") else (if (n == 1) "H" else "HH")); i += n; lastWasHour = true; continue }
                c == 's' -> { val n = run('s'); sb.append(if (n == 1) "s" else "ss"); i += n; lastWasHour = false; continue }
                c == 'm' -> {
                    val n = run('m')
                    // "m" after an hour or before seconds means minutes.
                    val rest = lower.substring(i + n).trimStart(':', ' ')
                    val minutes = n <= 2 && (lastWasHour || rest.startsWith("s"))
                    sb.append(
                        if (minutes) (if (n == 1) "m" else "mm")
                        else when (n) { 1 -> "M"; 2 -> "MM"; 3 -> "MMM"; 4 -> "MMMM"; else -> "MMMMM" },
                    )
                    i += n
                    lastWasHour = false
                    continue
                }
                c.isLetter() -> { sb.append('\'').append(section[i]).append('\''); i++; continue }
                else -> { if (c == '\'') sb.append("''") else sb.append(section[i]); i++; continue }
            }
        }
        return DateTimeFormatter.ofPattern(sb.toString(), locale).format(dt)
    }

    /** Converts what a user typed into a number and a suggested format (dates, percentages, currency). */
    fun parseInput(text: String): Pair<Double, String?>? {
        val t = text.trim()
        Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{4})$""").matchEntire(t)?.let { m ->
            val (d, mo, y) = m.destructured
            return runCatching { serial(y.toInt(), mo.toInt(), d.toInt()) to "dd.mm.yyyy" }.getOrNull()
        }
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").matchEntire(t)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { serial(y.toInt(), mo.toInt(), d.toInt()) to "yyyy-mm-dd" }.getOrNull()
        }
        Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(t)?.let { m ->
            val (h, mi) = m.destructured
            if (h.toInt() < 24 && mi.toInt() < 60) return (h.toInt() * 60 + mi.toInt()) / 1440.0 to "hh:mm"
        }
        Regex("""^-?\d+([.,]\d+)?%$""").matchEntire(t)?.let {
            return t.dropLast(1).replace(',', '.').toDouble() / 100 to (if (t.contains('.') || t.contains(',')) "0.00%" else "0%")
        }
        Regex("""^(-?[\d.,]+)\s*(₺|TL)$""").matchEntire(t)?.let { m ->
            parseLocalized(m.groupValues[1])?.let { return it to "#,##0.00 \"₺\"" }
        }
        Regex("""^([$€])\s*(-?[\d.,]+)$""").matchEntire(t)?.let { m ->
            parseLocalized(m.groupValues[2])?.let { return it to if (m.groupValues[1] == "$") "\"$\"#,##0.00" else "#,##0.00 \"€\"" }
        }
        return null
    }

    /** Accepts both 1,234.56 and 1.234,56. */
    private fun parseLocalized(s: String): Double? {
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lastComma >= 0 -> if (s.length - lastComma == 4 && s.count { it == ',' } >= 1 && lastComma > 0) s.replace(",", "") else s.replace(',', '.')
            else -> s
        }
        return normalized.toDoubleOrNull()
    }

    fun serial(year: Int, month: Int, day: Int): Double =
        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(1899, 12, 30), java.time.LocalDate.of(year, month, day)).toDouble()
}
