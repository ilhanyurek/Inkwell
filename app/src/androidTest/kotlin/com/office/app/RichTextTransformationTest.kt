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
}
