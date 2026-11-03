package com.office.core

/** The editors the app offers, chosen from a file's extension or MIME type. */
enum class FileKind(val extensions: List<String>, val mimeTypes: List<String>) {
    DOCUMENT(
        listOf("docx", "docm", "dotx"),
        listOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-word.document.macroEnabled.12",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        ),
    ),
    TEXT(listOf("txt", "log", "ini", "json", "xml", "yaml", "yml"), listOf("text/plain", "application/json", "text/xml")),
    MARKDOWN(listOf("md", "markdown"), listOf("text/markdown", "text/x-markdown")),
    SPREADSHEET(
        listOf("xlsx", "xlsm", "xltx"),
        listOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel.sheet.macroEnabled.12",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        ),
    ),
    CSV(listOf("csv", "tsv"), listOf("text/csv", "text/comma-separated-values", "text/tab-separated-values", "application/csv")),
    PDF(listOf("pdf"), listOf("application/pdf")),
    PRESENTATION(
        listOf("pptx", "ppsx", "potx"),
        listOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/vnd.openxmlformats-officedocument.presentationml.template",
        ),
    ),
    ;

    val primaryMime: String get() = mimeTypes.first()
    val primaryExtension: String get() = extensions.first()

    companion object {
        fun detect(fileName: String?, mimeType: String?): FileKind? {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            entries.firstOrNull { ext in it.extensions }?.let { return it }
            val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
            entries.firstOrNull { mime in it.mimeTypes }?.let { return it }
            if (mime.startsWith("text/")) return TEXT
            return null
        }

        /** Legacy binary formats we recognise but cannot open, so we can explain why. */
        fun isLegacyBinary(fileName: String?): Boolean =
            fileName?.substringAfterLast('.', "")?.lowercase() in setOf("doc", "xls", "ppt")

        /** All MIME types the app can open, for the system file picker. */
        val openableMimeTypes: Array<String> get() = entries.flatMap { it.mimeTypes }.distinct().toTypedArray()
    }
}
