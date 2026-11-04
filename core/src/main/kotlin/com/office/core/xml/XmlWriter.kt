package com.office.core.xml

/** Serializes the small DOM from [XmlParser] back to XML text, keeping prefixes and attribute order. */
object XmlWriter {

    fun write(element: XmlElement, declaration: Boolean = false): String {
        val sb = StringBuilder()
        if (declaration) sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        append(sb, element)
        return sb.toString()
    }

    fun append(sb: StringBuilder, node: XmlNode) {
        when (node) {
            is XmlText -> sb.append(escapeText(node.text))
            is XmlElement -> {
                sb.append('<').append(node.name)
                for ((k, v) in node.attributes) sb.append(' ').append(k).append("=\"").append(xmlEscape(v)).append('"')
                if (node.children.isEmpty()) {
                    sb.append("/>")
                } else {
                    sb.append('>')
                    node.children.forEach { append(sb, it) }
                    sb.append("</").append(node.name).append('>')
                }
            }
        }
    }

    /** Text content only needs <, > and & escaped; quotes are kept readable. */
    private fun escapeText(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '\t', '\n', '\r' -> sb.append(c)
                else -> if (c >= ' ') sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** Returns a copy of this element with [transform] applied to its direct children. */
fun XmlElement.withChildren(children: List<XmlNode>) = XmlElement(name, attributes, children)
