package com.office.core

import com.office.core.formula.FormulaEngine
import com.office.core.sheet.CellRef
import com.office.core.sheet.Sheet
import com.office.core.sheet.Workbook
import org.junit.Assert.assertEquals
import org.junit.Test

class FormulaTest {
    private fun sheetOf(vararg cells: Pair<String, String>): Pair<Workbook, Sheet> {
        val s = Sheet("Sheet1")
        for ((k, v) in cells) s[CellRef.parse(k)!!] = v
        return Workbook(mutableListOf(s)) to s
    }

    private fun eval(formula: String, vararg cells: Pair<String, String>): String {
        val (wb, s) = sheetOf(*cells)
        return FormulaEngine(wb).evaluateFormula(s, formula).display()
    }

    @Test fun arithmeticAndPrecedence() {
        assertEquals("7", eval("1+2*3"))
        assertEquals("9", eval("(1+2)*3"))
        assertEquals("4", eval("-2^2"))
        assertEquals("0.5", eval("50%"))
        assertEquals("#DIV/0!", eval("1/0"))
        assertEquals("0.3", eval("0.1+0.2"))
    }

    @Test fun references() {
        assertEquals("15", eval("SUM(A1:A5)", "A1" to "1", "A2" to "2", "A3" to "3", "A4" to "4", "A5" to "5"))
        assertEquals("3", eval("AVERAGE(A1:A3)", "A1" to "2", "A2" to "x", "A3" to "4"))
        assertEquals("2", eval("COUNT(A1:A3)", "A1" to "2", "A2" to "x", "A3" to "4"))
        assertEquals("3", eval("COUNTA(A1:A3)", "A1" to "2", "A2" to "x", "A3" to "4"))
        assertEquals("10", eval("A1*B1", "A1" to "2", "B1" to "5"))
        assertEquals("3.5", eval("A1+1", "A1" to "2,5"))
    }

    @Test fun logicAndText() {
        assertEquals("big", eval("IF(A1>10,\"big\",\"small\")", "A1" to "11"))
        assertEquals("TRUE", eval("AND(1,TRUE)"))
        assertEquals("ab", eval("CONCAT(\"a\";\"b\")"))
        assertEquals("ALİ".length.toString(), eval("LEN(\"ALİ\")"))
        assertEquals("0", eval("IFERROR(1/0,0)"))
        assertEquals("3.14", eval("ROUND(PI(),2)"))
        assertEquals("ell", eval("MID(\"hello\",2,3)"))
    }

    @Test fun turkishAliases() {
        assertEquals("6", eval("topla(1;2;3)"))
        assertEquals("evet", eval("eğer(1=1;\"evet\";\"hayır\")"))
        assertEquals("TRUE", eval("değil(yanlış)"))
    }

    @Test fun conditionalAggregates() {
        val cells = arrayOf("A1" to "5", "A2" to "15", "A3" to "25", "B1" to "1", "B2" to "2", "B3" to "3")
        assertEquals("2", eval("COUNTIF(A1:A3,\">10\")", *cells))
        assertEquals("5", eval("SUMIF(A1:A3,\">10\",B1:B3)", *cells))
    }

    @Test fun detectsCycles() {
        val (wb, s) = sheetOf("A1" to "=B1", "B1" to "=A1+1")
        assertEquals("#CYCLE!", FormulaEngine(wb).display(s, CellRef.parse("A1")!!))
    }

    @Test fun crossSheet() {
        val a = Sheet("Data"); a[CellRef(0, 0)] = "21"
        val b = Sheet("Calc"); b[CellRef(0, 0)] = "=Data!A1*2"
        val wb = Workbook(mutableListOf(a, b))
        assertEquals("42", FormulaEngine(wb).display(b, CellRef(0, 0)))
    }

    @Test fun unknownFunctionUsesCachedValue() {
        val (wb, s) = sheetOf("A1" to "=VLOOKUP(1,B1:C2,2,FALSE)")
        s.cachedValues[CellRef(0, 0)] = "hit"
        assertEquals("hit", FormulaEngine(wb).display(s, CellRef(0, 0)))
    }

    @Test fun cellRefNames() {
        assertEquals("AA10", CellRef(9, 26).toString())
        assertEquals(CellRef(0, 701), CellRef.parse("ZZ1"))
    }
}
