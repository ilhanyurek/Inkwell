package com.office.app.ui.document

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.office.app.AppContainer
import com.office.app.data.FileInfo
import com.office.app.export.PdfExporter
import com.office.core.FileKind
import com.office.core.docx.DocxReader
import com.office.core.docx.DocxWriter
import com.office.core.text.Embed
import com.office.core.text.TextDocument
import com.office.core.zip.ZipPackage
import com.office.core.text.Alignment
import com.office.core.text.CharStyle
import com.office.core.text.InlineAttr
import com.office.core.text.ListType
import com.office.core.text.MarkdownCodec
import com.office.core.text.ParagraphStyle
import com.office.core.text.PlainTextCodec
import com.office.core.text.RichText
import com.office.core.text.has
import com.office.core.text.with
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DocumentEditorViewModel(
    private val container: AppContainer,
    initialUri: Uri?,
    info: FileInfo?,
    val kind: FileKind,
) : ViewModel() {

    sealed interface LoadState {
        data object Loading : LoadState
        data object Ready : LoadState
        data class Failed(val message: String?) : LoadState
    }

    var loadState by mutableStateOf<LoadState>(if (initialUri == null) LoadState.Ready else LoadState.Loading)
        private set
    var uri by mutableStateOf(initialUri)
        private set
    var fileName by mutableStateOf(info?.name)
        private set
    var model by mutableStateOf(RichText.empty())
        private set
    var value by mutableStateOf(TextFieldValue(""))
        private set
    /** Formatting chosen with a collapsed selection, applied to the next typed characters. */
    var pendingStyle by mutableStateOf<CharStyle?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var lossyImport by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set

    /** Images, tables, links… from the source file; kept as-is and written back on save. */
    var embeds by mutableStateOf<Map<Int, Embed>>(emptyMap())
        private set

    /** The DOCX package the document came from, so saving keeps everything we do not edit. */
    private var originalPackage: ZipPackage? = null

    fun currentDocument(): TextDocument = model.toDocument(embeds = embeds)

    private data class Snapshot(val model: RichText, val selection: TextRange)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastTypingAt = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val supportsRichFormatting get() = kind == FileKind.DOCUMENT
    val supportsMarkdownFormatting get() = kind == FileKind.MARKDOWN

    init {
        if (initialUri != null) load(initialUri)
    }

    private fun load(uri: Uri) {
        viewModelScope.launch {
            loadState = LoadState.Loading
            runCatching {
                val bytes = container.files.readBytes(uri)
                withContext(Dispatchers.Default) {
                    when (kind) {
                        FileKind.DOCUMENT -> ZipPackage.read(ByteArrayInputStream(bytes)).let { pkg ->
                            originalPackage = pkg
                            DocxReader.read(pkg)
                        }
                        FileKind.MARKDOWN -> MarkdownCodec.read(bytes)
                        else -> PlainTextCodec.read(bytes)
                    }
                }
            }.onSuccess { doc ->
                model = RichText.fromDocument(doc)
                lossyImport = doc.lossyImport
                embeds = doc.embeds
                value = TextFieldValue(model.text, TextRange(0))
                loadState = LoadState.Ready
            }.onFailure {
                loadState = LoadState.Failed(it.message)
            }
        }
    }

    // --- editing ----------------------------------------------------------------------

    fun onValueChange(new: TextFieldValue) {
        val old = value
        if (new.text == old.text) {
            if (new.selection != old.selection) pendingStyle = null
            value = new
            return
        }
        val typing = pendingStyle ?: if (old.selection.collapsed) model.typingStyleAt(old.selection.start)
        else model.styleAt(old.selection.min).forTyping
        val next = model.applyEdit(new.text, typing)
        val now = System.currentTimeMillis()
        if (now - lastTypingAt > 900) pushUndo()
        lastTypingAt = now
        model = next
        value = if (next.text == new.text) new else TextFieldValue(next.text, old.selection)
        dirty = true
    }

    private fun pushUndo() {
        undoStack.addLast(Snapshot(model, value.selection))
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
    }

    private fun commit(next: RichText, selection: TextRange = value.selection) {
        if (next == model) return
        pushUndo()
        lastTypingAt = 0
        model = next
        value = TextFieldValue(next.text, selection)
        dirty = true
    }

    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(model, value.selection))
        model = snap.model
        value = TextFieldValue(snap.model.text, snap.selection)
        dirty = true
        canUndo = undoStack.isNotEmpty()
        canRedo = true
        lastTypingAt = 0
    }

    fun redo() {
        val snap = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(model, value.selection))
        model = snap.model
        value = TextFieldValue(snap.model.text, snap.selection)
        dirty = true
        canUndo = true
        canRedo = redoStack.isNotEmpty()
        lastTypingAt = 0
    }

    // --- formatting -------------------------------------------------------------------

    /** Effective style at the caret, for highlighting toolbar buttons. */
    val caretStyle: CharStyle
        get() {
            pendingStyle?.let { return it }
            val sel = value.selection
            return if (sel.collapsed) model.typingStyleAt(sel.start) else model.styleAt(sel.min)
        }

    val caretParagraph get() = model.paras.getOrNull(model.lineIndexAt(value.selection.start))

    fun toggleInline(attr: InlineAttr) {
        val sel = value.selection
        if (sel.collapsed) {
            val current = caretStyle
            pendingStyle = current.with(attr, !current.has(attr))
        } else {
            commit(model.toggleInline(sel.min, sel.max, attr))
        }
    }

    fun setParagraphStyle(style: ParagraphStyle) {
        val sel = value.selection
        commit(model.setParagraphStyle(sel.min, sel.max, style))
    }

    fun toggleList(type: ListType) {
        val sel = value.selection
        commit(model.toggleList(sel.min, sel.max, type))
    }

    fun setAlignment(alignment: Alignment) {
        val sel = value.selection
        commit(model.setAlignment(sel.min, sel.max, alignment))
    }

    fun setFontSize(sizePt: Float?) {
        val sel = value.selection
        if (sel.collapsed) pendingStyle = caretStyle.copy(sizePt = sizePt)
        else commit(model.setSize(sel.min, sel.max, sizePt))
    }

    fun clearFormatting() {
        val sel = value.selection
        if (sel.collapsed) {
            pendingStyle = CharStyle.PLAIN
            val line = model.lineIndexAt(sel.start)
            commit(model.withParas(line..line) { com.office.core.text.ParaAttrs() })
        } else {
            commit(model.clearFormatting(sel.min, sel.max).withParas(model.paragraphsIn(sel.min, sel.max)) { com.office.core.text.ParaAttrs() })
        }
    }

    // --- find & replace ------------------------------------------------------------------

    fun select(range: IntRange) {
        value = value.copy(selection = TextRange(range.first, range.last + 1))
    }

    fun replaceSelection(replacement: String) {
        val sel = value.selection
        if (sel.collapsed) return
        val next = model.replace(sel.min, sel.max, replacement, model.styleAt(sel.min))
        commit(next, TextRange(sel.min + replacement.length))
    }

    fun replaceAll(query: String, replacement: String): Int {
        val (next, count) = model.replaceAll(query, replacement)
        if (count > 0) commit(next, TextRange(0))
        return count
    }

    // --- saving ---------------------------------------------------------------------------

    val mimeType: String get() = kind.primaryMime

    fun suggestedName(base: String): String {
        val stem = fileName?.substringBeforeLast('.') ?: base
        return "$stem.${kind.primaryExtension}"
    }

    private fun serialize(targetKind: FileKind = kind): ByteArray {
        val doc = currentDocument()
        return when (targetKind) {
            FileKind.DOCUMENT -> ByteArrayOutputStream().also { DocxWriter.write(doc, it, originalPackage) }.toByteArray()
            FileKind.MARKDOWN -> MarkdownCodec.write(doc)
            else -> PlainTextCodec.write(doc)
        }
    }

    /** Saves to the current file. Returns null on success or an error message. */
    fun save(onDone: (Throwable?) -> Unit) {
        val target = uri ?: return onDone(IllegalStateException("No target"))
        saveTo(target, onDone)
    }

    fun saveTo(target: Uri, onDone: (Throwable?) -> Unit) {
        viewModelScope.launch {
            saving = true
            val result = runCatching {
                val bytes = withContext(Dispatchers.Default) { serialize() }
                container.files.writeBytes(target, bytes)
            }
            saving = false
            result.onSuccess {
                if (uri != target) {
                    uri = target
                    container.files.takePersistablePermission(target)
                    val info = container.files.info(target)
                    fileName = info.name
                    container.recents.record(info.copy(kind = kind))
                }
                dirty = false
                lossyImport = false
            }
            onDone(result.exceptionOrNull())
        }
    }

    fun exportPdf(target: Uri, title: String, onDone: (Throwable?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { PdfExporter.document(currentDocument(), title, it) }.toByteArray()
                }
                container.files.writeBytes(target, bytes)
            }
            onDone(result.exceptionOrNull())
        }
    }

    suspend fun exportPdfToCache(title: String): java.io.File = withContext(Dispatchers.Default) {
        val file = java.io.File(container.files.exportsDir(), "${title.substringBeforeLast('.')}.pdf")
        file.outputStream().use { PdfExporter.document(currentDocument(), title, it) }
        file
    }

    val stats: Triple<Int, Int, Int>
        get() {
            val doc = currentDocument()
            return Triple(doc.wordCount, doc.characterCount, doc.paragraphs.count { it.text.isNotBlank() })
        }
}
