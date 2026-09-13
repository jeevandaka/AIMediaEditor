package com.aimediaeditor.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.data.project.ProjectSummary
import com.aimediaeditor.app.ui.permissions.rememberMediaPermissionState
import com.aimediaeditor.app.ui.projects.ProjectThumbnail

/**
 * Interaction model (changed this round -- previously a single tap toggled
 * selection directly):
 * - Normal mode: tap opens a full-screen preview (MediaPreviewDialog), which
 *   is where "Add to Project" for a single item lives now.
 * - Long press: enters selection mode, selecting the long-pressed item.
 * - Selection mode: taps toggle selection instead of opening the preview;
 *   deselecting the last item exits selection mode automatically, same as
 *   the explicit close (X) action in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onCreateProject: (List<MediaItem>) -> Unit = {},
    onOpenProject: (String) -> Unit = {},
    onOpenProjects: () -> Unit = {}
) {
    val permissionState = rememberMediaPermissionState()
    val uiState by viewModel.uiState.collectAsState()
    val recentProjects by viewModel.recentProjects.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshRecentProjects() }

    // View-only state, not persisted -- doesn't survive rotation yet (see
    // README known limitations), same trade-off as before this round.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var previewItem by remember { mutableStateOf<MediaItem?>(null) }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds = emptySet()
    }

    LaunchedEffect(permissionState.isGranted) {
        if (permissionState.isGranted) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        TextButton(onClick = { exitSelectionMode() }) { Text("\u2715") }
                    }
                )
            } else {
                TopAppBar(title = { Text("AI Media Editor") })
            }
        },
        floatingActionButton = {
            val loaded = (uiState as? HomeUiState.Loaded)?.items.orEmpty()
            if (selectionMode && selectedIds.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onCreateProject(loaded.filter { it.id in selectedIds })
                        exitSelectionMode()
                    }
                ) {
                    Text("Add to Project (${selectedIds.size})")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!selectionMode && recentProjects.isNotEmpty()) {
                RecentProjectsSection(
                    projects = recentProjects,
                    onOpenProject = onOpenProject,
                    onSeeAll = onOpenProjects
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val state = uiState) {
                is HomeUiState.NoPermission -> PermissionRequest(onRequest = permissionState.request)
                is HomeUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is HomeUiState.Loaded -> MediaGrid(
                    items = state.items,
                    selectedIds = selectedIds,
                    selectionMode = selectionMode,
                    onTap = { item ->
                        if (selectionMode) {
                            val next = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                            selectedIds = next
                            if (next.isEmpty()) selectionMode = false
                        } else {
                            previewItem = item
                        }
                    },
                    onLongPress = { item ->
                        selectionMode = true
                        selectedIds = selectedIds + item.id
                    }
                )
            }
            }
        }
    }

    previewItem?.let { item ->
        MediaPreviewDialog(
            item = item,
            onDismiss = { previewItem = null },
            onAddToProject = {
                onCreateProject(listOf(item))
                previewItem = null
            }
        )
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Your media. Your AI editor.",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "We read your photos and videos on this device to help you " +
                "find and edit them. Nothing is uploaded just to browse or search.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onRequest) {
            Text("Allow access to photos & videos")
        }
    }
}

@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onTap: (MediaItem) -> Unit,
    onLongPress: (MediaItem) -> Unit
) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "No photos or videos found on this device yet.",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp)
    ) {
        items(items, key = { it.id }) { item ->
            MediaThumbnail(
                item = item,
                isSelected = item.id in selectedIds,
                selectionMode = selectionMode,
                onTap = { onTap(item) },
                onLongPress = { onLongPress(item) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(
    item: MediaItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .background(Color.DarkGray, RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    ) {
        if (item.type == MediaType.IMAGE) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Real decoded video frames need Media3/coil-video wiring --
            // deferred, see README.
            Text(
                text = "\u25B6",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (selectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSelected) Color.Black.copy(alpha = 0.35f) else Color.Transparent)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(
                        color = if (isSelected) Color(0xFFE94560) else Color.Black.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
            ) {
                if (isSelected) {
                    Text(
                        text = "\u2713",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/** Spec section 3's "Projects: Show recent editable projects" -- kept compact, full management (rename/delete) lives on [com.aimediaeditor.app.ui.projects.ProjectsScreen]. */
@Composable
private fun RecentProjectsSection(
    projects: List<ProjectSummary>,
    onOpenProject: (String) -> Unit,
    onSeeAll: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Projects", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            lazyRowItems(projects, key = { it.id }) { project ->
                Column(
                    modifier = Modifier
                        .width(96.dp)
                        .clickable { onOpenProject(project.id) }
                ) {
                    ProjectThumbnail(project, modifier = Modifier.size(96.dp))
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
