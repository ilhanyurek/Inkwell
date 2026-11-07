package com.office.app.ui.sheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.FileInfo
import com.office.app.export.printPdf
import com.office.app.ui.common.BackButton
import com.office.app.ui.common.DraftBanner
import com.office.app.ui.common.ErrorScreen
import com.office.app.ui.common.LoadingScreen
import com.office.app.ui.common.UndoRedoButtons
import com.office.app.ui.common.UnsavedChangesDialog
import com.office.app.ui.common.shareUri
import com.office.app.ui.common.toast
import com.office.app.ui.theme.MonoStyle
import com.office.core.FileKind
import com.office.core.sheet.CellRef
import com.office.core.sheet.Value
import com.office.core.sheet.HAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch

private val ROW_HEIGHT = 34.dp
private val HEADER_HEIGHT = 28.dp
private val ROW_HEADER_WIDTH = 44.dp
private const val DP_PER_CHAR = 8.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetEditorScreen(
    container: AppContainer,
    uri: Uri?,
    info: FileInfo?,
    kind: FileKind,
    onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenOther: (Uri) -> Unit,
    draftKey: String? = null,
) {
    val vm: SheetEditorViewModel = viewModel(key = "sheet:$uri:$kind:$draftKey") { SheetEditorViewModel(container, uri, info, kind, draftKey) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushDraft() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val untitled = stringResource(R.string.untitled)
    val savedMsg = stringResource(R.string.saved)
    val failedMsg = stringResource(R.string.error_save_failed)

    var showUnsaved by remember { mutableStateOf(false) }
    var closeAfterSave by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showCharts by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(kind) }

    fun afterSave(e: Throwable?) {
        toast(context, if (e == null) savedMsg else failedMsg)
        if (e == null && closeAfterSave) onClose()
        closeAfterSave = false
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(vm.mimeType)) { target ->
        if (target != null) vm.saveTo(target, kind, ::afterSave) else { closeAfterSave = false }
    }
    val exportAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        if (target != null) vm.saveTo(target, exportFormat) { e -> toast(context, context.getString(if (e == null) R.string.exported else R.string.error_save_failed)) }
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) vm.exportPdf(target) { e -> toast(context, context.getString(if (e == null) R.string.exported else R.string.error_save_failed)) }
    }

    fun requestSave() {
        if (vm.uri == null) saveAs.launch(vm.suggestedName(untitled)) else vm.save(::afterSave)
    }

    BackHandler(enabled = vm.dirty || vm.editing) {
        if (vm.editing) vm.cancelEdit() else showUnsaved = true
    }

    when (val st = vm.loadState) {
        SheetEditorViewModel.LoadState.Loading -> { LoadingScreen(onBack = onClose, title = vm.fileName.orEmpty()); return }
        is SheetEditorViewModel.LoadState.Failed -> {
            ErrorScreen(stringResource(R.string.error_cannot_read_sheet), onBack = onClose, detail = st.message)
            return
        }
        SheetEditorViewModel.LoadState.Ready -> {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton { if (vm.dirty) showUnsaved = true else onClose() } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            vm.fileName ?: untitled,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (vm.dirty) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = ::requestSave, enabled = !vm.saving && (vm.dirty || vm.uri == null)) {
                        Icon(Icons.Outlined.Save, stringResource(R.string.action_save))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_save_as)) }, onClick = {
                                menuOpen = false; saveAs.launch(vm.suggestedName(untitled))
                            })
                            if (vm.sheet.charts.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sheet_charts)) },
                                    onClick = { menuOpen = false; showCharts = true },
                                    modifier = Modifier.testTag("menu_charts"),
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sheet_filter)) },
                                onClick = { menuOpen = false; if (vm.editing) vm.commitEdit(move = false); showFilter = true },
                                modifier = Modifier.testTag("menu_filter"),
                            )
                            if (!vm.isCsv && !vm.isOdf) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (vm.isFrozen) R.string.sheet_unfreeze else R.string.sheet_freeze)) },
                                    onClick = {
                                        menuOpen = false
                                        if (vm.isFrozen) vm.unfreeze() else vm.freezeAtSelection()
                                    },
                                    modifier = Modifier.testTag("menu_freeze"),
                                )
                            }
                            val other = if (vm.isCsv) FileKind.SPREADSHEET else FileKind.CSV
                            DropdownMenuItem(
                                text = { Text(stringResource(if (other == FileKind.CSV) R.string.action_export_csv else R.string.action_export_xlsx)) },
                                onClick = {
                                    menuOpen = false
                                    exportFormat = other
                                    exportAs.launch(vm.suggestedName(untitled, other))
                                },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export_pdf)) }, onClick = {
                                menuOpen = false
                                exportPdf.launch((vm.fileName ?: untitled).substringBeforeLast('.') + ".pdf")
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_print)) }, onClick = {
                                menuOpen = false
                                scope.launch {
                                    val name = vm.fileName ?: untitled
                                    runCatching { vm.exportPdfToCache(name) }.onSuccess { printPdf(context, it, name) }
                                }
                            })
                            vm.uri?.let { u ->
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                                    menuOpen = false
                                    shareUri(context, u, vm.mimeType, context.getString(R.string.action_share))
                                })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (vm.saving) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            vm.recoverableDraft?.let { d -> DraftBanner(d.savedAt, onRestore = vm::restoreDraft, onDiscard = vm::discardDraft) }
            FormulaBar(vm)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FilterBar(vm)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Grid(vm)
            }
            SelectionStats(vm)
            if (!vm.editing) {
                ActionBar(vm, onSort = { showSort = true }, onFormat = { showFormat = true })
                SheetTabs(vm)
            }
        }
    }

    if (showUnsaved) {
        UnsavedChangesDialog(
            onSave = { showUnsaved = false; closeAfterSave = true; requestSave() },
            onDiscard = { showUnsaved = false; vm.discardDraft(); onClose() },
            onDismiss = { showUnsaved = false },
        )
    }
    if (showSort) SortDialog(vm, onDismiss = { showSort = false })
    if (showFormat) FormatSheet(vm, onDismiss = { showFormat = false })
    if (showFilter) FilterDialog(vm, onDismiss = { showFilter = false })
    if (showCharts) ChartsDialog(vm, onDismiss = { showCharts = false })
}

@Composable
private fun FilterBar(vm: SheetEditorViewModel) {
    val f = vm.filter?.takeIf { it.sheet == vm.activeSheetIndex } ?: return
    val hidden = remember(vm.revision, f) { vm.hiddenRows().size }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FilterList, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "${CellRef.columnName(f.col)} · ${pluralStringResource(R.plurals.sheet_rows_hidden, hidden, hidden)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).testTag("filter_bar"),
            )
            TextButton(onClick = vm::clearFilter) { Text(stringResource(R.string.sheet_filter_clear)) }
        }
    }
}

@Composable
private fun FormulaBar(vm: SheetEditorViewModel) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var fnMenu by remember { mutableStateOf(false) }

    LaunchedEffect(vm.editing) {
        if (!vm.editing) focusManager.clearFocus()
    }

    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            vm.selection.label(),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp).padding(start = 4.dp),
            maxLines = 1,
        )
        Box {
            TextButton(onClick = { fnMenu = true }) {
                Text("fx", style = MonoStyle.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = fnMenu, onDismissRequest = { fnMenu = false }) {
                SheetEditorViewModel.FUNCTIONS.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, style = MonoStyle) },
                        onClick = {
                            fnMenu = false
                            vm.insertFunction(name)
                            runCatching { focus.requestFocus() }
                        },
                    )
                }
            }
        }
        VerticalDivider(Modifier.height(22.dp), color = MaterialTheme.colorScheme.outlineVariant)
        BasicTextField(
            value = vm.editText,
            onValueChange = vm::onEditChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { vm.commitEdit(move = true) }),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .focusRequester(focus)
                .onFocusChanged { if (it.isFocused) vm.beginEditing() },
        )
        if (vm.editing) {
            IconButton(onClick = vm::cancelEdit) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel)) }
            IconButton(onClick = { vm.commitEdit(move = false) }) {
                Icon(Icons.Default.Check, stringResource(R.string.action_confirm), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun widthDp(vm: SheetEditorViewModel, col: Int): Dp = (vm.columnWidthChars(col) * DP_PER_CHAR).coerceIn(40f, 520f).dp

private class GridColors(
    val gridLine: Color,
    val headerBg: Color,
    val selectionBg: Color,
    val headerSelBg: Color,
    val focus: Color,
    val error: Color,
    val text: Color,
    val cellBorder: Color,
    val muted: Color,
    val frozenLine: Color,
)

@Composable
private fun Grid(vm: SheetEditorViewModel) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision // read to recompose on mutations
    val sheet = vm.sheet
    val rowCount = maxOf(sheet.maxRow + 40, vm.selection.bottom + 20, 100)
    val colCount = maxOf(sheet.maxCol + 6, vm.selection.right + 4, 26)
    val widths = remember(vm.revision, vm.activeSheetIndex, colCount) { List(colCount) { widthDp(vm, it) } }
    val frozenCols = sheet.frozenCols.coerceIn(0, colCount - 1)
    val frozenRows = sheet.frozenRows.coerceIn(0, rowCount - 1)
    val hidden = remember(vm.revision, vm.activeSheetIndex, vm.filter) { vm.hiddenRows() }
    val topRows = remember(frozenRows, hidden) { (0 until frozenRows).filter { it !in hidden } }
    val scrollRows = remember(frozenRows, rowCount, hidden) { (frozenRows until rowCount).filter { it !in hidden } }
    val leftCols = 0 until frozenCols
    val rightCols = frozenCols until colCount

    val listState = rememberLazyListState()
    val headerState = rememberLazyListState()
    val leftState = rememberLazyListState()
    val hScroll = rememberScrollState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) ->
                headerState.scrollToItem(i, o)
                leftState.scrollToItem(i, o)
            }
    }

    val colors = GridColors(
        gridLine = MaterialTheme.colorScheme.outlineVariant,
        headerBg = MaterialTheme.colorScheme.surfaceContainer,
        selectionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        headerSelBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        focus = MaterialTheme.colorScheme.primary,
        error = MaterialTheme.colorScheme.error,
        text = MaterialTheme.colorScheme.onSurface,
        cellBorder = MaterialTheme.colorScheme.onSurfaceVariant,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        frozenLine = MaterialTheme.colorScheme.outline,
    )
    val filterCol = vm.filter?.takeIf { it.sheet == vm.activeSheetIndex }?.col

    @Composable
    fun Pane(cols: IntRange, state: LazyListState, scrollable: Boolean, modifier: Modifier) {
        Column(modifier) {
            ColumnHeaders(vm, cols, widths, colors, filterCol)
            topRows.forEach { r -> CellRow(vm, r, cols, widths, colors) }
            if (topRows.isNotEmpty()) FrozenDivider(colors, horizontal = true)
            LazyColumn(state = state, userScrollEnabled = scrollable, modifier = Modifier.fillMaxSize()) {
                items(scrollRows.size, key = { scrollRows[it] }) { i -> CellRow(vm, scrollRows[i], cols, widths, colors) }
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        // Row numbers
        Column(Modifier.width(ROW_HEADER_WIDTH).fillMaxHeight().background(colors.headerBg)) {
            Box(Modifier.height(HEADER_HEIGHT).fillMaxWidth().drawBehind {
                drawLine(colors.gridLine, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            })
            topRows.forEach { r -> RowHeader(vm, r, colors) }
            if (topRows.isNotEmpty()) FrozenDivider(colors, horizontal = true)
            LazyColumn(state = headerState, userScrollEnabled = false) {
                items(scrollRows.size, key = { scrollRows[it] }) { i -> RowHeader(vm, scrollRows[i], colors) }
            }
        }
        if (!leftCols.isEmpty()) {
            val leftWidth = leftCols.fold(0.dp) { a, c -> a + widths[c] }
            Pane(leftCols, leftState, scrollable = false, modifier = Modifier.width(leftWidth).fillMaxHeight())
            FrozenDivider(colors, horizontal = false)
        }
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            val width = rightCols.fold(0.dp) { a, c -> a + widths[c] }
            Pane(rightCols, listState, scrollable = true, modifier = Modifier.width(width))
        }
    }
}

@Composable
private fun FrozenDivider(colors: GridColors, horizontal: Boolean) {
    Box(
        (if (horizontal) Modifier.fillMaxWidth().height(2.dp) else Modifier.fillMaxHeight().width(2.dp))
            .background(colors.frozenLine),
    )
}

@Composable
private fun RowHeader(vm: SheetEditorViewModel, r: Int, colors: GridColors) {
    val selected = r in vm.selection.top..vm.selection.bottom
    Box(
        Modifier
            .height(ROW_HEIGHT)
            .fillMaxWidth()
            .background(if (selected) colors.headerSelBg else Color.Transparent)
            .clickable { vm.selectRow(r) }
            .drawBehind {
                drawLine(colors.gridLine, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                drawLine(colors.gridLine, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text((r + 1).toString(), fontSize = 11.sp, color = if (selected) colors.focus else colors.muted)
    }
}

@Composable
private fun ColumnHeaders(vm: SheetEditorViewModel, cols: IntRange, widths: List<Dp>, colors: GridColors, filterCol: Int?) {
    val sel = vm.selection
    Row(Modifier.height(HEADER_HEIGHT).background(colors.headerBg)) {
        for (c in cols) {
            val selected = c in sel.left..sel.right
            Box(
                Modifier
                    .width(widths[c])
                    .fillMaxHeight()
                    .background(if (selected) colors.headerSelBg else Color.Transparent)
                    .clickable { vm.selectColumn(c) }
                    .drawBehind {
                        drawLine(colors.gridLine, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
                        drawLine(colors.gridLine, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(CellRef.columnName(c), fontSize = 11.sp, color = if (selected || c == filterCol) colors.focus else colors.muted)
                    if (c == filterCol) {
                        Icon(Icons.Default.FilterList, stringResource(R.string.sheet_filter), Modifier.size(12.dp), tint = colors.focus)
                    }
                }
            }
        }
    }
}

@Composable
private fun CellRow(vm: SheetEditorViewModel, r: Int, cols: IntRange, widths: List<Dp>, colors: GridColors) {
    val sheet = vm.sheet
    val sel = vm.selection
    val density = LocalDensity.current
    val starts = remember(widths, cols) { cols.runningFold(0.dp) { a, c -> a + widths[c] } }
    Row(
        Modifier
            .height(ROW_HEIGHT)
            .pointerInput(r, starts, cols) {
                fun colAt(x: Float): Int {
                    val xdp = with(density) { x.toDp() }
                    val idx = starts.indexOfFirst { it > xdp } - 1
                    return cols.first + (if (idx < 0) cols.count() - 1 else idx.coerceIn(0, cols.count() - 1))
                }
                detectTapGestures(
                    onTap = { vm.tapCell(CellRef(r, colAt(it.x))) },
                    onDoubleTap = {
                        vm.tapCell(CellRef(r, colAt(it.x)))
                        vm.beginEditing()
                    },
                    onLongPress = { vm.extendSelection(CellRef(r, colAt(it.x))) },
                )
            },
    ) {
        for (c in cols) {
            val ref = CellRef(r, c)
            val inSel = ref in sel
            val isFocus = ref == sel.focus
            val value = if (c <= sheet.maxCol && r <= sheet.maxRow) vm.display(ref) else Value.Empty
            val st = sheet.styles[ref]
            val fill = st?.fill?.let { Color(it) }
            val align = when (st?.hAlign) {
                HAlign.LEFT -> TextAlign.Start
                HAlign.CENTER -> TextAlign.Center
                HAlign.RIGHT -> TextAlign.End
                null -> if (value is Value.Num) TextAlign.End else TextAlign.Start
            }
            Box(
                Modifier
                    .width(widths[c])
                    .fillMaxHeight()
                    .then(if (fill != null) Modifier.background(fill) else Modifier)
                    .background(if (inSel && !sel.isSingle) colors.selectionBg else Color.Transparent)
                    .drawBehind {
                        drawLine(colors.gridLine, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
                        drawLine(colors.gridLine, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                        if (st?.border == true) {
                            drawRect(colors.cellBorder, style = Stroke(1.5f))
                        }
                    }
                    .then(if (isFocus) Modifier.border(2.dp, colors.focus) else Modifier)
                    .padding(horizontal = 6.dp),
                contentAlignment = when (align) {
                    TextAlign.End -> Alignment.CenterEnd
                    TextAlign.Center -> Alignment.Center
                    else -> Alignment.CenterStart
                },
            ) {
                if (value != Value.Empty) {
                    val decorations = buildList {
                        if (st?.underline == true) add(TextDecoration.Underline)
                        if (st?.strike == true) add(TextDecoration.LineThrough)
                    }
                    Text(
                        vm.displayText(ref, value),
                        fontSize = (st?.fontSizePt?.let { it * 14f / 11f } ?: 14f).coerceIn(8f, 28f).sp,
                        fontWeight = if (st?.bold == true) FontWeight.Bold else null,
                        fontStyle = if (st?.italic == true) FontStyle.Italic else null,
                        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                        maxLines = if (st?.wrap == true) 2 else 1,
                        lineHeight = 15.sp,
                        overflow = TextOverflow.Clip,
                        softWrap = st?.wrap == true,
                        textAlign = align,
                        color = when {
                            value is Value.Err -> colors.error
                            st?.fontColor != null -> Color(st.fontColor!!)
                            // Keep text readable on light fills in dark mode.
                            fill != null -> if (fill.luminance() > 0.5f) Color(0xFF1B1C1E) else Color(0xFFF5F5F5)
                            value is Value.Bool -> colors.focus
                            else -> colors.text
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionStats(vm: SheetEditorViewModel) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val stats = remember(vm.selection, vm.revision) { vm.selectionStats() } ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (stats.numeric > 0) {
                StatText(stringResource(R.string.stat_sum), Value.formatNumber(stats.sum))
                StatText(stringResource(R.string.stat_average), Value.formatNumber(Math.round(stats.average * 1e6) / 1e6))
            }
            StatText(stringResource(R.string.stat_count), stats.count.toString())
        }
    }
}

@Composable
private fun StatText(label: String, value: String) {
    Row {
        Text("$label ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun ActionBar(vm: SheetEditorViewModel, onSort: () -> Unit, onFormat: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
            UndoRedoButtons(vm.canUndo, vm.canRedo, vm::undo, vm::redo, modifier = Modifier.padding(start = 4.dp))
            Sep()
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!vm.isCsv) {
                    Action(R.string.sheet_format) { onFormat() }
                    Sep()
                }
                Action(R.string.sheet_copy) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("cells", vm.copyText()))
                    toast(context, context.getString(R.string.copied))
                }
                Action(R.string.sheet_cut) { clipboard.setPrimaryClip(ClipData.newPlainText("cells", vm.cut())) }
                Action(R.string.sheet_paste) {
                    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text != null) vm.paste(text)
                }
                Action(R.string.sheet_clear) { vm.clearSelection() }
                Action(R.string.sheet_fill_down) { vm.fillDown() }
                Sep()
                Action(R.string.sheet_insert_row_above) { vm.insertRow(above = true) }
                Action(R.string.sheet_insert_row_below) { vm.insertRow(above = false) }
                Action(R.string.sheet_delete_rows) { vm.deleteRows() }
                Sep()
                Action(R.string.sheet_insert_col_left) { vm.insertColumn(left = true) }
                Action(R.string.sheet_insert_col_right) { vm.insertColumn(left = false) }
                Action(R.string.sheet_delete_cols) { vm.deleteColumns() }
                Sep()
                Action(R.string.sheet_sort) { onSort() }
                Action(R.string.sheet_narrower) { vm.adjustColumnWidth(-2f) }
                Action(R.string.sheet_wider) { vm.adjustColumnWidth(2f) }
            }
            }
        }
    }
}

@Composable
private fun Action(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Sep() {
    VerticalDivider(Modifier.height(20.dp).padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetTabs(vm: SheetEditorViewModel) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    val baseName = stringResource(R.string.sheet_default_name)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f)) {
                itemsIndexed(vm.sheets) { i, s ->
                    val selected = i == vm.activeSheetIndex
                    Box {
                        Column(
                            Modifier
                                .fillMaxHeight()
                                .combinedClickable(onClick = { vm.selectSheet(i) }, onLongClick = { menuFor = i })
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier.height(2.dp).width(24.dp)
                                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                            )
                        }
                        DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.sheet_rename)) }, onClick = { menuFor = null; renaming = i })
                            if (vm.sheets.size > 1) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.sheet_delete)) }, onClick = { menuFor = null; vm.deleteSheet(i) })
                            }
                        }
                    }
                }
            }
            if (!vm.isCsv) {
                IconButton(onClick = { vm.addSheet(baseName) }) {
                    Icon(Icons.Default.Add, stringResource(R.string.sheet_add))
                }
            }
        }
    }
    renaming?.let { index ->
        var name by remember(index) { mutableStateOf(vm.sheets.getOrNull(index)?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.sheet_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(31) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameSheet(index, name); renaming = null }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SortDialog(vm: SheetEditorViewModel, onDismiss: () -> Unit) {
    var header by remember { mutableStateOf(true) }
    val column = CellRef.columnName(vm.selection.focus.col)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sheet_sort_title, column)) },
        text = {
            Row(Modifier.fillMaxWidth().clickable { header = !header }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = header, onCheckedChange = { header = it })
                Text(stringResource(R.string.sheet_sort_has_header))
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.sort(ascending = false, hasHeader = header); onDismiss() }) { Text(stringResource(R.string.sheet_sort_desc)) }
                TextButton(onClick = { vm.sort(ascending = true, hasHeader = header); onDismiss() }) { Text(stringResource(R.string.sheet_sort_asc)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
