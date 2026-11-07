package com.office.app.ui.sheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.office.app.R
import com.office.core.sheet.CellRef
import com.office.core.sheet.ChartSpec
import com.office.core.sheet.ChartType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Checklist of the values in the selected column; unchecked values hide their rows. */
@Composable
internal fun FilterDialog(vm: SheetEditorViewModel, onDismiss: () -> Unit) {
    val col = vm.selection.focus.col
    val (header, values) = remember(vm.revision, col) { vm.filterChoices(col) }
    val current = vm.filter?.takeIf { it.sheet == vm.activeSheetIndex && it.col == col }?.allowed
    val checked = remember(values) { mutableStateListOf(*values.filter { current == null || it in current }.toTypedArray()) }
    val blank = stringResource(R.string.sheet_filter_blank)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.sheet_filter)} · ${CellRef.columnName(col)}") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                    if (checked.size == values.size) checked.clear() else { checked.clear(); checked.addAll(values) }
                }) {
                    Checkbox(checked = checked.size == values.size, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sheet_filter_all), style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(values) { v ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (v in checked) checked.remove(v) else checked.add(v) }
                                .testTag("filter_value_$v"),
                        ) {
                            Checkbox(checked = v in checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(v.ifEmpty { blank }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.setFilter(col, header, checked.toSet(), values.size); onDismiss() }, modifier = Modifier.testTag("filter_apply")) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.clearFilter(); onDismiss() }) { Text(stringResource(R.string.sheet_filter_clear)) }
        },
    )
}

private val PALETTE = listOf(
    Color(0xFF2F5BC4), Color(0xFF2E7D32), Color(0xFFE08A00), Color(0xFFC62828),
    Color(0xFF6A3FB5), Color(0xFF00838F), Color(0xFF8D6E63), Color(0xFFAD1457),
)

/** All charts on the current sheet, drawn from the live cell values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChartsDialog(vm: SheetEditorViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) } },
                    title = { Text(stringResource(R.string.sheet_charts)) },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(vm.sheet.charts.size) { i ->
                    val chart = vm.sheet.charts[i]
                    ChartCard(chart, remember(vm.revision, chart) { vm.chartData(chart) }, Modifier.testTag("chart_$i"))
                }
            }
        }
    }
}

@Composable
private fun ChartCard(chart: ChartSpec, data: SheetEditorViewModel.ChartData, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val pie = chart.type == ChartType.PIE || chart.type == ChartType.DOUGHNUT
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        if (chart.title.isNotEmpty()) Text(chart.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(260.dp)) {
            val style = TextStyle(fontSize = 11.sp, color = textColor)
            if (pie) drawPie(data, chart.type == ChartType.DOUGHNUT) else drawAxes(chart.type, data, measurer, style, gridColor)
        }
        Spacer(Modifier.height(12.dp))
        // Legend: series for axis charts, categories for pies.
        val entries = if (pie) data.categories.mapIndexed { i, c -> c.ifEmpty { "${i + 1}" } } else data.series.map { it.first }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEachIndexed { i, name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(PALETTE[i % PALETTE.size], RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPie(data: SheetEditorViewModel.ChartData, hole: Boolean) {
    val values = data.series.firstOrNull()?.second.orEmpty().map { (it ?: 0.0).coerceAtLeast(0.0) }
    val total = values.sum()
    if (total <= 0) return
    val d = min(size.width, size.height)
    val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
    var start = -90f
    values.forEachIndexed { i, v ->
        val sweep = (v / total * 360).toFloat()
        if (hole) drawArc(PALETTE[i % PALETTE.size], start, sweep, false, topLeft, Size(d, d), style = Stroke(d * 0.18f))
        else drawArc(PALETTE[i % PALETTE.size], start, sweep, true, topLeft, Size(d, d))
        start += sweep
    }
}

/** A "nice" axis maximum and step for values up to [maxValue]. */
private fun niceScale(minValue: Double, maxValue: Double): Triple<Double, Double, Double> {
    val lo = min(0.0, minValue)
    val hi = max(0.0, maxValue).let { if (it == lo) lo + 1 else it }
    val raw = (hi - lo) / 4
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    return Triple(floor(lo / step) * step, ceil(hi / step) * step, step)
}

private fun label(v: Double): String =
    if (abs(v) >= 1_000_000) "%.1fM".format(v / 1_000_000) else if (abs(v) >= 10_000) "%.0fk".format(v / 1000)
    else if (v == floor(v)) v.toLong().toString() else "%.1f".format(v)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxes(
    type: ChartType,
    data: SheetEditorViewModel.ChartData,
    measurer: TextMeasurer,
    style: TextStyle,
    gridColor: Color,
) {
    val all = data.series.flatMap { it.second }.filterNotNull()
    if (all.isEmpty()) return
    val (lo, hi, step) = niceScale(all.min(), all.max())
    val count = max(data.categories.size, data.series.maxOf { it.second.size })
    if (count == 0) return
    val horizontal = type == ChartType.BAR
    val left = 44.dp.toPx()
    val bottom = 22.dp.toPx()
    val plotW = size.width - left
    val plotH = size.height - bottom
    fun scale(v: Double): Float = ((v - lo) / (hi - lo)).toFloat()

    // Grid lines and value labels.
    var v = lo
    while (v <= hi + step / 2) {
        val t = scale(v)
        if (horizontal) {
            val x = left + t * plotW
            drawLine(gridColor, Offset(x, 0f), Offset(x, plotH), 1f)
            drawText(measurer, label(v), Offset(x - 10.dp.toPx(), plotH + 4.dp.toPx()), style)
        } else {
            val y = plotH - t * plotH
            drawLine(gridColor, Offset(left, y), Offset(size.width, y), 1f)
            val m = measurer.measure(label(v), style)
            drawText(m, topLeft = Offset(left - m.size.width - 6.dp.toPx(), y - m.size.height / 2))
        }
        v += step
    }
    val zero = scale(0.0)
    val band = (if (horizontal) plotH else plotW) / count

    // Category labels.
    for (i in 0 until count) {
        val text = data.categories.getOrNull(i).orEmpty()
        if (text.isEmpty()) continue
        val m = measurer.measure(text, style, maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = max(1, (if (horizontal) left - 4.dp.toPx() else band).toInt())))
        if (horizontal) drawText(m, topLeft = Offset(0f, i * band + band / 2 - m.size.height / 2))
        else drawText(m, topLeft = Offset(left + i * band + (band - m.size.width) / 2, plotH + 4.dp.toPx()))
    }

    val n = data.series.size
    data.series.forEachIndexed { s, (_, values) ->
        val color = PALETTE[s % PALETTE.size]
        when (type) {
            ChartType.COLUMN, ChartType.BAR -> {
                val inner = band * 0.75f
                val w = inner / n
                values.forEachIndexed { i, value ->
                    if (value == null) return@forEachIndexed
                    val t = scale(value)
                    val start = i * band + (band - inner) / 2 + s * w
                    if (horizontal) {
                        val x0 = left + min(zero, t) * plotW
                        drawRect(color, Offset(x0, start), Size(abs(t - zero) * plotW, w * 0.9f))
                    } else {
                        val y0 = plotH - max(zero, t) * plotH
                        drawRect(color, Offset(left + start, y0), Size(w * 0.9f, abs(t - zero) * plotH))
                    }
                }
            }
            else -> {
                val path = Path()
                val fill = Path()
                var started = false
                values.forEachIndexed { i, value ->
                    if (value == null) return@forEachIndexed
                    val x = left + i * band + band / 2
                    val y = plotH - scale(value) * plotH
                    if (!started) {
                        path.moveTo(x, y); fill.moveTo(x, plotH - zero * plotH); fill.lineTo(x, y); started = true
                    } else {
                        path.lineTo(x, y); fill.lineTo(x, y)
                    }
                    if (type != ChartType.AREA) drawCircle(color, 3.dp.toPx(), Offset(x, y))
                }
                if (type == ChartType.AREA && started) {
                    val lastX = left + (values.indexOfLast { it != null }) * band + band / 2
                    fill.lineTo(lastX, plotH - zero * plotH)
                    fill.close()
                    drawPath(fill, color.copy(alpha = 0.35f))
                }
                if (type != ChartType.SCATTER) drawPath(path, color, style = Stroke(2.dp.toPx()))
            }
        }
    }
    // Axis line at zero.
    if (horizontal) drawLine(gridColor.copy(alpha = 1f), Offset(left + zero * plotW, 0f), Offset(left + zero * plotW, plotH), 2f)
    else drawLine(gridColor.copy(alpha = 1f), Offset(left, plotH - zero * plotH), Offset(size.width, plotH - zero * plotH), 2f)
}
