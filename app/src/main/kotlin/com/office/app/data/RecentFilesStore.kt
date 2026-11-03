package com.office.app.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.office.core.FileKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: Uri,
    val name: String,
    val kind: FileKind,
    val size: Long?,
    val lastOpened: Long,
    val pinned: Boolean = false,
)

private val Context.recentsStore by preferencesDataStore("recents")

class RecentFilesStore(private val context: Context) {

    private val key = stringPreferencesKey("files")

    val files: Flow<List<RecentFile>> = context.recentsStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun record(info: FileInfo) {
        val kind = info.kind ?: return
        update { list ->
            val existing = list.firstOrNull { it.uri == info.uri }
            val entry = RecentFile(info.uri, info.name, kind, info.size, System.currentTimeMillis(), existing?.pinned ?: false)
            (listOf(entry) + list.filter { it.uri != info.uri }).take(MAX)
        }
    }

    suspend fun setPinned(uri: Uri, pinned: Boolean) = update { list ->
        list.map { if (it.uri == uri) it.copy(pinned = pinned) else it }
    }

    suspend fun remove(uri: Uri) = update { list -> list.filter { it.uri != uri } }

    suspend fun clear() = update { list -> list.filter { it.pinned } }

    private suspend fun update(transform: (List<RecentFile>) -> List<RecentFile>) {
        context.recentsStore.edit { prefs -> prefs[key] = encode(transform(decode(prefs[key]))) }
    }

    private fun encode(list: List<RecentFile>): String = JSONArray().apply {
        list.forEach { f ->
            put(
                JSONObject()
                    .put("uri", f.uri.toString())
                    .put("name", f.name)
                    .put("kind", f.kind.name)
                    .put("size", f.size ?: -1L)
                    .put("time", f.lastOpened)
                    .put("pinned", f.pinned),
            )
        }
    }.toString()

    private fun decode(json: String?): List<RecentFile> {
        if (json.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val kind = runCatching { FileKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                RecentFile(
                    uri = Uri.parse(o.getString("uri")),
                    name = o.getString("name"),
                    kind = kind,
                    size = o.optLong("size", -1L).takeIf { it >= 0 },
                    lastOpened = o.optLong("time"),
                    pinned = o.optBoolean("pinned"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX = 60
    }
}
