package com.office.core.util

/**
 * Parses page selections such as "1-3, 5, 8-" into zero-based page indexes, in the order written and without
 * duplicates. "8-" runs to the last page, "-3" starts at the first. Returns null when the text is not valid.
 */
object PageRanges {

    fun parse(text: String, pageCount: Int): List<Int>? {
        if (pageCount <= 0) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val out = LinkedHashSet<Int>()
        for (raw in trimmed.split(',', ';')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            if (dash < 0) {
                val n = part.toIntOrNull() ?: return null
                if (n !in 1..pageCount) return null
                out += n - 1
            } else {
                val a = part.substring(0, dash).trim()
                val b = part.substring(dash + 1).trim()
                val from = if (a.isEmpty()) 1 else a.toIntOrNull() ?: return null
                val to = if (b.isEmpty()) pageCount else b.toIntOrNull() ?: return null
                if (from !in 1..pageCount || to !in 1..pageCount) return null
                val range = if (from <= to) from..to else from downTo to
                for (n in range) out += n - 1
            }
        }
        return out.toList().ifEmpty { null }
    }

    /** Formats zero-based indexes back to a compact selection, e.g. [0,1,2,4] → "1-3, 5". */
    fun format(pages: List<Int>): String {
        if (pages.isEmpty()) return ""
        val sorted = pages.distinct().sorted()
        val parts = ArrayList<String>()
        var start = sorted[0]
        var prev = start
        for (p in sorted.drop(1) + Int.MIN_VALUE) {
            if (p == prev + 1) { prev = p; continue }
            parts += if (start == prev) "${start + 1}" else "${start + 1}-${prev + 1}"
            start = p
            prev = p
        }
        return parts.joinToString(", ")
    }
}
