package com.office.core.zip

import com.office.core.xml.XmlElement
import com.office.core.xml.XmlParser
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** An in-memory view of an OPC (Office Open XML) zip package. */
class ZipPackage(val entries: Map<String, ByteArray>) {

    operator fun contains(path: String) = entries.containsKey(path.removePrefix("/"))

    fun bytes(path: String): ByteArray? = entries[path.removePrefix("/")]

    fun xml(path: String): XmlElement? = bytes(path)?.let { XmlParser.parse(it) }

    /**
     * Reads the relationships of [partPath] (e.g. `word/document.xml` →
     * `word/_rels/document.xml.rels`) as id → absolute target path.
     */
    fun relationships(partPath: String): Map<String, Relationship> {
        val dir = partPath.substringBeforeLast('/', "")
        val file = partPath.substringAfterLast('/')
        val relsPath = if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
        val root = xml(relsPath) ?: return emptyMap()
        return root.children("Relationship").mapNotNull { r ->
            val id = r.attr("Id") ?: return@mapNotNull null
            val target = r.attr("Target") ?: return@mapNotNull null
            val external = r.attr("TargetMode") == "External"
            id to Relationship(
                id = id,
                type = r.attr("Type").orEmpty(),
                target = if (external) target else resolve(dir, target),
                external = external,
            )
        }.toMap()
    }

    data class Relationship(val id: String, val type: String, val target: String, val external: Boolean)

    companion object {
        /** Guards against zip bombs: refuse packages that inflate beyond this. */
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

        fun read(input: InputStream): ZipPackage {
            val map = LinkedHashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val data = zip.readBytes()
                        total += data.size
                        if (total > MAX_TOTAL_BYTES) throw IllegalStateException("Package too large")
                        map[entry.name.removePrefix("/")] = data
                    }
                    zip.closeEntry()
                }
            }
            if (map.isEmpty()) throw IllegalArgumentException("Not a zip package or empty")
            return ZipPackage(map)
        }

        /** Resolves an OPC relative target against the directory of the source part. */
        fun resolve(baseDir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList<String>()
            if (baseDir.isNotEmpty()) parts += baseDir.split('/')
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += seg
                }
            }
            return parts.joinToString("/")
        }

        fun write(output: OutputStream, entries: List<Pair<String, ByteArray>>) {
            val zip = ZipOutputStream(output)
            // OpenDocument requires "mimetype" first and uncompressed; it is written that way when present.
            val ordered = entries.sortedBy { if (it.first == "mimetype") 0 else 1 }
            for ((name, data) in ordered) {
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = java.util.zip.CRC32().also { it.update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
        }
    }
}
