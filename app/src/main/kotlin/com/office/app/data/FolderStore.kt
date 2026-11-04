package com.office.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import com.office.core.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.folderStore by preferencesDataStore("folders")

/** One entry in a folder listing. */
data class FolderEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val kind: FileKind?,
    val size: Long,
    val modified: Long,
    val canRename: Boolean,
    val canDelete: Boolean,
)

/** Folders the user granted access to (Storage Access Framework trees) and operations on their files. */
class FolderStore(private val context: Context) {
    private val key = stringSetPreferencesKey("trees")

    val folders: Flow<List<Uri>> = context.folderStore.data.map { p -> p[key].orEmpty().map(Uri::parse).sortedBy { it.toString() } }

    suspend fun add(tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        context.folderStore.edit { it[key] = it[key].orEmpty() + tree.toString() }
    }

    suspend fun remove(tree: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        context.folderStore.edit { it[key] = it[key].orEmpty() - tree.toString() }
    }

    fun displayName(uri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: uri.toString()

    suspend fun list(folder: Uri): List<FolderEntry> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, folder) ?: return@withContext emptyList()
        dir.listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (name.startsWith(".")) return@mapNotNull null
            FolderEntry(
                uri = f.uri,
                name = name,
                isDirectory = f.isDirectory,
                kind = if (f.isDirectory) null else FileKind.detect(name, f.type),
                size = f.length(),
                modified = f.lastModified(),
                canRename = f.canWrite(),
                canDelete = f.canWrite(),
            )
        }.sortedWith(compareByDescending<FolderEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun createFolder(parent: Uri, name: String): Boolean = withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, parent)?.createDirectory(name) != null
    }

    /** Renames a document; returns the new URI or null if the provider does not support it. */
    suspend fun rename(uri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, newName) }.getOrNull()
    }

    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
    }
}
