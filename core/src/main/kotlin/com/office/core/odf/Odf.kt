package com.office.core.odf

import com.office.core.text.Alignment
import com.office.core.xml.XmlElement
import com.office.core.zip.ZipPackage

/** Namespaces and shared helpers for OpenDocument files. */
object Odf {
    const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    const val XLINK = "http://www.w3.org/1999/xlink"
    const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    const val NUMBER = "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"
    const val OF = "urn:oasis:names:tc:opendocument:xmlns:of:1.2"
    const val META = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
    const val PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    const val MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"

    const val ODT_MIME = "application/vnd.oasis.opendocument.text"
    const val ODS_MIME = "application/vnd.oasis.opendocument.spreadsheet"
    const val ODP_MIME = "application/vnd.oasis.opendocument.presentation"

    /** Namespace declarations for a new document root. */
    val ROOT_NS = """xmlns:office="$OFFICE" xmlns:style="$STYLE" xmlns:text="$TEXT" xmlns:table="$TABLE" xmlns:draw="$DRAW" xmlns:fo="$FO" xmlns:xlink="$XLINK" xmlns:svg="$SVG" xmlns:number="$NUMBER" xmlns:of="$OF" xmlns:meta="$META""""

    fun isOdf(pkg: ZipPackage): Boolean = pkg.bytes("mimetype")?.let { String(it, Charsets.US_ASCII).startsWith("application/vnd.oasis.opendocument") } == true

    /** Length like `2.5cm`, `1in`, `12pt`, `30mm` in EMU (914400 per inch). */
    fun toEmu(length: String?): Long? {
        val m = Regex("""^\s*(-?[0-9.]+)\s*(cm|mm|in|pt|pc|px)?\s*$""").matchEntire(length ?: return null) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val inches = when (m.groupValues[2]) {
            "cm" -> v / 2.54
            "mm" -> v / 25.4
            "pt" -> v / 72
            "pc" -> v / 6
            "px" -> v / 96
            else -> v
        }
        return (inches * 914400).toLong()
    }

    fun toPt(length: String?): Float? = toEmu(length)?.let { it / 12700f }

    fun manifest(mime: String, extra: List<Pair<String, String>> = emptyList()): String =
        """<?xml version="1.0" encoding="UTF-8"?><manifest:manifest xmlns:manifest="$MANIFEST" manifest:version="1.3">""" +
            """<manifest:file-entry manifest:full-path="/" manifest:version="1.3" manifest:media-type="$mime"/>""" +
            """<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>""" +
            """<manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>""" +
            """<manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>""" +
            extra.joinToString("") { (path, type) -> """<manifest:file-entry manifest:full-path="$path" manifest:media-type="$type"/>""" } +
            "</manifest:manifest>"

    fun meta(): String =
        """<?xml version="1.0" encoding="UTF-8"?><office:document-meta xmlns:office="$OFFICE" xmlns:meta="$META" office:version="1.3"><office:meta><meta:generator>Inkwell</meta:generator></office:meta></office:document-meta>"""
}

/** Formatting of one style, with values inherited from its parents filled in by [OdfStyles.resolve]. */
data class OdfStyle(
    val name: String,
    val family: String,
    val parent: String?,
    val automatic: Boolean,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val sizePt: Float? = null,
    val color: Long? = null,
    val background: Long? = null,
    val align: Alignment? = null,
    val listStyle: String? = null,
    val dataStyle: String? = null,
    val wrap: Boolean? = null,
    val border: Boolean? = null,
    val columnWidthEmu: Long? = null,
)

/** Styles from `styles.xml` and the automatic styles of `content.xml`, plus list styles. */
class OdfStyles(pkg: ZipPackage?, content: XmlElement?) {
    val styles = HashMap<String, OdfStyle>()
    /** List style name → true when its first level is numbered. */
    val numberedLists = HashMap<String, Boolean>()
    /** Data style name → number format code in Excel terms (dates and percentages only). */
    val dataFormats = HashMap<String, String>()

    init {
        val stylesXml = runCatching { pkg?.xml("styles.xml") }.getOrNull()
        stylesXml?.child("styles")?.let { collect(it, automatic = false) }
        stylesXml?.child("automatic-styles")?.let { collect(it, automatic = true) }
        content?.child("automatic-styles")?.let { collect(it, automatic = true) }
    }

    private fun collect(container: XmlElement, automatic: Boolean) {
        for (el in container.elements) {
            when (el.localName) {
                "style", "default-style" -> {
                    val name = el.attr("style:name") ?: if (el.localName == "default-style") "__default_${el.attr("style:family")}" else continue
                    styles[name] = parse(el, name, automatic)
                }
                "list-style" -> {
                    val name = el.attr("style:name") ?: continue
                    val first = el.elements.firstOrNull { it.attr("text:level") == "1" } ?: el.elements.firstOrNull()
                    numberedLists[name] = first?.localName == "list-level-style-number"
                }
                "date-style" -> el.attr("style:name")?.let { dataFormats[it] = "yyyy-mm-dd" }
                "percentage-style" -> el.attr("style:name")?.let { n ->
                    val decimals = el.child("number")?.attr("number:decimal-places")?.toIntOrNull() ?: 0
                    dataFormats[n] = if (decimals > 0) "0." + "0".repeat(decimals) + "%" else "0%"
                }
            }
        }
    }

    private fun parse(el: XmlElement, name: String, automatic: Boolean): OdfStyle {
        val t = el.child("text-properties")
        val p = el.child("paragraph-properties")
        val c = el.child("table-cell-properties")
        val col = el.child("table-column-properties")
        fun color(v: String?): Long? = v?.takeIf { it.startsWith("#") && it.length == 7 }?.substring(1)?.toLongOrNull(16)?.let { it or 0xFF000000L }
        return OdfStyle(
            name = name,
            family = el.attr("style:family").orEmpty(),
            parent = el.attr("style:parent-style-name"),
            automatic = automatic,
            bold = t?.attr("fo:font-weight")?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 },
            italic = t?.attr("fo:font-style")?.let { it == "italic" || it == "oblique" },
            underline = t?.attr("style:text-underline-style")?.let { it != "none" },
            strike = t?.attr("style:text-line-through-style")?.let { it != "none" },
            sizePt = t?.attr("fo:font-size")?.takeIf { !it.endsWith("%") }?.let { Odf.toPt(it) },
            color = color(t?.attr("fo:color")),
            background = color(c?.attr("fo:background-color")),
            align = (p?.attr("fo:text-align"))?.let {
                when (it) {
                    "center" -> Alignment.CENTER
                    "end", "right" -> Alignment.END
                    "justify" -> Alignment.JUSTIFY
                    else -> Alignment.START
                }
            },
            listStyle = el.attr("style:list-style-name"),
            dataStyle = el.attr("style:data-style-name"),
            wrap = c?.attr("fo:wrap-option")?.let { it == "wrap" },
            border = c?.let { cell -> listOf("fo:border", "fo:border-top", "fo:border-bottom").any { (cell.attr(it) ?: "none") != "none" } },
            columnWidthEmu = Odf.toEmu(col?.attr("style:column-width")),
        )
    }

    /** [name] with unset values taken from its parent chain (and the family default). */
    fun resolve(name: String?): OdfStyle? {
        var s = styles[name ?: return null] ?: return null
        var parent = s.parent
        val seen = HashSet<String>()
        while (parent != null && seen.add(parent)) {
            val p = styles[parent] ?: break
            s = s.copy(
                bold = s.bold ?: p.bold, italic = s.italic ?: p.italic, underline = s.underline ?: p.underline,
                strike = s.strike ?: p.strike, sizePt = s.sizePt ?: p.sizePt, color = s.color ?: p.color,
                background = s.background ?: p.background, align = s.align ?: p.align, listStyle = s.listStyle ?: p.listStyle,
                dataStyle = s.dataStyle ?: p.dataStyle, wrap = s.wrap ?: p.wrap, border = s.border ?: p.border,
            )
            parent = p.parent
        }
        return s
    }

    /** Names in the parent chain of [name], starting with [name]. */
    fun chain(name: String?): List<String> {
        val out = ArrayList<String>()
        var n = name
        while (n != null && n !in out) {
            out += n
            n = styles[n]?.parent
        }
        return out
    }
}
