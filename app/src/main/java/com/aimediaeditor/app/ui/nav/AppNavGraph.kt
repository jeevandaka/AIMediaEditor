package com.aimediaeditor.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.ui.editor.EditorScreen
import com.aimediaeditor.app.ui.home.HomeScreen

private object Routes {
    const val HOME = "home"
    const val EDITOR = "editor"
}

/**
 * Holds the media items selected on Home across the single navigation hop
 * into the editor. Deliberately not passed as a nav argument: MediaItem
 * carries a Uri, and Parcelable/JSON-encoding it just to cross one
 * in-process navigation call is overhead this app doesn't need -- this
 * holder's lifetime is scoped to the graph (cleared implicitly whenever
 * the process restarts, same as any other in-memory nav state would be).
 */
private class PendingProjectHolder {
    var items: List<MediaItem> = emptyList()
}

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    val pending = remember { PendingProjectHolder() }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onCreateProject = { items ->
                    pending.items = items
                    navController.navigate(Routes.EDITOR)
                }
            )
        }
        composable(Routes.EDITOR) {
            EditorScreen(
                initialMedia = pending.items,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
