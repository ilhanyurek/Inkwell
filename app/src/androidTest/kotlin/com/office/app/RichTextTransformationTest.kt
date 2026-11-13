package com.office.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.office.app.ui.document.RichTextTransformation
import com.office.core.text.ParagraphStyle
import com.office.core.text.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Line heights must follow the largest text of a paragraph, or wrapped headings overlap. */
@RunWith(AndroidJUnit4::class)
class RichTextTransformationTest {

    @Test
    fun lineHeightCoversHeadingAndLargeRuns() {
        val text = "A long document title\nBody with BIG word\nPlain body"
        var rt = RichText.plain(text).setParagraphStyle(0, 0, ParagraphStyle.TITLE)
        val big = text.indexOf("BIG")
        rt = rt.setSize(big, big + 3, 28f)
        val out = RichTextTransformation(rt, 16.sp, Color.Gray, Color.Blue).filter(AnnotatedString(rt.text)).text

        val paragraphs = out.paragraphStyles.sortedBy { it.start }
        assertEquals(3, paragraphs.size)
        for (p in paragraphs) {
            assertTrue(p.item.lineHeight.isSp)
            val largest = out.spanStyles.filter { it.start < p.end && it.end > p.start && it.item.fontSize.isSp }
                .maxOfOrNull { it.item.fontSize.value } ?: 16f
            assertTrue("line ${p.item.lineHeight.value} for text $largest", p.item.lineHeight.value >= largest * 1.2f)
        }
        assertEquals(16f * 1.85f * 1.25f, paragraphs[0].item.lineHeight.value, 0.01f)
        assertEquals(24f, paragraphs[2].item.lineHeight.value, 0.01f)
    }

    /** Every caret position must map to its own place on screen and back, or the caret sticks to run starts. */
    @Test
    fun caretOffsetsMapOneToOne() {
        val text = "First paragraph here\nitem one\nlast line"
        var rt = RichText.plain(text)
        rt = rt.toggleInline(6, 15, com.office.core.text.InlineAttr.BOLD)
        rt = rt.toggleList(21, 21, com.office.core.text.ListType.BULLET)
        val result = RichTextTransformation(rt, 16.sp, Color.Gray, Color.Blue).filter(AnnotatedString(rt.text))
        val map = result.offsetMapping
        val shown = result.text.text
        var previous = -1
        for (o in 0..text.length) {
            val t = map.originalToTransformed(o)
            assertTrue("offset $o maps to $t after $previous", t > previous)
            assertEquals(o, map.transformedToOriginal(t))
            if (o < text.length) assertEquals(text[o], shown[t])
            previous = t
        }
        // The bullet marker ("• ") shifts the list paragraph by two characters.
        assertEquals(21 + 2, map.originalToTransformed(21))
    }

    /** With a document's formatting: real list numbers, colours and sizes, and still a one-to-one caret mapping. */
    @Test
    fun showsDocumentFormatting() {
        val pkg = com.office.core.zip.ZipPackage.read(Fixtures.asset("styled.docx").inputStream())
        val rt = RichText.fromDocument(com.office.core.docx.DocxReader.read(pkg))
        val formatting = com.office.core.docx.DocxFormatting(pkg)
        val result = RichTextTransformation(rt, 16.sp, Color.Gray, Color.Blue, formatting = formatting, background = Color.White)
            .filter(AnnotatedString(rt.text))
        val shown = result.text.text
        assertTrue(shown.contains("1.1.\u2002First child"))
        assertTrue(shown.contains("1.2.\u2002Second child"))
        assertTrue(shown.contains("a)\u2002Deep a"))
        assertTrue(shown.contains("2.\u2002Second"))

        fun spansAt(word: String) = shown.indexOf(word).let { at -> result.text.spanStyles.filter { it.start <= at && it.end > at }.map { it.item } }
        assertTrue(spansAt("red,").any { it.color == Color(0xFFC00000) })
        assertTrue(spansAt("highlighted").any { it.background == Color(0xFFFFFF00) })
        assertTrue(spansAt("Times 14").any { it.fontSize.value > 20f && it.fontFamily == androidx.compose.ui.text.font.FontFamily.Serif })
        assertTrue(spansAt("Styled Title").any { it.fontSize.value > 30f })
        assertTrue(spansAt("light word").none { it.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        assertTrue(spansAt("Heading with").any { it.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })

        val map = result.offsetMapping
        var previous = -1
        for (o in 0..rt.text.length) {
            val t = map.originalToTransformed(o)
            assertTrue("offset $o maps to $t after $previous", t > previous)
            assertEquals(o, map.transformedToOriginal(t))
            previous = t
        }
    }
}
