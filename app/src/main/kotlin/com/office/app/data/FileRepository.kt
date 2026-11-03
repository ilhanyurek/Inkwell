package com.office.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.office.core.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

data class FileInfo(val uri: Uri, val name: String, val size: Long?, val mimeType: String?, val kind: FileKind?)

/** All reads and writes of user files go through the Storage Access Framework. */
class FileRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun info(uri: Uri): FileInfo = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { File(it) }?.let { f -> name = f.name; size = f.length() }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        FileInfo(uri, finalName, size, mime, FileKind.detect(finalName, mime))
    }

    suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException(uri.toString())
    }

    suspend fun writeBytes(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        // "wt" truncates; some providers only support "w", which also truncates on most of them.
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw FileNotFoundException(uri.toString())
        stream.use { it.write(bytes) }
    }

    /** Copies a document into the cache so APIs needing a seekable file (PdfRenderer) can use it. */
    suspend fun copyToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "open").apply { mkdirs() }
        val target = File(dir, "${uri.toString().hashCode().toUInt()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw FileNotFoundException(uri.toString())
        target
    }

    fun exportsDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun takePersistablePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun canWrite(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } ||
            uri.scheme == ContentResolver.SCHEME_FILE
}
