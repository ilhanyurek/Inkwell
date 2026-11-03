package com.office.core.formula

import com.office.core.sheet.CellRef
import com.office.core.sheet.Evaluator
import com.office.core.sheet.Sheet
import com.office.core.sheet.Value
import com.office.core.sheet.Workbook
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Evaluates cells of a [Workbook]. Results are memoized until [invalidate] is called,
 * which the editor does after every change.
 */
class FormulaEngine(private val workbook: Workbook) : Evaluator {

    private val cache = IdentityHashMap<Sheet, HashMap<CellRef, Value>>()
    private val inProgress = IdentityHashMap<Sheet, HashSet<CellRef>>()
    private val parsed = HashMap<String, Expr>()

    fun invalidate() = cache.clear()

    override fun value(sheet: Sheet, ref: CellRef): Value {
        cache[sheet]?.get(ref)?.let { return it }
        val visiting = inProgress.getOrPut(sheet) { HashSet() }
        if (!visiting.add(ref)) return Value.Err(CYCLE)
        val result = try {
            compute(sheet, ref)
        } finally {
            visiting.remove(ref)
        }
        cache.getOrPut(sheet) { HashMap() }[ref] = result
        return result
    }

    fun display(sheet: Sheet, ref: CellRef): String = value(sheet, ref).display()

    private fun compute(sheet: Sheet, ref: CellRef): Value {
        val raw = sheet[ref]
        if (raw.isEmpty()) return Value.Empty
        if (raw.length > 1 && raw[0] == '=') {
            val result = evaluateFormula(sheet, raw.substring(1))
            // Fall back to the value Excel stored if we hit something we do not implement.
            if (result is Value.Err && result.code == NAME) {
                sheet.cachedValues[ref]?.let { return literal(it) }
            }
            return result
        }
        return literal(raw)
    }

    /** Evaluates an arbitrary formula (without `=`) in the context of [sheet]. */
    fun evaluateFormula(sheet: Sheet, formula: String): Value {
        val expr = parsed[formula] ?: try {
            FormulaParser.parse(formula).also { if (parsed.size < 10_000) parsed[formula] = it }
        } catch (_: FormulaSyntaxException) {
            return Value.Err("#ERROR!")
        }
        return try {
            when (val v = eval(sheet, expr)) {
                is Range -> v.single()
                is Value -> v
                else -> Value.Err(VALUE)
            }
        } catch (_: StackOverflowError) {
            Value.Err(CYCLE)
        }
    }

    // --- evaluation -----------------------------------------------------------------

    /** A rectangular set of cells; only valid as a function argument. */
    private inner class Range(val sheet: Sheet, val from: CellRef, val to: CellRef) {
        fun values(): Sequence<Value> = sequence {
            // Clamp to the used area so `A:A`-like large ranges stay cheap.
            val lastRow = minOf(to.row, sheet.maxRow)
            val lastCol = minOf(to.col, sheet.maxCol)
            for (r in from.row..lastRow) for (c in from.col..lastCol) yield(value(sheet, CellRef(r, c)))
        }

        fun single(): Value =
            if (from == to) value(sheet, from) else Value.Err(VALUE)
    }

    private fun resolveSheet(current: Sheet, name: String?): Sheet? =
        if (name == null) current else workbook.sheet(name)

    private fun eval(sheet: Sheet, e: Expr): Any = when (e) {
        is Expr.Num -> Value.Num(e.v)
        is Expr.Str -> Value.Text(e.v)
        is Expr.Bool -> Value.Bool(e.v)
        is Expr.Error -> Value.Err(e.code)
        is Expr.Ref -> resolveSheet(sheet, e.sheet)?.let { value(it, e.ref) } ?: Value.Err(REF)
        is Expr.Range -> resolveSheet(sheet, e.sheet)?.let { Range(it, e.from, e.to) } ?: Value.Err(REF)
        is Expr.Unary -> {
            val v = scalar(sheet, e.expr)
            num(v).let { if (it is Value.Num) Value.Num(-it.v) else it }
        }
        is Expr.Percent -> num(scalar(sheet, e.expr)).let { if (it is Value.Num) Value.Num(it.v / 100) else it }
        is Expr.Binary -> binary(sheet, e)
        is Expr.Call -> call(sheet, e)
    }

    private fun scalar(sheet: Sheet, e: Expr): Value = when (val v = eval(sheet, e)) {
        is Range -> v.single()
        is Value -> v
        else -> Value.Err(VALUE)
    }

    private fun binary(sheet: Sheet, e: Expr.Binary): Value {
        val l = scalar(sheet, e.left)
        val r = scalar(sheet, e.right)
        if (l is Value.Err) return l
        if (r is Value.Err) return r
        return when (e.op) {
            "&" -> Value.Text(text(l) + text(r))
            "=", "<>", "<", ">", "<=", ">=" -> {
                val c = compare(l, r)
                Value.Bool(
                    when (e.op) {
                        "=" -> c == 0
                        "<>" -> c != 0
                        "<" -> c < 0
                        ">" -> c > 0
                        "<=" -> c <= 0
                        else -> c >= 0
                    },
                )
            }
            else -> {
                val a = num(l)
                val b = num(r)
                if (a !is Value.Num) return a
                if (b !is Value.Num) return b
                val x = a.v
                val y = b.v
                when (e.op) {
                    "+" -> Value.Num(x + y)
                    "-" -> Value.Num(x - y)
                    "*" -> Value.Num(x * y)
                    "/" -> if (y == 0.0) Value.Err(DIV0) else Value.Num(x / y)
                    "^" -> x.pow(y).let { if (it.isNaN()) Value.Err(NUM) else Value.Num(it) }
                    else -> Value.Err(VALUE)
                }
            }
        }
    }

    private fun compare(a: Value, b: Value): Int {
        fun rank(v: Value) = when (v) {
            is Value.Num, Value.Empty -> 0
            is Value.Text -> 1
            is Value.Bool -> 2
            is Value.Err -> 3
        }
        // Empty compares equal to both 0 and "".
        if (a == Value.Empty && b is Value.Text) return "".compareTo(b.v, ignoreCase = true)
        if (b == Value.Empty && a is Value.Text) return a.v.compareTo("", ignoreCase = true)
        val ra = rank(a)
        val rb = rank(b)
        if (ra != rb) return ra.compareTo(rb)
        return when (a) {
            is Value.Num, Value.Empty -> toDouble(a).compareTo(toDouble(b))
            is Value.Text -> a.v.compareTo((b as Value.Text).v, ignoreCase = true)
            is Value.Bool -> a.v.compareTo((b as Value.Bool).v)
            else -> 0
        }
    }

    private fun toDouble(v: Value) = (v as? Value.Num)?.v ?: 0.0

    // --- functions --------------------------------------------------------------------

    private fun call(sheet: Sheet, e: Expr.Call): Value {
        val name = ALIASES[e.name.replace('İ', 'I')] ?: e.name
        val args = e.args
        fun arg(i: Int): Value = if (i < args.size) scalar(sheet, args[i]) else Value.Empty
        fun numArg(i: Int): Value = num(arg(i))

        return when (name) {
            "SUM" -> aggregate(sheet, args) { it.sum() }
            "AVERAGE" -> aggregate(sheet, args) { if (it.isEmpty()) null else it.average() }
            "MIN" -> aggregate(sheet, args) { it.minOrNull() ?: 0.0 }
            "MAX" -> aggregate(sheet, args) { it.maxOrNull() ?: 0.0 }
            "PRODUCT" -> aggregate(sheet, args) { l -> l.fold(1.0) { a, b -> a * b } }
            "COUNT" -> Value.Num(flatten(sheet, args).count { it is Value.Num }.toDouble())
            "COUNTA" -> Value.Num(flatten(sheet, args).count { it != Value.Empty }.toDouble())
            "COUNTBLANK" -> Value.Num(flattenAll(sheet, args).count { it == Value.Empty || (it is Value.Text && it.v.isEmpty()) }.toDouble())
            "IF" -> {
                val cond = truthy(arg(0))
                when {
                    cond is Value.Err -> cond
                    (cond as Value.Bool).v -> if (args.size > 1) arg(1) else Value.Bool(true)
                    else -> if (args.size > 2) arg(2) else Value.Bool(false)
                }
            }
            "IFERROR" -> arg(0).let { if (it is Value.Err) arg(1) else it }
            "AND", "OR" -> {
                val bools = flatten(sheet, args).filter { it != Value.Empty && it !is Value.Text }.map { truthy(it) }
                bools.firstOrNull { it is Value.Err }
                    ?: if (bools.isEmpty()) Value.Err(VALUE)
                    else Value.Bool(
                        if (name == "AND") bools.all { (it as Value.Bool).v } else bools.any { (it as Value.Bool).v },
                    )
            }
            "NOT" -> truthy(arg(0)).let { if (it is Value.Bool) Value.Bool(!it.v) else it }
            "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                val x = numArg(0); if (x !is Value.Num) return x
                val d = if (args.size > 1) numArg(1) else Value.Num(0.0); if (d !is Value.Num) return d
                val mode = when (name) {
                    "ROUND" -> RoundingMode.HALF_UP
                    "ROUNDUP" -> RoundingMode.UP
                    else -> RoundingMode.DOWN
                }
                Value.Num(BigDecimal.valueOf(x.v).setScale(d.v.toInt(), mode).toDouble())
            }
            "ABS" -> unaryNum(numArg(0)) { abs(it) }
            "INT" -> unaryNum(numArg(0)) { floor(it) }
            "SQRT" -> numArg(0).let { if (it is Value.Num && it.v < 0) Value.Err(NUM) else unaryNum(it) { v -> sqrt(v) } }
            "POWER" -> {
                val a = numArg(0); val b = numArg(1)
                if (a !is Value.Num) a else if (b !is Value.Num) b else Value.Num(a.v.pow(b.v))
            }
            "MOD" -> {
                val a = numArg(0); val b = numArg(1)
                when {
                    a !is Value.Num -> a
                    b !is Value.Num -> b
                    b.v == 0.0 -> Value.Err(DIV0)
                    else -> Value.Num(a.v - b.v * floor(a.v / b.v))
                }
            }
            "PI" -> Value.Num(Math.PI)
            "CONCAT", "CONCATENATE" -> {
                val parts = flattenAll(sheet, args)
                parts.firstOrNull { it is Value.Err } ?: Value.Text(parts.joinToString("") { text(it) })
            }
            "LEN" -> arg(0).let { if (it is Value.Err) it else Value.Num(text(it).length.toDouble()) }
            "UPPER" -> textFn(arg(0)) { it.uppercase() }
            "LOWER" -> textFn(arg(0)) { it.lowercase() }
            "TRIM" -> textFn(arg(0)) { it.trim().replace(Regex(" +"), " ") }
            "LEFT", "RIGHT" -> {
                val s = arg(0); if (s is Value.Err) return s
                val n = if (args.size > 1) numArg(1) else Value.Num(1.0); if (n !is Value.Num) return n
                val str = text(s)
                val k = n.v.toInt().coerceIn(0, str.length)
                Value.Text(if (name == "LEFT") str.take(k) else str.takeLast(k))
            }
            "MID" -> {
                val s = arg(0); if (s is Value.Err) return s
                val start = numArg(1); if (start !is Value.Num) return start
                val len = numArg(2); if (len !is Value.Num) return len
                val str = text(s)
                val from = (start.v.toInt() - 1).coerceAtLeast(0)
                if (from >= str.length) Value.Text("") else Value.Text(str.substring(from, minOf(str.length, from + len.v.toInt().coerceAtLeast(0))))
            }
            "SUMIF", "COUNTIF", "AVERAGEIF" -> conditional(sheet, name, args)
            else -> Value.Err(NAME)
        }
    }

    private fun conditional(sheet: Sheet, name: String, args: List<Expr>): Value {
        if (args.size < 2) return Value.Err(VALUE)
        val range = eval(sheet, args[0]) as? Range ?: return Value.Err(VALUE)
        val criterion = scalar(sheet, args[1])
        val sumRange = if (args.size > 2) eval(sheet, args[2]) as? Range ?: return Value.Err(VALUE) else range
        val matcher = criteria(criterion)
        var total = 0.0
        var count = 0
        val lastRow = minOf(range.to.row, range.sheet.maxRow)
        for (r in range.from.row..lastRow) for (c in range.from.col..range.to.col) {
            val v = value(range.sheet, CellRef(r, c))
            if (!matcher(v)) continue
            count++
            val target = value(sumRange.sheet, CellRef(sumRange.from.row + (r - range.from.row), sumRange.from.col + (c - range.from.col)))
            if (target is Value.Num) total += target.v
        }
        return when (name) {
            "COUNTIF" -> Value.Num(count.toDouble())
            "SUMIF" -> Value.Num(total)
            else -> if (count == 0) Value.Err(DIV0) else Value.Num(total / count)
        }
    }

    private fun criteria(c: Value): (Value) -> Boolean {
        if (c is Value.Num) return { it is Value.Num && it.v == c.v }
        if (c is Value.Bool) return { it is Value.Bool && it.v == c.v }
        val s = text(c)
        val ops = listOf("<=", ">=", "<>", "<", ">", "=")
        val op = ops.firstOrNull { s.startsWith(it) }
        val operand = if (op != null) s.substring(op.length) else s
        val opNum = parseNumber(operand)
        return { v ->
            val cmp: Int? = if (opNum != null) (v as? Value.Num)?.v?.compareTo(opNum)
            else if (operand.contains('*') || operand.contains('?')) {
                val regex = Regex(
                    operand.map { ch ->
                        when (ch) {
                            '*' -> ".*"
                            '?' -> "."
                            else -> Regex.escape(ch.toString())
                        }
                    }.joinToString(""),
                    RegexOption.IGNORE_CASE,
                )
                if (regex.matches(text(v))) 0 else 1
            } else text(v).compareTo(operand, ignoreCase = true)
            when (op) {
                null, "=" -> cmp == 0
                "<>" -> cmp != 0
                "<" -> cmp != null && cmp < 0
                ">" -> cmp != null && cmp > 0
                "<=" -> cmp != null && cmp <= 0
                else -> cmp != null && cmp >= 0
            }
        }
    }

    private inline fun aggregate(sheet: Sheet, args: List<Expr>, f: (List<Double>) -> Double?): Value {
        val nums = ArrayList<Double>()
        for (a in args) {
            when (val v = eval(sheet, a)) {
                is Range -> for (x in v.values()) {
                    when (x) {
                        is Value.Num -> nums += x.v
                        is Value.Err -> return x
                        else -> {} // text and blanks inside ranges are ignored, like Excel
                    }
                }
                is Value -> when (val n = num(v)) {
                    is Value.Num -> nums += n.v
                    is Value.Err -> return n
                    else -> {}
                }
            }
        }
        return f(nums)?.let { Value.Num(it) } ?: Value.Err(DIV0)
    }

    /** Non-empty values from ranges plus scalar arguments. */
    private fun flatten(sheet: Sheet, args: List<Expr>): List<Value> = flattenAll(sheet, args)

    private fun flattenAll(sheet: Sheet, args: List<Expr>): List<Value> {
        val out = ArrayList<Value>()
        for (a in args) {
            when (val v = eval(sheet, a)) {
                is Range -> {
                    // Include blanks inside the declared range for COUNTBLANK.
                    for (r in v.from.row..v.to.row.coerceAtMost(maxOf(v.from.row, v.sheet.maxRow)))
                        for (c in v.from.col..v.to.col.coerceAtMost(maxOf(v.from.col, v.sheet.maxCol)))
                            out += value(v.sheet, CellRef(r, c))
                }
                is Value -> out += v
            }
        }
        return out
    }

    private inline fun unaryNum(v: Value, f: (Double) -> Double): Value = if (v is Value.Num) Value.Num(f(v.v)) else v

    private inline fun textFn(v: Value, f: (String) -> String): Value = if (v is Value.Err) v else Value.Text(f(text(v)))

    // --- coercion -------------------------------------------------------------------

    private fun num(v: Value): Value = when (v) {
        is Value.Num -> v
        is Value.Bool -> Value.Num(if (v.v) 1.0 else 0.0)
        Value.Empty -> Value.Num(0.0)
        is Value.Text -> parseNumber(v.v)?.let { Value.Num(it) } ?: Value.Err(VALUE)
        is Value.Err -> v
    }

    private fun truthy(v: Value): Value = when (v) {
        is Value.Bool -> v
        is Value.Num -> Value.Bool(v.v != 0.0)
        Value.Empty -> Value.Bool(false)
        is Value.Text -> when (v.v.uppercase(Locale.ROOT)) {
            "TRUE" -> Value.Bool(true)
            "FALSE" -> Value.Bool(false)
            else -> Value.Err(VALUE)
        }
        is Value.Err -> v
    }

    private fun text(v: Value): String = v.display()

    companion object {
        const val DIV0 = "#DIV/0!"
        const val VALUE = "#VALUE!"
        const val REF = "#REF!"
        const val NAME = "#NAME?"
        const val NUM = "#NUM!"
        const val CYCLE = "#CYCLE!"

        /** Turkish Excel function names mapped to their English equivalents. */
        val ALIASES = mapOf(
            "TOPLA" to "SUM", "ORTALAMA" to "AVERAGE", "MİN" to "MIN", "MAK" to "MAX",
            "ÇARPIM" to "PRODUCT", "BAĞ_DEĞ_SAY" to "COUNT", "BAĞ_DEĞ_DOLU_SAY" to "COUNTA",
            "BOŞLUKSAY" to "COUNTBLANK", "EĞER" to "IF", "EĞERHATA" to "IFERROR", "VE" to "AND",
            "YADA" to "OR", "DEĞİL" to "NOT", "YUVARLA" to "ROUND", "YUKARIYUVARLA" to "ROUNDUP",
            "AŞAĞIYUVARLA" to "ROUNDDOWN", "MUTLAK" to "ABS", "TAMSAYI" to "INT", "KAREKÖK" to "SQRT",
            "KUVVET" to "POWER", "BİRLEŞTİR" to "CONCATENATE", "UZUNLUK" to "LEN", "BÜYÜKHARF" to "UPPER",
            "KÜÇÜKHARF" to "LOWER", "KIRP" to "TRIM", "SOLDAN" to "LEFT", "SAĞDAN" to "RIGHT",
            "PARÇAAL" to "MID", "ETOPLA" to "SUMIF", "EĞERSAY" to "COUNTIF", "EĞERORTALAMA" to "AVERAGEIF",
        ).mapKeys { it.key.replace('İ', 'I') }

        val FUNCTIONS = listOf(
            "SUM", "AVERAGE", "MIN", "MAX", "PRODUCT", "COUNT", "COUNTA", "COUNTBLANK", "IF", "IFERROR",
            "AND", "OR", "NOT", "ROUND", "ROUNDUP", "ROUNDDOWN", "ABS", "INT", "SQRT", "POWER", "MOD", "PI",
            "CONCAT", "CONCATENATE", "LEN", "UPPER", "LOWER", "TRIM", "LEFT", "RIGHT", "MID",
            "SUMIF", "COUNTIF", "AVERAGEIF",
        )

        private val decimalComma = Regex("^-?\\d+,\\d+$")

        /** Parses user-typed numbers: `12`, `-3.5`, `3,5` (Turkish decimal comma), `1e3`, `45%`. */
        fun parseNumber(s: String): Double? {
            val t = s.trim()
            if (t.isEmpty()) return null
            if (t.endsWith("%")) return parseNumber(t.dropLast(1))?.div(100)
            if (decimalComma.matches(t)) return t.replace(',', '.').toDoubleOrNull()
            if (t.any { it.isLetter() && it != 'e' && it != 'E' }) return null
            return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
        }

        fun literal(raw: String): Value {
            if (raw.startsWith("'")) return Value.Text(raw.substring(1))
            parseNumber(raw)?.let { return Value.Num(it) }
            return when (raw.uppercase(Locale.ROOT)) {
                "TRUE" -> Value.Bool(true)
                "FALSE" -> Value.Bool(false)
                else -> if (raw.startsWith("#") && raw.endsWith("!") || raw == NAME || raw == "#N/A") Value.Err(raw) else Value.Text(raw)
            }
        }
    }
}
