package com.office.core.formula

import com.office.core.sheet.CellRef

/** Formula syntax tree. */
sealed class Expr {
    data class Num(val v: Double) : Expr()
    data class Str(val v: String) : Expr()
    data class Bool(val v: Boolean) : Expr()
    data class Ref(val sheet: String?, val ref: CellRef) : Expr()
    data class Range(val sheet: String?, val from: CellRef, val to: CellRef) : Expr()
    data class Unary(val op: Char, val expr: Expr) : Expr()
    data class Percent(val expr: Expr) : Expr()
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
    data class Call(val name: String, val args: List<Expr>) : Expr()
    data class Error(val code: String) : Expr()
}

class FormulaSyntaxException(message: String) : Exception(message)

private sealed class Tok {
    data class Num(val v: Double) : Tok()
    data class Str(val v: String) : Tok()
    data class Ident(val v: String) : Tok()
    /** A sheet-qualified prefix like `Sheet1!` or `'My sheet'!`. */
    data class SheetPrefix(val v: String) : Tok()
    data class Op(val v: String) : Tok()
    data class Err(val v: String) : Tok()
    object LParen : Tok()
    object RParen : Tok()
    object Sep : Tok()
    object Colon : Tok()
}

object FormulaParser {

    /** Parses formula text without the leading `=`. */
    fun parse(text: String): Expr {
        val tokens = tokenize(text)
        val p = P(tokens)
        val e = p.comparison()
        if (!p.atEnd()) throw FormulaSyntaxException("Unexpected token near end")
        return e
    }

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length &&
                        (s[i + 1].isDigit() || ((s[i + 1] == '+' || s[i + 1] == '-') && i + 2 < s.length && s[i + 2].isDigit()))
                    ) {
                        i += 2
                        while (i < s.length && s[i].isDigit()) i++
                    }
                    // A number directly followed by letters (e.g. "1A") is not valid; a digit-led identifier is never a ref.
                    out += Tok.Num(s.substring(start, i).toDoubleOrNull() ?: throw FormulaSyntaxException("Bad number"))
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (true) {
                        if (i >= s.length) throw FormulaSyntaxException("Unterminated string")
                        if (s[i] == '"') {
                            if (i + 1 < s.length && s[i + 1] == '"') { sb.append('"'); i += 2; continue }
                            i++
                            break
                        }
                        sb.append(s[i++])
                    }
                    out += Tok.Str(sb.toString())
                }
                c == '\'' -> {
                    val end = s.indexOf('\'', i + 1)
                    if (end < 0 || end + 1 >= s.length || s[end + 1] != '!') throw FormulaSyntaxException("Bad sheet name")
                    out += Tok.SheetPrefix(s.substring(i + 1, end))
                    i = end + 2
                }
                c == '#' -> {
                    val start = i
                    i++
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '/' || s[i] == '!' || s[i] == '?')) {
                        val ch = s[i++]
                        if (ch == '!' || ch == '?') break
                    }
                    out += Tok.Err(s.substring(start, i).uppercase())
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.' || s[i] == '$')) i++
                    val word = s.substring(start, i)
                    if (i < s.length && s[i] == '!') {
                        out += Tok.SheetPrefix(word)
                        i++
                    } else {
                        out += Tok.Ident(word)
                    }
                }
                c == '(' -> { out += Tok.LParen; i++ }
                c == ')' -> { out += Tok.RParen; i++ }
                c == ',' || c == ';' -> { out += Tok.Sep; i++ }
                c == ':' -> { out += Tok.Colon; i++ }
                c == '<' && i + 1 < s.length && (s[i + 1] == '=' || s[i + 1] == '>') -> { out += Tok.Op(s.substring(i, i + 2)); i += 2 }
                c == '>' && i + 1 < s.length && s[i + 1] == '=' -> { out += Tok.Op(">="); i += 2 }
                c in "+-*/^&=<>%" -> { out += Tok.Op(c.toString()); i++ }
                else -> throw FormulaSyntaxException("Unexpected character '$c'")
            }
        }
        return out
    }

    private class P(val t: List<Tok>) {
        var pos = 0
        fun atEnd() = pos >= t.size
        fun peek(): Tok? = t.getOrNull(pos)
        fun peekOp(vararg ops: String): String? = (peek() as? Tok.Op)?.v?.takeIf { it in ops }

        fun comparison(): Expr {
            var left = concat()
            while (true) {
                val op = peekOp("=", "<>", "<", ">", "<=", ">=") ?: return left
                pos++
                left = Expr.Binary(op, left, concat())
            }
        }

        fun concat(): Expr {
            var left = additive()
            while (peekOp("&") != null) {
                pos++
                left = Expr.Binary("&", left, additive())
            }
            return left
        }

        fun additive(): Expr {
            var left = multiplicative()
            while (true) {
                val op = peekOp("+", "-") ?: return left
                pos++
                left = Expr.Binary(op, left, multiplicative())
            }
        }

        fun multiplicative(): Expr {
            var left = power()
            while (true) {
                val op = peekOp("*", "/") ?: return left
                pos++
                left = Expr.Binary(op, left, power())
            }
        }

        fun power(): Expr {
            var left = unary()
            while (peekOp("^") != null) {
                pos++
                left = Expr.Binary("^", left, unary())
            }
            return left
        }

        fun unary(): Expr {
            val op = peekOp("+", "-")
            if (op != null) {
                pos++
                val inner = unary()
                return if (op == "-") Expr.Unary('-', inner) else inner
            }
            return postfix()
        }

        fun postfix(): Expr {
            var e = primary()
            while (peekOp("%") != null) {
                pos++
                e = Expr.Percent(e)
            }
            return e
        }

        fun primary(): Expr {
            val tok = peek() ?: throw FormulaSyntaxException("Unexpected end of formula")
            pos++
            return when (tok) {
                is Tok.Num -> Expr.Num(tok.v)
                is Tok.Str -> Expr.Str(tok.v)
                is Tok.Err -> Expr.Error(tok.v)
                is Tok.LParen -> {
                    val e = comparison()
                    expect(Tok.RParen)
                    e
                }
                is Tok.SheetPrefix -> {
                    val id = peek() as? Tok.Ident ?: throw FormulaSyntaxException("Expected reference after sheet name")
                    pos++
                    reference(tok.v, id.v)
                }
                is Tok.Ident -> {
                    if (peek() == Tok.LParen) {
                        pos++
                        val args = ArrayList<Expr>()
                        if (peek() != Tok.RParen) {
                            while (true) {
                                // Allow empty arguments like IF(A1,,1)
                                if (peek() == Tok.Sep || peek() == Tok.RParen) args += Expr.Str("")
                                else args += comparison()
                                if (peek() == Tok.Sep) { pos++; continue }
                                break
                            }
                        }
                        expect(Tok.RParen)
                        Expr.Call(tok.v.uppercase(java.util.Locale.ROOT), args)
                    } else {
                        when (tok.v.uppercase(java.util.Locale.ROOT)) {
                            "TRUE", "DOĞRU", "DOGRU" -> Expr.Bool(true)
                            "FALSE", "YANLIŞ", "YANLIS" -> Expr.Bool(false)
                            else -> reference(null, tok.v)
                        }
                    }
                }
                else -> throw FormulaSyntaxException("Unexpected token")
            }
        }

        private fun reference(sheet: String?, first: String): Expr {
            val from = CellRef.parse(first) ?: return Expr.Error("#NAME?")
            if (peek() == Tok.Colon) {
                pos++
                val second = peek() as? Tok.Ident ?: throw FormulaSyntaxException("Expected range end")
                pos++
                val to = CellRef.parse(second.v) ?: throw FormulaSyntaxException("Bad range end")
                return Expr.Range(
                    sheet,
                    CellRef(minOf(from.row, to.row), minOf(from.col, to.col)),
                    CellRef(maxOf(from.row, to.row), maxOf(from.col, to.col)),
                )
            }
            return Expr.Ref(sheet, from)
        }

        private fun expect(tok: Tok) {
            if (peek() != tok) throw FormulaSyntaxException("Expected ${tok::class.simpleName}")
            pos++
        }
    }
}
