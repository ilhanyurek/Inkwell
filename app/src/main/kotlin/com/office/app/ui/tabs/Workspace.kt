package com.office.app.ui.tabs

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.office.app.AppContainer
import com.office.app.ui.EditorHost
import com.office.core.FileKind

/**
 * The files the user has open, one shown at a time. Each file keeps its editor state with the open-files
 * list, so going Home and coming back, or switching files, keeps undo history and unsaved text. The editor's
 * top bar carries the number of open files, which opens the list.
 */
@Composable
fun Workspace(container: AppContainer, onAllClosed: () -> Unit, onHome: () -> Unit) {
    val tabs = container.tabs
    val selected = tabs.selected
    LaunchedEffect(selected == null) { if (selected == null) onAllClosed() }
    if (selected == null) return

    var overview by rememberSaveable { mutableStateOf(false) }
    val home by rememberUpdatedState(onHome)
    // Back leaves the files open and goes Home; edit, search and tool modes of the editor come first.
    BackHandler { home() }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) tabs.open(uri)
    }
    val actions = remember(tabs) { WorkspaceActions(tabs, onHome = { home() }, onOverview = { overview = true }) }
    // Keeps each file's scroll positions and other saved UI state while another one is shown.
    val saveable = rememberSaveableStateHolder()
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        key(selected.id) {
            val owner = remember(selected.id) { StoreOwner(tabs.viewModelStore(selected.id)) }
            saveable.SaveableStateProvider(selected.id) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalWorkspace provides actions) {
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
                                // A new presentation is created as a file first; its placeholder is done.
                                if (selected.uri == null && selected.newKind == FileKind.PRESENTATION) tabs.close(selected.id)
                            },
                        )
                    }
                }
            }
        }
    }
    if (overview) {
        TabOverview(
            container,
            onDismiss = { overview = false },
            onOpenFile = { openFile.launch(FileKind.openableMimeTypes + "*/*") },
        )
    }
}

private class StoreOwner(override val viewModelStore: ViewModelStore) : ViewModelStoreOwner
