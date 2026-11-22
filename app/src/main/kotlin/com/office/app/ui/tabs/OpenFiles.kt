package com.office.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.OpenTabs
import com.office.app.data.Tab
import com.office.app.ui.common.KindBadge
import com.office.app.ui.design.BackChevron
import com.office.app.ui.design.CardDivider
import com.office.app.ui.design.CardGroup
import com.office.app.ui.design.ListRow

/** Lets an editor tell its open file its title and whether it has unsaved changes. */
class TabReporter(val tabs: OpenTabs, val id: String) {
    fun report(title: String?, dirty: Boolean) {
        tabs.setDirty(id, dirty)
        if (title != null) tabs.setTitle(id, title)
    }
}

val LocalTabReporter = compositionLocalOf<TabReporter?> { null }

/** What an editor shown among the open files can do besides its own work. */
class WorkspaceActions(val tabs: OpenTabs, val onHome: () -> Unit, val onOverview: () -> Unit)

val LocalWorkspace = staticCompositionLocalOf<WorkspaceActions?> { null }

/** True for an editor shown among the open files, where leaving it does not close the file. */
@Composable
fun inWorkspace(): Boolean = LocalWorkspace.current != null

/** Called by editors: keeps their file's title and unsaved-changes note up to date. */
@Composable
fun ReportTab(title: String?, dirty: Boolean) {
    val reporter = LocalTabReporter.current ?: return
    LaunchedEffect(reporter, title, dirty) { reporter.report(title, dirty) }
}

/**
 * Called by editors with their own close flow: it runs when the file is closed from the open-files list
 * while it has unsaved changes, so the editor can offer to save first.
 */
@Composable
fun TabCloseRequests(onCloseRequest: () -> Unit) {
    val reporter = LocalTabReporter.current ?: return
    val handler by rememberUpdatedState(onCloseRequest)
    val pending = reporter.tabs.pendingClose
    LaunchedEffect(pending) {
        if (pending == reporter.id) {
            reporter.tabs.pendingClose = null
            handler()
        }
    }
}

@Composable
fun ProvideTab(container: AppContainer, tab: Tab, content: @Composable () -> Unit) {
    val reporter = remember(tab.id) { TabReporter(container.tabs, tab.id) }
    CompositionLocalProvider(LocalTabReporter provides reporter, content = content)
}

@Composable
fun tabTitle(tabs: OpenTabs, tab: Tab): String =
    tabs.title(tab.id) ?: tab.uri?.lastPathSegment?.substringAfterLast('/') ?: stringResource(R.string.untitled)

/** The editor's back chevron: among the open files it goes Home and the file stays open. */
@Composable
fun EditorBack(onCloseFile: () -> Unit) {
    val ws = LocalWorkspace.current
    BackChevron(onBack = ws?.onHome ?: onCloseFile)
}

/** The number of open files in an outlined box, as in browsers; opens the list of open files. */
@Composable
fun OpenFilesButton() {
    val ws = LocalWorkspace.current ?: return
    val label = stringResource(R.string.tabs_overview)
    val count = ws.tabs.tabs.size
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = ws.onOverview)
            .semantics { contentDescription = label }
            .testTag("tab_count"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .border(BorderStroke(1.8.dp, MaterialTheme.colorScheme.onSurface), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (count > 99) "∞" else count.toString(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** All open files as cards: switch to one, close one, open another, or close every saved one. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TabOverview(container: AppContainer, onDismiss: () -> Unit, onOpenFile: () -> Unit) {
    val tabs = container.tabs
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.home_open_tabs, tabs.tabs.size),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
            )
            val selected = tabs.selected?.id
            CardGroup(Modifier.testTag("tab_overview")) {
                tabs.tabs.toList().forEachIndexed { i, tab ->
                    if (i > 0) CardDivider()
                    val dirty = tabs.isDirty(tab.id)
                    ListRow(
                        title = tabTitle(tabs, tab),
                        subtitle = if (dirty) stringResource(R.string.tabs_unsaved) else null,
                        subtitleColor = MaterialTheme.colorScheme.primary,
                        titleColor = if (tab.id == selected) MaterialTheme.colorScheme.primary else null,
                        leading = { KindBadge(tabs.kindOf(tab)) },
                        onClick = { tabs.select(tab.id); onDismiss() },
                        modifier = Modifier.testTag("tab_${tab.id}"),
                        trailing = {
                            IconButton(
                                onClick = {
                                    // A file with unsaved changes is shown first, where its editor asks about saving.
                                    if (dirty) onDismiss()
                                    tabs.requestClose(tab.id)
                                },
                                modifier = Modifier.testTag("tab_close_${tab.id}"),
                            ) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.tab_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
            FlowRow(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onDismiss(); onOpenFile() }, shape = CircleShape) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.action_open_file), modifier = Modifier.padding(start = 8.dp))
                }
                if (tabs.tabs.any { !tabs.isDirty(it.id) }) {
                    OutlinedButton(
                        onClick = { tabs.closeSaved(); onDismiss() },
                        shape = CircleShape,
                        modifier = Modifier.testTag("tabs_close_saved"),
                    ) { Text(stringResource(R.string.tabs_close_saved)) }
                }
            }
        }
    }
}
