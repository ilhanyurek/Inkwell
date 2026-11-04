package com.office.app.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.tools.PdfOps
import com.office.app.ui.common.BackButton
import com.office.app.ui.common.formatSize
import com.office.app.ui.common.shareUri
import com.office.app.ui.common.toast
import com.office.core.util.PageRanges
import com.office.core.util.TextDiff
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A file the user picked, with its display name and size. */
data class Picked(val uri: Uri, val name: String, val size: Long?)

/** What a run produced, waiting to be saved. */
private class Output(val files: List<File>, val mime: String, val note: String? = null)

private sealed interface Phase {
    data object Setup : Phase
    data object Running : Phase
    data class Saved(val uri: Uri?, val mime: String, val message: String) : Phase
    data class Diff(val lines: List<TextDiff.Line>) : Phase
}

private const val MIME_PDF = "application/pdf"
private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
private const val MIME_TXT = "text/plain"

private fun ToolInput.mimes(): Array<String> = when (this) {
    ToolInput.PDF, ToolInput.PDFS, ToolInput.TWO_PDFS -> arrayOf(MIME_PDF)
    ToolInput.IMAGES, ToolInput.CAMERA -> arrayOf("image/*")
    ToolInput.WORD -> arrayOf(MIME_DOCX)
    ToolInput.SHEET -> arrayOf(MIME_XLSX, "text/csv", "text/comma-separated-values", "text/plain")
    ToolInput.SLIDES -> arrayOf(MIME_PPTX)
    ToolInput.TEXT -> arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream")
}

/** Option values for every tool; only the ones the current tool shows are used. */
private class Options {
    var pages by mutableStateOf("")
    var splitEvery by mutableStateOf(false)
    var everyN by mutableStateOf("1")
    var degrees by mutableIntStateOf(90)
    var compressLevel by mutableIntStateOf(1)
    var pageSize by mutableStateOf(PdfOps.PageSize.A4)
    var margin by mutableIntStateOf(1)
    var documentFilter by mutableStateOf(false)
    var imageFormat by mutableStateOf(PdfOps.ImageFormat.JPG)
    var dpi by mutableIntStateOf(150)
    var text by mutableStateOf("")
    var textSize by mutableFloatStateOf(56f)
    var opacity by mutableFloatStateOf(0.25f)
    var angle by mutableIntStateOf(45)
    var color by mutableIntStateOf(0xC62828)
    var position by mutableStateOf(PdfOps.Position.BOTTOM_CENTER)
    var numberFormat by mutableStateOf(PdfOps.NumberFormat.OF_TOTAL)
    var start by mutableStateOf("1")
    var password by mutableStateOf("")
    var password2 by mutableStateOf("")
    var allowPrint by mutableStateOf(true)
    var allowCopy by mutableStateOf(false)
    var allowEdit by mutableStateOf(false)
    val cropMm = mutableStateListOf("10", "10", "10", "10")
    var meta by mutableStateOf(PdfOps.Metadata())
    var metaLoaded by mutableStateOf(false)
    /** Organize: current page list; thumbnails are keyed by source page. */
    val organize = mutableStateListOf<PdfOps.PageSpec>()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolScreen(
    container: AppContainer,
    tool: ToolId,
    onBack: () -> Unit,
    onOpen: (Uri) -> Unit,
    onOpenInViewer: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inputs = remember { mutableStateListOf<Picked>() }
    val opts = remember { Options() }
    var phase by remember { mutableStateOf<Phase>(Phase.Setup) }
    var pageCount by remember { mutableIntStateOf(0) }
    var thumbs by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var pending by remember { mutableStateOf<Output?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var inputPassword by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val workDir = remember { File(context.cacheDir, "tools/${System.currentTimeMillis()}").apply { mkdirs() } }
    val defaultWatermark = stringResource(R.string.tool_watermark_default)
    LaunchedEffect(Unit) {
        if (opts.text.isEmpty()) opts.text = defaultWatermark
        if (tool == ToolId.SCAN) opts.documentFilter = true
    }

    suspend fun pick(uris: List<Uri>, replace: Boolean) {
        val picked = uris.map { u ->
            runCatching { container.files.info(u) }.map { Picked(u, it.name, it.size) }.getOrElse { Picked(u, u.lastPathSegment ?: "file", null) }
        }
        if (replace) inputs.clear()
        inputs.addAll(picked)
    }

    // Local copy of an input, so PdfBox and PdfRenderer can work on real files.
    suspend fun local(p: Picked, index: Int): File = withContext(Dispatchers.IO) {
        val ext = p.name.substringAfterLast('.', "bin").take(8)
        File(workDir, "in${index}_${p.uri.toString().hashCode().toUInt()}.$ext").also { f ->
            if (!f.exists()) context.contentResolver.openInputStream(p.uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        }
    }

    // After choosing a single PDF: page count, thumbnails for Organize, properties for Metadata.
    LaunchedEffect(inputs.firstOrNull(), inputPassword) {
        val first = inputs.firstOrNull() ?: return@LaunchedEffect
        if (tool.input != ToolInput.PDF) return@LaunchedEffect
        val file = runCatching { local(first, 0) }.getOrNull() ?: return@LaunchedEffect
        val count = runCatching { PdfOps.pageCount(file, inputPassword) }
        if (count.exceptionOrNull() is InvalidPasswordException && tool != ToolId.UNLOCK) { askPassword = true; return@LaunchedEffect }
        pageCount = count.getOrDefault(0)
        if (tool == ToolId.ORGANIZE && opts.organize.isEmpty()) {
            opts.organize.addAll((0 until pageCount).map { PdfOps.PageSpec(it) })
            val map = HashMap<Int, Bitmap>()
            runCatching {
                PdfOps.renderPages(file, null, 320) { i, bmp ->
                    map[i] = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    if (i % 6 == 5) thumbs = HashMap(map)
                }
            }
            thumbs = map
        }
        if (tool == ToolId.METADATA && !opts.metaLoaded) {
            runCatching { PdfOps.readMetadata(file, inputPassword) }.onSuccess { opts.meta = it; opts.metaLoaded = true }
        }
    }

    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val vt = tool.viewerTool
            if (vt != null) { onOpenInViewer(uri, vt); return@rememberLauncherForActivityResult }
            opts.organize.clear(); thumbs = emptyMap(); opts.metaLoaded = false; inputPassword = null; pageCount = 0
            scope.launch { pick(listOf(uri), replace = true) }
        }
    }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { pick(uris, replace = false) }
    }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = cameraTarget
        if (ok && target != null) scope.launch { inputs.add(Picked(target, "scan-${inputs.size + 1}.jpg", null)) }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        cameraTarget = uri
        runCatching { camera.launch(uri) }.onFailure { toast(context, context.getString(R.string.error_no_app)) }
    }

    fun finish(saved: Uri?, mime: String, message: String) {
        pending = null
        phase = Phase.Saved(saved, mime, message)
    }

    // Saving: one file → "Save as" dialog; several → a folder.
    fun saveOne(target: Uri?) {
        val out = pending ?: return
        if (target == null) { phase = Phase.Setup; pending = null; return }
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target, "wt")!!.use { o -> out.files[0].inputStream().use { it.copyTo(o) } }
                }
            }
            if (r.isSuccess) {
                val name = runCatching { container.files.info(target).name }.getOrDefault(out.files[0].name)
                finish(target, out.mime, out.note ?: context.getString(R.string.tool_saved_to, name))
            } else {
                error = context.getString(R.string.error_save_failed); phase = Phase.Setup; pending = null
            }
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_PDF)) { saveOne(it) }
    val saveDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_DOCX)) { saveOne(it) }
    val savePptx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_PPTX)) { saveOne(it) }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_TXT)) { saveOne(it) }
    val saveFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val out = pending ?: return@rememberLauncherForActivityResult
        if (tree == null) { phase = Phase.Setup; pending = null; return@rememberLauncherForActivityResult }
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = DocumentFile.fromTreeUri(context, tree) ?: error("No folder")
                    for (f in out.files) {
                        val mime = when (f.extension.lowercase()) {
                            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "pdf" -> MIME_PDF; else -> "application/octet-stream"
                        }
                        val doc = dir.createFile(mime, f.name) ?: error("Cannot create ${f.name}")
                        context.contentResolver.openOutputStream(doc.uri, "wt")!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                    }
                }
            }
            if (r.isSuccess) finish(null, "", out.note ?: context.getString(R.string.tool_files_saved, out.files.size))
            else { error = context.getString(R.string.error_save_failed); phase = Phase.Setup; pending = null }
        }
    }

    fun requestSave(out: Output, suggested: String) {
        pending = out
        if (out.files.size != 1) { saveFolder.launch(null); return }
        when (out.mime) {
            MIME_DOCX -> saveDocx.launch(suggested)
            MIME_PPTX -> savePptx.launch(suggested)
            MIME_TXT -> saveTxt.launch(suggested)
            else -> savePdf.launch(suggested)
        }
    }

    fun run() {
        error = null
        phase = Phase.Running
        scope.launch {
            val result = runCatching { execute(context, tool, inputs.toList(), opts, workDir, inputPassword, pageCount) { p, i -> local(p, i) } }
            result.onSuccess { r ->
                when (r) {
                    is RunResult.Files -> requestSave(r.output, r.suggestedName)
                    is RunResult.Compare -> phase = Phase.Diff(r.lines)
                }
            }.onFailure { e ->
                phase = Phase.Setup
                if (e is InvalidPasswordException && tool != ToolId.UNLOCK) askPassword = true
                else error = (e as? ToolError)?.let { context.getString(it.message0, *it.args) }
                    ?: if (e is InvalidPasswordException) context.getString(R.string.pdf_password_wrong)
                    else context.getString(R.string.tool_error, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(tool.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        when (val ph = phase) {
            is Phase.Diff -> DiffView(ph.lines, Modifier.padding(padding))
            is Phase.Saved -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(ph.message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("tool_result"))
                Spacer(Modifier.height(20.dp))
                if (ph.uri != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onOpen(ph.uri) }) { Text(stringResource(R.string.tool_open_result)) }
                        OutlinedButton(onClick = { shareUri(context, ph.uri, ph.mime, context.getString(R.string.action_share)) }) {
                            Text(stringResource(R.string.tool_share_result))
                        }
                    }
                }
                TextButton(onClick = { inputs.clear(); opts.organize.clear(); thumbs = emptyMap(); phase = Phase.Setup }) {
                    Text(stringResource(R.string.tool_again))
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().then(
                        if (tool == ToolId.ORGANIZE && inputs.isNotEmpty()) Modifier else Modifier.verticalScroll(rememberScrollState()),
                    ).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(tool.desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InputSection(
                        tool, inputs, pageCount,
                        onPickSingle = { single.launch(tool.input.mimes()) },
                        onPickMore = { multiple.launch(tool.input.mimes()) },
                        onCamera = ::launchCamera,
                    )
                    if (inputs.isNotEmpty() || tool.input == ToolInput.CAMERA) {
                        ToolOptions(tool, opts, pageCount, thumbs)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.navigationBarsPadding()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (phase == Phase.Running) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.tool_working), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Button(
                                onClick = ::run,
                                enabled = ready(tool, inputs.size, opts, pageCount),
                                modifier = Modifier.fillMaxWidth().testTag("tool_run"),
                            ) { Text(stringResource(R.string.tool_run)) }
                        }
                    }
                }
            }
        }
    }

    if (askPassword) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text(stringResource(R.string.pdf_password_title)) },
            text = {
                OutlinedTextField(
                    pw, { pw = it },
                    label = { Text(stringResource(R.string.pdf_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            },
            confirmButton = { TextButton(onClick = { askPassword = false; inputPassword = pw }) { Text(stringResource(R.string.pdf_unlock)) } },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun ready(tool: ToolId, count: Int, o: Options, pages: Int): Boolean = when (tool.input) {
    ToolInput.PDFS -> count >= 2
    ToolInput.TWO_PDFS -> count == 2
    ToolInput.IMAGES, ToolInput.CAMERA -> count >= 1
    else -> count == 1
} && when (tool) {
    ToolId.SPLIT -> if (o.splitEvery) (o.everyN.toIntOrNull() ?: 0) >= 1 else PageRanges.parse(o.pages, pages) != null
    ToolId.DELETE_PAGES -> PageRanges.parse(o.pages, pages).let { it != null && it.size < pages }
    ToolId.PROTECT -> o.password.isNotEmpty() && o.password == o.password2
    ToolId.UNLOCK -> o.password.isNotEmpty()
    ToolId.WATERMARK -> o.text.isNotBlank()
    ToolId.PAGE_NUMBERS -> o.start.toIntOrNull() != null
    ToolId.ORGANIZE -> o.organize.isNotEmpty()
    else -> true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputSection(
    tool: ToolId,
    inputs: MutableList<Picked>,
    pageCount: Int,
    onPickSingle: () -> Unit,
    onPickMore: () -> Unit,
    onCamera: () -> Unit,
) {
    val context = LocalContext.current
    val many = tool.input in setOf(ToolInput.PDFS, ToolInput.IMAGES, ToolInput.CAMERA, ToolInput.TWO_PDFS)
    if (inputs.isEmpty() && tool.input != ToolInput.CAMERA) {
        Button(onClick = if (many) onPickMore else onPickSingle, modifier = Modifier.fillMaxWidth().testTag("tool_pick")) {
            Text(stringResource(if (tool.input == ToolInput.IMAGES) R.string.tool_choose_images else if (many) R.string.tool_choose_files else R.string.tool_choose_file))
        }
        return
    }
    if (tool.input == ToolInput.CAMERA && inputs.isEmpty()) {
        Text(stringResource(R.string.tool_scan_empty), style = MaterialTheme.typography.bodyMedium)
    }
    inputs.forEachIndexed { i, p ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (tool == ToolId.COMPARE) stringResource(if (i == 0) R.string.tool_compare_first else R.string.tool_compare_second) + " · " + p.name else p.name,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val detail = listOfNotNull(
                        p.size?.let { formatSize(context, it) },
                        if (!many && pageCount > 0) stringResource(R.string.tool_page_count, pageCount) else null,
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (many) {
                    IconButton(onClick = { if (i > 0) inputs.add(i - 1, inputs.removeAt(i)) }, enabled = i > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.tool_move_up))
                    }
                    IconButton(onClick = { if (i < inputs.size - 1) inputs.add(i + 1, inputs.removeAt(i)) }, enabled = i < inputs.size - 1) {
                        Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.tool_move_down))
                    }
                    IconButton(onClick = { inputs.removeAt(i) }) { Icon(Icons.Default.Close, stringResource(R.string.tool_remove)) }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            tool.input == ToolInput.CAMERA -> {
                Button(onClick = onCamera, modifier = Modifier.testTag("tool_camera")) {
                    Icon(Icons.Outlined.PhotoCamera, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tool_scan_add))
                }
                OutlinedButton(onClick = onPickMore) { Text(stringResource(R.string.tool_choose_images)) }
            }
            tool.input == ToolInput.TWO_PDFS -> if (inputs.size < 2) OutlinedButton(onClick = onPickMore) { Text(stringResource(R.string.tool_add_more)) }
            many -> OutlinedButton(onClick = onPickMore) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tool_add_more))
            }
            else -> OutlinedButton(onClick = onPickSingle) { Text(stringResource(R.string.tool_change_file)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(label: Int, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (v, text) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(text) }) }
        }
    }
}

@Composable
private fun PagesField(o: Options, pageCount: Int, allowAll: Boolean = false) {
    val valid = o.pages.isBlank() && allowAll || PageRanges.parse(o.pages, pageCount) != null
    OutlinedTextField(
        o.pages, { o.pages = it },
        label = { Text(stringResource(if (allowAll) R.string.tool_pages_optional else R.string.tool_pages)) },
        placeholder = { Text(stringResource(R.string.tool_pages_hint)) },
        isError = !valid && o.pages.isNotBlank(),
        supportingText = { if (!valid && o.pages.isNotBlank() && pageCount > 0) Text(stringResource(R.string.tool_pages_invalid, pageCount)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("tool_pages"),
    )
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
private fun CheckRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SliderRow(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(display, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value, onChange, valueRange = range)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolOptions(tool: ToolId, o: Options, pageCount: Int, thumbs: Map<Int, Bitmap>) {
    when (tool) {
        ToolId.MERGE -> Text(stringResource(R.string.tool_merge_order), style = MaterialTheme.typography.bodySmall)
        ToolId.SPLIT -> {
            ChoiceRow(
                R.string.tool_split_mode,
                listOf(false to stringResource(R.string.tool_split_mode_extract), true to stringResource(R.string.tool_split_mode_every)),
                o.splitEvery,
            ) { o.splitEvery = it }
            if (o.splitEvery) {
                OutlinedTextField(
                    o.everyN, { v -> o.everyN = v.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.tool_split_every_n)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else PagesField(o, pageCount)
        }
        ToolId.ROTATE -> {
            ChoiceRow(R.string.tool_rotate_by, listOf(90 to "90°", 180 to "180°", 270 to "270°"), o.degrees) { o.degrees = it }
            PagesField(o, pageCount, allowAll = true)
        }
        ToolId.DELETE_PAGES -> PagesField(o, pageCount)
        ToolId.ORGANIZE -> OrganizeGrid(o, thumbs)
        ToolId.COMPRESS -> ChoiceRow(
            R.string.tool_compress_level,
            listOf(0 to stringResource(R.string.tool_compress_low), 1 to stringResource(R.string.tool_compress_medium), 2 to stringResource(R.string.tool_compress_high)),
            o.compressLevel,
        ) { o.compressLevel = it }
        ToolId.IMAGES_TO_PDF, ToolId.SCAN -> {
            ChoiceRow(
                R.string.tool_page_size,
                listOf(PdfOps.PageSize.A4 to "A4", PdfOps.PageSize.LETTER to "Letter", PdfOps.PageSize.FIT to stringResource(R.string.tool_page_size_fit)),
                o.pageSize,
            ) { o.pageSize = it }
            ChoiceRow(
                R.string.tool_margin,
                listOf(0 to stringResource(R.string.tool_margin_none), 1 to stringResource(R.string.tool_margin_small), 2 to stringResource(R.string.tool_margin_large)),
                o.margin,
            ) { o.margin = it }
            SwitchRow(R.string.tool_document_filter, o.documentFilter) { o.documentFilter = it }
        }
        ToolId.PDF_TO_IMAGES -> {
            ChoiceRow(R.string.tool_image_format, listOf(PdfOps.ImageFormat.JPG to "JPG", PdfOps.ImageFormat.PNG to "PNG"), o.imageFormat) { o.imageFormat = it }
            ChoiceRow(
                R.string.tool_quality,
                listOf(72 to stringResource(R.string.tool_dpi_screen), 150 to stringResource(R.string.tool_dpi_standard), 300 to stringResource(R.string.tool_dpi_high)),
                o.dpi,
            ) { o.dpi = it }
        }
        ToolId.PDF_TO_WORD -> Text(stringResource(R.string.tool_pdf_to_word_note), style = MaterialTheme.typography.bodySmall)
        ToolId.WATERMARK -> {
            OutlinedTextField(o.text, { o.text = it }, label = { Text(stringResource(R.string.tool_watermark_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SliderRow(R.string.tool_size, o.textSize, 16f..120f, "${o.textSize.toInt()} pt") { o.textSize = it }
            SliderRow(R.string.tool_opacity, o.opacity, 0.05f..1f, "${(o.opacity * 100).toInt()}%") { o.opacity = it }
            ChoiceRow(R.string.tool_angle, listOf(0 to "0°", 45 to "45°", 90 to "90°"), o.angle) { o.angle = it }
            Column {
                Text(stringResource(R.string.tool_color), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(0xC62828, 0x1B1C1E, 0x2F5BC4, 0x2E7D32, 0x757575).forEach { c ->
                        val selected = o.color == c
                        Box(
                            Modifier.size(32.dp)
                                .background(Color(0xFF000000 or c.toLong()), CircleShape)
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { o.color = c },
                        )
                    }
                }
            }
        }
        ToolId.PAGE_NUMBERS -> {
            ChoiceRow(
                R.string.tool_position,
                listOf(
                    PdfOps.Position.TOP_LEFT to stringResource(R.string.tool_pos_top_left),
                    PdfOps.Position.TOP_CENTER to stringResource(R.string.tool_pos_top_center),
                    PdfOps.Position.TOP_RIGHT to stringResource(R.string.tool_pos_top_right),
                    PdfOps.Position.BOTTOM_LEFT to stringResource(R.string.tool_pos_bottom_left),
                    PdfOps.Position.BOTTOM_CENTER to stringResource(R.string.tool_pos_bottom_center),
                    PdfOps.Position.BOTTOM_RIGHT to stringResource(R.string.tool_pos_bottom_right),
                ),
                o.position,
            ) { o.position = it }
            val word = stringResource(R.string.tool_number_page_word)
            ChoiceRow(
                R.string.tool_number_format,
                listOf(PdfOps.NumberFormat.PLAIN to "1", PdfOps.NumberFormat.OF_TOTAL to "1 / N", PdfOps.NumberFormat.PAGE_WORD to "$word 1"),
                o.numberFormat,
            ) { o.numberFormat = it }
            OutlinedTextField(
                o.start, { v -> o.start = v.filter { it.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.tool_start_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ToolId.PROTECT -> {
            PasswordField(R.string.tool_password, o.password) { o.password = it }
            PasswordField(R.string.tool_password_confirm, o.password2, isError = o.password2.isNotEmpty() && o.password != o.password2) { o.password2 = it }
            if (o.password2.isNotEmpty() && o.password != o.password2) {
                Text(stringResource(R.string.tool_password_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            CheckRow(R.string.tool_allow_print, o.allowPrint) { o.allowPrint = it }
            CheckRow(R.string.tool_allow_copy, o.allowCopy) { o.allowCopy = it }
            CheckRow(R.string.tool_allow_edit, o.allowEdit) { o.allowEdit = it }
        }
        ToolId.UNLOCK -> PasswordField(R.string.tool_password, o.password) { o.password = it }
        ToolId.CROP -> {
            Text(stringResource(R.string.tool_crop_margins), style = MaterialTheme.typography.labelLarge)
            val labels = listOf(R.string.tool_crop_left, R.string.tool_crop_top, R.string.tool_crop_right, R.string.tool_crop_bottom)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, l ->
                    OutlinedTextField(
                        o.cropMm[i], { v -> o.cropMm[i] = v.filter { it.isDigit() || it == '.' }.take(5) },
                        label = { Text(stringResource(l)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(80.dp),
                    )
                }
            }
            PagesField(o, pageCount, allowAll = true)
        }
        ToolId.FLATTEN -> Text(stringResource(R.string.tool_flatten_note), style = MaterialTheme.typography.bodySmall)
        ToolId.METADATA -> {
            val m = o.meta
            MetaField(R.string.tool_meta_title, m.title) { o.meta = o.meta.copy(title = it) }
            MetaField(R.string.tool_meta_author, m.author) { o.meta = o.meta.copy(author = it) }
            MetaField(R.string.tool_meta_subject, m.subject) { o.meta = o.meta.copy(subject = it) }
            MetaField(R.string.tool_meta_keywords, m.keywords) { o.meta = o.meta.copy(keywords = it) }
            MetaField(R.string.tool_meta_creator, m.creator) { o.meta = o.meta.copy(creator = it) }
            MetaField(R.string.tool_meta_producer, m.producer) { o.meta = o.meta.copy(producer = it) }
            TextButton(onClick = { o.meta = PdfOps.Metadata() }) { Text(stringResource(R.string.tool_meta_clear)) }
        }
        else -> {}
    }
}

@Composable
private fun PasswordField(label: Int, value: String, isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MetaField(label: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun OrganizeGrid(o: Options, thumbs: Map<Int, Bitmap>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxWidth().height(560.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        itemsIndexed(o.organize, key = { i, s -> "$i:${s.source}:${s.rotation}" }) { i, spec ->
            var menu by remember { mutableStateOf(false) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.72f).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = if (spec.isBlank) null else thumbs[spec.source]
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp).rotate(spec.rotation.toFloat()))
                    } else if (spec.isBlank) {
                        Box(Modifier.fillMaxSize().padding(6.dp).background(Color.White))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (spec.isBlank) stringResource(R.string.tool_blank_page) else "${spec.source + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        maxLines = 1,
                    )
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_move_up)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                                enabled = i > 0,
                                onClick = { menu = false; o.organize.add(i - 1, o.organize.removeAt(i)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_move_down)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                                enabled = i < o.organize.size - 1,
                                onClick = { menu = false; o.organize.add(i + 1, o.organize.removeAt(i)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_rotate_right)) },
                                leadingIcon = { Icon(Icons.Outlined.RotateRight, null) },
                                onClick = { menu = false; o.organize[i] = spec.copy(rotation = (spec.rotation + 90) % 360) },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.tool_duplicate)) }, onClick = { menu = false; o.organize.add(i + 1, spec) })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tool_insert_blank)) },
                                onClick = { menu = false; o.organize.add(i + 1, PdfOps.PageSpec(-1)) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                enabled = o.organize.size > 1,
                                onClick = { menu = false; o.organize.removeAt(i) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffView(lines: List<TextDiff.Line>, modifier: Modifier) {
    val added = lines.count { it.kind == TextDiff.Kind.ADDED }
    val removed = lines.count { it.kind == TextDiff.Kind.REMOVED }
    Column(modifier.fillMaxSize()) {
        Text(
            if (added + removed == 0) stringResource(R.string.tool_compare_same) else stringResource(R.string.tool_compare_summary, added, removed),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(16.dp).testTag("tool_result"),
        )
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(lines) { _, l ->
                val (bg, prefix) = when (l.kind) {
                    TextDiff.Kind.ADDED -> Color(0x332E7D32) to "+ "
                    TextDiff.Kind.REMOVED -> Color(0x33C62828) to "− "
                    TextDiff.Kind.SAME -> Color.Transparent to "  "
                }
                Text(
                    prefix + l.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** A failure with a translated message. */
private class ToolError(val message0: Int, vararg val args: Any) : Exception()

private sealed interface RunResult {
    class Files(val output: Output, val suggestedName: String) : RunResult
    class Compare(val lines: List<TextDiff.Line>) : RunResult
}

private suspend fun execute(
    context: Context,
    tool: ToolId,
    inputs: List<Picked>,
    o: Options,
    dir: File,
    password: String?,
    pageCount: Int,
    local: suspend (Picked, Int) -> File,
): RunResult {
    val first = inputs.firstOrNull()
    val base = first?.name?.substringBeforeLast('.')?.ifBlank { null } ?: "Inkwell"
    val outDir = File(dir, "out-${System.currentTimeMillis()}").apply { mkdirs() }
    fun out(name: String) = File(outDir, name)
    fun pdf(suffix: String) = out("$base-$suffix.pdf")
    fun pages(): List<Int> = PageRanges.parse(o.pages, pageCount) ?: throw ToolError(R.string.tool_pages_invalid, pageCount)
    fun optionalPages(): Set<Int>? = if (o.pages.isBlank()) null else pages().toSet()
    fun files(f: File, mime: String = MIME_PDF, note: String? = null) = RunResult.Files(Output(listOf(f), mime, note), f.name)

    return when (tool) {
        ToolId.MERGE -> {
            val files = inputs.mapIndexed { i, p -> local(p, i) }
            val f = out(context.getString(R.string.pdf_merged_file_name)).let { if (it.extension == "pdf") it else File(it.path + ".pdf") }
            PdfOps.merge(files, f)
            files(f)
        }
        ToolId.SPLIT -> {
            val src = local(first!!, 0)
            if (o.splitEvery) {
                val parts = PdfOps.split(src, o.everyN.toIntOrNull()?.coerceAtLeast(1) ?: 1, outDir, password)
                if (parts.size == 1) files(parts[0]) else RunResult.Files(Output(parts, MIME_PDF), parts[0].name)
            } else {
                val f = pdf("pages")
                PdfOps.extractPages(src, pages(), f, password)
                files(f)
            }
        }
        ToolId.ORGANIZE -> pdf("organized").also { PdfOps.organize(local(first!!, 0), o.organize.toList(), it, password) }.let { files(it) }
        ToolId.ROTATE -> pdf("rotated").also { PdfOps.rotatePages(local(first!!, 0), optionalPages(), o.degrees, it, password) }.let { files(it) }
        ToolId.DELETE_PAGES -> pdf("edited").also { PdfOps.deletePages(local(first!!, 0), pages().toSet(), it, password) }.let { files(it) }
        ToolId.COMPRESS -> {
            val src = local(first!!, 0)
            val f = pdf("compressed")
            val (side, q) = when (o.compressLevel) { 0 -> 2400 to 0.85f; 2 -> 1100 to 0.5f; else -> 1600 to 0.7f }
            val smaller = PdfOps.compress(src, side, q, f, password)
            if (!smaller) throw ToolError(R.string.tool_compress_no_gain)
            val note = context.getString(
                R.string.tool_compress_result,
                android.text.format.Formatter.formatShortFileSize(context, src.length()),
                android.text.format.Formatter.formatShortFileSize(context, f.length()),
            )
            files(f, note = note)
        }
        ToolId.REPAIR -> pdf("repaired").also { PdfOps.repair(local(first!!, 0), it, password) }.let { files(it) }
        ToolId.IMAGES_TO_PDF, ToolId.SCAN -> {
            val f = out((if (tool == ToolId.SCAN) "Scan" else base) + ".pdf")
            val margin = when (o.margin) { 0 -> 0f; 2 -> 48f; else -> 20f }
            PdfOps.imagesToPdf(context, inputs.map { it.uri }, o.pageSize, margin, o.documentFilter, f)
            files(f)
        }
        ToolId.WORD_TO_PDF -> out("$base.pdf").also { PdfOps.wordToPdf(local(first!!, 0), it) }.let { files(it) }
        ToolId.EXCEL_TO_PDF -> {
            val src = local(first!!, 0)
            out("$base.pdf").also { PdfOps.sheetToPdf(src, csv = src.extension.lowercase() != "xlsx", it) }.let { files(it) }
        }
        ToolId.PPT_TO_PDF -> out("$base.pdf").also { PdfOps.slidesToPdf(local(first!!, 0), it) }.let { files(it) }
        ToolId.TEXT_TO_PDF -> {
            val src = local(first!!, 0)
            out("$base.pdf").also { PdfOps.textToPdf(src, markdown = src.extension.lowercase() in setOf("md", "markdown"), it) }.let { files(it) }
        }
        ToolId.PDF_TO_IMAGES -> {
            val produced = ArrayList<File>()
            PdfOps.pdfToImages(unlocked(local(first!!, 0), password, outDir), o.dpi, o.imageFormat) { name, bytes -> produced += out(name).also { it.writeBytes(bytes) } }
            RunResult.Files(Output(produced, if (o.imageFormat == PdfOps.ImageFormat.PNG) "image/png" else "image/jpeg"), produced.first().name)
        }
        ToolId.PDF_TO_WORD -> out("$base.docx").also { f ->
            PdfOps.pdfToWord(local(first!!, 0), f, { n -> context.getString(R.string.tool_page_label, n) }, password)
        }.let { files(it, MIME_DOCX) }
        ToolId.PDF_TO_PPT -> out("$base.pptx").also { PdfOps.pdfToPowerPoint(unlocked(local(first!!, 0), password, outDir), it) }.let { files(it, MIME_PPTX) }
        ToolId.PDF_TO_TEXT -> out("$base.txt").also { it.writeText(PdfOps.pdfToText(local(first!!, 0), password)) }.let { files(it, MIME_TXT) }
        ToolId.WATERMARK -> pdf("watermarked").also {
            PdfOps.watermark(local(first!!, 0), o.text.trim(), o.textSize, o.opacity, o.angle.toFloat(), o.color, it, password)
        }.let { files(it) }
        ToolId.PAGE_NUMBERS -> pdf("numbered").also {
            PdfOps.pageNumbers(
                local(first!!, 0), o.position, o.numberFormat, o.start.toIntOrNull() ?: 1, 11f,
                context.getString(R.string.tool_number_page_word), it, password,
            )
        }.let { files(it) }
        ToolId.PROTECT -> pdf("protected").also {
            PdfOps.protect(local(first!!, 0), o.password, o.allowPrint, o.allowCopy, o.allowEdit, it, password)
        }.let { files(it) }
        ToolId.UNLOCK -> pdf("unlocked").also { PdfOps.unlock(local(first!!, 0), o.password, it) }.let { files(it) }
        ToolId.CROP -> {
            val mm = o.cropMm.map { (it.toFloatOrNull() ?: 0f) * 72f / 25.4f }.toFloatArray()
            pdf("cropped").also { PdfOps.crop(local(first!!, 0), optionalPages(), mm, it, password) }.let { files(it) }
        }
        ToolId.FLATTEN -> pdf("flattened").also { PdfOps.flatten(local(first!!, 0), it, password) }.let { files(it) }
        ToolId.METADATA -> out("$base.pdf").also { PdfOps.writeMetadata(local(first!!, 0), o.meta, it, password) }.let { files(it) }
        ToolId.EXTRACT_IMAGES -> {
            val produced = ArrayList<File>()
            val n = PdfOps.extractImages(local(first!!, 0), password) { name, bytes -> produced += out(name).also { it.writeBytes(bytes) } }
            if (n == 0) throw ToolError(R.string.tool_no_images)
            RunResult.Files(Output(produced, "image/*"), produced.first().name)
        }
        ToolId.COMPARE -> RunResult.Compare(
            PdfOps.compare(local(inputs[0], 0), local(inputs[1], 1)) { n -> context.getString(R.string.tool_page_label, n) },
        )
        ToolId.SIGN, ToolId.REDACT, ToolId.FILL_FORM -> error("Handled by the viewer")
    }
}

/** PdfRenderer cannot open encrypted files: hand it a decrypted copy when a password was given. */
private suspend fun unlocked(file: File, password: String?, dir: File): File =
    if (password.isNullOrEmpty()) file else File(dir, "unlocked-" + file.name).also { PdfOps.unlock(file, password, it) }
