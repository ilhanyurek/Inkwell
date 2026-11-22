package com.office.app.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.office.app.AppContainer
import com.office.app.R
import com.office.app.data.Tab
import com.office.app.ui.common.KindBadge
import com.office.core.FileKind

/** Lets an editor tell its tab its title and whether it has unsaved changes. */
class TabReporter(private val container: AppContainer, private val id: String, private val titles: MutableMap<String, String>) {
    fun report(title: String?, dirty: Boolean) {
        container.tabs.setDirty(id, dirty)
        if (title != null && titles[id] != title) titles[id] = title
    }
}

val LocalTabReporter = compositionLocalOf<TabReporter?> { null }

/** Called by editors: keeps their tab's title and unsaved-changes dot up to date. */
@Composable
fun ReportTab(title: String?, dirty: Boolean) {
    val reporter = LocalTabReporter.current ?: return
    LaunchedEffect(reporter, title, dirty) { reporter.report(title, dirty) }
}

/** Titles of the open tabs, filled in by their editors. */
class TabTitles {
    val map = mutableStateMapOf<String, String>()
}

@Composable
fun ProvideTab(container: AppContainer, tab: Tab, titles: TabTitles, content: @Composable () -> Unit) {
    val reporter = remember(tab.id) { TabReporter(container, tab.id, titles.map) }
    CompositionLocalProvider(LocalTabReporter provides reporter, content = content)
}

/** Desktop-style tabs: one per open file, the selected one raised, "+" to open or create another. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabStrip(
    container: AppContainer,
    titles: TabTitles,
    onNew: (FileKind) -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = container.tabs
    val selected = tabs.selected?.id
    val untitled = stringResource(R.string.untitled)
    var menuFor by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(44.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 6.dp), verticalAlignment = Alignment.Bottom) {
                for (tab in tabs.tabs) {
                    val isSelected = tab.id == selected
                    val dirty = tabs.isDirty(tab.id)
                    val kind = tab.newKind ?: FileKind.detect(titles.map[tab.id], null) ?: FileKind.DOCUMENT
                    val title = titles.map[tab.id] ?: tab.uri?.lastPathSegment?.substringAfterLast('/') ?: untitled
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(end = 4.dp, top = 6.dp)
                                .height(38.dp)
                                .widthIn(min = 96.dp, max = 200.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                                )
                                .combinedClickable(onClick = { tabs.select(tab.id) }, onLongClick = { menuFor = tab.id })
                                .padding(start = 8.dp)
                                .testTag("tab_${tab.id}"),
                        ) {
                            KindBadge(kind, size = 20.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (dirty) {
                                // Unsaved: closed from inside the tab, where saving is offered.
                                Box(Modifier.padding(horizontal = 12.dp).size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            } else {
                                IconButton(onClick = { tabs.close(tab.id) }, modifier = Modifier.size(32.dp).testTag("tab_close_${tab.id}")) {
                                    Icon(Icons.Default.Close, stringResource(R.string.tab_close), Modifier.size(16.dp))
                                }
                            }
                        }
                        DropdownMenu(expanded = menuFor == tab.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tab_close)) },
                                onClick = {
                                    menuFor = null
                                    if (tabs.isDirty(tab.id)) tabs.select(tab.id) else tabs.close(tab.id)
                                },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.tab_close_others)) }, onClick = { menuFor = null; tabs.closeOthers(tab.id) })
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { addMenu = true }, modifier = Modifier.testTag("tab_new")) {
                    Icon(Icons.Default.Add, stringResource(R.string.tab_new))
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_open_file)) }, onClick = { addMenu = false; onOpenFile() })
                    HorizontalDivider()
                    listOf(
                        FileKind.DOCUMENT to R.string.new_document,
                        FileKind.SPREADSHEET to R.string.new_sheet,
                        FileKind.PRESENTATION to R.string.new_presentation,
                        FileKind.MARKDOWN to R.string.new_note,
                        FileKind.TEXT to R.string.new_text,
                    ).forEach { (kind, label) ->
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
}
