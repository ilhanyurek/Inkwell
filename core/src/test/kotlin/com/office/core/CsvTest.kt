package com.office.core

import com.office.core.csv.Csv
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun parsesQuotedFieldsAndNewlines() {
        val p = Csv.parse("a,\"b,c\",\"d \"\"q\"\"\"\r\n1,\"multi\nline\",3\n")
        assertEquals(',', p.delimiter)
        assertEquals(listOf(listOf("a", "b,c", "d \"q\""), listOf("1", "multi\nline", "3")), p.rows)
    }

    @Test fun detectsSemicolon() {
        val p = Csv.parse("ad;soyad;yaş\nAli;Yılmaz;30\nAyşe;Kaya;25")
        assertEquals(';', p.delimiter)
        assertEquals("Yılmaz", p.rows[1][1])
    }

    @Test fun detectsTab() {
        assertEquals('\t', Csv.parse("a\tb\tc\n1\t2\t3").delimiter)
    }

    @Test fun roundTrip() {
        val rows = listOf(listOf("x", "y,z", "q\"uote"), listOf("", "line\nbreak", " pad "))
        val text = Csv.write(rows)
        assertEquals(rows, Csv.parse(text).rows)
    }

    @Test fun decodesBomAndLegacyTurkish() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ş,ğ".toByteArray()
        assertEquals(listOf("ş", "ğ"), Csv.parse(bom).rows[0])
        val legacy = "şehir,ılık".toByteArray(charset("windows-1254"))
        assertEquals(listOf("şehir", "ılık"), Csv.parse(legacy).rows[0])
    }
}
