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
    const val OPEN = "open?uri={uri}"
    const val NEW = "new/{kind}"

    fun open(uri: Uri) = "open?uri=" + Uri.encode(uri.toString())
    fun new(kind: FileKind) = "new/${kind.name}"
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
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container = container, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.OPEN,
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { entry ->
            val uri = Uri.parse(entry.arguments?.getString("uri").orEmpty())
            EditorHost(
                container = container,
                uri = uri,
                newKind = null,
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
