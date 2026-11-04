package com.office.core

import com.office.core.formula.FormulaEngine
import com.office.core.sheet.CellRef
import com.office.core.sheet.Sheet
import com.office.core.sheet.Workbook
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class MoreFunctionsTest {
    private val sheet = Sheet("Data").apply {
        val rows = listOf(
            listOf("Ürün", "Şehir", "Adet", "Fiyat"),
            listOf("Kalem", "Ankara", "10", "3.5"),
            listOf("Defter", "İzmir", "4", "12"),
            listOf("Silgi", "Ankara", "7", "2"),
            listOf("Kalem", "İzmir", "5", "3.5"),
        )
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, v -> this[CellRef(r, c)] = v } }
        this[CellRef(6, 0)] = "45672" // 2025-01-15
    }
    private val engine = FormulaEngine(Workbook(mutableListOf(sheet))) { LocalDateTime.of(2025, 3, 1, 18, 0) }
    private fun f(formula: String) = engine.evaluateFormula(sheet, formula).display()

    @Test fun lookups() {
        assertEquals("12", f("VLOOKUP(\"Defter\",A2:D5,4,FALSE)"))
        assertEquals("12", f("DÜŞEYARA(\"defter\";A2:D5;4;YANLIŞ)"))
        assertEquals("#N/A", f("VLOOKUP(\"Cetvel\",A2:D5,2,FALSE)"))
        assertEquals("İzmir", f("INDEX(A1:D5,3,2)"))
        assertEquals("3", f("MATCH(\"Defter\",A1:A5,0)"))
        assertEquals("Silgi", f("INDEX(A1:A5,MATCH(7,C1:C5,0))"))
        assertEquals("2", f("XLOOKUP(\"Silgi\",A2:A5,D2:D5)"))
        assertEquals("yok", f("XLOOKUP(\"Cetvel\",A2:A5,D2:D5,\"yok\")"))
        assertEquals("Adet", f("HLOOKUP(\"Adet\",A1:D5,1,FALSE)"))
        assertEquals("Defter", f("VLOOKUP(\"Def*\",A2:B5,1,FALSE)"))
    }

    @Test fun multiCriteria() {
        assertEquals("17", f("SUMIFS(C2:C5,B2:B5,\"Ankara\")"))
        assertEquals("10", f("SUMIFS(C2:C5,B2:B5,\"Ankara\",A2:A5,\"Kalem\")"))
        assertEquals("2", f("COUNTIFS(A2:A5,\"Kalem\")"))
        assertEquals("4.5", f("AVERAGEIFS(C2:C5,B2:B5,\"İzmir\")"))
        assertEquals("orta", f("IFS(C2>20,\"çok\",C2>5,\"orta\",TRUE,\"az\")"))
        assertEquals("6", f("MEDIAN(C2:C5)"))
    }

    @Test fun dates() {
        assertEquals("45717", f("TODAY()"))
        assertEquals("45672", f("DATE(2025,1,15)"))
        assertEquals("2025", f("YEAR(A7)"))
        assertEquals("1", f("MONTH(A7)"))
        assertEquals("15", f("GÜN(A7)"))
        assertEquals("4", f("WEEKDAY(A7)")) // Wednesday
        assertEquals("45", f("DATEDIF(A7,TODAY(),\"D\")"))
        assertEquals("1", f("DATEDIF(A7,TODAY(),\"M\")"))
        assertEquals("15.01.2025", f("TEXT(A7,\"dd.mm.yyyy\")"))
        assertEquals("1234.5", f("VALUE(\"1234,5\")"))
        assertEquals("45672", f("VALUE(\"15.01.2025\")"))
    }
}
