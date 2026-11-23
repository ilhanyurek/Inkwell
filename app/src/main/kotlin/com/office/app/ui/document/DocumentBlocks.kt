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
                    .background(if (r == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
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
internal fun ImageBlock(bitmap: ImageBitmap?, size: Pair<Dp, Dp>, onClick: () -> Unit, modifier: Modifier = Modifier, fallbackText: String = "") {
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
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                // A text box or shape: show its text rather than an empty frame.
                if (fallbackText.isNotBlank()) {
                    Text(fallbackText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp), overflow = TextOverflow.Ellipsis)
                } else {
                    Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.embed_image), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun rememberEmbedBitmap(vm: DocumentEditorViewModel, id: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, id, vm.embeds[id]) { value = vm.imageBitmap(id) }
    return bmp
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
