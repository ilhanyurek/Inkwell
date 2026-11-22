package com.office.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.office.app.AppContainer
import com.office.app.ui.home.HomeScreen
import com.office.app.ui.settings.SettingsScreen
import com.office.core.FileKind

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val OPEN = "open?uri={uri}&tool={tool}"
    const val TOOL = "tool/{id}"
    const val NEW = "new/{kind}"
    const val DRAFT = "draft/{key}"
    const val FOLDER = "folder?uri={uri}&name={name}"
    const val WORKSPACE = "workspace"

    fun open(uri: Uri, tool: String? = null) = "open?uri=" + Uri.encode(uri.toString()) + (tool?.let { "&tool=$it" } ?: "")
    fun tool(id: com.office.app.ui.tools.ToolId) = "tool/${id.name}"
    fun new(kind: FileKind) = "new/${kind.name}"
    fun draft(key: String) = "draft/$key"
    fun folder(uri: Uri, name: String) = "folder?uri=" + Uri.encode(uri.toString()) + "&name=" + Uri.encode(name)
}

@Composable
fun AppNavHost(
    container: AppContainer,
    incomingUri: Uri?,
    onIncomingConsumed: () -> Unit,
    incomingShortcut: String? = null,
    onShortcutConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    // Home tab asked for by a launcher shortcut; HomeScreen consumes it.
    var requestedHomeTab by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(incomingShortcut) {
        when (incomingShortcut) {
            null -> return@LaunchedEffect
            "new_document" -> nav.navigate(Routes.new(FileKind.DOCUMENT)) { popUpTo(Routes.HOME) }
            "new_sheet" -> nav.navigate(Routes.new(FileKind.SPREADSHEET)) { popUpTo(Routes.HOME) }
            "scan" -> nav.navigate(Routes.tool(com.office.app.ui.tools.ToolId.SCAN)) { popUpTo(Routes.HOME) }
            "tools" -> {
                nav.popBackStack(Routes.HOME, inclusive = false)
                requestedHomeTab = 2
            }
        }
        onShortcutConsumed()
    }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            nav.navigate(Routes.open(incomingUri)) { launchSingleTop = false }
            onIncomingConsumed()
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onOpen = { nav.navigate(Routes.open(it)) },
                onNew = { nav.navigate(Routes.new(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenDraft = { nav.navigate(Routes.draft(it)) },
                onOpenFolder = { uri, name -> nav.navigate(Routes.folder(uri, name)) },
                onTool = { nav.navigate(Routes.tool(it)) },
                requestedTab = requestedHomeTab,
                onTabRequestHandled = { requestedHomeTab = null },
            )
        }
        composable(Routes.TOOL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = runCatching { com.office.app.ui.tools.ToolId.valueOf(entry.arguments?.getString("id").orEmpty()) }.getOrNull()
            if (id == null) LaunchedEffect(Unit) { nav.popBackStack() }
            else com.office.app.ui.tools.ToolScreen(
                container = container,
                tool = id,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.open(it)) },
                onOpenInViewer = { uri, t -> nav.navigate(Routes.open(uri, t)) { popUpTo(Routes.TOOL) { inclusive = true } } },
            )
        }
        composable(Routes.DRAFT, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            val draft = container.drafts.get(key)
            when {
                draft == null -> LaunchedEffect(key) { nav.popBackStack() }
                // Drafts of existing files open the file; the editor then offers to restore the draft.
                else -> LaunchedEffect(key) {
                    container.tabs.openDraft(key, draft.uri, draft.kind)
                    toWorkspace(nav, Routes.DRAFT)
                }
            }
        }
        composable(
            Routes.FOLDER,
            arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            com.office.app.ui.folders.FolderScreen(
                container = container,
                folder = Uri.parse(entry.arguments?.getString("uri").orEmpty()),
                title = entry.arguments?.getString("name").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenFile = { nav.navigate(Routes.open(it)) },
                onOpenFolder = { uri, name -> nav.navigate(Routes.folder(uri, name)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container = container, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.OPEN,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("tool") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val uri = Uri.parse(entry.arguments?.getString("uri").orEmpty())
            val tool = entry.arguments?.getString("tool")
            LaunchedEffect(uri) {
                container.tabs.open(uri, tool)
                toWorkspace(nav, Routes.OPEN)
            }
        }
        composable(
            Routes.NEW,
            arguments = listOf(navArgument("kind") { type = NavType.StringType }),
        ) { entry ->
            val kind = runCatching { FileKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(FileKind.DOCUMENT)
            LaunchedEffect(kind) {
                container.tabs.openNew(kind)
                toWorkspace(nav, Routes.NEW)
            }
        }
        composable(Routes.WORKSPACE) {
            com.office.app.ui.tabs.Workspace(
                container = container,
                onAllClosed = { nav.popBackStack(Routes.WORKSPACE, inclusive = true) },
            )
        }
    }
}

/**
 * Shows the workspace in place of the route that asked for a file. The workspace entry is reused when it is
 * already on the back stack, so the other open files keep their state.
 */
private fun toWorkspace(nav: androidx.navigation.NavHostController, from: String) {
    val inStack = runCatching { nav.getBackStackEntry(Routes.WORKSPACE) }.isSuccess
    if (inStack) {
        nav.popBackStack(Routes.WORKSPACE, inclusive = false)
    } else {
        nav.navigate(Routes.WORKSPACE) {
            popUpTo(from) { inclusive = true }
            launchSingleTop = true
        }
    }
}
