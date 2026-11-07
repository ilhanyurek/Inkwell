package com.office.core

import com.office.core.sheet.CellRef
import com.office.core.sheet.ChartRef
import com.office.core.sheet.ChartType
import com.office.core.xlsx.XlsxCharts
import com.office.core.xlsx.XlsxViews
import com.office.core.xml.XmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class XlsxChartsViewsTest {

    @Test fun parsesPieChartWithCachedValues() {
        val xml = """<c:chartSpace xmlns:c="c" xmlns:a="a"><c:chart><c:title><c:tx><c:rich><a:p><a:r><a:t>Share</a:t></a:r></a:p></c:rich></c:tx></c:title>
            <c:plotArea><c:pieChart><c:ser><c:tx><c:strRef><c:f>'My Sheet'!${'$'}B${'$'}1</c:f><c:strCache><c:pt idx="0"><c:v>Units</c:v></c:pt></c:strCache></c:strRef></c:tx>
            <c:cat><c:strRef><c:f>'My Sheet'!${'$'}A${'$'}2:${'$'}A${'$'}3</c:f><c:strCache><c:ptCount val="2"/><c:pt idx="0"><c:v>Pens</c:v></c:pt><c:pt idx="1"><c:v>Books</c:v></c:pt></c:strCache></c:strRef></c:cat>
            <c:val><c:numRef><c:f>'My Sheet'!${'$'}B${'$'}2:${'$'}B${'$'}3</c:f><c:numCache><c:ptCount val="2"/><c:pt idx="1"><c:v>4.5</c:v></c:pt></c:numCache></c:numRef></c:val></c:ser></c:pieChart></c:plotArea></c:chart></c:chartSpace>"""
        val chart = XlsxCharts.parseChart(XmlParser.parse(xml), null)!!
        assertEquals(ChartType.PIE, chart.type)
        assertEquals("Share", chart.title)
        assertEquals("Units", chart.series[0].name)
        assertEquals(listOf(null, 4.5), chart.series[0].cachedValues)
        assertEquals(listOf("Pens", "Books"), chart.cachedCategories)
        assertEquals(ChartRef("My Sheet", CellRef(1, 1), CellRef(2, 1)), chart.series[0].values)
    }

    @Test fun freezeAndUnfreeze() {
        val original = """<sheetViews><sheetView tabSelected="1" workbookViewId="0"><selection activeCell="C3" sqref="C3"/></sheetView></sheetViews>"""
        val frozen = XlsxViews.withFrozen(original, 2, 0)
        assertEquals(2 to 0, XlsxViews.frozen(XmlParser.parse(frozen)))
        val view = XmlParser.parse(frozen).child("sheetView")!!
        assertEquals("pane", view.elements.first().localName)
        assertEquals("A3", view.child("pane")!!.attr("topLeftCell"))
        val back = XlsxViews.withFrozen(frozen, 0, 0)
        assertEquals(0 to 0, XlsxViews.frozen(XmlParser.parse(back)))
        assertFalse(back.contains("pane"))
        assertEquals(original, XlsxViews.withFrozen(original, 0, 0))
    }
}
