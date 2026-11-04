package com.office.core.util

/** A line-level difference between two texts (longest common subsequence). */
object TextDiff {

    enum class Kind { SAME, ADDED, REMOVED }

    data class Line(val kind: Kind, val text: String)

    fun lines(a: String, b: String): List<Line> = diff(split(a), split(b))

    fun diff(a: List<String>, b: List<String>): List<Line> {
        // Trim the common head and tail so the quadratic table only covers the changed middle.
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val am = a.subList(head, a.size - tail)
        val bm = b.subList(head, b.size - tail)
        val out = ArrayList<Line>()
        a.subList(0, head).forEach { out += Line(Kind.SAME, it) }
        if (am.size.toLong() * bm.size > 4_000_000L) {
            // Too large for a table: report the middle as replaced.
            am.forEach { out += Line(Kind.REMOVED, it) }
            bm.forEach { out += Line(Kind.ADDED, it) }
        } else {
            val lcs = Array(am.size + 1) { IntArray(bm.size + 1) }
            for (i in am.indices.reversed()) for (j in bm.indices.reversed()) {
                lcs[i][j] = if (am[i] == bm[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < am.size && j < bm.size) {
                when {
                    am[i] == bm[j] -> { out += Line(Kind.SAME, am[i]); i++; j++ }
                    lcs[i + 1][j] >= lcs[i][j + 1] -> { out += Line(Kind.REMOVED, am[i]); i++ }
                    else -> { out += Line(Kind.ADDED, bm[j]); j++ }
                }
            }
            while (i < am.size) out += Line(Kind.REMOVED, am[i++])
            while (j < bm.size) out += Line(Kind.ADDED, bm[j++])
        }
        a.subList(a.size - tail, a.size).forEach { out += Line(Kind.SAME, it) }
        return out
    }

    private fun split(text: String): List<String> =
        text.replace("\r\n", "\n").split('\n').map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
}
