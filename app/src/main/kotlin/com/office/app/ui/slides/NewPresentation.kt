package com.office.app.ui.slides

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.ui.common.LoadingScreen
import com.office.app.ui.common.toast
import com.office.core.FileKind
import com.office.core.pptx.PptxSlides
import kotlinx.coroutines.launch

/** Asks where to save a new presentation, writes the starter deck there and opens it. */
@Composable
fun NewPresentation(container: AppContainer, onClose: () -> Unit, onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = stringResource(R.string.new_presentation)
    var asked by rememberSaveable { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FileKind.PRESENTATION.primaryMime)) { target ->
        if (target == null) { onClose(); return@rememberLauncherForActivityResult }
        scope.launch {
            val title = runCatching { container.files.info(target).name.substringBeforeLast('.') }.getOrDefault(name)
            runCatching { container.files.writeBytes(target, PptxSlides.newPresentation(title, "")) }
                .onSuccess { onOpen(target) }
                .onFailure { toast(context, context.getString(R.string.error_save_failed)); onClose() }
        }
    }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            create.launch("$name.pptx")
        }
    }
    LoadingScreen(onBack = onClose, title = name)
}
