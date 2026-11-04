package com.office.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.Draft
import com.office.app.data.RecentFile
import com.office.app.ui.common.KindBadge
import com.office.app.ui.common.formatRelative
import com.office.app.ui.common.formatSize
import com.office.app.ui.common.shareUri
import com.office.app.ui.common.toast
import com.office.app.ui.pdf.PdfTools
import com.office.core.FileKind
import kotlinx.coroutines.launch

private enum class Filter(val label: Int, val kinds: Set<FileKind>?) {
    ALL(R.string.filter_all, null),
    DOCS(R.string.filter_documents, setOf(FileKind.DOCUMENT, FileKind.TEXT, FileKind.MARKDOWN)),
    SHEETS(R.string.filter_sheets, setOf(FileKind.SPREADSHEET, FileKind.CSV)),
    PDF(R.string.filter_pdf, setOf(FileKind.PDF)),
    SLIDES(R.string.filter_slides, setOf(FileKind.PRESENTATION)),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpen: (Uri) -> Unit,
    onNew: (FileKind) -> Unit,
    onSettings: () -> Unit,
    onOpenDraft: (String) -> Unit = {},
    onOpenFolder: (Uri, String) -> Unit = { _, _ -> },
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) kotlinx.coroutines.MainScope().launch { container.folders.add(tree) }
    }
    val context = LocalContext.current
    val drafts by container.drafts.drafts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val recents by container.recents.files.collectAsStateWithLifecycle(initialValue = null)
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showNewSheet by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var mergeInputs by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpen(uri)
    }
    val mergeOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val inputs = mergeInputs
        mergeInputs = emptyList()
        if (out != null && inputs.size >= 2) {
            scope.launch {
                val result = PdfTools.merge(context, inputs, out)
                if (result.isSuccess) onOpen(out) else toast(context, context.getString(R.string.error_pdf_merge))
            }
        }
    }
    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size < 2) {
            if (uris.isNotEmpty()) toast(context, context.getString(R.string.pdf_merge_need_two))
        } else {
            mergeInputs = uris
            mergeOutput.launch(context.getString(R.string.pdf_merged_file_name))
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (searching) {
                SearchBar(query, onQuery = { query = it }, onClose = { searching = false; query = "" })
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                        }
                        IconButton(onClick = { openLauncher.launch(FileKind.openableMimeTypes + "*/*") }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.action_open_file))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pdf_merge)) },
                                    onClick = { menuOpen = false; mergePicker.launch(arrayOf("application/pdf")) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.home_clear_recents)) },
                                    onClick = { menuOpen = false; scope.launch { container.recents.clear() } },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_title)) },
                                    onClick = { menuOpen = false; onSettings() },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewSheet = true },
                modifier = Modifier.testTag("new_button"),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_new)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        val all = recents
        if (all == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val filtered = all.filter { f ->
            (filter.kinds == null || f.kind in filter.kinds!!) &&
                (query.isBlank() || f.name.contains(query.trim(), ignoreCase = true))
        }
        val pinned = filtered.filter { it.pinned }
        val others = filtered.filterNot { it.pinned }

        Column(Modifier.fillMaxSize().padding(padding)) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.home_recent)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.home_folders)) })
        }
        if (tab == 1) {
            FoldersTab(container, onOpenFolder = onOpenFolder, onAdd = { treeLauncher.launch(null) })
        } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(Filter.entries.toList()) { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(stringResource(f.label)) },
                        )
                    }
                }
            }
            if (all.isEmpty()) {
                item { EmptyState(onOpenFile = { openLauncher.launch(FileKind.openableMimeTypes + "*/*") }) }
            } else if (filtered.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.home_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            if (drafts.isNotEmpty() && query.isBlank()) {
                item { SectionHeader(stringResource(R.string.home_drafts)) }
                items(drafts, key = { "d" + it.key }) { d ->
                    DraftRow(d, onOpen = { onOpenDraft(d.key) }, onDiscard = { container.drafts.delete(d.key) })
                }
            }
            if (pinned.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_pinned)) }
                items(pinned, key = { "p" + it.uri }) { f ->
                    FileRow(f, onOpen, onPin = { scope.launch { container.recents.setPinned(f.uri, !f.pinned) } },
                        onRemove = { scope.launch { container.recents.remove(f.uri) } })
                }
            }
            if (others.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_recent)) }
                items(others, key = { "r" + it.uri }) { f ->
                    FileRow(f, onOpen, onPin = { scope.launch { container.recents.setPinned(f.uri, !f.pinned) } },
                        onRemove = { scope.launch { container.recents.remove(f.uri) } })
                }
            }
        }
        }
        }
    }

    if (showNewSheet) {
        NewFileSheet(
            onDismiss = { showNewSheet = false },
            onPick = { showNewSheet = false; onNew(it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun FileRow(file: RecentFile, onOpen: (Uri) -> Unit, onPin: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val container = (context.applicationContext as com.office.app.OfficeApp).container
    val scope = rememberCoroutineScope()
    if (renaming) {
        com.office.app.ui.folders.NameDialog(
            title = stringResource(R.string.action_rename),
            initial = file.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                scope.launch {
                    val newUri = container.folders.rename(file.uri, name)
                    if (newUri == null) toast(context, context.getString(R.string.folder_action_failed))
                    else {
                        container.recents.remove(file.uri)
                        container.recents.record(container.files.info(newUri))
                    }
                }
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(file.uri) }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(file.kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (file.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).size(14.dp),
                    )
                }
            }
            val meta = listOf(formatRelative(file.lastOpened), formatSize(context, file.size)).filter { it.isNotEmpty() }
            Text(
                meta.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (file.pinned) R.string.action_unpin else R.string.action_pin)) },
                    onClick = { menu = false; onPin() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share)) },
                    onClick = {
                        menu = false
                        shareUri(context, file.uri, file.kind.primaryMime, context.getString(R.string.action_share))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = { menu = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_from_list)) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onOpenFile: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onOpenFile) { Text(stringResource(R.string.action_open_file)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewFileSheet(onDismiss: () -> Unit, onPick: (FileKind) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.new_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            NewRow(FileKind.DOCUMENT, R.string.new_document, R.string.new_document_desc, onPick)
            NewRow(FileKind.SPREADSHEET, R.string.new_sheet, R.string.new_sheet_desc, onPick)
            NewRow(FileKind.CSV, R.string.new_csv, R.string.new_csv_desc, onPick)
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            NewRow(FileKind.MARKDOWN, R.string.new_note, R.string.new_note_desc, onPick)
            NewRow(FileKind.TEXT, R.string.new_text, R.string.new_text_desc, onPick)
        }
    }
}

@Composable
private fun NewRow(kind: FileKind, title: Int, desc: Int, onPick: (FileKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(kind) }.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(kind, size = 36.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DraftRow(draft: Draft, onOpen: () -> Unit, onDiscard: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(draft.kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(draft.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.draft_unsaved, formatRelative(draft.savedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.draft_discard)) }, onClick = { menu = false; onDiscard() })
            }
        }
    }
}

@Composable
private fun FoldersTab(container: AppContainer, onOpenFolder: (Uri, String) -> Unit, onAdd: () -> Unit) {
    val folders by container.folders.folders.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (folders.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.folders_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.folders_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(folders, key = { it.toString() }) { uri ->
            val name = remember(uri) { container.folders.displayName(uri) }
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { onOpenFolder(uri, name) }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_from_list)) }, onClick = { menu = false; scope.launch { container.folders.remove(uri) } })
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onAdd, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.folders_add)) }
        }
    }
}
