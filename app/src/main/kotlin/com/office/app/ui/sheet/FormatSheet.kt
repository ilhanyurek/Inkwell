package com.office.app.ui.sheet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.BorderAll
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.office.app.R
import com.office.core.sheet.CellStyle
import com.office.core.sheet.HAlign
import com.office.core.sheet.NumberFormat

private val TextColors = listOf(0xFF1B1C1E, 0xFF5B5E63, 0xFFC0392B, 0xFFD35400, 0xFF1E7F4F, 0xFF2B59C3, 0xFF6C3483, 0xFFFFFFFF)
private val FillColors = listOf(0xFFFFF2CC, 0xFFE2EFDA, 0xFFDDEBF7, 0xFFFCE4D6, 0xFFF2DCDB, 0xFFEDEDED, 0xFF2B59C3, 0xFF1B1C1E)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun FormatSheet(vm: SheetEditorViewModel, onDismiss: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.revision
    val st = vm.focusStyle
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.format_title, vm.selection.label()), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tool(Icons.Default.FormatBold, R.string.format_bold, st.bold) { vm.applyStyle { it.copy(bold = !st.bold) } }
                Tool(Icons.Default.FormatItalic, R.string.format_italic, st.italic) { vm.applyStyle { it.copy(italic = !st.italic) } }
                Tool(Icons.Default.FormatUnderlined, R.string.format_underline, st.underline) { vm.applyStyle { it.copy(underline = !st.underline) } }
                Tool(Icons.Default.FormatStrikethrough, R.string.format_strike, st.strike) { vm.applyStyle { it.copy(strike = !st.strike) } }
                Tool(Icons.Default.BorderAll, R.string.format_border, st.border) { vm.applyStyle { it.copy(border = !st.border) } }
                Tool(Icons.AutoMirrored.Filled.WrapText, R.string.format_wrap, st.wrap) { vm.applyStyle { it.copy(wrap = !st.wrap) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tool(Icons.AutoMirrored.Filled.FormatAlignLeft, R.string.align_left, st.hAlign == HAlign.LEFT) { vm.applyStyle { it.copy(hAlign = if (st.hAlign == HAlign.LEFT) null else HAlign.LEFT) } }
                Tool(Icons.Default.FormatAlignCenter, R.string.align_center, st.hAlign == HAlign.CENTER) { vm.applyStyle { it.copy(hAlign = if (st.hAlign == HAlign.CENTER) null else HAlign.CENTER) } }
                Tool(Icons.AutoMirrored.Filled.FormatAlignRight, R.string.align_right, st.hAlign == HAlign.RIGHT) { vm.applyStyle { it.copy(hAlign = if (st.hAlign == HAlign.RIGHT) null else HAlign.RIGHT) } }
                Tool(Icons.Default.FormatClear, R.string.format_clear, false) { vm.applyStyle { CellStyle(numFmt = it.numFmt) } }
            }
            Palette(stringResource(R.string.format_text_color), TextColors, st.fontColor) { c -> vm.applyStyle { it.copy(fontColor = c) } }
            Palette(stringResource(R.string.format_fill), FillColors, st.fill) { c -> vm.applyStyle { it.copy(fill = c) } }
            Text(stringResource(R.string.format_number), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberFormat.PRESETS.forEach { (label, code) ->
                    val text = when (label) {
                        "General" -> stringResource(R.string.numfmt_general)
                        "Date" -> stringResource(R.string.numfmt_date)
                        "ISO date" -> stringResource(R.string.numfmt_iso_date)
                        "Time" -> stringResource(R.string.numfmt_time)
                        "Text" -> stringResource(R.string.numfmt_text)
                        else -> label
                    }
                    FilterChip(selected = st.numFmt == code, onClick = { vm.applyStyle { it.copy(numFmt = code) } }, label = { Text(text) })
                }
            }
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: Int, checked: Boolean, onClick: () -> Unit) {
    IconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        Box(
            Modifier.size(38.dp).background(if (checked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = stringResource(label), tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Palette(title: String, colors: List<Long>, current: Long?, onPick: (Long?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.format_auto)) }
            colors.forEach { c ->
                Box(
                    Modifier
                        .size(26.dp)
                        .background(Color(c), CircleShape)
                        .border(
                            if (current == c) 2.5.dp else 1.dp,
                            if (current == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                        .clickable { onPick(c) },
                )
            }
        }
    }
}
