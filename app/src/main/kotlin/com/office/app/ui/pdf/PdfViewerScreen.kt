package com.office.app.ui.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.office.app.ui.common.openWithOtherApp
import com.office.app.ui.common.shareUri
import com.office.app.ui.common.toast
import kotlinx.coroutines.launch

private enum class Tool { VIEW, HIGHLIGHT, TEXT, SIGN, REDACT }

/** Where a signature is about to be placed: page, centre and width as a fraction of the page width. */
private data class Placement(val page: Int, val center: PagePoint, val width: Float)

private val HighlightColors = listOf(0xFFE45C, 0x7EE0A1, 0x8EC5FF, 0xFF9EB5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    container: AppContainer,
    uri: Uri,
    info: FileInfo,
    onClose: () -> Unit,
    onOpenOther: (Uri) -> Unit,
) {
    val vm: PdfViewerViewModel = viewModel(key = "pdf:$uri") { PdfViewerViewModel(container, uri, info) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushDraft() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var tool by remember { mutableStateOf(Tool.VIEW) }
    var highlightColor by remember { mutableStateOf(HighlightColors[0]) }
    var searching by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var showExtract by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }
    var closeAfterSave by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf<Pair<Int, PagePoint>?>(null) }
    var extractRange by remember { mutableStateOf(0 to 0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var signature by remember { mutableStateOf<Bitmap?>(null) }
    var placement by remember { mutableStateOf<Placement?>(null) }
    var pickSignatureAt by remember { mutableStateOf<Pair<Int, PagePoint>?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var showAnnotations by remember { mutableStateOf(false) }
    val redactions = remember { androidx.compose.runtime.mutableStateMapOf<Int, List<Pair<PagePoint, PagePoint>>>() }
    var redactMode by remember { mutableStateOf(RedactMode.BLUR) }
    var confirmRedact by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<AnnotationInfo?>(null) }

    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val savedMsg = stringResource(R.string.saved)
    val failedMsg = stringResource(R.string.error_save_failed)

    fun afterSave(e: Throwable?) {
        toast(context, if (e == null) savedMsg else failedMsg)
        if (e == null && closeAfterSave) onClose()
        closeAfterSave = false
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) vm.save(target) { e ->
            afterSave(e)
            if (e == null) onOpenOther(target)
        } else { closeAfterSave = false }
    }
    val extractTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) {
            val (from, to) = extractRange
            vm.extract(from, to, target) { e -> toast(context, context.getString(if (e == null) R.string.exported else R.string.error_save_failed)) }
        }
    }

    fun requestSave() {
        if (container.files.canWrite(uri)) vm.save(onDone = ::afterSave)
        else saveAs.launch(vm.fileName)
    }

    BackHandler(enabled = vm.dirty || searching || tool != Tool.VIEW) {
        when {
            searching -> { searching = false; vm.clearSearch() }
            tool != Tool.VIEW -> tool = Tool.VIEW
            else -> showUnsaved = true
        }
    }

    when (val st = vm.state) {
        PdfViewerViewModel.State.Loading -> { LoadingScreen(onBack = onClose, title = vm.fileName); return }
        is PdfViewerViewModel.State.Failed -> {
            ErrorScreen(stringResource(R.string.error_cannot_read_pdf), onBack = onClose, detail = st.message)
            return
        }
        PdfViewerViewModel.State.NeedsPassword -> { PasswordScreen(vm, onClose); return }
        PdfViewerViewModel.State.Ready -> {}
    }

    Scaffold(
        topBar = {
            if (searching) {
                SearchTopBar(vm, onClose = { searching = false; vm.clearSearch() })
            } else {
                TopAppBar(
                    navigationIcon = { BackButton { if (vm.dirty) showUnsaved = true else onClose() } },
                    title = {
                        Column {
                            Text(vm.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.pdf_page_of, currentPage + 1, vm.pageSizes.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.action_search)) }
                        if (vm.dirty) {
                            IconButton(onClick = ::requestSave, enabled = !vm.busy) { Icon(Icons.Outlined.Save, stringResource(R.string.action_save)) }
                        }
                        Box(if (vm.formFields.isNotEmpty()) Modifier.testTag("pdf_form_ready") else Modifier) {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.pdf_go_to_page)) }, onClick = { menuOpen = false; showGoTo = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.pdf_rotate_page)) }, onClick = { menuOpen = false; vm.rotate(currentPage, 90) })
                                if (vm.pageSizes.size > 1) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_delete_page)) }, onClick = { menuOpen = false; vm.deletePage(currentPage) })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.pdf_extract_pages)) }, onClick = { menuOpen = false; showExtract = true })
                                if (vm.annotations.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.pdf_annotations_count, vm.annotations.size)) },
                                        onClick = { menuOpen = false; showAnnotations = true },
                                    )
                                }
                                if (vm.formFields.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_fill_form)) }, onClick = { menuOpen = false; showForm = true })
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_save_as)) }, onClick = { menuOpen = false; saveAs.launch(vm.fileName) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_print)) }, onClick = {
                                    menuOpen = false
                                    vm.workingFile()?.let { printPdf(context, it, vm.fileName) }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                                    menuOpen = false
                                    shareUri(context, uri, "application/pdf", context.getString(R.string.action_share))
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_open_with)) }, onClick = {
                                    menuOpen = false
                                    openWithOtherApp(context, uri, "application/pdf")
                                })
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            val p = placement
            if (p != null && signature != null) {
                PlacementBar(
                    width = p.width,
                    onWidth = { placement = p.copy(width = it) },
                    onCancel = { placement = null },
                    onConfirm = {
                        val bmp = signature!!
                        val (pw, ph) = vm.pageSizes.getOrNull(p.page) ?: (612 to 792)
                        val h = p.width * bmp.height / bmp.width * pw / ph
                        vm.stampSignature(
                            p.page,
                            PagePoint(p.center.x - p.width / 2, p.center.y - h / 2),
                            PagePoint(p.center.x + p.width / 2, p.center.y + h / 2),
                            bmp,
                        )
                        placement = null
                    },
                )
            } else if (tool == Tool.REDACT) {
                RedactBar(
                    mode = redactMode,
                    onMode = { redactMode = it },
                    count = redactions.values.sumOf { it.size },
                    onClear = { redactions.clear() },
                    onCancel = { redactions.clear(); tool = Tool.VIEW },
                    onApply = { confirmRedact = true },
                )
            } else if (!searching) {
                ToolBar(
                    vm = vm,
                    tool = tool,
                    onTool = { tool = it },
                    color = highlightColor,
                    onColor = { highlightColor = it },
                    scale = scale,
                    onZoom = { scale = it.coerceIn(1f, 4f) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            vm.recoverableDraft?.let { d -> DraftBanner(d.savedAt, onRestore = vm::restoreDraft, onDiscard = vm::discardDraft) }
            vm.searchResults?.let { results ->
                SearchResults(results) { page ->
                    scope.launch { listState.animateScrollToItem(page) }
                }
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerInput(Unit) {
                        // Two-finger pinch zoom; single-finger gestures fall through to scrolling.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) scale = (scale * zoom).coerceIn(1f, 4f)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                val viewport = maxWidth
                val pageWidth = viewport * scale - 16.dp
                val hScroll = rememberScrollState()
                Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = scale > 1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.width(viewport * scale).fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        userScrollEnabled = tool != Tool.HIGHLIGHT,
                    ) {
                        items(vm.pageSizes.size, key = { "${vm.version}:$it" }) { index ->
                            PdfPage(
                                vm = vm,
                                index = index,
                                width = pageWidth,
                                tool = tool,
                                highlightColor = highlightColor,
                                onHighlight = { a, b -> vm.highlight(index, a, b, highlightColor) },
                                onTextAt = { p -> pendingText = index to p },
                                placement = placement?.takeIf { it.page == index },
                                signature = signature,
                                onSignAt = { pt ->
                                    if (signature == null) pickSignatureAt = index to pt
                                    else placement = Placement(index, pt, placement?.width ?: 0.3f)
                                },
                                onMovePlacement = { pt -> placement = placement?.copy(center = pt) },
                                onLongPress = { pt -> deleteCandidate = vm.annotations.lastOrNull { it.page == index && pt in it } },
                                redactions = redactions[index].orEmpty(),
                                onRedact = { a, b -> redactions[index] = redactions[index].orEmpty() + (a to b) },
                            )
                        }
                    }
                }
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
    if (showGoTo) {
        PageNumberDialog(
            title = stringResource(R.string.pdf_go_to_page),
            max = vm.pageSizes.size,
            onDismiss = { showGoTo = false },
            onConfirm = { page -> showGoTo = false; scope.launch { listState.scrollToItem(page - 1) } },
        )
    }
    if (showExtract) {
        ExtractDialog(
            max = vm.pageSizes.size,
            onDismiss = { showExtract = false },
            onConfirm = { from, to ->
                showExtract = false
                extractRange = (from - 1) to (to - 1)
                extractTarget.launch(vm.fileName.substringBeforeLast('.') + "_$from-$to.pdf")
            },
        )
    }
    pickSignatureAt?.let { (page, point) ->
        SignaturePicker(
            store = vm.signatures,
            onPick = { bmp -> signature = bmp; placement = Placement(page, point, 0.3f); pickSignatureAt = null },
            onDismiss = { pickSignatureAt = null },
        )
    }
    if (confirmRedact) {
        AlertDialog(
            onDismissRequest = { confirmRedact = false },
            title = { Text(stringResource(R.string.pdf_redact_confirm_title)) },
            text = { Text(stringResource(R.string.pdf_redact_confirm_message, redactions.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRedact = false
                    vm.redact(redactions.toMap(), redactMode)
                    redactions.clear()
                    tool = Tool.VIEW
                }) { Text(stringResource(R.string.pdf_tool_redact), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRedact = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showAnnotations) {
        AnnotationsDialog(
            vm.annotations,
            onGoTo = { a -> showAnnotations = false; scope.launch { listState.animateScrollToItem(a.page) } },
            onDelete = { a -> vm.deleteAnnotation(a) },
            onDismiss = { showAnnotations = false },
        )
    }
    deleteCandidate?.let { a ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.pdf_annotation_delete_title)) },
            text = { Text(annotationLabel(a)) },
            confirmButton = {
                TextButton(onClick = { deleteCandidate = null; vm.deleteAnnotation(a) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showForm) {
        FormDialog(vm.formFields, onDismiss = { showForm = false }, onApply = { values, flatten -> showForm = false; vm.fillForm(values, flatten) })
    }
    pendingText?.let { (page, point) ->
        AddTextDialog(
            onDismiss = { pendingText = null },
            onConfirm = { text, size ->
                pendingText = null
                vm.addText(page, point, text, size, 0x1B1C1E) { toast(context, failedMsg) }
            },
        )
    }
}

@Composable
private fun PdfPage(
    vm: PdfViewerViewModel,
    index: Int,
    width: Dp,
    tool: Tool,
    highlightColor: Int,
    onHighlight: (PagePoint, PagePoint) -> Unit,
    onTextAt: (PagePoint) -> Unit,
    placement: Placement? = null,
    signature: Bitmap? = null,
    onSignAt: (PagePoint) -> Unit = {},
    onMovePlacement: (PagePoint) -> Unit = {},
    onLongPress: (PagePoint) -> Unit = {},
    redactions: List<Pair<PagePoint, PagePoint>> = emptyList(),
    onRedact: (PagePoint, PagePoint) -> Unit = { _, _ -> },
) {
    val (pw, ph) = vm.pageSizes.getOrNull(index) ?: (612 to 792)
    val density = LocalDensity.current
    // Render at the on-screen width, bucketed so small zoom changes reuse bitmaps.
    val targetPx = with(density) { width.toPx() }.let { ((it / 256f).toInt() + 1) * 256 }.coerceIn(256, 2560)
    val bitmap by produceState<Bitmap?>(null, index, targetPx, vm.version) { value = vm.render(index, targetPx) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val hl = Color(0xFF000000 or highlightColor.toLong())

    Box(
        Modifier
            .width(width)
            .testTag("pdf_page_$index")
            .aspectRatio(pw.toFloat() / ph)
            .background(Color.White)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(
                when (tool) {
                    Tool.HIGHLIGHT, Tool.REDACT -> Modifier.pointerInput(index, tool) {
                        detectDragGestures(
                            onDragStart = { dragStart = it; dragEnd = it },
                            onDrag = { change, _ -> dragEnd = change.position },
                            onDragEnd = {
                                val a = dragStart
                                val b = dragEnd
                                if (a != null && b != null && size.width > 0) {
                                    val p1 = PagePoint(a.x / size.width, a.y / size.height)
                                    val p2 = PagePoint(b.x / size.width, b.y / size.height)
                                    if (tool == Tool.REDACT) onRedact(p1, p2) else onHighlight(p1, p2)
                                }
                                dragStart = null; dragEnd = null
                            },
                            onDragCancel = { dragStart = null; dragEnd = null },
                        )
                    }
                    Tool.TEXT -> Modifier.pointerInput(index) {
                        detectTapGestures { p -> onTextAt(PagePoint(p.x / size.width, p.y / size.height)) }
                    }
                    Tool.SIGN -> Modifier.pointerInput(index) {
                        detectTapGestures { p -> onSignAt(PagePoint(p.x / size.width, p.y / size.height)) }
                    }
                    Tool.VIEW -> Modifier.pointerInput(index) {
                        detectTapGestures(onLongPress = { p -> onLongPress(PagePoint(p.x / size.width, p.y / size.height)) })
                    }
                },
            ),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
        if (placement != null && signature != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pagePx = with(density) { maxWidth.toPx() }
                val pageHeightPx = with(density) { maxHeight.toPx() }
                val wPx = placement.width * pagePx
                val hPx = wPx * signature.height / signature.width
                var center by remember(placement.page) { mutableStateOf(Offset(placement.center.x * pagePx, placement.center.y * pageHeightPx)) }
                androidx.compose.runtime.LaunchedEffect(placement.center) {
                    center = Offset(placement.center.x * pagePx, placement.center.y * pageHeightPx)
                }
                Image(
                    signature.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset((center.x - wPx / 2).toInt(), (center.y - hPx / 2).toInt()) }
                        .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                        .border(1.dp, MaterialTheme.colorScheme.primary)
                        .pointerInput(placement.page) {
                            detectDragGestures(
                                onDrag = { change, drag ->
                                    change.consume()
                                    center += drag
                                },
                                onDragEnd = { onMovePlacement(PagePoint(center.x / pagePx, center.y / pageHeightPx)) },
                            )
                        },
                )
            }
        }
        if (redactions.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                redactions.forEach { (p1, p2) ->
                    val tl = Offset(minOf(p1.x, p2.x) * size.width, minOf(p1.y, p2.y) * size.height)
                    val sz = Size(kotlin.math.abs(p1.x - p2.x) * size.width, kotlin.math.abs(p1.y - p2.y) * size.height)
                    drawRect(Color.Black.copy(alpha = 0.55f), tl, sz)
                    drawRect(Color(0xFFB8432F), tl, sz, style = Stroke(2f))
                }
            }
        }
        val a = dragStart
        val b = dragEnd
        if (a != null && b != null) {
            Canvas(Modifier.fillMaxSize()) {
                val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val sz = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                val c = if (tool == Tool.REDACT) Color.Black else hl
                drawRect(c.copy(alpha = 0.35f), tl, sz)
                drawRect(c, tl, sz, style = Stroke(1.5f))
            }
        }
    }
}

@Composable
private fun ToolBar(vm: PdfViewerViewModel, tool: Tool, onTool: (Tool) -> Unit, color: Int, onColor: (Int) -> Unit, scale: Float, onZoom: (Float) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.navigationBarsPadding()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UndoRedoButtons(vm.canUndo && !vm.busy, vm.canRedo && !vm.busy, vm::undo, vm::redo)
                FilterChip(selected = tool == Tool.VIEW, onClick = { onTool(Tool.VIEW) }, label = { Text(stringResource(R.string.pdf_tool_view)) })
                FilterChip(selected = tool == Tool.HIGHLIGHT, onClick = { onTool(Tool.HIGHLIGHT) }, label = { Text(stringResource(R.string.pdf_tool_highlight)) })
                FilterChip(selected = tool == Tool.TEXT, onClick = { onTool(Tool.TEXT) }, label = { Text(stringResource(R.string.pdf_tool_text)) })
                FilterChip(selected = tool == Tool.SIGN, onClick = { onTool(Tool.SIGN) }, label = { Text(stringResource(R.string.pdf_tool_sign)) })
                FilterChip(selected = tool == Tool.REDACT, onClick = { onTool(Tool.REDACT) }, label = { Text(stringResource(R.string.pdf_tool_redact)) })
                Spacer(Modifier.width(4.dp))
                if (tool == Tool.HIGHLIGHT) {
                    HighlightColors.forEach { c ->
                        Box(
                            Modifier
                                .size(22.dp)
                                .background(Color(0xFF000000 or c.toLong()), CircleShape)
                                .then(if (c == color) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { onColor(c) },
                        )
                    }
                } else {
                    TextButton(onClick = { onZoom(scale / 1.25f) }, enabled = scale > 1f) { Text("−") }
                    Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { onZoom(scale * 1.25f) }, enabled = scale < 4f) { Text("+") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(vm: PdfViewerViewModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) } },
        title = {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.pdf_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            IconButton(onClick = { vm.search(query) }) { Icon(Icons.Default.Search, stringResource(R.string.action_search)) }
        },
    )
}

@Composable
private fun SearchResults(results: List<SearchHit>, onPick: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            Text(
                if (results.isEmpty()) stringResource(R.string.pdf_no_results)
                else LocalContext.current.resources.getQuantityString(R.plurals.pdf_results, results.size, results.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(results) { hit ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(hit.page) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.pdf_page_short, hit.page + 1),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordScreen(vm: PdfViewerViewModel, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text(vm.fileName, maxLines = 1) }, navigationIcon = { BackButton(onClose) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.pdf_password_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                isError = vm.wrongPassword,
                supportingText = { if (vm.wrongPassword) Text(stringResource(R.string.pdf_password_wrong)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.unlock(password) }),
                label = { Text(stringResource(R.string.pdf_password_label)) },
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { vm.unlock(password) }, enabled = password.isNotEmpty() && !vm.busy) {
                Text(stringResource(R.string.pdf_unlock))
            }
        }
    }
}

@Composable
private fun PageNumberDialog(title: String, max: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val value = text.toIntOrNull()?.takeIf { it in 1..max }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(6) },
                singleLine = true,
                label = { Text("1 – $max") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ExtractDialog(max: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf(max.toString()) }
    val f = from.toIntOrNull()
    val t = to.toIntOrNull()
    val valid = f != null && t != null && f in 1..max && t in f..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_extract_pages)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = from, onValueChange = { from = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text(stringResource(R.string.pdf_from_page)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = to, onValueChange = { to = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text(stringResource(R.string.pdf_to_page)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (valid) onConfirm(f!!, t!!) }, enabled = valid) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AddTextDialog(onDismiss: () -> Unit, onConfirm: (String, Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableFloatStateOf(12f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_add_text)) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    listOf(9f, 12f, 16f, 24f).forEach { s ->
                        FilterChip(
                            selected = size == s,
                            onClick = { size = s },
                            label = { Text("${s.toInt()} pt") },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text, size) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PlacementBar(width: Float, onWidth: (Float) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.pdf_signature_place_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pdf_signature_size), style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(value = width, onValueChange = onWidth, valueRange = 0.1f..0.8f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.pdf_signature_apply)) }
            }
        }
    }
}

@Composable
private fun FormDialog(fields: List<FormField>, onDismiss: () -> Unit, onApply: (Map<String, String>, Boolean) -> Unit) {
    val values = remember(fields) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { fields.forEach { put(it.name, it.value) } } }
    var flatten by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_fill_form)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(fields, key = { it.name }) { f ->
                    when (f.type) {
                        FieldType.TEXT -> OutlinedTextField(
                            value = values[f.name].orEmpty(),
                            onValueChange = { values[f.name] = it },
                            label = { Text(f.label) },
                            enabled = !f.readOnly,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FieldType.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = values[f.name] == "true",
                                onCheckedChange = { values[f.name] = it.toString() },
                                enabled = !f.readOnly,
                            )
                            Text(f.label)
                        }
                        FieldType.CHOICE, FieldType.RADIO -> Column {
                            Text(f.label, style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                f.options.forEach { o ->
                                    FilterChip(selected = values[f.name] == o, onClick = { values[f.name] = o }, label = { Text(o) }, enabled = !f.readOnly)
                                }
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = flatten, onCheckedChange = { flatten = it })
                        Column {
                            Text(stringResource(R.string.pdf_form_flatten))
                            Text(stringResource(R.string.pdf_form_flatten_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(values.filter { (k, v) -> fields.first { it.name == k }.value != v }, flatten) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun annotationLabel(a: AnnotationInfo): String {
    val kind = when (a.subtype) {
        "Highlight" -> stringResource(R.string.pdf_tool_highlight)
        "FreeText" -> stringResource(R.string.pdf_tool_text)
        "Text" -> stringResource(R.string.pdf_annotation_note)
        else -> a.subtype
    }
    return listOf(kind, a.text.take(60)).filter { it.isNotBlank() }.joinToString(": ")
}

@Composable
private fun AnnotationsDialog(list: List<AnnotationInfo>, onGoTo: (AnnotationInfo) -> Unit, onDelete: (AnnotationInfo) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_annotations)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { "${it.page}:${it.index}:${it.subtype}" }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { onGoTo(a) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.pdf_page_short, a.page + 1),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(52.dp),
                        )
                        Text(annotationLabel(a), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onDelete(a) }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

@Composable
private fun RedactBar(mode: RedactMode, onMode: (RedactMode) -> Unit, count: Int, onClear: () -> Unit, onCancel: () -> Unit, onApply: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.pdf_redact_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == RedactMode.BLUR, onClick = { onMode(RedactMode.BLUR) }, label = { Text(stringResource(R.string.pdf_redact_blur)) })
                FilterChip(selected = mode == RedactMode.BLACK, onClick = { onMode(RedactMode.BLACK) }, label = { Text(stringResource(R.string.pdf_redact_black)) })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = if (count > 0) onClear else onCancel) {
                    Text(stringResource(if (count > 0) R.string.sheet_clear else R.string.action_cancel))
                }
                TextButton(onClick = onApply, enabled = count > 0) { Text(stringResource(R.string.pdf_redact_apply)) }
            }
        }
    }
}
