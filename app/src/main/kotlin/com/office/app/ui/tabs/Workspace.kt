package com.office.app.ui.tabs

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.office.app.AppContainer
import com.office.app.ui.EditorHost
import com.office.core.FileKind

/**
 * Several files open at once: a tab strip on top and the selected file's editor below. Editor view models
 * are kept per file, so switching tabs keeps undo history and unsaved text of every tab.
 */
@Composable
fun Workspace(container: AppContainer, onAllClosed: () -> Unit, onHome: () -> Unit) {
    val tabs = container.tabs
    val selected = tabs.selected
    LaunchedEffect(selected == null) { if (selected == null) onAllClosed() }
    if (selected == null) return

    var overview by rememberSaveable { mutableStateOf(false) }
    // Back closes the file in front (editors with unsaved changes ask first); the last one returns Home.
    BackHandler { if (tabs.close(selected.id)) onAllClosed() }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) tabs.open(uri)
    }
    // Keeps each tab's scroll positions and other saved UI state while another tab is shown.
    val saveable = rememberSaveableStateHolder()
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabStrip(
                container = container,
                onHome = onHome,
                onNew = { tabs.openNew(it) },
                onOpenFile = { openFile.launch(FileKind.openableMimeTypes + "*/*") },
                onOverview = { overview = true },
                modifier = Modifier.statusBarsPadding(),
            )
            Box(Modifier.fillMaxWidth().weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                key(selected.id) {
                    saveable.SaveableStateProvider(selected.id) {
                        ProvideTab(container, selected) {
                            EditorHost(
                                container = container,
                                uri = selected.uri,
                                newKind = if (selected.uri == null) selected.newKind else null,
                                draftKey = selected.draftKey,
                                startTool = selected.tool,
                                onClose = { if (tabs.close(selected.id)) onAllClosed() },
                                onOpenOther = { opened ->
                                    tabs.open(opened)
                                    // A new presentation is created as a file first; its placeholder tab is done.
                                    if (selected.uri == null && selected.newKind == FileKind.PRESENTATION) tabs.close(selected.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (overview) TabOverview(container, onDismiss = { overview = false })
}
