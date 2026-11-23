package com.office.app.ui.document

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.East
import androidx.compose.material.icons.outlined.North
import androidx.compose.material.icons.outlined.South
import androidx.compose.material.icons.outlined.West
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.office.app.R

/**
 * A table cell being edited in place: the table's place in the text ([offset]), the cell, and the text
 * typed so far. [generation] changes whenever the cell or the table changes, so stale callbacks are ignored.
 */
internal data class CellEdit(
    val offset: Int,
    val row: Int,
    val tc: Int,
    val text: TextFieldValue,
    val original: String,
    val generation: Int,
)

/**
 * A Word table drawn as laid out in the file. When [editable], tapping a cell starts editing it right
 * there: a text field with the cell's own width, look and fill is laid over it.
 */
@Composable
internal fun TableView(
    block: MeasuredBlock,
    index: Int,
    editable: Boolean,
    edit: CellEdit?,
    page: Color,
    onCell: (row: Int, tc: Int) -> Unit,
    onText: (TextFieldValue) -> Unit,
    onFocusLost: (generation: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    fun Float.dp() = with(density) { this@dp.toDp() }
    Box(modifier) {
        MeasuredBlockView(block)
        if (editable) {
            block.cells.forEach { c ->
                Box(
                    Modifier
                        .offset { IntOffset(c.x.toInt(), c.y.toInt()) }
                        .size(c.w.dp(), c.h.dp())
                        // Tapping a cell must not take focus away from the cell being typed in first.
                        .focusProperties { canFocus = false }
                        .clickable { onCell(c.row, c.tc) }
                        .testTag("doc_cell_${index}_${c.row}_${c.tc}"),
                )
            }
        }
        val c = edit?.let { e -> block.cells.firstOrNull { it.row == e.row && it.tc == e.tc } }
        if (edit != null && c != null) {
            val accent = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .offset { IntOffset(c.x.toInt(), c.y.toInt()) }
                    .size(c.w.dp(), c.h.dp())
                    .border(2.dp, accent),
            )
            val focus = remember(edit.generation) { FocusRequester() }
            // The same field stays focused while moving between cells, so this is not reset per cell.
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(edit.generation) { runCatching { focus.requestFocus() } }
            BasicTextField(
                value = edit.text,
                onValueChange = onText,
                textStyle = c.style,
                cursorBrush = SolidColor(accent),
                modifier = Modifier
                    .offset { IntOffset((c.x + c.padX).toInt(), (c.y + c.padY).toInt()) }
                    .width((c.w - 2 * c.padX).coerceAtLeast(8f).dp())
                    .heightIn(min = (c.h - 2 * c.padY).coerceAtLeast(8f).dp())
                    .background(c.fill ?: page)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) onFocusLost(edit.generation) }
                    .testTag("table_cell_editor"),
            )
        }
    }
}

/**
 * Moving between cells and changing the table's shape, all in view at once: the cell arrows and Done on top,
 * and below them the row and column buttons in two small labelled groups.
 */
@Composable
internal fun TableBar(
    regular: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRowAbove: () -> Unit,
    onRowBelow: () -> Unit,
    onDeleteRow: () -> Unit,
    onColLeft: () -> Unit,
    onColRight: () -> Unit,
    onDeleteCol: () -> Unit,
    onDeleteTable: () -> Unit,
    onDone: () -> Unit,
) {
    com.office.app.ui.design.FloatingBar(Modifier.navigationBarsPadding().imePadding()) {
        // The buttons never take focus: the cell keeps it, so a press (also with a keyboard attached, where
        // buttons are focusable) does not end the edit before the button acts.
        Column(Modifier.fillMaxWidth().focusProperties { canFocus = false }.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrev, modifier = Modifier.testTag("table_prev")) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.table_prev_cell), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                TextButton(onClick = onNext, modifier = Modifier.testTag("table_next")) {
                    Text(stringResource(R.string.table_next_cell), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDone, shape = CircleShape, modifier = Modifier.testTag("table_done")) {
                    Text(stringResource(R.string.pdf_edit_done))
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Group(R.string.table_rows) {
                    Tool(Icons.Outlined.North, R.string.table_row_above, "table_row_above", add = true, onClick = onRowAbove)
                    Tool(Icons.Outlined.South, R.string.table_row_below, "table_row_below", add = true, onClick = onRowBelow)
                    Tool(Icons.Outlined.Remove, R.string.table_remove_row, "table_delete_row", onClick = onDeleteRow)
                }
                Spacer(Modifier.width(12.dp))
                Group(R.string.table_columns) {
                    Tool(Icons.Outlined.West, R.string.table_col_left, "table_col_left", add = true, enabled = regular, onClick = onColLeft)
                    Tool(Icons.Outlined.East, R.string.table_col_right, "table_col_right", add = true, enabled = regular, onClick = onColRight)
                    Tool(Icons.Outlined.Remove, R.string.table_remove_column, "table_delete_col", enabled = regular, onClick = onDeleteCol)
                }
                Spacer(Modifier.width(12.dp))
                Group(R.string.table_delete) {
                    Tool(Icons.Outlined.Delete, R.string.table_delete, "table_delete", onClick = onDeleteTable)
                }
            }
        }
    }
}

/** A few buttons under a small caption, e.g. "Rows". */
@Composable
private fun Group(label: Int, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row { content() }
    }
}

/** An icon button; [add] puts a small plus on the arrow, so "add a row above" reads at a glance. */
@Composable
private fun Tool(icon: ImageVector, label: Int, tag: String, add: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp).testTag(tag)) {
        Box(Modifier.size(24.dp)) {
            Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(20.dp).align(Alignment.BottomStart))
            if (add) Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp).align(Alignment.TopEnd))
        }
    }
}
