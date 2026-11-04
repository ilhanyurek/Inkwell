package com.office.core

import com.office.core.pptx.PptxReader
import com.office.core.pptx.PptxWriter
import com.office.core.pptx.SlideShape
import com.office.core.util.PageRanges
import com.office.core.util.TextDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ToolsCoreTest {

    @Test
    fun pageRanges() {
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3, 5", 10))
        assertEquals(listOf(7, 8, 9), PageRanges.parse("8-", 10))
        assertEquals(listOf(0, 1), PageRanges.parse("-2", 10))
        assertEquals(listOf(2, 1, 0), PageRanges.parse("3-1", 10))
        assertEquals(listOf(0), PageRanges.parse("1,1;1", 10))
        assertNull(PageRanges.parse("0", 10))
        assertNull(PageRanges.parse("11", 10))
        assertNull(PageRanges.parse("a-b", 10))
        assertNull(PageRanges.parse("  ", 10))
        assertEquals("1-3, 5, 8", PageRanges.format(listOf(2, 0, 1, 4, 7)))
    }

    @Test
    fun textDiff() {
        val d = TextDiff.lines("a\nb\nc\nd", "a\nB\nc\nd\ne")
        assertEquals(
            listOf(
                TextDiff.Line(TextDiff.Kind.SAME, "a"),
                TextDiff.Line(TextDiff.Kind.REMOVED, "b"),
                TextDiff.Line(TextDiff.Kind.ADDED, "B"),
                TextDiff.Line(TextDiff.Kind.SAME, "c"),
                TextDiff.Line(TextDiff.Kind.SAME, "d"),
                TextDiff.Line(TextDiff.Kind.ADDED, "e"),
            ),
            d,
        )
        assertEquals(3, TextDiff.lines("x\ny\nz", "x\ny\nz").count { it.kind == TextDiff.Kind.SAME })
    }

    @Test
    fun pptxWriterMakesPictureSlides() {
        val png = javaClass.classLoader!!.getResourceAsStream("fixtures/deck.pptx")!!.use { input ->
            com.office.core.zip.ZipPackage.read(input).entries.entries.first { it.key.endsWith(".png") }.value
        }
        val out = ByteArrayOutputStream()
        PptxWriter.writePictures(
            listOf(PptxWriter.Picture(png, 200, 100, jpeg = false), PptxWriter.Picture(png, 100, 200, jpeg = false)),
            out,
        )
        File("build/fixture-out").apply { mkdirs() }.resolve("pictures.pptx").writeBytes(out.toByteArray())
        val pres = PptxReader.read(out.toByteArray().inputStream())
        assertEquals(2, pres.slides.size)
        val pic = pres.slides[1].shapes.single() as SlideShape.Picture
        // A portrait picture on a 16:9 slide is centred horizontally and fills the height.
        assertEquals(pres.height, pic.frame.height)
        assertEquals((pres.width - pic.frame.width) / 2, pic.frame.x)
    }
}
