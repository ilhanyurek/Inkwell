package com.office.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import com.office.app.ui.design.CardDivider
import com.office.app.ui.design.CardGroup
import com.office.app.ui.design.CardRadius
import com.office.app.ui.design.IconTile
import com.office.app.ui.design.ListRow
import com.office.app.ui.design.PageScaffold
import com.office.app.ui.design.PageTitleBar
import com.office.app.ui.design.PillChip
import com.office.app.ui.design.PrimaryPillButton
import com.office.app.ui.design.SectionHeader
import com.office.app.ui.design.SegmentedPills
import com.office.app.ui.design.cardBorder
import com.office.app.ui.design.cardColor
import com.office.app.ui.design.cardSegment
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
    onTool: (com.office.app.ui.tools.ToolId) -> Unit = {},
    onOpenTabs: () -> Unit = {},
    requestedTab: Int? = null,
    onTabRequestHandled: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) { tab = requestedTab; onTabRequestHandled() }
    }
    var grid by rememberSaveable { mutableStateOf(false) }
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

    val openTabs = container.tabs.tabs

    PageScaffold(
        title = stringResource(R.string.home_title),
        topBar = {
            if (searching) {
                SearchBar(query, onQuery = { query = it }, onClose = { searching = false; query = "" })
            } else {
                PageTitleBar(title = stringResource(R.string.home_title)) {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = { openLauncher.launch(FileKind.openableMimeTypes + "*/*") }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.action_open_file))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
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
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                PrimaryPillButton(
                    text = stringResource(R.string.action_new),
                    icon = Icons.Rounded.Add,
                    onClick = { showNewSheet = true },
                    modifier = Modifier.testTag("new_button"),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SegmentedPills(
                options = listOf(
                    stringResource(R.string.home_recent),
                    stringResource(R.string.home_folders),
                    stringResource(R.string.home_tools),
                ),
                selected = tab,
                onSelect = { tab = it },
                testTags = listOf("tab_recent", "tab_folders", "tab_tools"),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
            if (openTabs.isNotEmpty()) {
                val untitled = stringResource(R.string.untitled)
                OpenTabsCard(openTabs.size, openTabs.joinToString(", ") { container.tabs.title(it.id) ?: it.uri?.lastPathSegment?.substringAfterLast('/') ?: untitled }, onOpenTabs)
            }
            when (tab) {
                2 -> com.office.app.ui.tools.ToolsTab(onTool = onTool)
                1 -> FoldersTab(container, onOpenFolder = onOpenFolder, onAdd = { treeLauncher.launch(null) })
                else -> RecentList(
                    container = container,
                    recents = recents,
                    drafts = drafts,
                    filter = filter,
                    onFilter = { filter = it },
                    query = query,
                    grid = grid,
                    onToggleGrid = { grid = !grid },
                    onOpen = onOpen,
                    onOpenDraft = onOpenDraft,
                    onOpenFile = { openLauncher.launch(FileKind.openableMimeTypes + "*/*") },
                )
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

@Composable
private fun OpenTabsCard(count: Int, names: String, onClick: () -> Unit) {
    CardGroup(Modifier.padding(bottom = 4.dp)) {
        ListRow(
            title = stringResource(R.string.home_open_tabs, count),
            subtitle = names,
            leading = { IconTile(Icons.Outlined.Tab, selected = true) },
            trailing = {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
            onClick = onClick,
            modifier = Modifier.testTag("open_tabs"),
        )
    }
}

@Composable
private fun RecentList(
    container: AppContainer,
    recents: List<RecentFile>?,
    drafts: List<Draft>,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    query: String,
    grid: Boolean,
    onToggleGrid: () -> Unit,
    onOpen: (Uri) -> Unit,
    onOpenDraft: (String) -> Unit,
    onOpenFile: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val all = recents ?: run {
        Box(Modifier.fillMaxSize())
        return
    }
    val filtered = all.filter { f ->
        (filter.kinds == null || f.kind in filter.kinds) &&
            (query.isBlank() || f.name.contains(query.trim(), ignoreCase = true))
    }
    val pinned = filtered.filter { it.pinned }
    val others = filtered.filterNot { it.pinned }
    val header: @Composable () -> Unit = { FilterRow(filter, onFilter, grid, onToggleGrid) }

    if (grid) {
        RecentGrid(container = container, files = pinned + others, header = header, onOpen = onOpen)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 112.dp),
    ) {
        item { header() }
        if (all.isEmpty()) {
            item { EmptyState(onOpenFile = onOpenFile) }
        } else if (filtered.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                )
            }
        }
        if (drafts.isNotEmpty() && query.isBlank()) {
            item { SectionHeader(stringResource(R.string.home_drafts)) }
            itemsIndexed(drafts, key = { _, d -> "d" + d.key }) { i, d ->
                DraftRow(d, Modifier.cardSegment(i, drafts.size), onOpen = { onOpenDraft(d.key) }, onDiscard = { container.drafts.delete(d.key) })
            }
        }
        if (pinned.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_pinned)) }
            itemsIndexed(pinned, key = { _, f -> "p" + f.uri }) { i, f ->
                FileRow(
                    f, Modifier.cardSegment(i, pinned.size), onOpen,
                    onPin = { scope.launch { container.recents.setPinned(f.uri, !f.pinned) } },
                    onRemove = { scope.launch { container.recents.remove(f.uri) } },
                )
            }
        }
        if (others.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_recent)) }
            itemsIndexed(others, key = { _, f -> "r" + f.uri }) { i, f ->
                FileRow(
                    f, Modifier.cardSegment(i, others.size), onOpen,
                    onPin = { scope.launch { container.recents.setPinned(f.uri, !f.pinned) } },
                    onRemove = { scope.launch { container.recents.remove(f.uri) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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
private fun FileRow(file: RecentFile, modifier: Modifier, onOpen: (Uri) -> Unit, onPin: () -> Unit, onRemove: () -> Unit) {
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
    val meta = listOf(formatRelative(file.lastOpened), formatSize(context, file.size)).filter { it.isNotEmpty() }
    ListRow(
        title = file.name,
        subtitle = meta.joinToString("  ·  "),
        modifier = modifier,
        leading = { KindBadge(file.kind) },
        onClick = { onOpen(file.uri) },
        onLongClick = { menu = true },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (file.pinned) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        },
    )
}

@Composable
private fun EmptyState(onOpenFile: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(Icons.AutoMirrored.Outlined.InsertDriveFile, size = 64.dp, selected = true)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onOpenFile, shape = CircleShape) { Text(stringResource(R.string.action_open_file)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewFileSheet(onDismiss: () -> Unit, onPick: (FileKind) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.new_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
            )
            CardGroup {
                NewRow(FileKind.DOCUMENT, R.string.new_document, R.string.new_document_desc, onPick)
                CardDivider()
                NewRow(FileKind.SPREADSHEET, R.string.new_sheet, R.string.new_sheet_desc, onPick)
                CardDivider()
                NewRow(FileKind.CSV, R.string.new_csv, R.string.new_csv_desc, onPick)
                CardDivider()
                NewRow(FileKind.PRESENTATION, R.string.new_presentation, R.string.new_presentation_desc, onPick)
            }
            Spacer(Modifier.height(12.dp))
            CardGroup {
                NewRow(FileKind.MARKDOWN, R.string.new_note, R.string.new_note_desc, onPick)
                CardDivider()
                NewRow(FileKind.TEXT, R.string.new_text, R.string.new_text_desc, onPick)
            }
        }
    }
}

@Composable
private fun NewRow(kind: FileKind, title: Int, desc: Int, onPick: (FileKind) -> Unit) {
    ListRow(
        title = stringResource(title),
        subtitle = stringResource(desc),
        leading = { KindBadge(kind) },
        onClick = { onPick(kind) },
    )
}

@Composable
private fun DraftRow(draft: Draft, modifier: Modifier, onOpen: () -> Unit, onDiscard: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListRow(
        title = draft.name,
        subtitle = stringResource(R.string.draft_unsaved, formatRelative(draft.savedAt)),
        subtitleColor = MaterialTheme.colorScheme.primary,
        modifier = modifier,
        leading = { KindBadge(draft.kind) },
        onClick = onOpen,
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.draft_discard)) }, onClick = { menu = false; onDiscard() })
                }
            }
        },
    )
}

@Composable
private fun FoldersTab(container: AppContainer, onOpenFolder: (Uri, String) -> Unit, onAdd: () -> Unit) {
    val folders by container.folders.folders.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 112.dp)) {
        if (folders.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTile(Icons.Outlined.FolderOpen, size = 64.dp, selected = true)
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.folders_empty_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.folders_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            item { SectionHeader(stringResource(R.string.home_folders)) }
        }
        itemsIndexed(folders, key = { _, u -> u.toString() }) { i, uri ->
            val name = remember(uri) { container.folders.displayName(uri) }
            var menu by remember { mutableStateOf(false) }
            ListRow(
                title = name,
                modifier = Modifier.cardSegment(i, folders.size),
                leading = { IconTile(Icons.Outlined.Folder) },
                onClick = { onOpenFolder(uri, name) },
                trailing = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_remove_from_list)) },
                                onClick = { menu = false; scope.launch { container.folders.remove(uri) } },
                            )
                        }
                    }
                },
            )
        }
        item {
            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                PrimaryPillButton(text = stringResource(R.string.folders_add), icon = Icons.Rounded.CreateNewFolder, onClick = onAdd)
            }
        }
    }
}

@Composable
private fun FilterRow(filter: Filter, onFilter: (Filter) -> Unit, grid: Boolean, onToggleGrid: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Filter.entries.toList()) { f ->
                PillChip(label = stringResource(f.label), selected = filter == f, onClick = { onFilter(f) })
            }
        }
        IconButton(onClick = onToggleGrid, modifier = Modifier.padding(end = 8.dp)) {
            Icon(
                if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                contentDescription = stringResource(if (grid) R.string.home_list_view else R.string.home_grid_view),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentGrid(container: AppContainer, files: List<RecentFile>, header: @Composable () -> Unit, onOpen: (Uri) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // The filter row spans the full width, past the grid's side padding.
            Box(
                Modifier.layout { m, c ->
                    val p = 16.dp.roundToPx()
                    val placeable = m.measure(c.copy(minWidth = c.minWidth + 2 * p, maxWidth = c.maxWidth + 2 * p))
                    layout(c.maxWidth, placeable.height) { placeable.place(-p, 0) }
                },
            ) { header() }
        }
        gridItems(files, key = { "g" + it.uri }) { f -> ThumbCard(container, f, onOpen) }
    }
}

@Composable
private fun ThumbCard(container: AppContainer, file: RecentFile, onOpen: (Uri) -> Unit) {
    val thumb by produceState<android.graphics.Bitmap?>(null, file.uri, file.lastOpened) { value = container.thumbnails.get(file) }
    Surface(
        onClick = { onOpen(file.uri) },
        shape = RoundedCornerShape(CardRadius),
        color = cardColor(),
        border = cardBorder(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.8f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val t = thumb
                if (t != null) {
                    // Slides are landscape: show them whole instead of cropping the sides off.
                    val landscape = t.width > t.height
                    Image(
                        t.asImageBitmap(),
                        contentDescription = null,
                        contentScale = if (landscape) ContentScale.Fit else ContentScale.Crop,
                        alignment = if (landscape) Alignment.Center else Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    KindBadge(file.kind, size = 48.dp)
                }
                if (file.pinned) {
                    Icon(Icons.Rounded.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
                }
            }
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                KindBadge(file.kind, size = 22.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatRelative(file.lastOpened), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
