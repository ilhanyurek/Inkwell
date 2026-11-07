package com.office.app.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.office.app.AppContainer
import com.office.app.data.Draft
import com.office.app.data.DraftAutosaver
import com.office.app.data.DraftStore
import com.office.app.data.FileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class SearchHit(val page: Int, val snippet: String)

private const val MAX_HISTORY = 20

class PdfViewerViewModel(
    private val container: AppContainer,
    val uri: Uri,
    info: FileInfo,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object NeedsPassword : State
        data object Ready : State
        data class Failed(val message: String?) : State
    }

    val fileName = info.name
    var state by mutableStateOf<State>(State.Loading)
        private set
    /** Page sizes in points as displayed (rotation applied by the renderer). */
    var pageSizes by mutableStateOf<List<Pair<Int, Int>>>(emptyList())
        private set
    /** Bumped whenever the working copy changes, so pages re-render. */
    var version by mutableIntStateOf(0)
        private set
    var dirty by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var wrongPassword by mutableStateOf(false)
        private set
    var searchResults by mutableStateOf<List<SearchHit>?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Snapshot of the working file plus the id of the state it captured. */
    private data class Snapshot(val file: File, val stateId: Int)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var stateId = 0
    private var nextStateId = 1
    private var savedStateId = 0

    private var original: File? = null
    /** The file we render and edit; saved back to [uri] on demand. */
    private var working: File? = null
    private var password: String? = null
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private val renderLock = Mutex()
    private var pageTexts: List<String>? = null

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val autosaver = DraftAutosaver(
        scope = container.appScope,
        store = container.drafts,
        settings = container.settings,
        key = DraftStore.keyFor(uri),
        describe = { Triple(uri, fileName, com.office.core.FileKind.PDF) },
        writeDraft = { f -> withContext(Dispatchers.IO) { working?.copyTo(f, overwrite = true) } },
    )

    var recoverableDraft by mutableStateOf<Draft?>(null)
        private set

    init {
        viewModelScope.launch {
            runCatching {
                val copy = container.files.copyToCache(uri, info.name)
                original = copy
                val work = File(copy.parentFile, "work_" + copy.name)
                copy.copyTo(work, overwrite = true)
                working = work
                openRenderer()
                recoverableDraft = container.drafts.get(autosaver.key)
                loadFormFields()
            }.onFailure { e ->
                state = if (e is SecurityException) State.NeedsPassword else State.Failed(e.message)
            }
        }
    }

    private suspend fun openRenderer() = renderLock.withLock {
        withContext(Dispatchers.IO) {
            closeRendererLocked()
            val file = working ?: error("No file")
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
            descriptor = pfd
            renderer = r
            pageSizes = (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } }
            cache.evictAll()
            version++
            state = State.Ready
        }
    }

    private fun closeRendererLocked() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    fun unlock(pass: String) {
        val src = original ?: return
        viewModelScope.launch {
            busy = true
            val result = runCatching {
                val decrypted = File(src.parentFile, "work_" + src.name)
                PdfTools.decrypt(src, pass, decrypted)
                working = decrypted
                password = pass
                openRenderer()
            }
            busy = false
            wrongPassword = result.isFailure
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = "$version:$index:$widthPx"
        cache.get(key)?.let { return it }
        return renderLock.withLock {
            withContext(Dispatchers.IO) {
                val r = renderer ?: return@withContext null
                if (index !in 0 until r.pageCount) return@withContext null
                runCatching {
                    r.openPage(index).use { page ->
                        val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }.getOrNull()?.also { cache.put(key, it) }
            }
        }
    }

    // --- search ---------------------------------------------------------------------------------

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchResults = null
            return
        }
        viewModelScope.launch {
            busy = true
            val texts = pageTexts ?: runCatching { PdfTools.pageTexts(working!!) }.getOrDefault(emptyList()).also { pageTexts = it }
            searchResults = texts.flatMapIndexed { page, text ->
                val flat = text.replace(Regex("\\s+"), " ")
                val hits = ArrayList<SearchHit>()
                var i = flat.indexOf(q, ignoreCase = true)
                while (i >= 0 && hits.size < 20) {
                    val from = (i - 30).coerceAtLeast(0)
                    val to = (i + q.length + 40).coerceAtMost(flat.length)
                    hits += SearchHit(page, (if (from > 0) "…" else "") + flat.substring(from, to) + (if (to < flat.length) "…" else ""))
                    i = flat.indexOf(q, i + q.length, ignoreCase = true)
                }
                hits
            }
            busy = false
        }
    }

    fun clearSearch() {
        searchResults = null
    }

    // --- editing ----------------------------------------------------------------------------------

    private fun historyDir(): File = File(working!!.parentFile, "pdf_history").apply { mkdirs() }

    private fun snapshot(): Snapshot {
        val copy = File(historyDir(), "s_${System.nanoTime()}.pdf")
        working!!.copyTo(copy, overwrite = true)
        return Snapshot(copy, stateId)
    }

    private fun updateHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        dirty = stateId != savedStateId
        if (dirty) autosaver.changed() else autosaver.discard()
        loadFormFields()
    }

    /** Replaces the working copy with the saved draft (all PDF edits are kept in the file itself). */
    fun restoreDraft() {
        val draft = recoverableDraft ?: return
        val file = working ?: return
        recoverableDraft = null
        viewModelScope.launch {
            busy = true
            runCatching {
                renderLock.withLock { withContext(Dispatchers.IO) { closeRendererLocked() } }
                withContext(Dispatchers.IO) {
                    undoStack.addLast(snapshot())
                    draft.file.copyTo(file, overwrite = true)
                }
                stateId = nextStateId++
                pageTexts = null
            }
            runCatching { openRenderer() }.onFailure { state = State.Failed(it.message) }
            updateHistoryFlags()
            busy = false
        }
    }

    fun discardDraft() {
        recoverableDraft = null
        autosaver.discard()
    }

    fun flushDraft() {
        container.appScope.launch { autosaver.flush() }
    }

    private fun modify(block: suspend (File) -> Unit, onError: (Throwable) -> Unit = {}) {
        if (busy) return
        val file = working ?: return
        viewModelScope.launch {
            busy = true
            val before = withContext(Dispatchers.IO) { runCatching { snapshot() }.getOrNull() }
            val result = runCatching {
                // Release the renderer's handle while PDFBox rewrites the file.
                renderLock.withLock { withContext(Dispatchers.IO) { closeRendererLocked() } }
                block(file)
            }
            result.onSuccess {
                if (before != null) {
                    undoStack.addLast(before)
                    while (undoStack.size > MAX_HISTORY) undoStack.removeFirst().file.delete()
                }
                redoStack.forEach { it.file.delete() }
                redoStack.clear()
                stateId = nextStateId++
                pageTexts = null
            }.onFailure {
                // Roll back a half-written file.
                if (before != null) withContext(Dispatchers.IO) { before.file.copyTo(file, overwrite = true); before.file.delete() }
                onError(it)
            }
            runCatching { openRenderer() }.onFailure { state = State.Failed(it.message) }
            updateHistoryFlags()
            busy = false
        }
    }

    fun undo() = travel(from = undoStack, to = redoStack)

    fun redo() = travel(from = redoStack, to = undoStack)

    private fun travel(from: ArrayDeque<Snapshot>, to: ArrayDeque<Snapshot>) {
        if (busy) return
        val file = working ?: return
        val target = from.removeLastOrNull() ?: return
        viewModelScope.launch {
            busy = true
            runCatching {
                renderLock.withLock { withContext(Dispatchers.IO) { closeRendererLocked() } }
                withContext(Dispatchers.IO) {
                    to.addLast(snapshot())
                    target.file.copyTo(file, overwrite = true)
                    target.file.delete()
                }
                stateId = target.stateId
                pageTexts = null
            }
            runCatching { openRenderer() }.onFailure { state = State.Failed(it.message) }
            updateHistoryFlags()
            busy = false
        }
    }

    /** Fillable form fields, loaded after the document opens. */
    var formFields by mutableStateOf<List<FormField>>(emptyList())
        private set

    /** Removable annotations (highlights, notes…) across the document. */
    var annotations by mutableStateOf<List<AnnotationInfo>>(emptyList())
        private set

    /** Bookmarks of the document, loaded with the form fields. */
    var outline by mutableStateOf<List<OutlineItem>>(emptyList())
        private set

    private val wordCache = HashMap<Pair<Int, Int>, List<PdfWord>>()

    /** Words of a page for text selection; cached per document version. */
    suspend fun words(page: Int): List<PdfWord> {
        val key = version to page
        wordCache[key]?.let { return it }
        val file = working ?: return emptyList()
        return runCatching { PdfTools.words(file, page) }.getOrDefault(emptyList()).also { wordCache[key] = it }
    }

    fun loadFormFields() {
        val file = working ?: return
        viewModelScope.launch {
            formFields = runCatching { PdfTools.formFields(file) }.getOrDefault(emptyList())
            annotations = runCatching { PdfTools.annotations(file) }.getOrDefault(emptyList())
            outline = runCatching { PdfTools.outline(file) }.getOrDefault(emptyList())
        }
    }

    /** Renders the pages at 200 dpi, then replaces them with redacted images. */
    fun redact(regions: Map<Int, List<Pair<PagePoint, PagePoint>>>, mode: RedactMode) {
        if (busy || regions.isEmpty()) return
        viewModelScope.launch {
            busy = true
            val bitmaps = HashMap<Int, android.graphics.Bitmap>()
            for (page in regions.keys) {
                val (w, _) = pageSizes.getOrNull(page) ?: continue
                val px = (w * 200f / 72f).toInt().coerceIn(600, 2400)
                renderUncached(page, px)?.let { bitmaps[page] = it }
            }
            busy = false
            modify({ PdfTools.redact(it, bitmaps, regions, mode) })
        }
    }

    private suspend fun renderUncached(index: Int, widthPx: Int): android.graphics.Bitmap? = renderLock.withLock {
        withContext(Dispatchers.IO) {
            val r = renderer ?: return@withContext null
            runCatching {
                r.openPage(index).use { page ->
                    val bmp = android.graphics.Bitmap.createBitmap(widthPx, (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }.getOrNull()
        }
    }

    fun deleteAnnotation(info: AnnotationInfo) = modify({ PdfTools.deleteAnnotation(it, info.page, info.index) })

    fun fillForm(values: Map<String, String>, flatten: Boolean) =
        modify({ PdfTools.fillForm(it, values, flatten) })

    fun stampSignature(page: Int, a: PagePoint, b: PagePoint, image: android.graphics.Bitmap) =
        modify({ PdfTools.stampImage(it, page, a, b, image) })

    val signatures = SignatureStore(container.appContext)

    fun rotate(page: Int, degrees: Int) = modify({ PdfTools.rotate(it, page, degrees) })

    fun deletePage(page: Int) {
        if (pageSizes.size <= 1) return
        modify({ PdfTools.deletePage(it, page) })
    }

    fun highlight(page: Int, a: PagePoint, b: PagePoint, rgb: Int) = modify({ PdfTools.highlight(it, page, a, b, rgb) })

    fun addText(page: Int, at: PagePoint, text: String, sizePt: Float, rgb: Int, onError: (Throwable) -> Unit) =
        modify({ PdfTools.addText(it, page, at, text, sizePt, rgb) }, onError)

    fun extract(from: Int, to: Int, output: Uri, onDone: (Throwable?) -> Unit) {
        val file = working ?: return
        viewModelScope.launch {
            busy = true
            val r = runCatching { PdfTools.extract(container.appContext, file, from, to, output) }
            busy = false
            onDone(r.exceptionOrNull())
        }
    }

    fun save(target: Uri = uri, onDone: (Throwable?) -> Unit) {
        val file = working ?: return
        viewModelScope.launch {
            busy = true
            val r = runCatching {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                container.files.writeBytes(target, bytes)
            }
            busy = false
            if (r.isSuccess && target == uri) {
                savedStateId = stateId
                updateHistoryFlags()
            }
            onDone(r.exceptionOrNull())
        }
    }

    /** The file to print or share as-is. */
    fun workingFile(): File? = working

    override fun onCleared() {
        // Copy the working file synchronously: it is deleted with the cache below otherwise.
        if (dirty) kotlinx.coroutines.runBlocking { autosaver.flush() }
        closeRendererLocked()
        (undoStack + redoStack).forEach { it.file.delete() }
        cache.evictAll()
    }
}
