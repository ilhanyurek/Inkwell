package com.office.app.ui.document

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.FileInfo
import com.office.app.data.Settings
import com.office.app.export.printPdf
import com.office.app.ui.common.BackButton
import com.office.app.ui.common.DraftBanner
import com.office.app.ui.common.ErrorScreen
import com.office.app.ui.common.InfoBanner
import com.office.app.ui.common.LoadingScreen
import com.office.app.ui.common.UndoRedoButtons
import com.office.app.ui.common.UnsavedChangesDialog
import com.office.app.ui.common.shareUri
import com.office.app.ui.common.toast
import com.office.core.FileKind
import com.office.core.docx.DocxReader
import com.office.core.text.Embed
import com.office.core.text.EmbedKind
import com.office.core.text.InlineAttr
import com.office.core.text.ListType
import com.office.core.text.ParagraphStyle
import kotlinx.coroutines.launch
import com.office.core.text.Alignment as DocAlignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentEditorScreen(
    container: AppContainer,
    uri: Uri?,
    info: FileInfo?,
    kind: FileKind,
    onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenOther: (Uri) -> Unit,
    draftKey: String? = null,
) {
    val vm: DocumentEditorViewModel = viewModel(key = "doc:$uri:$kind:$draftKey") { DocumentEditorViewModel(container, uri, info, kind, draftKey) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushDraft() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())

    var showUnsaved by remember { mutableStateOf(false) }
    var closeAfterSave by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showLossyWarning by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showInsertTable by remember { mutableStateOf(false) }

    val untitled = stringResource(R.string.untitled)
    val savedMsg = stringResource(R.string.saved)
    val saveFailedMsg = stringResource(R.string.error_save_failed)

    fun afterSave(error: Throwable?) {
        if (error == null) {
            toast(context, savedMsg)
            if (closeAfterSave) onClose()
        } else {
            toast(context, saveFailedMsg)
        }
        closeAfterSave = false
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(vm.mimeType)) { target ->
        if (target != null) vm.saveTo(target, ::afterSave) else { closeAfterSave = false }
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) vm.exportPdf(target, vm.fileName ?: untitled) { e ->
            toast(context, context.getString(if (e == null) R.string.exported else R.string.error_save_failed))
        }
    }

    fun requestSave() {
        when {
            vm.uri == null || vm.isLegacy -> saveAs.launch(vm.suggestedName(untitled))
            vm.lossyImport -> showLossyWarning = true
            else -> vm.save(::afterSave)
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) vm.insertImage(picked) { e -> if (e != null) toast(context, context.getString(R.string.image_unsupported)) }
    }

    BackHandler(enabled = vm.dirty) { showUnsaved = true }

    when (val state = vm.loadState) {
        DocumentEditorViewModel.LoadState.Loading -> { LoadingScreen(onBack = onClose, title = vm.fileName.orEmpty()); return }
        is DocumentEditorViewModel.LoadState.Failed -> {
            ErrorScreen(stringResource(R.string.error_cannot_read_document), onBack = onClose, detail = state.message)
            return
        }
        DocumentEditorViewModel.LoadState.Ready -> {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton { if (vm.dirty) showUnsaved = true else onClose() } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            vm.fileName ?: untitled,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (vm.dirty) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = ::requestSave, enabled = !vm.saving && (vm.dirty || vm.uri == null || vm.isLegacy)) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.action_save))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_find_replace)) }, onClick = { menuOpen = false; showFind = true })
                            if (vm.supportsBlockEditing) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.insert_table)) },
                                    onClick = { menuOpen = false; showInsertTable = true },
                                    modifier = Modifier.testTag("menu_insert_table"),
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.insert_image)) },
                                    onClick = { menuOpen = false; pickImage.launch("image/*") },
                                )
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_save_as)) }, onClick = { menuOpen = false; saveAs.launch(vm.suggestedName(untitled)) })
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
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_document_info)) }, onClick = { menuOpen = false; showStats = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (vm.saving) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            vm.recoverableDraft?.let { d -> DraftBanner(d.savedAt, onRestore = vm::restoreDraft, onDiscard = vm::discardDraft) }
            if (vm.isLegacy) InfoBanner(stringResource(R.string.legacy_banner)) else if (vm.lossyImport) LossyBanner()
            if (showFind) FindBar(vm, onClose = { showFind = false })

            val baseSize = (16f * settings.editorTextScale).sp
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val accent = MaterialTheme.colorScheme.primary
            val chipBg = MaterialTheme.colorScheme.primaryContainer
            val labeler = embedLabeler()
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BoxWithConstraints(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                        val contentWidth = maxWidth - 44.dp
                        val sizes = remember(vm.embeds, contentWidth, vm.supportsRichFormatting) {
                            if (vm.supportsRichFormatting) blockSizes(vm.embeds, contentWidth) else emptyMap()
                        }
                        val heights = remember(sizes, density) { with(density) { sizes.mapValues { it.value.toSp() } } }
                        val pageColor = MaterialTheme.colorScheme.surface
                        val transformation = remember(vm.model, baseSize, muted, accent, vm.embeds, chipBg, heights, vm.formatting, pageColor) {
                            RichTextTransformation(vm.model, baseSize, muted, accent, vm.embeds, labeler, chipBg, heights, vm.formatting, pageColor)
                        }
                        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                        BasicTextField(
                            value = vm.value,
                            onValueChange = vm::onValueChange,
                            visualTransformation = transformation,
                            onTextLayout = { layout = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = baseSize,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().heightIn(min = viewport).testTag("document_text"),
                            decorationBox = { inner ->
                                Box(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
                                    if (vm.value.text.isEmpty()) {
                                        Text(
                                            stringResource(R.string.document_placeholder),
                                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = baseSize),
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        val l = layout
                        if (l != null) {
                            var tables = 0
                            var images = 0
                            transformation.blockAnchors.forEach { a ->
                                if (a.transformedOffset > l.layoutInput.text.length) return@forEach
                                val embed = vm.embeds[a.embedId] ?: return@forEach
                                val top = with(density) { l.getLineTop(l.getLineForOffset(a.transformedOffset)).toDp() }
                                val place = Modifier.offset(x = 22.dp, y = 18.dp + top)
                                key(a.offset, a.embedId) {
                                    if (embed.kind == EmbedKind.TABLE) {
                                        TableBlock(embed, contentWidth, onClick = { if (vm.supportsBlockEditing) editingBlock = a.offset to a.embedId }, modifier = place.testTag("doc_table_${tables++}"))
                                    } else {
                                        ImageBlock(
                                            rememberEmbedBitmap(vm, a.embedId),
                                            imageSize(embed, contentWidth),
                                            onClick = { editingBlock = a.offset to a.embedId },
                                            modifier = place.testTag("doc_image_${images++}"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            EmbedBar(vm) { o, id -> editingBlock = o to id }
            FormattingToolbar(vm, showFormatting = vm.supportsRichFormatting || vm.supportsMarkdownFormatting)
        }
    }

    editingBlock?.let { (offset, id) ->
        val embed = vm.embeds[id]
        if (embed == null || vm.model.styles.getOrNull(offset)?.embedId != id) {
            editingBlock = null
        } else if (embed.kind == EmbedKind.TABLE) {
            TableEditorDialog(
                embed,
                onSave = { vm.replaceEmbed(offset, it); editingBlock = null },
                onDelete = { vm.removeEmbed(offset); editingBlock = null },
                onDismiss = { editingBlock = null },
            )
        } else {
            ImageDialog(
                rememberEmbedBitmap(vm, id),
                onDelete = { vm.removeEmbed(offset); editingBlock = null },
                onDismiss = { editingBlock = null },
            )
        }
    }
    if (showInsertTable) {
        InsertTableDialog(onInsert = { r, c -> showInsertTable = false; vm.insertTable(r, c) }, onDismiss = { showInsertTable = false })
    }
    if (showUnsaved) {
        UnsavedChangesDialog(
            onSave = { showUnsaved = false; closeAfterSave = true; requestSave() },
            onDiscard = { showUnsaved = false; vm.discardDraft(); onClose() },
            onDismiss = { showUnsaved = false },
        )
    }
    if (showLossyWarning) {
        AlertDialog(
            onDismissRequest = { showLossyWarning = false; closeAfterSave = false },
            title = { Text(stringResource(R.string.lossy_save_title)) },
            text = { Text(stringResource(R.string.lossy_save_message)) },
            confirmButton = {
                TextButton(onClick = { showLossyWarning = false; saveAs.launch(vm.suggestedName(untitled)) }) {
                    Text(stringResource(R.string.action_save_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLossyWarning = false; vm.save(::afterSave) }) {
                    Text(stringResource(R.string.action_overwrite))
                }
            },
        )
    }
    if (showStats) {
        val (words, chars, paras) = vm.stats
        AlertDialog(
            onDismissRequest = { showStats = false },
            title = { Text(stringResource(R.string.action_document_info)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow(stringResource(R.string.stat_words), words)
                    StatRow(stringResource(R.string.stat_characters), chars)
                    StatRow(stringResource(R.string.stat_paragraphs), paras)
                }
            },
            confirmButton = { TextButton(onClick = { showStats = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.toString(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LossyBanner() {
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.lossy_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { visible = false }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FindBar(vm: DocumentEditorViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    val hits = remember(query, vm.model) { vm.model.findAll(query) }

    fun go(delta: Int) {
        if (hits.isEmpty()) return
        index = ((index + delta) % hits.size + hits.size) % hits.size
        vm.select(hits[index])
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; index = 0 },
                    placeholder = { Text(stringResource(R.string.find_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    suffix = {
                        if (query.isNotEmpty()) {
                            Text(
                                if (hits.isEmpty()) "0" else "${index + 1}/${hits.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                IconButton(onClick = { go(-1) }, enabled = hits.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.action_previous)) }
                IconButton(onClick = { go(1) }, enabled = hits.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.action_next)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
            }
            if (showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        placeholder = { Text(stringResource(R.string.replace_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (hits.isNotEmpty()) {
                                vm.select(hits[index.coerceIn(0, hits.lastIndex)])
                                vm.replaceSelection(replacement)
                            }
                        },
                        enabled = hits.isNotEmpty(),
                    ) { Text(stringResource(R.string.action_replace)) }
                    TextButton(
                        onClick = {
                            val n = vm.replaceAll(query, replacement)
                            toast(context, context.resources.getQuantityString(R.plurals.replaced_count, n, n))
                        },
                        enabled = hits.isNotEmpty(),
                    ) { Text(stringResource(R.string.action_replace_all)) }
                }
            } else {
                TextButton(onClick = { showReplace = true }) { Text(stringResource(R.string.action_replace)) }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FormattingToolbar(vm: DocumentEditorViewModel, showFormatting: Boolean) {
    val style = vm.caretStyle
    val para = vm.caretParagraph
    val rich = vm.supportsRichFormatting
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.navigationBarsPadding()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
            // Undo/redo stay pinned at the start; the formatting tools scroll beside them.
            UndoRedoButtons(vm.canUndo, vm.canRedo, vm::undo, vm::redo, modifier = Modifier.padding(start = 4.dp))
            if (showFormatting) {
            Divider()
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ParagraphStyleMenu(current = para?.style ?: ParagraphStyle.NORMAL, onPick = vm::setParagraphStyle, rich = rich)
                Divider()
                ToggleTool(Icons.Default.FormatBold, R.string.format_bold, style.bold) { vm.toggleInline(InlineAttr.BOLD) }
                ToggleTool(Icons.Default.FormatItalic, R.string.format_italic, style.italic) { vm.toggleInline(InlineAttr.ITALIC) }
                if (rich) ToggleTool(Icons.Default.FormatUnderlined, R.string.format_underline, style.underline) { vm.toggleInline(InlineAttr.UNDERLINE) }
                ToggleTool(Icons.Default.FormatStrikethrough, R.string.format_strike, style.strike) { vm.toggleInline(InlineAttr.STRIKE) }
                if (rich) FontSizeMenu(current = style.sizePt, onPick = vm::setFontSize)
                Divider()
                ToggleTool(Icons.AutoMirrored.Filled.FormatListBulleted, R.string.format_bullets, para?.list == ListType.BULLET) { vm.toggleList(ListType.BULLET) }
                ToggleTool(Icons.Default.FormatListNumbered, R.string.format_numbered, para?.list == ListType.NUMBERED) { vm.toggleList(ListType.NUMBERED) }
                ToggleTool(Icons.Default.FormatQuote, R.string.format_quote, para?.style == ParagraphStyle.QUOTE) { vm.setParagraphStyle(ParagraphStyle.QUOTE) }
                if (rich) {
                    Divider()
                    ToggleTool(Icons.AutoMirrored.Filled.FormatAlignLeft, R.string.align_left, para?.alignment == DocAlignment.START) { vm.setAlignment(DocAlignment.START) }
                    ToggleTool(Icons.Default.FormatAlignCenter, R.string.align_center, para?.alignment == DocAlignment.CENTER) { vm.setAlignment(DocAlignment.CENTER) }
                    ToggleTool(Icons.AutoMirrored.Filled.FormatAlignRight, R.string.align_right, para?.alignment == DocAlignment.END) { vm.setAlignment(DocAlignment.END) }
                    ToggleTool(Icons.Default.FormatAlignJustify, R.string.align_justify, para?.alignment == DocAlignment.JUSTIFY) { vm.setAlignment(DocAlignment.JUSTIFY) }
                }
                Divider()
                IconButton(onClick = vm::clearFormatting) {
                    Icon(Icons.Default.FormatClear, contentDescription = stringResource(R.string.format_clear))
                }
            }
            }
            }
        }
    }
}

@Composable
private fun Divider() {
    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ToggleTool(icon: ImageVector, label: Int, checked: Boolean, onClick: () -> Unit) {
    IconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        Box(
            Modifier
                .size(36.dp)
                .background(
                    if (checked) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = stringResource(label),
                tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ParagraphStyleMenu(current: ParagraphStyle, onPick: (ParagraphStyle) -> Unit, rich: Boolean) {
    var open by remember { mutableStateOf(false) }
    val options = buildList {
        add(ParagraphStyle.NORMAL to R.string.style_normal)
        if (rich) add(ParagraphStyle.TITLE to R.string.style_title)
        add(ParagraphStyle.HEADING1 to R.string.style_heading1)
        add(ParagraphStyle.HEADING2 to R.string.style_heading2)
        add(ParagraphStyle.HEADING3 to R.string.style_heading3)
    }
    val label = options.firstOrNull { it.first == current }?.second ?: R.string.style_normal
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 72.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (style, res) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(res),
                            style = when (style) {
                                ParagraphStyle.TITLE -> MaterialTheme.typography.headlineSmall
                                ParagraphStyle.HEADING1 -> MaterialTheme.typography.titleLarge
                                ParagraphStyle.HEADING2 -> MaterialTheme.typography.titleMedium
                                ParagraphStyle.HEADING3 -> MaterialTheme.typography.titleSmall
                                else -> MaterialTheme.typography.bodyLarge
                            },
                        )
                    },
                    onClick = {
                        open = false
                        // Picking the current style again would toggle it off; only apply real changes.
                        if (style != current) onPick(if (style == ParagraphStyle.NORMAL) current else style)
                    },
                )
            }
        }
    }
}

@Composable
private fun FontSizeMenu(current: Float?, onPick: (Float?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.FormatSize, contentDescription = stringResource(R.string.format_size))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.format_size_default)) },
                onClick = { open = false; onPick(null) },
                trailingIcon = { if (current == null) Text("✓") },
            )
            listOf(9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 36f).forEach { s ->
                DropdownMenuItem(
                    text = { Text("${s.toInt()} pt") },
                    onClick = { open = false; onPick(s) },
                    trailingIcon = { if (current == s) Text("✓") },
                )
            }
        }
    }
}

/** Builds the visible text for an embed chip. */
@Composable
private fun embedLabeler(): (Embed) -> String {
    val image = stringResource(R.string.embed_image)
    val table = stringResource(R.string.embed_table)
    val equation = stringResource(R.string.embed_equation)
    val obj = stringResource(R.string.embed_object)
    return remember(image, table, equation, obj) {
        { e ->
            when {
                e.label == DocxReader.MARKER_LABEL -> ""
                e.kind == EmbedKind.LINK -> e.label.ifEmpty { "\u2197" }
                e.kind == EmbedKind.IMAGE -> "\u2009\u25A3 $image\u2009"
                e.kind == EmbedKind.TABLE -> "\u2009\u25A6 $table ${e.label}\u2009"
                e.kind == EmbedKind.EQUATION -> "\u2009\u2211 ${e.label.take(24).ifEmpty { equation }}\u2009"
                e.kind == EmbedKind.FIELD -> e.label.ifEmpty { "\u22EF" }
                else -> "\u2009\u25C7 ${e.label.take(24).ifEmpty { obj }}\u2009"
            }
        }
    }
}

/** When the caret sits next to a table that is not drawn in place, offers to edit it. */
@Composable
private fun EmbedBar(vm: DocumentEditorViewModel, onEdit: (offset: Int, id: Int) -> Unit) {
    if (!vm.supportsBlockEditing) return
    val sel = vm.value.selection
    val hit = listOf(sel.start - 1, sel.start)
        .firstOrNull { o -> vm.model.styles.getOrNull(o)?.embedId?.let { vm.embeds[it]?.kind == EmbedKind.TABLE } == true } ?: return
    val id = vm.model.styles[hit].embedId ?: return
    val table = vm.embeds[id] ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${stringResource(R.string.embed_table)} ${table.label}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onEdit(hit, id) }) { Text(stringResource(R.string.table_edit)) }
        }
    }
}
