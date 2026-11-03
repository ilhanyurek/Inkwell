package com.office.core.xml

/**
 * A tiny, dependency-free XML DOM good enough for Office Open XML parts.
 *
 * Android ships XmlPullParser but the JVM does not, and StAX is missing on Android,
 * so the core module carries its own small parser that works identically on both.
 */
sealed class XmlNode

class XmlText(val text: String) : XmlNode()

class XmlElement(
    /** Qualified name as written, e.g. `w:p`. */
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlNode>,
) : XmlNode() {

    /** Name without namespace prefix, e.g. `p` for `w:p`. */
    val localName: String get() = name.substringAfter(':')

    val elements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()

    fun child(localName: String): XmlElement? = elements.firstOrNull { it.localName == localName }

    fun children(localName: String): List<XmlElement> = elements.filter { it.localName == localName }

    /** Follows a path of local names, e.g. `path("spPr", "xfrm", "off")`. */
    fun path(vararg localNames: String): XmlElement? {
        var current: XmlElement? = this
        for (n in localNames) current = current?.child(n) ?: return null
        return current
    }

    /** Attribute lookup by exact qualified name first, then by local name. */
    fun attr(name: String): String? =
        attributes[name] ?: attributes.entries.firstOrNull { it.key.substringAfter(':') == name }?.value

    /** All descendant elements with the given local name, depth-first, document order. */
    fun descendants(localName: String): List<XmlElement> {
        val out = ArrayList<XmlElement>()
        fun walk(e: XmlElement) {
            for (c in e.elements) {
                if (c.localName == localName) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }

    /** Concatenated text content of this element and all descendants. */
    fun text(): String {
        val sb = StringBuilder()
        fun walk(n: XmlNode) {
            when (n) {
                is XmlText -> sb.append(n.text)
                is XmlElement -> n.children.forEach(::walk)
            }
        }
        walk(this)
        return sb.toString()
    }
}

class XmlParseException(message: String) : Exception(message)

object XmlParser {

    fun parse(bytes: ByteArray): XmlElement = parse(decode(bytes))

    fun parse(input: String): XmlElement {
        val p = Parser(input)
        return p.document()
    }

    private fun decode(bytes: ByteArray): String {
        // UTF-16 BOMs; OOXML is almost always UTF-8.
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): XmlElement {
            skipProlog()
            if (i >= s.length || s[i] != '<') throw XmlParseException("No root element")
            return element()
        }

        private fun skipProlog() {
            while (i < s.length) {
                skipWhitespace()
                when {
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<!DOCTYPE", i) -> skipDoctype()
                    else -> return
                }
            }
        }

        private fun skipDoctype() {
            var depth = 0
            while (i < s.length) {
                val c = s[i++]
                if (c == '[') depth++
                if (c == ']') depth--
                if (c == '>' && depth <= 0) return
            }
        }

        private fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun skipPast(token: String) {
            val idx = s.indexOf(token, i)
            if (idx < 0) throw XmlParseException("Unterminated construct, expected $token")
            i = idx + token.length
        }

        private fun name(): String {
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace() || c == '>' || c == '/' || c == '=') break
                i++
            }
            if (start == i) throw XmlParseException("Expected name at $i")
            return s.substring(start, i)
        }

        private fun element(): XmlElement {
            // at '<'
            i++
            val name = name()
            val attrs = LinkedHashMap<String, String>()
            while (true) {
                skipWhitespace()
                if (i >= s.length) throw XmlParseException("Unexpected end in <$name>")
                val c = s[i]
                if (c == '/') {
                    if (s.startsWith("/>", i)) {
                        i += 2
                        return XmlElement(name, attrs, emptyList())
                    }
                    throw XmlParseException("Malformed tag <$name>")
                }
                if (c == '>') {
                    i++
                    break
                }
                val attrName = name()
                skipWhitespace()
                if (i >= s.length || s[i] != '=') throw XmlParseException("Expected '=' after $attrName")
                i++
                skipWhitespace()
                val quote = s[i]
                if (quote != '"' && quote != '\'') throw XmlParseException("Expected quote for $attrName")
                i++
                val end = s.indexOf(quote, i)
                if (end < 0) throw XmlParseException("Unterminated attribute $attrName")
                attrs[attrName] = unescape(s.substring(i, end))
                i = end + 1
            }

            val children = ArrayList<XmlNode>()
            val text = StringBuilder()
            fun flushText() {
                if (text.isNotEmpty()) {
                    children += XmlText(text.toString())
                    text.setLength(0)
                }
            }
            while (true) {
                if (i >= s.length) throw XmlParseException("Unclosed element <$name>")
                val lt = s.indexOf('<', i)
                if (lt < 0) throw XmlParseException("Unclosed element <$name>")
                if (lt > i) {
                    text.append(unescape(s.substring(i, lt)))
                    i = lt
                }
                when {
                    s.startsWith("</", i) -> {
                        i += 2
                        val closing = name()
                        skipWhitespace()
                        if (closing != name) throw XmlParseException("Mismatched </$closing>, expected </$name>")
                        if (i >= s.length || s[i] != '>') throw XmlParseException("Malformed closing tag")
                        i++
                        flushText()
                        return XmlElement(name, attrs, children)
                    }
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i)
                        if (end < 0) throw XmlParseException("Unterminated CDATA")
                        text.append(s, i + 9, end)
                        i = end + 3
                    }
                    s.startsWith("<?", i) -> skipPast("?>")
                    else -> {
                        flushText()
                        children += element()
                    }
                }
            }
        }
    }

    fun unescape(raw: String): String {
        if (raw.indexOf('&') < 0) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '&') {
                val semi = raw.indexOf(';', i)
                if (semi > i) {
                    val entity = raw.substring(i + 1, semi)
                    val replacement: String? = when {
                        entity == "lt" -> "<"
                        entity == "gt" -> ">"
                        entity == "amp" -> "&"
                        entity == "quot" -> "\""
                        entity == "apos" -> "'"
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (replacement != null) {
                        sb.append(replacement)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/** Escapes text for use in XML content or attribute values; strips characters illegal in XML 1.0. */
fun xmlEscape(text: String): String {
    val sb = StringBuilder(text.length + 16)
    for (c in text) {
        when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            '\t', '\n', '\r' -> sb.append(c)
            else -> if (c >= ' ') sb.append(c)
        }
    }
    return sb.toString()
}
