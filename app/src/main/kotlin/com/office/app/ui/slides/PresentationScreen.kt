package com.office.app.ui.slides

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.FileInfo
import com.office.app.ui.common.BackButton
import com.office.app.ui.common.ErrorScreen
import com.office.app.ui.common.LoadingScreen
import com.office.app.ui.common.openWithOtherApp
import com.office.app.ui.common.shareUri
import com.office.core.pptx.PptxReader
import com.office.core.pptx.Presentation
import com.office.core.pptx.Slide
import com.office.core.pptx.SlideShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import com.office.core.text.Alignment as DocAlignment

class PresentationViewModel(container: AppContainer, uri: Uri) : ViewModel() {
    var presentation by mutableStateOf<Presentation?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val images = HashMap<String, ImageBitmap?>()

    init {
        viewModelScope.launch {
            runCatching {
                val bytes = container.files.readBytes(uri)
                withContext(Dispatchers.Default) { PptxReader.read(ByteArrayInputStream(bytes)) }
            }.onSuccess { presentation = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    /** Decodes slide pictures once, downsampled; unsupported formats (EMF/WMF) yield null. */
    fun image(pic: SlideShape.Picture): ImageBitmap? = synchronized(images) {
        images.getOrPut(pic.contentPath) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(pic.image, 0, pic.image.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1600 || bounds.outHeight / (sample * 2) >= 1600) sample *= 2
                BitmapFactory.decodeByteArray(pic.image, 0, pic.image.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PresentationScreen(container: AppContainer, uri: Uri, info: FileInfo, onClose: () -> Unit) {
    val vm: PresentationViewModel = viewModel(key = "pptx:$uri") { PresentationViewModel(container, uri) }
    val context = LocalContext.current
    val pres = vm.presentation
    vm.error?.let {
        ErrorScreen(stringResource(R.string.error_cannot_read_presentation), onBack = onClose, detail = it)
        return
    }
    if (pres == null) {
        LoadingScreen(onBack = onClose, title = info.name)
        return
    }
    if (pres.slides.isEmpty()) {
        ErrorScreen(stringResource(R.string.presentation_empty), onBack = onClose)
        return
    }

    val pager = rememberPagerState { pres.slides.size }
    val thumbs = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var presenting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(pager.currentPage) { thumbs.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onClose) },
                title = {
                    Column {
                        Text(info.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            stringResource(R.string.slide_of, pager.currentPage + 1, pres.slides.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { presenting = true }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.slide_present)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                                menu = false
                                shareUri(context, uri, info.kind?.primaryMime ?: "*/*", context.getString(R.string.action_share))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_open_with)) }, onClick = {
                                menu = false
                                openWithOtherApp(context, uri, info.kind?.primaryMime ?: "*/*")
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                pageSpacing = 16.dp,
                verticalAlignment = Alignment.CenterVertically,
            ) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    SlideView(
                        vm, pres, pres.slides[page],
                        Modifier.fillMaxWidth().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    )
                    val notes = pres.slides[page].notes
                    if (notes.isNotBlank()) {
                        Text(
                            stringResource(R.string.slide_notes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                        Text(notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.navigationBarsPadding()) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LazyRow(
                        state = thumbs,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(pres.slides) { i, slide ->
                            val selected = i == pager.currentPage
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SlideView(
                                    vm, pres, slide,
                                    Modifier
                                        .width(96.dp)
                                        .border(
                                            if (selected) 2.dp else 0.5.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        )
                                        .clickable { scope.launch { pager.animateScrollToPage(i) } },
                                    thumbnail = true,
                                )
                                Text(
                                    (i + 1).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (presenting) {
        PresentMode(vm, pres, start = pager.currentPage, onExit = { last ->
            presenting = false
            scope.launch { pager.scrollToPage(last) }
        })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresentMode(vm: PresentationViewModel, pres: Presentation, start: Int, onExit: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { pres.slides.size }
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { onExit(pager.currentPage) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        scope.launch {
                            if (p.x > size.width / 2) {
                                if (pager.currentPage < pres.slides.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else onExit(pager.currentPage)
                            } else if (pager.currentPage > 0) {
                                pager.animateScrollToPage(pager.currentPage - 1)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) { page ->
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val byWidth = maxWidth / pres.aspectRatio <= maxHeight
                    val mod = if (byWidth) Modifier.fillMaxWidth() else Modifier.size(maxHeight * pres.aspectRatio, maxHeight)
                    SlideView(vm, pres, pres.slides[page], mod)
                }
            }
            Text(
                "${pager.currentPage + 1} / ${pres.slides.size}",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

/** Draws a slide by placing its shapes at their EMU positions scaled to the available width. */
@Composable
fun SlideView(vm: PresentationViewModel, pres: Presentation, slide: Slide, modifier: Modifier, thumbnail: Boolean = false) {
    val bg = slide.background?.let { Color(it) } ?: Color.White
    val defaultText = if (bg.luminance() < 0.4f) Color(0xFFF5F5F5) else Color(0xFF1B1C1E)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.aspectRatio(pres.aspectRatio).background(bg).clipToBounds()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val emuToPx = widthPx / pres.width
        // 1pt = 12700 EMU. Convert to sp independent of the user's font scale so slides keep their layout.
        val ptToSp = 12700f * emuToPx / (density.density * density.fontScale)
        for (shape in slide.shapes) {
            val f = shape.frame
            val x = (f.x * emuToPx).roundToInt()
            val y = (f.y * emuToPx).roundToInt()
            val w = with(density) { (f.width * emuToPx).toDp() }
            val h = with(density) { (f.height * emuToPx).toDp() }
            val placed = Modifier.offset { IntOffset(x, y) }.size(w, h)
            when (shape) {
                is SlideShape.Rect -> Box(placed.background(Color(shape.fill)))
                is SlideShape.Picture -> {
                    val img = remember(shape.contentPath) { vm.image(shape) }
                    if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = placed)
                    else Box(placed.background(Color.Gray.copy(alpha = 0.15f)))
                }
                is SlideShape.TextBox -> TextBoxView(shape, placed, ptToSp, defaultText, thumbnail, w)
            }
        }
    }
}

@Composable
private fun TextBoxView(box: SlideShape.TextBox, modifier: Modifier, ptToSp: Float, defaultColor: Color, thumbnail: Boolean, width: Dp) {
    val inset = (width.value * 0.02f).coerceIn(0f, 8f).dp
    Box(
        modifier
            .then(box.fill?.let { Modifier.background(Color(it)) } ?: Modifier)
            .padding(horizontal = inset),
        contentAlignment = if (box.verticalCenter) Alignment.CenterStart else Alignment.TopStart,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 0.dp)) {
            for (p in box.paragraphs) {
                val align = when (p.alignment) {
                    DocAlignment.CENTER -> TextAlign.Center
                    DocAlignment.END -> TextAlign.End
                    DocAlignment.JUSTIFY -> TextAlign.Justify
                    DocAlignment.START -> TextAlign.Start
                }
                val baseSize = (p.runs.firstOrNull()?.sizePt ?: 18f) * ptToSp
                val text = buildAnnotatedString {
                    if (p.bullet) append("•  ")
                    for (r in p.runs) {
                        withStyle(
                            SpanStyle(
                                color = r.color?.let { Color(it) } ?: defaultColor,
                                fontSize = ((r.sizePt ?: 18f) * ptToSp).coerceAtLeast(1f).sp,
                                fontWeight = if (r.bold) FontWeight.Bold else null,
                                fontStyle = if (r.italic) FontStyle.Italic else null,
                                textDecoration = if (r.underline) TextDecoration.Underline else null,
                            ),
                        ) { append(r.text) }
                    }
                }
                Text(
                    text,
                    textAlign = align,
                    lineHeight = (baseSize * 1.15f).coerceAtLeast(1f).sp,
                    fontSize = baseSize.coerceAtLeast(1f).sp,
                    modifier = Modifier.fillMaxWidth().padding(start = (p.level * 18 * ptToSp).dp),
                )
            }
        }
    }
}
