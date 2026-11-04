package com.office.app.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.office.app.R
import com.office.app.ui.theme.LocalKindColors
import com.office.core.FileKind

fun FileKind.badgeLabel(): String = when (this) {
    FileKind.DOCUMENT -> "DOC"
    FileKind.TEXT -> "TXT"
    FileKind.MARKDOWN -> "MD"
    FileKind.SPREADSHEET -> "XLS"
    FileKind.CSV -> "CSV"
    FileKind.PDF -> "PDF"
    FileKind.PRESENTATION -> "PPT"
}

/** A small tinted square with the format abbreviation; the only place file-type colour appears. */
@Composable
fun KindBadge(kind: FileKind, size: Dp = 40.dp) {
    val color = LocalKindColors.current.of(kind)
    Box(
        modifier = Modifier
            .size(size)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            kind.badgeLabel(),
            color = color,
            fontSize = (size.value * 0.27f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoadingScreen(onBack: () -> Unit, title: String = "") {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorScreen(message: String, onBack: () -> Unit, detail: String? = null) {
    Scaffold(
        topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp).widthIn(max = 360.dp),
                )
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.action_go_back))
            }
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

@Composable
fun UnsavedChangesDialog(onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unsaved_title)) },
        text = { Text(stringResource(R.string.unsaved_message)) },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.action_discard), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

fun formatSize(context: Context, size: Long?): String = size?.let { Formatter.formatShortFileSize(context, it) }.orEmpty()

fun formatRelative(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

fun shareUri(context: Context, uri: Uri, mime: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, chooserTitle)) }
}

fun openWithOtherApp(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_SHORT).show()
    }
}

fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

/** Divider colour used for hairlines throughout the app. */
@Composable
fun hairline(): Color = MaterialTheme.colorScheme.outlineVariant

/** Undo/redo pair placed in bottom toolbars where the thumb can reach them. */
@Composable
fun UndoRedoButtons(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.action_undo))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.action_redo))
        }
    }
}
