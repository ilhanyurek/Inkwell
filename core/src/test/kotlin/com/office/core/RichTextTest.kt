package com.office.core

import com.office.core.text.CharStyle
import com.office.core.text.InlineAttr
import com.office.core.text.ListType
import com.office.core.text.Paragraph
import com.office.core.text.ParagraphStyle
import com.office.core.text.RichText
import com.office.core.text.TextDocument
import com.office.core.text.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextTest {
    private val bold = CharStyle(bold = true)

    @Test fun typingKeepsFormattingAroundEdit() {
        var m = RichText.plain("hello world")
        m = m.toggleInline(0, 5, InlineAttr.BOLD)
        m = m.applyEdit("hello big world", CharStyle.PLAIN)
        val doc = m.toDocument()
        assertEquals(listOf(TextRun("hello", bold = true), TextRun(" big world")), doc.paragraphs[0].runs)
    }

    @Test fun enterSplitsHeadingIntoBodyParagraph() {
        var m = RichText.plain("Title")
        m = m.setParagraphStyle(0, 0, ParagraphStyle.HEADING1)
        m = m.applyEdit("Title\nBody", CharStyle.PLAIN)
        assertEquals(listOf(ParagraphStyle.HEADING1, ParagraphStyle.NORMAL), m.paras.map { it.style })
    }

    @Test fun enterOnEmptyListItemEndsList() {
        var m = RichText.plain("item\n")
        m = m.toggleList(0, 5, ListType.BULLET)
        assertEquals(2, m.paras.count { it.list == ListType.BULLET })
        m = m.applyEdit("item\n\n", CharStyle.PLAIN)
        assertEquals("item\n", m.text)
        assertEquals(listOf(ListType.BULLET, ListType.NONE), m.paras.map { it.list })
    }

    @Test fun deletingLineBreakMergesParagraphs() {
        var m = RichText.plain("a\nb\nc")
        m = m.setParagraphStyle(2, 2, ParagraphStyle.HEADING2)
        m = m.applyEdit("ab\nc", CharStyle.PLAIN)
        assertEquals(2, m.paragraphCount)
        assertEquals(ParagraphStyle.NORMAL, m.paras[0].style)
    }

    @Test fun toggleIsSymmetric() {
        var m = RichText.plain("abc")
        m = m.toggleInline(0, 3, InlineAttr.ITALIC).toggleInline(0, 3, InlineAttr.ITALIC)
        assertTrue(m.styles.all { it == CharStyle.PLAIN })
    }

    @Test fun replaceAllAndDocumentRoundTrip() {
        val doc = TextDocument(listOf(Paragraph(listOf(TextRun("Merhaba "), TextRun("dünya", bold = true))), Paragraph.plain("dünya!")))
        val m = RichText.fromDocument(doc)
        assertEquals(doc.paragraphs, m.toDocument().paragraphs)
        val (r, n) = m.replaceAll("dünya", "evren")
        assertEquals(2, n)
        assertEquals("Merhaba evren\nevren!", r.text)
        assertEquals(bold, r.styleAt(8))
    }
}
