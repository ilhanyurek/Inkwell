package com.office.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    fun open(uri: Uri, tool: String? = null) = "open?uri=" + Uri.encode(uri.toString()) + (tool?.let { "&tool=$it" } ?: "")
    fun tool(id: com.office.app.ui.tools.ToolId) = "tool/${id.name}"
    fun new(kind: FileKind) = "new/${kind.name}"
    fun draft(key: String) = "draft/$key"
    fun folder(uri: Uri, name: String) = "folder?uri=" + Uri.encode(uri.toString()) + "&name=" + Uri.encode(name)
}

@Composable
fun AppNavHost(container: AppContainer, incomingUri: Uri?, onIncomingConsumed: () -> Unit) {
    val nav = rememberNavController()

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
                onOpenInViewer = { uri, t -> nav.navigate(Routes.open(uri, t)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.DRAFT, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            val draft = container.drafts.get(key)
            when {
                draft == null -> LaunchedEffect(key) { nav.popBackStack() }
                // Drafts of existing files open the file; the editor then offers to restore the draft.
                draft.uri != null -> EditorHost(container, draft.uri, null, onClose = { nav.popBackStack() }, onOpenOther = { nav.navigate(Routes.open(it)) })
                else -> EditorHost(container, null, draft.kind, onClose = { nav.popBackStack() }, onOpenOther = { nav.navigate(Routes.open(it)) }, draftKey = key)
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
            EditorHost(
                container = container,
                uri = uri,
                newKind = null,
                startTool = entry.arguments?.getString("tool"),
                onClose = { nav.popBackStack() },
                onOpenOther = { nav.navigate(Routes.open(it)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(
            Routes.NEW,
            arguments = listOf(navArgument("kind") { type = NavType.StringType }),
        ) { entry ->
            val kind = runCatching { FileKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(FileKind.DOCUMENT)
            EditorHost(
                container = container,
                uri = null,
                newKind = kind,
                onClose = { nav.popBackStack() },
                onOpenOther = { nav.navigate(Routes.open(it)) { popUpTo(Routes.HOME) } },
            )
        }
    }
}
