package com.office.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.OpenTabs
import com.office.app.data.Tab
import com.office.app.ui.common.KindBadge
import com.office.app.ui.design.CardDivider
import com.office.app.ui.design.CardGroup
import com.office.app.ui.design.ListRow
import com.office.app.ui.design.cardBorder
import com.office.app.ui.theme.LocalAppearance
import com.office.core.FileKind

/** Lets an editor tell its tab its title and whether it has unsaved changes. */
class TabReporter(val tabs: OpenTabs, val id: String) {
    fun report(title: String?, dirty: Boolean) {
        tabs.setDirty(id, dirty)
        if (title != null) tabs.setTitle(id, title)
    }
}

val LocalTabReporter = compositionLocalOf<TabReporter?> { null }

/** Called by editors: keeps their tab's title and unsaved-changes dot up to date. */
@Composable
fun ReportTab(title: String?, dirty: Boolean) {
    val reporter = LocalTabReporter.current ?: return
    LaunchedEffect(reporter, title, dirty) { reporter.report(title, dirty) }
}

/**
 * Called by editors with their own close flow (the one behind the back button): it runs when the tab's ×
 * is pressed while the tab has unsaved changes, so the editor can offer to save first.
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

/**
 * Browser-style tabs: Home on the left, one rounded tab per open file (the selected one raised as a card),
 * then the number of open files, which opens the overview, and "+" to open or create another.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabStrip(
    container: AppContainer,
    onHome: () -> Unit,
    onNew: (FileKind) -> Unit,
    onOpenFile: () -> Unit,
    onOverview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = container.tabs
    val selected = tabs.selected?.id
    var menuFor by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(selected, tabs.tabs.size) {
        val i = tabs.tabs.indexOfFirst { it.id == selected }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    val black = LocalAppearance.current.black

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .height(52.dp)
            .padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onHome, modifier = Modifier.testTag("tab_home")) {
            Icon(Icons.Rounded.Home, contentDescription = stringResource(R.string.tabs_home))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            // Few tabs share the width; many keep a readable width and scroll.
            val count = tabs.tabs.size.coerceAtLeast(1)
            val tabWidth = ((maxWidth - 4.dp * count) / count).coerceIn(104.dp, 220.dp)
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(tabs.tabs, key = { _, t -> t.id }) { _, tab ->
                    val isSelected = tab.id == selected
                    val dirty = tabs.isDirty(tab.id)
                    Box {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
                            shadowElevation = if (isSelected && !black) 2.dp else 0.dp,
                            border = if (isSelected) cardBorder() else null,
                            modifier = Modifier
                                .height(40.dp)
                                .width(tabWidth)
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(onClick = { tabs.select(tab.id) }, onLongClick = { menuFor = tab.id })
                                .testTag("tab_${tab.id}"),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                                KindBadge(tabs.kindOf(tab), size = 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    tabTitle(tabs, tab),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (dirty) {
                                    Box(
                                        Modifier
                                            .padding(start = 6.dp)
                                            .size(7.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                                            .testTag("tab_dirty_${tab.id}"),
                                    )
                                }
                                IconButton(
                                    onClick = { tabs.requestClose(tab.id) },
                                    modifier = Modifier.size(34.dp).testTag("tab_close_${tab.id}"),
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        stringResource(R.string.tab_close),
                                        Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        DropdownMenu(expanded = menuFor == tab.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tab_close)) },
                                onClick = { menuFor = null; tabs.requestClose(tab.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tab_close_others)) },
                                onClick = { menuFor = null; tabs.closeOthers(tab.id) },
                            )
                        }
                    }
                }
            }
        }
        TabCount(tabs.tabs.size, onClick = onOverview)
        Box {
            IconButton(onClick = { addMenu = true }, modifier = Modifier.testTag("tab_new")) {
                Icon(Icons.Rounded.Add, stringResource(R.string.tab_new))
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_open_file)) }, onClick = { addMenu = false; onOpenFile() })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NEW_KINDS.forEach { (kind, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { KindBadge(kind, size = 24.dp) },
                        onClick = { addMenu = false; onNew(kind) },
                    )
                }
            }
        }
    }
}

private val NEW_KINDS = listOf(
    FileKind.DOCUMENT to R.string.new_document,
    FileKind.SPREADSHEET to R.string.new_sheet,
    FileKind.PRESENTATION to R.string.new_presentation,
    FileKind.MARKDOWN to R.string.new_note,
    FileKind.TEXT to R.string.new_text,
)

/** A small outlined box with the number of open files, as in browsers. */
@Composable
private fun TabCount(count: Int, onClick: () -> Unit) {
    val label = stringResource(R.string.tabs_overview)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickableCompat(onClick)
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

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = combinedClickable(onClick = onClick)

/** All open files as cards: pick one, close one, or close every file without unsaved changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabOverview(container: AppContainer, onDismiss: () -> Unit) {
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
                        trailing = {
                            IconButton(onClick = {
                                if (dirty) onDismiss()
                                tabs.requestClose(tab.id)
                            }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.tab_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
            if (tabs.tabs.any { !tabs.isDirty(it.id) }) {
                OutlinedButton(
                    onClick = { tabs.closeSaved(); onDismiss() },
                    shape = CircleShape,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp).testTag("tabs_close_saved"),
                ) { Text(stringResource(R.string.tabs_close_saved)) }
            }
        }
    }
}
