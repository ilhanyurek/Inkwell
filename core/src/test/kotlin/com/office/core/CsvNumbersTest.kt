package com.office.core

import com.office.core.csv.Csv
import com.office.core.formula.FormulaEngine
import com.office.core.sheet.CellRef
import org.junit.Assert.assertEquals
import org.junit.Test

/** Numbers in CSV files written the Turkish way (1.250,50) or the English way (1,250.50) are numbers. */
class CsvNumbersTest {
    @Test fun groupedNumbersAreNumbers() {
        assertEquals(1250.5, FormulaEngine.parseNumber("1.250,50")!!, 1e-9)
        assertEquals(-1234567.25, FormulaEngine.parseNumber("-1.234.567,25")!!, 1e-9)
        assertEquals(1250.5, FormulaEngine.parseNumber("1,250.50")!!, 1e-9)
        assertEquals(3.5, FormulaEngine.parseNumber("3,5")!!, 1e-9)
        assertEquals(null, FormulaEngine.parseNumber("1.25.0,5"))
    }

    @Test fun csvColumnAddsUp() {
        val wb = Csv.toWorkbook(Csv.parse("Ad;Tutar\nŞule;1.250,50\nÖmer;980,00\nİpek;310,20\n"))
        val sheet = wb.sheets[0]
        sheet[CellRef(4, 1)] = "=SUM(B2:B4)"
        assertEquals("2540.7", FormulaEngine(wb).value(sheet, CellRef(4, 1)).display())
        // The cells keep what the file said, so saving writes it back unchanged.
        assertEquals("1.250,50", sheet[CellRef(1, 1)])
    }
}
