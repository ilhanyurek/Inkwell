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
import com.office.app.data.FileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class SearchHit(val page: Int, val snippet: String)

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

    init {
        viewModelScope.launch {
            runCatching {
                val copy = container.files.copyToCache(uri, info.name)
                original = copy
                val work = File(copy.parentFile, "work_" + copy.name)
                copy.copyTo(work, overwrite = true)
                working = work
                openRenderer()
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

    private fun modify(block: suspend (File) -> Unit, onError: (Throwable) -> Unit = {}) {
        val file = working ?: return
        viewModelScope.launch {
            busy = true
            val result = runCatching {
                // Release the renderer's handle while PDFBox rewrites the file.
                renderLock.withLock { withContext(Dispatchers.IO) { closeRendererLocked() } }
                block(file)
            }
            runCatching { openRenderer() }.onFailure { state = State.Failed(it.message) }
            result.onSuccess {
                dirty = true
                pageTexts = null
            }.onFailure(onError)
            busy = false
        }
    }

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
            if (r.isSuccess && target == uri) dirty = false
            onDone(r.exceptionOrNull())
        }
    }

    /** The file to print or share as-is. */
    fun workingFile(): File? = working

    override fun onCleared() {
        closeRendererLocked()
        cache.evictAll()
    }
}
