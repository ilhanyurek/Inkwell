package com.office.app.ui.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A point on a rendered page, normalised to 0..1 from the top-left of what the user sees. */
data class PagePoint(val x: Float, val y: Float)

/** An annotation on a page; the rectangle is normalised like [PagePoint]. */
data class AnnotationInfo(
    val page: Int,
    val index: Int,
    val subtype: String,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** RGB colour (no alpha), used to draw the annotation over the page. */
    val color: Int = 0xFFE066,
    /** Font size in points for free-text notes. */
    val fontSizePt: Float = 12f,
) {
    operator fun contains(p: PagePoint) = p.x in left..right && p.y in top..bottom
}

enum class FieldType { TEXT, CHECKBOX, CHOICE, RADIO }

enum class RedactMode { BLUR, BLACK }

/** An interactive form field and its current value ("true"/"false" for check boxes). */
data class FormField(
    val name: String,
    val label: String,
    val type: FieldType,
    val value: String,
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false,
)

/** PDF operations backed by PDFBox. All functions run on the IO dispatcher. */
object PdfTools {

    suspend fun merge(context: Context, inputs: List<Uri>, output: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val merger = PDFMergerUtility()
            val streams = inputs.map { context.contentResolver.openInputStream(it) ?: error("Cannot read $it") }
            try {
                streams.forEach { merger.addSource(it) }
                val out = context.contentResolver.openOutputStream(output, "wt") ?: error("Cannot write")
                out.use {
                    merger.destinationStream = it
                    merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
                }
            } finally {
                streams.forEach { runCatching { it.close() } }
            }
        }
    }

    /** Opens [file] (optionally with a password), applies [block] and saves back to [file]. */
    private suspend fun edit(file: File, password: String?, block: (PDDocument) -> Unit) = withContext(Dispatchers.IO) {
        val doc = if (password != null) PDDocument.load(file, password) else PDDocument.load(file)
        doc.use {
            if (it.isEncrypted) it.isAllSecurityToBeRemoved = true
            block(it)
            val tmp = File(file.parentFile, file.name + ".tmp")
            it.save(tmp)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Writes a decrypted copy of a password-protected PDF. Throws if the password is wrong. */
    suspend fun decrypt(source: File, password: String, target: File) = withContext(Dispatchers.IO) {
        PDDocument.load(source, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(target)
        }
    }

    suspend fun rotate(file: File, pageIndex: Int, degrees: Int) = edit(file, null) { doc ->
        val page = doc.getPage(pageIndex)
        page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
    }

    suspend fun deletePage(file: File, pageIndex: Int) = edit(file, null) { doc ->
        if (doc.numberOfPages > 1) doc.removePage(pageIndex)
    }

    /** Copies pages [from]..[to] (0-based, inclusive) into a new PDF written to [output]. */
    suspend fun extract(context: Context, file: File, from: Int, to: Int, output: Uri) = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { src ->
            PDDocument().use { dst ->
                for (i in from..to) {
                    val imported = dst.importPage(src.getPage(i))
                    imported.rotation = src.getPage(i).rotation
                }
                val out = context.contentResolver.openOutputStream(output, "wt") ?: error("Cannot write")
                out.use { dst.save(it) }
            }
        }
    }

    /** Builds an appearance stream of the given size so every viewer (and PdfRenderer) draws the annotation. */
    private fun appearance(doc: PDDocument, w: Float, h: Float, draw: (PDPageContentStream) -> Unit): PDAppearanceDictionary {
        val stream = PDAppearanceStream(doc)
        stream.bBox = PDRectangle(0f, 0f, w, h)
        stream.resources = PDResources()
        PDPageContentStream(doc, stream).use(draw)
        return PDAppearanceDictionary().apply { setNormalAppearance(stream) }
    }

    private fun addAnnotation(page: PDPage, annotation: PDAnnotation) {
        page.annotations = page.annotations.toMutableList().also { it.add(annotation) }
    }

    private fun rgbFloats(rgb: Int) = floatArrayOf(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)

    /** Adds a highlight annotation between two corners; it can be removed again later. */
    suspend fun highlight(file: File, pageIndex: Int, a: PagePoint, b: PagePoint, rgb: Int) = edit(file, null) { doc ->
        val page = doc.getPage(pageIndex)
        val p1 = toUserSpace(page, a)
        val p2 = toUserSpace(page, b)
        val x = minOf(p1.first, p2.first)
        val y = minOf(p1.second, p2.second)
        val w = kotlin.math.abs(p1.first - p2.first).coerceAtLeast(1f)
        val h = kotlin.math.abs(p1.second - p2.second).coerceAtLeast(1f)
        val color = rgbFloats(rgb)
        val ann = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
        ann.rectangle = PDRectangle(x, y, w, h)
        ann.quadPoints = floatArrayOf(x, y + h, x + w, y + h, x, y, x + w, y)
        ann.color = PDColor(color, PDDeviceRGB.INSTANCE)
        ann.constantOpacity = 0.4f
        ann.titlePopup = "Inkwell"
        ann.appearance = appearance(doc, w, h) { cs ->
            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.4f })
            cs.setNonStrokingColor(color[0], color[1], color[2])
            cs.addRect(0f, 0f, w, h)
            cs.fill()
        }
        addAnnotation(page, ann)
    }

    /** Adds a free-text annotation with its top-left at [at]. */
    suspend fun addText(file: File, pageIndex: Int, at: PagePoint, text: String, sizePt: Float, rgb: Int) = edit(file, null) { doc ->
        val page = doc.getPage(pageIndex)
        val font = loadFont(doc)
        val lines = text.split('\n').map { if (font is PDType1Font) asciiFallback(it) else it }
        val pad = 2f
        val w = (lines.maxOf { runCatching { font.getStringWidth(it) }.getOrDefault(it.length * 500f) } / 1000f * sizePt + pad * 2).coerceAtLeast(10f)
        val h = lines.size * sizePt * 1.25f + pad * 2
        val (x, top) = toUserSpace(page, at)
        val color = rgbFloats(rgb)
        val ann = PDAnnotationMarkup()
        ann.cosObject.setName(COSName.SUBTYPE, "FreeText")
        ann.cosObject.setString(COSName.DA, "/Helv ${sizePt} Tf ${color[0]} ${color[1]} ${color[2]} rg")
        ann.contents = text
        ann.titlePopup = "Inkwell"
        ann.rectangle = PDRectangle(x, top - h, w, h)
        ann.appearance = appearance(doc, w, h) { cs ->
            cs.beginText()
            cs.setFont(font, sizePt)
            cs.setNonStrokingColor(color[0], color[1], color[2])
            cs.setLeading(sizePt * 1.25f)
            cs.newLineAtOffset(pad, h - pad - sizePt)
            lines.forEachIndexed { i, line ->
                if (i > 0) cs.newLine()
                cs.showText(line)
            }
            cs.endText()
        }
        addAnnotation(page, ann)
    }

    /** Annotations a user may want to remove (not form fields, links or popups). */
    suspend fun annotations(file: File): List<AnnotationInfo> = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { doc ->
            val out = ArrayList<AnnotationInfo>()
            doc.pages.forEachIndexed { pageIndex, page ->
                page.annotations.forEachIndexed { i, ann ->
                    val subtype = ann.subtype ?: return@forEachIndexed
                    if (subtype in setOf("Widget", "Link", "Popup")) return@forEachIndexed
                    val r = ann.rectangle ?: return@forEachIndexed
                    val tl = fromUserSpace(page, r.lowerLeftX, r.upperRightY)
                    val br = fromUserSpace(page, r.upperRightX, r.lowerLeftY)
                    out += AnnotationInfo(
                        page = pageIndex,
                        index = i,
                        subtype = subtype,
                        text = ann.contents.orEmpty(),
                        left = minOf(tl.x, br.x), top = minOf(tl.y, br.y), right = maxOf(tl.x, br.x), bottom = maxOf(tl.y, br.y),
                        color = annotationColor(ann) ?: if (subtype == "FreeText") 0x000000 else 0xFFE066,
                        fontSizePt = ann.cosObject.getString(COSName.DA)
                            ?.let { Regex("([0-9.]+)\\s+Tf").find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 12f,
                    )
                }
            }
            out
        }
    }

    /** The colour of an annotation: from `/DA` for free text, else its `/C` entry. */
    private fun annotationColor(ann: PDAnnotation): Int? {
        if (ann.subtype == "FreeText") {
            val da = ann.cosObject.getString(COSName.DA) ?: return null
            val m = Regex("([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s+rg").find(da) ?: return null
            val (r, g, b) = m.destructured
            return rgbInt(r.toFloat(), g.toFloat(), b.toFloat())
        }
        val c = runCatching { ann.color?.components }.getOrNull() ?: return null
        return when (c.size) {
            3 -> rgbInt(c[0], c[1], c[2])
            1 -> rgbInt(c[0], c[0], c[0])
            4 -> rgbInt((1 - c[0]) * (1 - c[3]), (1 - c[1]) * (1 - c[3]), (1 - c[2]) * (1 - c[3]))
            else -> null
        }
    }

    private fun rgbInt(r: Float, g: Float, b: Float): Int {
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    suspend fun deleteAnnotation(file: File, pageIndex: Int, index: Int) = edit(file, null) { doc ->
        val page = doc.getPage(pageIndex)
        val list = page.annotations.toMutableList()
        if (index in list.indices) {
            val removed = list.removeAt(index)
            // Drop the popup that belongs to a markup annotation, if any.
            (removed as? PDAnnotationMarkup)?.popup?.let { popup -> list.removeAll { it.cosObject == popup.cosObject } }
            page.annotations = list
        }
    }

    /** Stamps an image (e.g. a signature PNG) into the page between two corners. */
    suspend fun stampImage(file: File, pageIndex: Int, a: PagePoint, b: PagePoint, image: android.graphics.Bitmap) = edit(file, null) { doc ->
        val page = doc.getPage(pageIndex)
        val p1 = toUserSpace(page, a)
        val p2 = toUserSpace(page, b)
        val xobject = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, image)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.drawImage(
                xobject,
                minOf(p1.first, p2.first),
                minOf(p1.second, p2.second),
                kotlin.math.abs(p1.first - p2.first),
                kotlin.math.abs(p1.second - p2.second),
            )
        }
    }

    /** Lists the fillable fields of an AcroForm, or an empty list when the PDF has none. */
    suspend fun formFields(file: File): List<FormField> = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return@use emptyList()
            form.fieldTree.mapNotNull { f ->
                val label = f.alternateFieldName?.takeIf { it.isNotBlank() } ?: f.partialName ?: f.fullyQualifiedName
                when (f) {
                    is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField ->
                        FormField(f.fullyQualifiedName, label, FieldType.TEXT, f.value.orEmpty(), readOnly = f.isReadOnly)
                    is com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox ->
                        FormField(f.fullyQualifiedName, label, FieldType.CHECKBOX, f.isChecked.toString(), readOnly = f.isReadOnly)
                    is com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton ->
                        FormField(f.fullyQualifiedName, label, FieldType.RADIO, f.value.orEmpty(), f.exportValues.orEmpty(), f.isReadOnly)
                    is com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice ->
                        FormField(f.fullyQualifiedName, label, FieldType.CHOICE, f.value.firstOrNull().orEmpty(), f.options.orEmpty(), f.isReadOnly)
                    else -> null
                }
            }.toList()
        }
    }

    /** Writes field values; with [flatten] the form becomes plain page content that can no longer be edited. */
    suspend fun fillForm(file: File, values: Map<String, String>, flatten: Boolean) = edit(file, null) { doc ->
        val form = doc.documentCatalog.acroForm ?: return@edit
        for ((name, value) in values) {
            val f = form.getField(name) ?: continue
            runCatching {
                when (f) {
                    is com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox -> if (value == "true") f.check() else f.unCheck()
                    is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField -> f.value = value
                    is com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton -> if (value.isNotEmpty()) f.value = value
                    is com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice -> if (value.isNotEmpty()) f.setValue(value)
                    else -> f.setValue(value)
                }
            }.onFailure {
                // Some fonts cannot regenerate appearances; let viewers draw them instead.
                form.needAppearances = true
            }
        }
        if (flatten) runCatching { form.flatten() }
    }

    /**
     * Irreversibly redacts regions: each affected page is replaced by an image of itself (rendered
     * beforehand into [pages]) in which the regions are pixelated or blacked out. The page's text,
     * fonts, images and annotations are removed, so the hidden content cannot be recovered.
     */
    suspend fun redact(file: File, pages: Map<Int, android.graphics.Bitmap>, regions: Map<Int, List<Pair<PagePoint, PagePoint>>>, mode: RedactMode) =
        edit(file, null) { doc ->
            val form = doc.documentCatalog.acroForm
            for ((index, source) in pages) {
                val bmp = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                regions[index].orEmpty().forEach { (a, b) -> obscure(bmp, a, b, mode) }
                val page = doc.getPage(index)
                // Size of the page as displayed (rotation applied), in points.
                val rotated = ((page.rotation % 360) + 360) % 360 in setOf(90, 270)
                val w = if (rotated) page.cropBox.height else page.cropBox.width
                val h = if (rotated) page.cropBox.width else page.cropBox.height
                // Drop form fields whose widgets live on this page, so their values go too.
                if (form != null) {
                    val widgets = page.annotations.filter { it.subtype == "Widget" }.map { it.cosObject }.toSet()
                    if (widgets.isNotEmpty()) {
                        form.fields = form.fields.filter { f -> f.widgets.none { it.cosObject in widgets } }
                    }
                }
                page.annotations = emptyList()
                page.resources = PDResources()
                page.rotation = 0
                page.mediaBox = PDRectangle(0f, 0f, w, h)
                page.cropBox = PDRectangle(0f, 0f, w, h)
                val image = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, bmp, 0.9f)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
                    cs.drawImage(image, 0f, 0f, w, h)
                }
            }
        }

    /** Destroys the pixels in a region: a heavy pixelation followed by a blur, or a solid black box. */
    private fun obscure(bmp: android.graphics.Bitmap, a: PagePoint, b: PagePoint, mode: RedactMode) {
        val left = (minOf(a.x, b.x) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val top = (minOf(a.y, b.y) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val right = (maxOf(a.x, b.x) * bmp.width).toInt().coerceIn(left + 1, bmp.width)
        val bottom = (maxOf(a.y, b.y) * bmp.height).toInt().coerceIn(top + 1, bmp.height)
        val canvas = android.graphics.Canvas(bmp)
        val rect = android.graphics.Rect(left, top, right, bottom)
        if (mode == RedactMode.BLACK) {
            canvas.drawRect(rect, android.graphics.Paint().apply { color = android.graphics.Color.BLACK })
            return
        }
        val region = android.graphics.Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
        // Average blocks of ~24 px (at 200 dpi a line of text is ~30 px tall), then blur the blocks.
        val tiny = android.graphics.Bitmap.createScaledBitmap(region, maxOf(1, region.width / 24), maxOf(1, region.height / 24), true)
        val smaller = android.graphics.Bitmap.createScaledBitmap(tiny, maxOf(1, tiny.width / 2), maxOf(1, tiny.height / 2), true)
        val blurred = android.graphics.Bitmap.createScaledBitmap(smaller, region.width, region.height, true)
        canvas.drawBitmap(blurred, null, rect, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    }

    /** Text of every page, used for search. */
    suspend fun pageTexts(file: File): List<String> = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            (1..doc.numberOfPages).map { n ->
                stripper.startPage = n
                stripper.endPage = n
                runCatching { stripper.getText(doc) }.getOrDefault("")
            }
        }
    }

    internal fun loadFont(doc: PDDocument): PDFont {
        val candidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) runCatching { return PDType0Font.load(doc, f) }
        }
        return PDType1Font.HELVETICA
    }

    /** Helvetica only covers WinAnsi; map Turkish letters to their closest ASCII forms. */
    internal fun asciiFallback(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                'ı' -> 'i'; 'İ' -> 'I'; 'ş' -> 's'; 'Ş' -> 'S'; 'ğ' -> 'g'; 'Ğ' -> 'G'
                else -> if (c.code < 256) c else '?'
            },
        )
    }

    /** Converts a PDF user-space point to a normalised point on the displayed page. */
    private fun fromUserSpace(page: PDPage, x: Float, y: Float): PagePoint {
        val box: PDRectangle = page.cropBox
        val u = (x - box.lowerLeftX) / box.width
        val v = (box.upperRightY - y) / box.height
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> PagePoint(1f - v, u)
            180 -> PagePoint(1f - u, 1f - v)
            270 -> PagePoint(v, 1f - u)
            else -> PagePoint(u, v)
        }
    }

    /** Converts a point on the displayed (rotated) page to PDF user space. */
    private fun toUserSpace(page: PDPage, p: PagePoint): Pair<Float, Float> {
        val box: PDRectangle = page.cropBox
        val (u, v) = when (((page.rotation % 360) + 360) % 360) {
            90 -> p.y to 1f - p.x
            180 -> 1f - p.x to 1f - p.y
            270 -> 1f - p.y to p.x
            else -> p.x to p.y
        }
        return (box.lowerLeftX + u * box.width) to (box.upperRightY - v * box.height)
    }
}
