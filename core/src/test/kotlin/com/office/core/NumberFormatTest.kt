package com.office.core

import com.office.core.sheet.NumberFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NumberFormatTest {
    private val en = Locale.US
    private val tr = Locale.forLanguageTag("tr-TR")

    @Test fun numbers() {
        assertEquals("1,234.57", NumberFormat.format(1234.567, "#,##0.00", en))
        assertEquals("1.234,57", NumberFormat.format(1234.567, "#,##0.00", tr))
        assertEquals("42", NumberFormat.format(41.6, "0", en))
        assertEquals("12.5%", NumberFormat.format(0.125, "0.0%", en))
        assertEquals("1,234.50 ₺", NumberFormat.format(1234.5, "#,##0.00 \"₺\"", en))
        assertEquals("$1,234.50", NumberFormat.format(1234.5, "\"$\"#,##0.00", en))
        assertEquals("(1,234)", NumberFormat.format(-1234.0, "#,##0 ;(#,##0)", en))
        assertEquals("1.5", NumberFormat.format(1.5, null, en))
    }

    @Test fun dates() {
        assertEquals("15.01.2025", NumberFormat.format(45672.0, "dd.mm.yyyy", en))
        assertEquals("2025-01-15", NumberFormat.format(45672.0, "yyyy-mm-dd", en))
        assertEquals("13:30", NumberFormat.format(45672.5625, "hh:mm", en))
        assertEquals("15 Jan 2025", NumberFormat.format(45672.0, "d mmm yyyy", en))
        assertEquals("1/15/25", NumberFormat.format(45672.0, "m/d/yy", en))
    }

    @Test fun parsesTypedInput() {
        assertEquals(45672.0 to "dd.mm.yyyy", NumberFormat.parseInput("15.01.2025"))
        assertEquals(45672.0 to "yyyy-mm-dd", NumberFormat.parseInput("2025-01-15"))
        assertEquals(0.25 to "0%", NumberFormat.parseInput("25%"))
        assertEquals(1234.5 to "#,##0.00 \"₺\"", NumberFormat.parseInput("1.234,50 ₺"))
        assertEquals(null, NumberFormat.parseInput("hello"))
    }
}
