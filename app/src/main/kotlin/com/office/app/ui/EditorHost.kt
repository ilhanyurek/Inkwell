package com.office.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.FileInfo
import com.office.app.ui.common.ErrorScreen
import com.office.app.ui.common.LoadingScreen
import com.office.app.ui.document.DocumentEditorScreen
import com.office.app.ui.pdf.PdfViewerScreen
import com.office.app.ui.sheet.SheetEditorScreen
import com.office.app.ui.slides.PresentationScreen
import com.office.core.FileKind

/** Resolves a file's type and shows the matching editor. */
@Composable
fun EditorHost(
    container: AppContainer,
    uri: Uri?,
    newKind: FileKind?,
    onClose: () -> Unit,
    onOpenOther: (Uri) -> Unit,
    draftKey: String? = null,
) {
    var info by remember(uri) { mutableStateOf<FileInfo?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    if (uri != null) {
        LaunchedEffect(uri) {
            runCatching {
                container.files.takePersistablePermission(uri)
                container.files.info(uri)
            }.onSuccess { i ->
                info = i
                if (i.kind != null) container.recents.record(i)
            }.onFailure { failed = true }
        }
    }

    val kind = newKind ?: info?.kind
    when {
        uri == null && newKind != null -> Editor(container, null, null, newKind, onClose, onOpenOther, draftKey)
        failed -> ErrorScreen(stringResource(R.string.error_cannot_open), onBack = onClose)
        info == null -> LoadingScreen(onBack = onClose)
        kind == null -> ErrorScreen(
            if (FileKind.isLegacyBinary(info?.name)) stringResource(R.string.error_legacy_format)
            else stringResource(R.string.error_unsupported_format),
            onBack = onClose,
        )
        else -> Editor(container, uri, info, kind, onClose, onOpenOther)
    }
}

@Composable
private fun Editor(
    container: AppContainer,
    uri: Uri?,
    info: FileInfo?,
    kind: FileKind,
    onClose: () -> Unit,
    onOpenOther: (Uri) -> Unit,
    draftKey: String? = null,
) {
    when (kind) {
        FileKind.DOCUMENT, FileKind.TEXT, FileKind.MARKDOWN ->
            DocumentEditorScreen(container, uri, info, kind, onClose, onOpenOther, draftKey)
        FileKind.SPREADSHEET, FileKind.CSV ->
            SheetEditorScreen(container, uri, info, kind, onClose, onOpenOther, draftKey)
        FileKind.PDF -> if (uri != null && info != null) PdfViewerScreen(container, uri, info, onClose, onOpenOther) else ErrorScreen(stringResource(R.string.error_cannot_open), onBack = onClose)
        FileKind.PRESENTATION -> if (uri != null && info != null) PresentationScreen(container, uri, info, onClose) else ErrorScreen(stringResource(R.string.error_cannot_open), onBack = onClose)
    }
}
