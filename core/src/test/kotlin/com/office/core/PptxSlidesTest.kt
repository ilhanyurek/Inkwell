package com.office.core

import com.office.core.pptx.PptxReader
import com.office.core.pptx.PptxSlides
import com.office.core.pptx.SlideShape
import com.office.core.zip.ZipPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class PptxSlidesTest {

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

    private val outDir = File("build/fixture-out").apply { mkdirs() }

    private fun titles(pkg: ZipPackage) = PptxReader.read(pkg).slides.map { s ->
        s.shapes.filterIsInstance<SlideShape.TextBox>().firstOrNull()?.paragraphs?.joinToString(" ") { it.text }.orEmpty()
    }

    @Test fun slideOperationsOnPythonPptxDeck() {
        var pkg = ZipPackage.read(ByteArrayInputStream(fixture("deck.pptx")))
        val before = titles(pkg)
        assertEquals(3, before.size)

        pkg = PptxSlides.move(pkg, 0, 2)
        assertEquals(listOf(before[1], before[2], before[0]), titles(pkg))
        pkg = PptxSlides.move(pkg, 2, 0)
        assertEquals(before, titles(pkg))

        pkg = PptxSlides.duplicate(pkg, 1)
        assertEquals(listOf(before[0], before[1], before[1], before[2]), titles(pkg))
        assertEquals("", PptxReader.read(pkg).slides[2].notes)

        pkg = PptxSlides.addSlide(pkg, 3)
        val slides = PptxReader.read(pkg).slides
        assertEquals(5, slides.size)
        val boxes = slides[4].shapes.filterIsInstance<SlideShape.TextBox>()
        assertEquals(2, boxes.size)
        assertTrue(boxes.all { b -> b.shapeId != null && b.paragraphs.all { it.text.isEmpty() } })

        pkg = PptxSlides.delete(pkg, 0)
        assertEquals(listOf(before[1], before[1], before[2], ""), titles(pkg))
        assertTrue(pkg.entries.keys.none { it.endsWith("slides/slide1.xml") })

        val out = ByteArrayOutputStream()
        ZipPackage.write(out, pkg.entries.toList())
        File(outDir, "deck-slides.pptx").writeBytes(out.toByteArray())
    }

    @Test fun newPresentationReadsBackAndGrows() {
        val bytes = PptxSlides.newPresentation("Launch plan", "Team review")
        File(outDir, "new.pptx").writeBytes(bytes)
        var pkg = ZipPackage.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("Launch plan"), titles(pkg))
        pkg = PptxSlides.addSlide(pkg, 0)
        pkg = PptxSlides.addSlide(pkg, 1)
        assertEquals(3, PptxReader.read(pkg).slides.size)
        val out = ByteArrayOutputStream()
        ZipPackage.write(out, pkg.entries.toList())
        File(outDir, "new-grown.pptx").writeBytes(out.toByteArray())
    }
}
