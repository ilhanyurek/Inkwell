package com.office.app.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import com.office.core.FileKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * A file open in a tab. [uri] is null for a new, unsaved document; [draftKey] then keeps it apart from
 * other new documents (and names its autosaved draft).
 */
data class Tab(
    val id: String,
    val uri: Uri?,
    val newKind: FileKind? = null,
    val draftKey: String? = null,
    val tool: String? = null,
)

/**
 * The files open in the workspace, the selected one, their titles and which of them have unsaved changes.
 * The list is kept on disk so the same files are offered again after the app was closed.
 */
class OpenTabs(context: Context? = null) {
    private val prefs = context?.applicationContext?.getSharedPreferences("open_tabs", Context.MODE_PRIVATE)

    val tabs = mutableStateListOf<Tab>()
    var selectedId by mutableStateOf<String?>(null)
        private set
    private val dirty = mutableStateMapOf<String, Boolean>()
    private val titles = mutableStateMapOf<String, String>()

    /** Each open file keeps its editor's state here, so leaving the editor screen does not lose it. */
    private val stores = HashMap<String, ViewModelStore>()

    /** A tab whose editor should run its own close flow (it asks about unsaved changes). */
    var pendingClose by mutableStateOf<String?>(null)
    private var counter = 0

    init {
        restore()
    }

    val selected: Tab? get() = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()

    fun isDirty(id: String) = dirty[id] == true

    fun setDirty(id: String, value: Boolean) {
        if (dirty[id] != value) dirty[id] = value
    }

    fun title(id: String): String? = titles[id]

    fun viewModelStore(id: String): ViewModelStore = stores.getOrPut(id) { ViewModelStore() }

    fun setTitle(id: String, title: String) {
        if (titles[id] != title) {
            titles[id] = title
            persist()
        }
    }

    /** The file type shown on the tab: from the new-document kind or the file name. */
    fun kindOf(tab: Tab): FileKind = tab.newKind ?: FileKind.detect(titles[tab.id] ?: tab.uri?.lastPathSegment, null) ?: FileKind.DOCUMENT

    fun select(id: String) {
        if (tabs.any { it.id == id } && selectedId != id) {
            selectedId = id
            persist()
        }
    }

    /** Opens [uri], or selects its tab when it is already open. */
    fun open(uri: Uri, tool: String? = null) {
        val existing = tabs.firstOrNull { it.uri == uri }
        if (existing != null) {
            if (tool != null && existing.tool != tool) tabs[tabs.indexOf(existing)] = existing.copy(tool = tool)
            selectedId = existing.id
            persist()
            return
        }
        add(Tab(nextId(), uri, tool = tool))
    }

    fun openNew(kind: FileKind) {
        add(Tab(nextId(), null, newKind = kind, draftKey = DraftStore.newKey()))
    }

    /** A recovered draft: of an existing file (opens the file) or of a new document. */
    fun openDraft(key: String, uri: Uri?, kind: FileKind) {
        if (uri != null) return open(uri)
        tabs.firstOrNull { it.draftKey == key }?.let { selectedId = it.id; persist(); return }
        add(Tab(nextId(), null, newKind = kind, draftKey = key))
    }

    /** A new document was saved under [uri]: the tab now stands for that file. */
    fun replaceUri(id: String, uri: Uri) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0 && tabs[i].uri == null) {
            tabs[i] = tabs[i].copy(uri = uri)
            persist()
        }
    }

    private fun add(tab: Tab) {
        val at = tabs.indexOfFirst { it.id == selectedId }.let { if (it < 0) tabs.size else it + 1 }
        tabs.add(at, tab)
        selectedId = tab.id
        persist()
    }

    /** Closes [id] and selects its neighbour. Returns true when no tab is left. */
    fun close(id: String): Boolean {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return tabs.isEmpty()
        tabs.removeAt(i)
        dirty.remove(id)
        titles.remove(id)
        stores.remove(id)?.clear()
        if (pendingClose == id) pendingClose = null
        if (selectedId == id) selectedId = tabs.getOrNull(i.coerceAtMost(tabs.lastIndex))?.id
        persist()
        return tabs.isEmpty()
    }

    /**
     * Asks to close [id]: a tab without unsaved changes closes at once; otherwise it is shown and its editor
     * asks whether to save. Returns true when no tab is left.
     */
    fun requestClose(id: String): Boolean {
        if (!isDirty(id)) return close(id)
        select(id)
        pendingClose = id
        return false
    }

    /** Closes every other tab without unsaved changes. */
    fun closeOthers(id: String) {
        tabs.filter { it.id != id && !isDirty(it.id) }.forEach { close(it.id) }
        select(id)
    }

    /** Closes every tab without unsaved changes. Returns true when no tab is left. */
    fun closeSaved(): Boolean {
        tabs.filter { !isDirty(it.id) }.forEach { close(it.id) }
        return tabs.isEmpty()
    }

    private fun nextId() = "t${++counter}"

    private fun persist() {
        val p = prefs ?: return
        val arr = JSONArray()
        for (t in tabs) {
            // Files opened for a one-off tool are not reopened with that tool.
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    t.uri?.let { put("uri", it.toString()) }
                    t.newKind?.let { put("kind", it.name) }
                    t.draftKey?.let { put("draft", it) }
                    titles[t.id]?.let { put("title", it) }
                },
            )
        }
        p.edit().putString("tabs", arr.toString()).putString("selected", selectedId).putInt("counter", counter).apply()
    }

    private fun restore() {
        val p = prefs ?: return
        runCatching {
            counter = p.getInt("counter", 0)
            val arr = JSONArray(p.getString("tabs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val uri = o.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse)
                val kind = o.optString("kind").takeIf { it.isNotEmpty() }?.let { runCatching { FileKind.valueOf(it) }.getOrNull() }
                if (uri == null && kind == null) continue
                tabs += Tab(id, uri, newKind = kind, draftKey = o.optString("draft").takeIf { it.isNotEmpty() })
                o.optString("title").takeIf { it.isNotEmpty() }?.let { titles[id] = it }
                id.removePrefix("t").toIntOrNull()?.let { if (it > counter) counter = it }
            }
            selectedId = p.getString("selected", null)?.takeIf { s -> tabs.any { it.id == s } } ?: tabs.firstOrNull()?.id
        }.onFailure { tabs.clear(); titles.clear() }
    }
}
