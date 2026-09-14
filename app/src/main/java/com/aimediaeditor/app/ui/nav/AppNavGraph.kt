package com.aimediaeditor.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.ui.editor.EditorScreen
import com.aimediaeditor.app.ui.home.HomeScreen
import com.aimediaeditor.app.ui.projects.ProjectsScreen
import com.aimediaeditor.app.ui.search.MediaSearchScreen

private object Routes {
    const val HOME = "home"
    const val EDITOR_NEW = "editor/new"
    const val EDITOR_EXISTING_PATTERN = "editor/existing/{projectId}"
    const val PROJECTS = "projects"
    const val SEARCH = "search"

    fun editorExisting(projectId: String) = "editor/existing/$projectId"
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
                    navController.navigate(Routes.EDITOR_NEW)
                },
                onOpenProject = { projectId -> navController.navigate(Routes.editorExisting(projectId)) },
                onOpenProjects = { navController.navigate(Routes.PROJECTS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) }
            )
        }
        composable(Routes.SEARCH) {
            MediaSearchScreen(
                onBack = { navController.popBackStack() },
                onCreateProject = { items ->
                    pending.items = items
                    navController.navigate(Routes.EDITOR_NEW)
                }
            )
        }
        composable(Routes.EDITOR_NEW) {
            EditorScreen(
                initialMedia = pending.items,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.EDITOR_EXISTING_PATTERN,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId")
            EditorScreen(
                existingProjectId = projectId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.PROJECTS) {
            ProjectsScreen(
                onOpenProject = { projectId -> navController.navigate(Routes.editorExisting(projectId)) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
