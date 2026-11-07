package com.office.core.xlsx

import com.office.core.sheet.CellRef
import com.office.core.xml.XmlElement
import com.office.core.xml.XmlNode
import com.office.core.xml.XmlParser
import com.office.core.xml.XmlWriter
import com.office.core.xml.withChildren

/** Frozen panes in `sheetViews`. */
object XlsxViews {

    /** (rows, cols) frozen in the first sheet view. */
    fun frozen(sheetViews: XmlElement?): Pair<Int, Int> {
        val pane = sheetViews?.child("sheetView")?.child("pane") ?: return 0 to 0
        val state = pane.attr("state")
        if (state != "frozen" && state != "frozenSplit") return 0 to 0
        val cols = pane.attr("xSplit")?.toDoubleOrNull()?.toInt() ?: 0
        val rows = pane.attr("ySplit")?.toDoubleOrNull()?.toInt() ?: 0
        return rows to cols
    }

    /** [sheetViews] (or a new element when null) with the first view frozen at [rows] × [cols]. */
    fun withFrozen(sheetViews: String?, rows: Int, cols: Int): String {
        val root = sheetViews?.let { runCatching { XmlParser.parse(it) }.getOrNull() }
            ?: XmlParser.parse("""<sheetViews><sheetView workbookViewId="0"/></sheetViews>""")
        if (frozen(root) == (rows to cols)) return sheetViews ?: XmlWriter.write(root)
        val p = root.name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
        val views = root.elements
        val first = views.firstOrNull { it.localName == "sheetView" } ?: XmlElement("${p}sheetView", mapOf("workbookViewId" to "0"), emptyList())
        val kept = first.children.filter { it !is XmlElement || (it.localName != "pane" && it.localName != "selection") }
        val newKids = ArrayList<XmlNode>()
        if (rows > 0 || cols > 0) {
            val attrs = LinkedHashMap<String, String>()
            if (cols > 0) attrs["xSplit"] = cols.toString()
            if (rows > 0) attrs["ySplit"] = rows.toString()
            attrs["topLeftCell"] = CellRef(rows, cols).toString()
            val active = when {
                rows > 0 && cols > 0 -> "bottomRight"
                rows > 0 -> "bottomLeft"
                else -> "topRight"
            }
            attrs["activePane"] = active
            attrs["state"] = "frozen"
            newKids += XmlElement("${p}pane", attrs, emptyList())
            newKids += XmlElement("${p}selection", mapOf("pane" to active), emptyList())
        }
        newKids += kept
        val updated = first.withChildren(newKids)
        val out = if (views.any { it.localName == "sheetView" }) {
            var replaced = false
            root.withChildren(root.children.map { if (!replaced && it is XmlElement && it.localName == "sheetView") { replaced = true; updated } else it })
        } else {
            root.withChildren(listOf(updated) + root.children)
        }
        return XmlWriter.write(out)
    }
}
