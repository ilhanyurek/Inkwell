package com.office.app.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.office.app.R
import java.io.File

/** Signatures the user has drawn, kept as transparent PNGs in app storage. */
class SignatureStore(context: Context) {
    private val dir = File(context.filesDir, "signatures").apply { mkdirs() }

    fun list(): List<File> = dir.listFiles { f -> f.extension == "png" }.orEmpty().sortedByDescending { it.lastModified() }

    fun save(bitmap: Bitmap): File = File(dir, "sig_${System.currentTimeMillis()}.png").also { f ->
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun load(file: File): Bitmap? = BitmapFactory.decodeFile(file.path)
}

/** Lets the user pick a saved signature or draw a new one. */
@Composable
fun SignaturePicker(store: SignatureStore, onPick: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    var drawing by remember { mutableStateOf(store.list().isEmpty()) }
    var saved by remember { mutableStateOf(store.list()) }
    if (drawing) {
        SignaturePadDialog(
            onDone = { bmp -> store.save(bmp); onPick(bmp) },
            onDismiss = { if (saved.isEmpty()) onDismiss() else drawing = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_signature_choose)) },
        text = {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(saved, key = { it.path }) { f ->
                    val bmp = remember(f) { store.load(f) }
                    Box {
                        Box(
                            Modifier
                                .size(width = 160.dp, height = 80.dp)
                                .background(Color.White, MaterialTheme.shapes.small)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                                .clickable { bmp?.let(onPick) }
                                .padding(6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            bmp?.let { Image(it.asImageBitmap(), contentDescription = null) }
                        }
                        IconButton(
                            onClick = { f.delete(); saved = store.list() },
                            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                        ) { Icon(Icons.Default.Close, stringResource(R.string.action_remove_from_list), Modifier.size(16.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { drawing = true }) { Text(stringResource(R.string.pdf_signature_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SignaturePadDialog(onDone: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val ink = Color(0xFF14284B)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_signature_draw)) },
        text = {
            Column {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.5f)
                        .background(Color.White, MaterialTheme.shapes.small)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDrag = { change, _ -> current = current + change.position },
                                onDragEnd = { if (current.size > 1) strokes += current; current = emptyList() },
                            )
                        },
                ) {
                    (strokes + listOf(current)).forEach { pts ->
                        if (pts.size < 2) return@forEach
                        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                        drawPath(path, ink, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                Text(
                    stringResource(R.string.pdf_signature_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { toBitmap(strokes, size)?.let(onDone) }, enabled = strokes.isNotEmpty()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.sheet_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Renders the strokes into a transparent bitmap cropped to the ink. */
private fun toBitmap(strokes: List<List<Offset>>, size: IntSize): Bitmap? {
    val all = strokes.flatten()
    if (all.isEmpty() || size.width == 0) return null
    val pad = 8f
    val left = (all.minOf { it.x } - pad).coerceAtLeast(0f)
    val top = (all.minOf { it.y } - pad).coerceAtLeast(0f)
    val right = (all.maxOf { it.x } + pad).coerceAtMost(size.width.toFloat())
    val bottom = (all.maxOf { it.y } + pad).coerceAtMost(size.height.toFloat())
    val scale = 2f
    val bmp = Bitmap.createBitmap(((right - left) * scale).toInt().coerceAtLeast(1), ((bottom - top) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(0x14, 0x28, 0x4B)
        style = Paint.Style.STROKE
        strokeWidth = 5f * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (pts in strokes) {
        val path = android.graphics.Path()
        path.moveTo((pts[0].x - left) * scale, (pts[0].y - top) * scale)
        pts.drop(1).forEach { path.lineTo((it.x - left) * scale, (it.y - top) * scale) }
        canvas.drawPath(path, paint)
    }
    return bmp
}
