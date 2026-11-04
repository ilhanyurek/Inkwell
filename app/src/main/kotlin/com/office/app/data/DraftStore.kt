package com.office.app.data

import android.content.Context
import android.net.Uri
import com.office.core.FileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** An unsaved copy of a document, kept in app storage so nothing is lost if the app is closed or crashes. */
data class Draft(
    val key: String,
    val uri: Uri?,
    val name: String,
    val kind: FileKind,
    val savedAt: Long,
    val file: File,
)

class DraftStore(context: Context) {

    private val root = File(context.filesDir, "drafts").apply { mkdirs() }
    private val _drafts = MutableStateFlow(load())

    /** All drafts, newest first. */
    val drafts: StateFlow<List<Draft>> = _drafts

    fun get(key: String): Draft? = _drafts.value.firstOrNull { it.key == key }

    suspend fun save(key: String, uri: Uri?, name: String, kind: FileKind, write: suspend (File) -> Unit) = withContext(Dispatchers.IO) {
        val dir = File(root, key).apply { mkdirs() }
        val tmp = File(dir, "content.tmp")
        write(tmp)
        val content = File(dir, "content.bin")
        if (!tmp.renameTo(content)) { tmp.copyTo(content, overwrite = true); tmp.delete() }
        JSONObject()
            .put("uri", uri?.toString())
            .put("name", name)
            .put("kind", kind.name)
            .put("time", System.currentTimeMillis())
            .let { File(dir, "meta.json").writeText(it.toString()) }
        _drafts.value = load()
    }

    fun delete(key: String) {
        File(root, key).deleteRecursively()
        _drafts.value = load()
    }

    private fun load(): List<Draft> = root.listFiles().orEmpty().mapNotNull { dir ->
        runCatching {
            val meta = JSONObject(File(dir, "meta.json").readText())
            val content = File(dir, "content.bin")
            if (!content.exists()) return@mapNotNull null
            Draft(
                key = dir.name,
                uri = meta.optString("uri").takeIf { it.isNotEmpty() && it != "null" }?.let(Uri::parse),
                name = meta.getString("name"),
                kind = FileKind.valueOf(meta.getString("kind")),
                savedAt = meta.getLong("time"),
                file = content,
            )
        }.getOrNull()
    }.sortedByDescending { it.savedAt }

    companion object {
        fun keyFor(uri: Uri): String = sha1(uri.toString())
        fun newKey(): String = "new-" + UUID.randomUUID().toString().take(12)

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}

/**
 * Writes a draft a few seconds after the last change. Editors call [changed] on every edit,
 * [flush] when leaving the screen, and [discard] after a successful save or an explicit discard.
 */
class DraftAutosaver(
    private val scope: CoroutineScope,
    private val store: DraftStore,
    private val settings: SettingsStore,
    val key: String,
    private val describe: () -> Triple<Uri?, String, FileKind>,
    private val writeDraft: suspend (File) -> Unit,
    /** Called when "also save to the file" is on; returns true if the file was written. */
    private val saveToFile: (suspend () -> Boolean)? = null,
) {
    private var job: Job? = null
    @Volatile private var pending = false

    fun changed() {
        pending = true
        job?.cancel()
        job = scope.launch {
            delay(DELAY_MS)
            flush()
        }
    }

    suspend fun flush() {
        if (!pending) return
        pending = false
        val (uri, name, kind) = describe()
        runCatching { store.save(key, uri, name, kind) { f -> writeDraft(f) } }
        val autoToFile = runCatching { settings.settings.first().autoSaveToFile }.getOrDefault(false)
        val save = saveToFile
        if (autoToFile && uri != null && save != null) {
            if (runCatching { save() }.getOrDefault(false)) store.delete(key)
        }
    }

    fun discard() {
        pending = false
        job?.cancel()
        store.delete(key)
    }

    private companion object {
        const val DELAY_MS = 3_000L
    }
}
