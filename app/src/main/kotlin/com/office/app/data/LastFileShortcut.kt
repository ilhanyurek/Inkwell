package com.office.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.office.app.MainActivity
import com.office.app.R

/** Keeps a launcher shortcut that reopens the most recently opened file. */
object LastFileShortcut {

    private const val ID = "last_file"

    fun update(context: Context, file: FileInfo) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName(context, MainActivity::class.java.name)
                setDataAndType(file.uri, file.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val label = file.name.take(24).ifBlank { context.getString(R.string.untitled) }
            val shortcut = ShortcutInfoCompat.Builder(context, ID)
                .setShortLabel(label)
                .setLongLabel(file.name.take(48))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_file))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    fun remove(context: Context) {
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(ID)) }
    }
}
