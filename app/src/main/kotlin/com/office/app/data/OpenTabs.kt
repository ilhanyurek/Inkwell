package com.office.app.data

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.office.core.FileKind

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

/** The files open in the workspace, the selected one and which of them have unsaved changes. */
class OpenTabs {
    val tabs = mutableStateListOf<Tab>()
    var selectedId by mutableStateOf<String?>(null)
        private set
    private val dirty = mutableStateMapOf<String, Boolean>()
    private var counter = 0

    val selected: Tab? get() = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()

    fun isDirty(id: String) = dirty[id] == true

    fun setDirty(id: String, value: Boolean) {
        if (dirty[id] != value) dirty[id] = value
    }

    fun select(id: String) {
        if (tabs.any { it.id == id }) selectedId = id
    }

    /** Opens [uri], or selects its tab when it is already open. */
    fun open(uri: Uri, tool: String? = null) {
        val existing = tabs.firstOrNull { it.uri == uri }
        if (existing != null) {
            if (tool != null && existing.tool != tool) tabs[tabs.indexOf(existing)] = existing.copy(tool = tool)
            selectedId = existing.id
            return
        }
        add(Tab(nextId(), uri, tool = tool))
    }

    fun openNew(kind: FileKind) {
        val id = nextId()
        add(Tab(id, null, newKind = kind, draftKey = DraftStore.newKey()))
    }

    /** A recovered draft: of an existing file (opens the file) or of a new document. */
    fun openDraft(key: String, uri: Uri?, kind: FileKind) {
        if (uri != null) return open(uri)
        tabs.firstOrNull { it.draftKey == key }?.let { selectedId = it.id; return }
        add(Tab(nextId(), null, newKind = kind, draftKey = key))
    }

    /** A new document was saved under [uri]: the tab now stands for that file. */
    fun replaceUri(id: String, uri: Uri) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0 && tabs[i].uri == null) tabs[i] = tabs[i].copy(uri = uri)
    }

    private fun add(tab: Tab) {
        val at = tabs.indexOfFirst { it.id == selectedId }.let { if (it < 0) tabs.size else it + 1 }
        tabs.add(at, tab)
        selectedId = tab.id
    }

    /** Closes [id] and selects its neighbour. Returns true when no tab is left. */
    fun close(id: String): Boolean {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return tabs.isEmpty()
        tabs.removeAt(i)
        dirty.remove(id)
        if (selectedId == id) selectedId = tabs.getOrNull(i.coerceAtMost(tabs.lastIndex))?.id
        return tabs.isEmpty()
    }

    /** Closes every other tab without unsaved changes. */
    fun closeOthers(id: String) {
        tabs.filter { it.id != id && !isDirty(it.id) }.forEach { close(it.id) }
        select(id)
    }

    private fun nextId() = "t${++counter}"
}
