package com.office.app.ui.document

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.office.app.R
import com.office.core.docx.DocxImages
import com.office.core.docx.DocxTable
import com.office.core.text.Embed
import com.office.core.text.EmbedKind

internal const val TABLE_ROW_HEIGHT = 44
internal const val TABLE_MAX_ROWS = 15
private const val TABLE_FOOTER_HEIGHT = 28
private const val PAGE_TEXT_WIDTH_EMU = 6.5 * 914400

/** Space to leave in the text for each table and picture, given the width of the text column. */
internal fun blockSizes(embeds: Map<Int, Embed>, contentWidth: Dp): Map<Int, Dp> {
    val out = HashMap<Int, Dp>()
    for ((id, e) in embeds) {
        when (e.kind) {
            EmbedKind.TABLE -> {
                val rows = e.tableRows.size
                if (rows == 0 || e.tableRows.all { it.isEmpty() }) continue
                val shown = minOf(rows, TABLE_MAX_ROWS)
                out[id] = (shown * TABLE_ROW_HEIGHT + (if (rows > shown) TABLE_FOOTER_HEIGHT else 0) + 2).dp
            }
            EmbedKind.IMAGE -> out[id] = imageSize(e, contentWidth).second + 8.dp
            else -> {}
        }
    }
    return out
}

/** Picture size on screen: its share of a typical page width, capped in height. */
internal fun imageSize(e: Embed, contentWidth: Dp): Pair<Dp, Dp> {
    val (cx, cy) = DocxImages.extent(e) ?: (4L to 3L).let { (a, b) -> (PAGE_TEXT_WIDTH_EMU.toLong() * a / 4) to (PAGE_TEXT_WIDTH_EMU.toLong() * b / 4) }
    var w = contentWidth * (cx / PAGE_TEXT_WIDTH_EMU).toFloat().coerceIn(0.15f, 1f)
    var h = w * (cy.toFloat() / cx)
    val maxH = 520.dp
    if (h > maxH) { w = w * (maxH / h); h = maxH }
    return w to h
}

/** A table drawn in place in the document; tapping it opens the editor. */
@Composable
internal fun TableBlock(embed: Embed, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rows = embed.tableRows
    val cols = rows.maxOfOrNull { it.size } ?: 0
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .width(width)
            .border(1.dp, line)
            .clickable(onClick = onClick),
    ) {
        rows.take(TABLE_MAX_ROWS).forEachIndexed { r, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(TABLE_ROW_HEIGHT.dp)
                    .background(if (r == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface),
            ) {
                for (c in 0 until cols) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .border(0.5.dp, line)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            row.getOrNull(c).orEmpty(),
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = if (r == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        if (rows.size > TABLE_MAX_ROWS) {
            Box(Modifier.fillMaxWidth().height(TABLE_FOOTER_HEIGHT.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    "+${rows.size - TABLE_MAX_ROWS}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A picture drawn in place in the document. */
@Composable
internal fun ImageBlock(bitmap: ImageBitmap?, size: Pair<Dp, Dp>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(vertical = 4.dp)
            .size(size.first, size.second)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = stringResource(R.string.embed_image), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.embed_image), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun rememberEmbedBitmap(vm: DocumentEditorViewModel, id: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, id, vm.embeds[id]) { value = vm.imageBitmap(id) }
    return bmp
}

/** Full-screen table editor: edit cells, add or remove rows and columns, or delete the table. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TableEditorDialog(embed: Embed, onSave: (Embed) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val original = remember(embed) { DocxTable.rows(embed.xml) }
    val origCols = original.maxOfOrNull { it.size } ?: 0
    val cells = remember(embed) { mutableStateListOf(*original.map { r -> mutableStateListOf(*Array(origCols) { r.getOrElse(it) { "" } }) }.toTypedArray()) }
    val rowMap = remember(embed) { mutableStateListOf<Int?>(*original.indices.map { it as Int? }.toTypedArray()) }
    val colMap = remember(embed) { mutableStateListOf<Int?>(*(0 until origCols).map { it as Int? }.toTypedArray()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val line = MaterialTheme.colorScheme.outlineVariant

    fun save() {
        val rows = cells.map { it.toList() }
        val xml = DocxTable.edit(embed.xml, rows, rowMap.toList(), colMap.toList())
        onSave(DocxTable.embed(xml))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) } },
                    title = { Text(stringResource(R.string.table_edit)) },
                    actions = {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.table_delete)) }
                        TextButton(onClick = ::save, enabled = cells.isNotEmpty() && colMap.isNotEmpty(), modifier = Modifier.testTag("table_save")) {
                            Text(stringResource(R.string.action_save))
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        cells.add(mutableStateListOf(*Array(colMap.size) { "" }))
                        rowMap.add(null)
                    }, modifier = Modifier.testTag("table_add_row")) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.table_add_row))
                    }
                    TextButton(onClick = {
                        cells.forEach { it.add("") }
                        colMap.add(null)
                    }, modifier = Modifier.testTag("table_add_column")) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.table_add_column))
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Row {
                        Spacer(Modifier.width(40.dp))
                        for (c in colMap.indices) {
                            Box(Modifier.width(140.dp), contentAlignment = Alignment.Center) {
                                IconButton(onClick = {
                                    if (colMap.size > 1) {
                                        cells.forEach { it.removeAt(c) }
                                        colMap.removeAt(c)
                                    }
                                }, enabled = colMap.size > 1) {
                                    Icon(Icons.Default.Close, stringResource(R.string.table_remove_column), Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    cells.forEachIndexed { r, row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (cells.size > 1) {
                                    cells.removeAt(r)
                                    rowMap.removeAt(r)
                                }
                            }, enabled = cells.size > 1, modifier = Modifier.width(40.dp)) {
                                Icon(Icons.Default.Close, stringResource(R.string.table_remove_row), Modifier.size(18.dp))
                            }
                            row.forEachIndexed { c, text ->
                                BasicTextField(
                                    value = text,
                                    onValueChange = { row[c] = it },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .width(140.dp)
                                        .heightIn(min = 44.dp)
                                        .border(0.5.dp, line)
                                        .background(if (r == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                                        .padding(8.dp)
                                        .testTag("table_cell_${r}_$c"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.table_delete)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A larger view of a picture with an option to remove it. */
@Composable
internal fun ImageDialog(bitmap: ImageBitmap?, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Surface(Modifier.fillMaxWidth()) {
                if (bitmap != null) Image(bitmap, stringResource(R.string.embed_image), Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit)
                else Text(stringResource(R.string.image_unsupported))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { TextButton(onClick = onDelete, modifier = Modifier.testTag("image_delete")) { Text(stringResource(R.string.action_delete)) } },
    )
}

/** Picks the size of a new table. */
@Composable
internal fun InsertTableDialog(onInsert: (rows: Int, cols: Int) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableIntStateOf(3) }
    var cols by remember { mutableIntStateOf(3) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_table)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(stringResource(R.string.table_rows), rows, 1..50) { rows = it }
                Stepper(stringResource(R.string.table_columns), cols, 1..10) { cols = it }
            }
        },
        confirmButton = { TextButton(onClick = { onInsert(rows, cols) }, modifier = Modifier.testTag("insert_table_confirm")) { Text(stringResource(R.string.action_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) { Text("−", fontSize = 20.sp) }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) { Text("+", fontSize = 20.sp) }
    }
}
