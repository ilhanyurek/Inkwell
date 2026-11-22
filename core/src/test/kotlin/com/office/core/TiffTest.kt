package com.office.core

import com.office.core.docx.DocxLayout
import com.office.core.docx.LayoutItem
import com.office.core.image.Tiff
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** TIFF pictures (Android cannot decode them) are turned into pixels by our own decoder. */
class TiffTest {

    private fun res(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

    /** Compares with the RGBA pixels Pillow wrote next to each TIFF; premultiplied alpha may be off by rounding. */
    private fun check(name: String, tolerance: Int = 0) {
        val img = Tiff.decode(res("tiff/$name.tiff"))
        assertNotNull("$name was not decoded", img)
        img!!
        assertEquals(37, img.width)
        assertEquals(23, img.height)
        val rgba = res("tiff/$name.rgba")
        for (i in img.argb.indices) {
            val p = img.argb[i]
            val expected = intArrayOf(
                rgba[i * 4].toInt() and 0xFF, rgba[i * 4 + 1].toInt() and 0xFF,
                rgba[i * 4 + 2].toInt() and 0xFF, rgba[i * 4 + 3].toInt() and 0xFF,
            )
            val actual = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, (p ushr 24) and 0xFF)
            for (c in 0 until 4) {
                assertTrue("$name pixel $i channel $c: ${actual[c]} vs ${expected[c]}", kotlin.math.abs(actual[c] - expected[c]) <= tolerance)
            }
        }
    }

    @Test fun lzwWithPredictorAndAlpha() = check("lzw_predictor_rgba")

    @Test fun packBits() = check("packbits_rgb")

    @Test fun deflateGrey() = check("deflate_grey")

    @Test fun palette() = check("raw_palette")

    @Test fun bilevel() = check("raw_bilevel")

    @Test fun tiles() = check("le_tiled_rgb")

    @Test fun bigEndianPremultiplied() = check("be_premultiplied_rgba", tolerance = 3)

    @Test fun notATiff() {
        assertFalse(Tiff.isTiff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 0)))
        assertEquals(null, Tiff.decode(ByteArray(64)))
        // A truncated file does not throw.
        val cut = res("tiff/lzw_predictor_rgba.tiff").copyOf(40)
        Tiff.decode(cut)
    }

    /** A logo stored as TIFF in a header table (as in many university forms) reaches the screen as pixels. */
    @Test fun headerLogo() {
        val pkg = ZipPackage.read(ByteArrayInputStream(res("logo-header.docx")))
        val (header, _) = DocxLayout(pkg).headerFooter()
        val table = header!!.filterIsInstance<LayoutItem.Table>().first().layout
        val picture = table.rows[0].cells[0].items.filterIsInstance<LayoutItem.Picture>().first()
        assertTrue(table.text.contains("Örnek Üniversitesi"))
        val img = Tiff.decode(picture.bytes!!)!!
        assertEquals(173, img.width)
        assertEquals(232, img.height)
        // Opaque blue in the middle, transparent in the corner.
        assertEquals(0xFF003D82.toInt(), img.argb[116 * 173 + 86])
        assertEquals(0, img.argb[0] ushr 24)
    }
}
