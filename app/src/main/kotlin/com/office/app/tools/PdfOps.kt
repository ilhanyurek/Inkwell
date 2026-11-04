package com.office.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.office.app.export.PdfExporter
import com.office.app.ui.pdf.PdfTools
import com.office.app.ui.slides.SlideRenderer
import com.office.core.csv.Csv
import com.office.core.docx.DocxReader
import com.office.core.docx.DocxWriter
import com.office.core.formula.FormulaEngine
import com.office.core.pptx.PptxReader
import com.office.core.pptx.PptxWriter
import com.office.core.text.MarkdownCodec
import com.office.core.text.Paragraph
import com.office.core.text.ParagraphStyle
import com.office.core.text.PlainTextCodec
import com.office.core.text.TextDocument
import com.office.core.text.TextRun
import com.office.core.util.TextDiff
import com.office.core.xlsx.XlsxReader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** File-to-file operations behind the Tools tab. Inputs are local copies; all functions run on IO. */
object PdfOps {

    enum class PageSize { A4, LETTER, FIT }
    enum class Position { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }
    enum class NumberFormat { PLAIN, OF_TOTAL, PAGE_WORD }
    enum class ImageFormat { JPG, PNG }

    /** One page of an "organize" result: a source page with a rotation, or a blank page. */
    data class PageSpec(val source: Int, val rotation: Int = 0) {
        val isBlank get() = source < 0
    }

    data class Metadata(
        val title: String = "",
        val author: String = "",
        val subject: String = "",
        val keywords: String = "",
        val creator: String = "",
        val producer: String = "",
    )

    private fun load(file: File, password: String?): PDDocument {
        val doc = if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    suspend fun pageCount(file: File, password: String? = null): Int = withContext(Dispatchers.IO) {
        load(file, password).use { it.numberOfPages }
    }

    // --- Organize -----------------------------------------------------------------------------

    suspend fun merge(inputs: List<File>, output: File) = withContext(Dispatchers.IO) {
        val merger = PDFMergerUtility()
        inputs.forEach { merger.addSource(it) }
        merger.destinationFileName = output.absolutePath
        merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
    }

    /** Builds a new PDF from [pages] of [file] (in that order, duplicates allowed, -1 = blank page). */
    suspend fun organize(file: File, pages: List<PageSpec>, output: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(file, password).use { src ->
            PDDocument().use { dst ->
                var lastBox = PDRectangle.A4
                for (spec in pages) {
                    val page = if (spec.isBlank) {
                        PDPage(PDRectangle(lastBox.width, lastBox.height)).also { dst.addPage(it) }
                    } else {
                        val s = src.getPage(spec.source)
                        lastBox = s.mediaBox
                        dst.importPage(s).also { it.rotation = s.rotation }
                    }
                    page.rotation = ((page.rotation + spec.rotation) % 360 + 360) % 360
                }
                dst.save(output)
            }
        }
    }

    suspend fun extractPages(file: File, pages: List<Int>, output: File, password: String? = null) =
        organize(file, pages.map { PageSpec(it) }, output, password)

    suspend fun deletePages(file: File, pages: Set<Int>, output: File, password: String? = null) {
        val count = pageCount(file, password)
        val keep = (0 until count).filter { it !in pages }
        require(keep.isNotEmpty()) { "At least one page must remain" }
        organize(file, keep.map { PageSpec(it) }, output, password)
    }

    suspend fun rotatePages(file: File, pages: Set<Int>?, degrees: Int, output: File, password: String? = null) {
        val count = pageCount(file, password)
        organize(file, (0 until count).map { PageSpec(it, if (pages == null || it in pages) degrees else 0) }, output, password)
    }

    /** Splits into files of [every] pages each; returns the parts written by [part]. */
    suspend fun split(file: File, every: Int, dir: File, password: String? = null): List<File> {
        val count = pageCount(file, password)
        val base = file.nameWithoutExtension
        return (0 until count step every).mapIndexed { n, start ->
            val out = File(dir, "$base-${n + 1}.pdf")
            extractPages(file, (start until minOf(count, start + every)).toList(), out, password)
            out
        }
    }

    // --- Optimize -----------------------------------------------------------------------------

    /**
     * Re-encodes page images as JPEG, downscaled to [maxSide] pixels at [quality]. Images with transparency are
     * left alone. Returns false when the result would not be smaller, in which case [output] is a plain copy.
     */
    suspend fun compress(file: File, maxSide: Int, quality: Float, output: File, password: String? = null): Boolean = withContext(Dispatchers.IO) {
        load(file, password).use { doc ->
            val done = HashMap<Any, PDImageXObject>()
            for (page in doc.pages) compressResources(doc, page.resources, maxSide, quality, done, 0)
            doc.save(output)
        }
        if (output.length() >= file.length()) {
            file.copyTo(output, overwrite = true)
            false
        } else true
    }

    private fun compressResources(doc: PDDocument, res: PDResources?, maxSide: Int, quality: Float, done: HashMap<Any, PDImageXObject>, depth: Int) {
        if (res == null || depth > 4) return
        for (name in res.xObjectNames.toList()) {
            val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (x) {
                is PDFormXObject -> compressResources(doc, x.resources, maxSide, quality, done, depth + 1)
                is PDImageXObject -> {
                    val key = x.cosObject
                    val already = done[key]
                    if (already != null) { res.put(name, already); continue }
                    if (x.cosObject.getDictionaryObject(COSName.SMASK) != null || x.isStencil || x.bitsPerComponent == 1) continue
                    val bmp = runCatching { x.image }.getOrNull() ?: continue
                    val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
                    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
                    val opaque = if (scaled.hasAlpha()) scaled.copy(Bitmap.Config.RGB_565, false) else scaled
                    val jpeg = runCatching { JPEGFactory.createFromImage(doc, opaque, quality) }.getOrNull() ?: continue
                    if (jpeg.cosObject.length < x.cosObject.length) {
                        res.put(name, jpeg)
                        done[key] = jpeg
                    }
                }
            }
        }
    }

    /** Loads leniently and writes the document again, which rebuilds a broken cross-reference table. */
    suspend fun repair(file: File, output: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(file, password).use { it.save(output) }
    }

    // --- Convert to PDF -------------------------------------------------------------------------

    /**
     * Places each image on its own page. With [PageSize.FIT] the page takes the image's proportions; otherwise
     * the image is fitted inside the page (turned to landscape for wide images) with [marginPt] around it.
     * [documentFilter] turns photos of paper into clean greyscale scans.
     */
    suspend fun imagesToPdf(
        context: Context,
        images: List<Uri>,
        size: PageSize,
        marginPt: Float,
        documentFilter: Boolean,
        output: File,
    ) = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            for (uri in images) {
                val bmp = decodeImage(context, uri, 2400) ?: continue
                val img = if (documentFilter) scanFilter(bmp) else bmp
                val landscape = img.width > img.height
                val box = when (size) {
                    PageSize.A4 -> if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                    PageSize.LETTER -> if (landscape) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width) else PDRectangle.LETTER
                    PageSize.FIT -> {
                        val s = 842f / maxOf(img.width, img.height)
                        PDRectangle(img.width * s + marginPt * 2, img.height * s + marginPt * 2)
                    }
                }
                val page = PDPage(box)
                doc.addPage(page)
                val xo = if (usesAlpha(img)) LosslessFactory.createFromImage(doc, img) else JPEGFactory.createFromImage(doc, img, 0.85f)
                val availW = box.width - marginPt * 2
                val availH = box.height - marginPt * 2
                val s = minOf(availW / img.width, availH / img.height)
                val w = img.width * s
                val h = img.height * s
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(xo, (box.width - w) / 2, (box.height - h) / 2, w, h)
                }
            }
            require(doc.numberOfPages > 0) { "No readable images" }
            doc.save(output)
        }
    }

    /** True when some pixel is actually transparent (PNG screenshots usually are fully opaque). */
    private fun usesAlpha(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val row = IntArray(b.width)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            if (row.any { (it ushr 24) != 0xFF }) return true
        }
        return false
    }

    /** Decodes an image no larger than [maxSide], turned upright according to its EXIF orientation. */
    fun decodeImage(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { s ->
                when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        val upright = if (degrees != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true) else bmp
        val s = minOf(1f, maxSide.toFloat() / maxOf(upright.width, upright.height))
        return if (s < 1f) Bitmap.createScaledBitmap(upright, (upright.width * s).toInt(), (upright.height * s).toInt(), true) else upright
    }

    /** Greyscale with raised contrast and brightness, so paper turns white and ink dark. */
    fun scanFilter(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val c = 1.6f
        val t = -0.3f * 255
        gray.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(gray) })
        return out
    }

    suspend fun wordToPdf(input: File, output: File) = withContext(Dispatchers.IO) {
        val doc = input.inputStream().use { DocxReader.read(it) }
        output.outputStream().use { PdfExporter.document(doc, input.name, it) }
    }

    suspend fun textToPdf(input: File, markdown: Boolean, output: File) = withContext(Dispatchers.IO) {
        val bytes = input.readBytes()
        val doc = if (markdown) MarkdownCodec.read(bytes) else PlainTextCodec.read(bytes)
        output.outputStream().use { PdfExporter.document(doc, input.name, it) }
    }

    /** Every sheet of a workbook (or a CSV) one after another. */
    suspend fun sheetToPdf(input: File, csv: Boolean, output: File) = withContext(Dispatchers.IO) {
        val wb = if (csv) Csv.toWorkbook(Csv.parse(input.readBytes())) else input.inputStream().use { XlsxReader.read(it) }
        val engine = FormulaEngine(wb)
        val parts = wb.sheets.mapIndexed { i, sheet ->
            File(output.parentFile, "${output.nameWithoutExtension}-part$i.pdf").also { f ->
                f.outputStream().use { PdfExporter.sheet(sheet, engine, it) }
            }
        }
        try {
            if (parts.size == 1) parts[0].copyTo(output, overwrite = true) else merge(parts, output)
        } finally {
            parts.forEach { it.delete() }
        }
    }

    suspend fun slidesToPdf(input: File, output: File) = withContext(Dispatchers.IO) {
        val pres = input.inputStream().use { PptxReader.read(it) }
        output.outputStream().use { SlideRenderer.exportPdf(pres, it) }
    }

    // --- Convert from PDF -----------------------------------------------------------------------

    /** Renders pages with the platform renderer; [onPage] receives each page bitmap and must not keep it. */
    suspend fun renderPages(file: File, pages: List<Int>?, longSidePx: Int, onPage: (Int, Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                for (i in pages ?: (0 until r.pageCount).toList()) {
                    r.openPage(i).use { page ->
                        val s = longSidePx.toFloat() / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * s).toInt().coerceAtLeast(1), (page.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        onPage(i, bmp)
                        bmp.recycle()
                    }
                }
            }
        }
    }

    /** Writes each page as an image via [sink] (file name, bytes). [dpi] is relative to 72 dpi page units. */
    suspend fun pdfToImages(file: File, dpi: Int, format: ImageFormat, sink: (String, ByteArray) -> Unit) {
        val base = file.nameWithoutExtension
        val longSide = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r -> r.openPage(0).use { maxOf(it.width, it.height) } }
            }
        }
        val px = (longSide * dpi / 72f).toInt().coerceIn(200, 6000)
        renderPages(file, null, px) { i, bmp ->
            val bytes = ByteArrayOutputStream().also {
                if (format == ImageFormat.PNG) bmp.compress(Bitmap.CompressFormat.PNG, 100, it) else bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray()
            sink("$base-${i + 1}.${if (format == ImageFormat.PNG) "png" else "jpg"}", bytes)
        }
    }

    suspend fun pdfToText(file: File, password: String? = null): String = withContext(Dispatchers.IO) {
        load(file, password).use { doc ->
            val stripper = PDFTextStripper()
            (1..doc.numberOfPages).joinToString("\n\n") { n ->
                stripper.startPage = n
                stripper.endPage = n
                runCatching { stripper.getText(doc) }.getOrDefault("").trim()
            }
        }
    }

    /** A text-only Word document: one paragraph per block of text, a heading per page. */
    suspend fun pdfToWord(file: File, output: File, pageLabel: (Int) -> String, password: String? = null) = withContext(Dispatchers.IO) {
        val paragraphs = ArrayList<Paragraph>()
        load(file, password).use { doc ->
            val stripper = PDFTextStripper().apply { paragraphStart = "\n\u0000"; paragraphEnd = "" }
            for (n in 1..doc.numberOfPages) {
                stripper.startPage = n
                stripper.endPage = n
                val text = runCatching { stripper.getText(doc) }.getOrDefault("")
                if (doc.numberOfPages > 1) paragraphs += Paragraph(listOf(TextRun(pageLabel(n))), ParagraphStyle.HEADING2)
                text.split('\u0000').map { block -> block.lines().joinToString(" ") { it.trim() }.replace(Regex(" +"), " ").trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { paragraphs += Paragraph(listOf(TextRun(it))) }
            }
        }
        if (paragraphs.isEmpty()) paragraphs += Paragraph(listOf(TextRun("")))
        output.outputStream().use { DocxWriter.write(TextDocument(paragraphs), it) }
    }

    /** One slide per page, each showing the page as a picture. */
    suspend fun pdfToPowerPoint(file: File, output: File) {
        val pictures = ArrayList<PptxWriter.Picture>()
        var aspect = 16f / 9f
        renderPages(file, null, 1920) { i, bmp ->
            if (i == 0) aspect = bmp.width.toFloat() / bmp.height
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            pictures += PptxWriter.Picture(bytes, bmp.width, bmp.height, jpeg = true)
        }
        val width = 12192000L
        withContext(Dispatchers.IO) {
            output.outputStream().use { PptxWriter.writePictures(pictures, it, width, (width / aspect).toLong()) }
        }
    }

    // --- Edit & secure --------------------------------------------------------------------------

    private suspend fun stamp(file: File, output: File, password: String?, draw: (PDDocument, PDPage, Int, Int, PDPageContentStream) -> Unit) =
        withContext(Dispatchers.IO) {
            load(file, password).use { doc ->
                val count = doc.numberOfPages
                doc.pages.forEachIndexed { i, page ->
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs -> draw(doc, page, i, count, cs) }
                }
                doc.save(output)
            }
        }

    private fun fontFor(doc: PDDocument, text: String) = PdfTools.loadFont(doc).let { f ->
        f to (if (f is PDType1Font) PdfTools.asciiFallback(text) else text)
    }

    private fun alpha(cs: PDPageContentStream, opacity: Float) {
        cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity; strokingAlphaConstant = opacity })
    }

    private fun rgb(color: Int) = floatArrayOf(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)

    /** Draws [text] across the middle of every page at [angle] degrees. */
    suspend fun watermark(file: File, text: String, sizePt: Float, opacity: Float, angle: Float, color: Int, output: File, password: String? = null) {
        val fonts = HashMap<PDDocument, Pair<com.tom_roush.pdfbox.pdmodel.font.PDFont, String>>()
        stamp(file, output, password) { doc, page, _, _, cs ->
            val (font, safe) = fonts.getOrPut(doc) { fontFor(doc, text) }
            val box = page.cropBox
            val width = font.getStringWidth(safe) / 1000f * sizePt
            val c = rgb(color)
            alpha(cs, opacity)
            cs.beginText()
            cs.setFont(font, sizePt)
            cs.setNonStrokingColor(c[0], c[1], c[2])
            val m = PdfMatrix.getTranslateInstance(box.lowerLeftX + box.width / 2, box.lowerLeftY + box.height / 2)
            m.rotate(Math.toRadians(angle.toDouble()))
            m.translate(-width / 2, -sizePt / 3)
            cs.setTextMatrix(m)
            cs.showText(safe)
            cs.endText()
        }
    }

    suspend fun pageNumbers(
        file: File,
        position: Position,
        format: NumberFormat,
        start: Int,
        sizePt: Float,
        pageWord: String,
        output: File,
        password: String? = null,
    ) {
        stamp(file, output, password) { doc, page, i, count, cs ->
            val n = start + i
            val label = when (format) {
                NumberFormat.PLAIN -> "$n"
                NumberFormat.OF_TOTAL -> "$n / ${count + start - 1}"
                NumberFormat.PAGE_WORD -> "$pageWord $n"
            }
            val (font, safe) = fontFor(doc, label)
            val box = page.cropBox
            val w = font.getStringWidth(safe) / 1000f * sizePt
            val margin = 28f
            val x = when (position) {
                Position.TOP_LEFT, Position.BOTTOM_LEFT -> box.lowerLeftX + margin
                Position.TOP_CENTER, Position.BOTTOM_CENTER -> box.lowerLeftX + (box.width - w) / 2
                else -> box.upperRightX - margin - w
            }
            val y = if (position.name.startsWith("TOP")) box.upperRightY - margin - sizePt else box.lowerLeftY + margin
            cs.beginText()
            cs.setFont(font, sizePt)
            cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
            cs.newLineAtOffset(x, y)
            cs.showText(safe)
            cs.endText()
        }
    }

    /** AES-256 encryption. [ownerPassword] defaults to the user password. */
    suspend fun protect(
        file: File,
        userPassword: String,
        allowPrint: Boolean,
        allowCopy: Boolean,
        allowEdit: Boolean,
        output: File,
        password: String? = null,
    ) = withContext(Dispatchers.IO) {
        load(file, password).use { doc ->
            val perms = AccessPermission().apply {
                setCanPrint(allowPrint)
                setCanPrintFaithful(allowPrint)
                setCanExtractContent(allowCopy)
                setCanExtractForAccessibility(true)
                setCanModify(allowEdit)
                setCanModifyAnnotations(allowEdit)
                setCanFillInForm(allowEdit)
                setCanAssembleDocument(allowEdit)
            }
            val policy = StandardProtectionPolicy(userPassword, userPassword, perms).apply { encryptionKeyLength = 256 }
            doc.protect(policy)
            doc.save(output)
        }
    }

    suspend fun unlock(file: File, password: String, output: File) = PdfTools.decrypt(file, password, output)

    /** Trims [marginsPt] (left, top, right, bottom) off the visible area of the chosen pages (all when null). */
    suspend fun crop(file: File, pages: Set<Int>?, marginsPt: FloatArray, output: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(file, password).use { doc ->
            doc.pages.forEachIndexed { i, page ->
                if (pages != null && i !in pages) return@forEachIndexed
                val b = page.cropBox
                val (l, t, r, bo) = marginsPt.toList()
                val w = (b.width - l - r).coerceAtLeast(10f)
                val h = (b.height - t - bo).coerceAtLeast(10f)
                page.cropBox = PDRectangle(b.lowerLeftX + l, b.lowerLeftY + bo, w, h)
            }
            doc.save(output)
        }
    }

    /** Burns form fields and annotations (highlights, notes, stamps) into the page so they can no longer change. */
    suspend fun flatten(file: File, output: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(file, password).use { doc ->
            doc.documentCatalog.acroForm?.let { form ->
                runCatching { form.refreshAppearances() }
                form.flatten()
            }
            for (page in doc.pages) {
                val keep = ArrayList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>()
                val burn = ArrayList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>()
                for (a in page.annotations) {
                    val ap = a.normalAppearanceStream
                    if (a.subtype in setOf("Link", "Popup", "Widget") || ap == null || a.isHidden || a.isNoView) keep += a else burn += a
                }
                if (burn.isEmpty()) continue
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in burn) {
                        val ap = a.normalAppearanceStream
                        val rect = a.rectangle ?: continue
                        val bbox = ap.bBox ?: continue
                        if (bbox.width <= 0f || bbox.height <= 0f) continue
                        cs.saveGraphicsState()
                        val opacity = a.cosObject.getFloat(COSName.CA, 1f)
                        if (opacity < 1f) alpha(cs, opacity)
                        val sx = rect.width / bbox.width
                        val sy = rect.height / bbox.height
                        cs.transform(PdfMatrix(sx, 0f, 0f, sy, rect.lowerLeftX - bbox.lowerLeftX * sx, rect.lowerLeftY - bbox.lowerLeftY * sy))
                        cs.drawForm(ap)
                        cs.restoreGraphicsState()
                    }
                }
                // Popups belong to the annotations that were burned in.
                page.annotations = keep.filter { it.subtype != "Popup" }
            }
            doc.save(output)
        }
    }

    suspend fun readMetadata(file: File, password: String? = null): Metadata = withContext(Dispatchers.IO) {
        load(file, password).use { doc ->
            val info = doc.documentInformation
            Metadata(
                info.title.orEmpty(), info.author.orEmpty(), info.subject.orEmpty(),
                info.keywords.orEmpty(), info.creator.orEmpty(), info.producer.orEmpty(),
            )
        }
    }

    /** Replaces the document properties; blank fields are removed, and the XMP copy is dropped. */
    suspend fun writeMetadata(file: File, meta: Metadata, output: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(file, password).use { doc ->
            val info = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
            meta.title.takeIf { it.isNotBlank() }?.let { info.title = it }
            meta.author.takeIf { it.isNotBlank() }?.let { info.author = it }
            meta.subject.takeIf { it.isNotBlank() }?.let { info.subject = it }
            meta.keywords.takeIf { it.isNotBlank() }?.let { info.keywords = it }
            meta.creator.takeIf { it.isNotBlank() }?.let { info.creator = it }
            meta.producer.takeIf { it.isNotBlank() }?.let { info.producer = it }
            doc.documentInformation = info
            doc.documentCatalog.metadata = null
            doc.save(output)
        }
    }

    /** Writes every distinct embedded image via [sink]; returns how many were found. */
    suspend fun extractImages(file: File, password: String? = null, sink: (String, ByteArray) -> Unit): Int = withContext(Dispatchers.IO) {
        var n = 0
        val seen = HashSet<Any>()
        load(file, password).use { doc ->
            fun walk(res: PDResources?, depth: Int) {
                if (res == null || depth > 4) return
                for (name in res.xObjectNames) {
                    when (val x = runCatching { res.getXObject(name) }.getOrNull()) {
                        is PDFormXObject -> walk(x.resources, depth + 1)
                        is PDImageXObject -> {
                            if (!seen.add(x.cosObject)) continue
                            val bmp = runCatching { x.image }.getOrNull() ?: continue
                            n++
                            val png = bmp.hasAlpha() || x.suffix == "png"
                            val bytes = ByteArrayOutputStream().also {
                                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
                            }.toByteArray()
                            sink("${file.nameWithoutExtension}-image$n.${if (png) "png" else "jpg"}", bytes)
                        }
                        else -> {}
                    }
                }
            }
            doc.pages.forEach { walk(it.resources, 0) }
        }
        n
    }

    /** Line differences between the texts of two PDFs, page by page. */
    suspend fun compare(a: File, b: File, pageLabel: (Int) -> String): List<TextDiff.Line> {
        val ta = PdfTools.pageTexts(a)
        val tb = PdfTools.pageTexts(b)
        val lines = { pages: List<String> ->
            pages.flatMapIndexed { i, t -> listOf(pageLabel(i + 1)) + t.lines().map { it.trim() }.filter { it.isNotEmpty() } }
        }
        return TextDiff.diff(lines(ta), lines(tb))
    }
}
