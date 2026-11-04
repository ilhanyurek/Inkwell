package com.office.app.ui.folders

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.FolderEntry
import com.office.app.ui.common.BackButton
import com.office.app.ui.common.KindBadge
import com.office.app.ui.common.formatRelative
import com.office.app.ui.common.formatSize
import com.office.app.ui.common.shareUri
import com.office.app.ui.common.toast
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    container: AppContainer,
    folder: Uri,
    title: String,
    onBack: () -> Unit,
    onOpenFile: (Uri) -> Unit,
    onOpenFolder: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<FolderEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var supportedOnly by rememberSaveable { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FolderEntry?>(null) }
    var deleting by remember { mutableStateOf<FolderEntry?>(null) }
    val failed = stringResource(R.string.folder_action_failed)

    LaunchedEffect(folder, reload) {
        entries = runCatching { container.folders.list(folder) }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.folder_new)) }
                },
            )
        },
    ) { padding ->
        val list = entries
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilterChip(selected = supportedOnly, onClick = { supportedOnly = !supportedOnly }, label = { Text(stringResource(R.string.folder_supported_only)) })
            }
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    stringResource(R.string.folder_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn {
                    items(list.filter { !supportedOnly || it.isDirectory || it.kind != null }, key = { it.uri.toString() }) { e ->
                        EntryRow(
                            e,
                            onClick = { if (e.isDirectory) onOpenFolder(e.uri, e.name) else if (e.kind != null) onOpenFile(e.uri) },
                            onRename = { renaming = e },
                            onDelete = { deleting = e },
                            onShare = { shareUri(context, e.uri, e.kind?.primaryMime ?: "*/*", context.getString(R.string.action_share)) },
                        )
                    }
                }
            }
        }
    }

    if (newFolder) {
        NameDialog(
            title = stringResource(R.string.folder_new),
            initial = "",
            onDismiss = { newFolder = false },
            onConfirm = { name ->
                newFolder = false
                scope.launch { if (container.folders.createFolder(folder, name)) reload++ else toast(context, failed) }
            },
        )
    }
    renaming?.let { e ->
        NameDialog(
            title = stringResource(R.string.action_rename),
            initial = e.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch {
                    val newUri = container.folders.rename(e.uri, name)
                    if (newUri != null) { container.recents.remove(e.uri); reload++ } else toast(context, failed)
                }
            },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message, e.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        if (container.folders.delete(e.uri)) { container.recents.remove(e.uri); reload++ } else toast(context, failed)
                    }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EntryRow(e: FolderEntry, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            e.isDirectory -> IconBox { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary) }
            e.kind != null -> KindBadge(e.kind)
            else -> IconBox { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.outline) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (e.isDirectory || e.kind != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!e.isDirectory) {
                Text(
                    listOf(if (e.modified > 0) formatRelative(e.modified) else "", formatSize(context, e.size)).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!e.isDirectory) DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = { menu = false; onShare() })
                if (e.canRename) DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
                if (e.canDelete) DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun IconBox(content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private const val INVALID_NAME_CHARS = "/\\:*?\"<>|"

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && name.none { it in INVALID_NAME_CHARS }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = valid) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
