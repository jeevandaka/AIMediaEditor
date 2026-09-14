package com.aimediaeditor.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.data.settings.ModelAccessTokenStore
import com.aimediaeditor.app.ui.settings.ModelDownloadDialog

/**
 * Spec section 3's "AI Search" entry, section 5's example queries ("Find pictures from
 * Goa," "Find my best portrait photos," ...). "Stage 1" of the search/select/assemble
 * chain (deterministic local matching, see [MediaSearchViewModel]'s doc comment) --
 * selecting results here and tapping "Add to Project" hands off to the same manual
 * editor entry point Home's own selection flow uses, so nothing downstream needs to
 * know results came from a search instead of the plain grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSearchScreen(
    onBack: () -> Unit,
    onCreateProject: (List<MediaItem>) -> Unit,
    viewModel: MediaSearchViewModel = viewModel()
) {
    val context = LocalContext.current
    val tokenStore = remember { ModelAccessTokenStore(context) }
    var query by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showModelDownloadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshModelReady() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Find my best travel photos...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { viewModel.search(query) }, enabled = query.isNotBlank()) {
                        Text("Search")
                    }
                    // "Stage 2" -- runs the same query through the on-device model
                    // (MediaSearchViewModel.aiSearch) instead of Stage 1's keyword
                    // matching, once the model is downloaded; before that, the same
                    // button opens the download flow instead of searching.
                    TextButton(
                        onClick = {
                            if (modelState.isReady) viewModel.aiSearch(query) else showModelDownloadDialog = true
                        },
                        enabled = query.isNotBlank() || !modelState.isReady
                    ) {
                        Text(if (modelState.isReady) "AI Search" else "AI Search (setup)")
                    }
                }
            )
        },
        floatingActionButton = {
            val results = (uiState as? SearchUiState.Results)?.items.orEmpty()
            if (selectedIds.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onCreateProject(results.filter { it.id in selectedIds })
                        selectedIds = emptySet()
                    }
                ) {
                    Text("Add to Project (${selectedIds.size})")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is SearchUiState.Idle -> SearchHint()
                is SearchUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SearchUiState.Results -> {
                    if (state.items.isEmpty()) {
                        Text(
                            state.assistantMessage
                                ?: "No matches for “${state.query}” yet — indexing may " +
                                    "still be catching up in the background, or try a different phrase.",
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                    } else {
                        SearchResultGrid(
                            items = state.items,
                            selectedIds = selectedIds,
                            onToggle = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
                        )
                    }
                }
            }
        }
    }

    if (showModelDownloadDialog) {
        ModelDownloadDialog(
            isModelReady = modelState.isReady,
            isDownloading = modelState.isDownloading,
            progress = modelState.progress,
            error = modelState.error,
            initialToken = tokenStore.getToken().orEmpty(),
            onDismiss = { showModelDownloadDialog = false },
            onDownload = { token ->
                tokenStore.setToken(token)
                viewModel.downloadModel(token)
            }
        )
    }
}

/** Not private-scoped to Box's own lambda since it's a separate composable function --
 *  BoxScope.align() is only reachable when the receiver is threaded through explicitly. */
@Composable
private fun BoxScope.SearchHint() {
    Text(
        "Try: “my best travel photos”, “photos from Goa”, " +
            "“videos of people”, “my best portrait photos”",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.align(Alignment.Center).padding(24.dp)
    )
}

/**
 * Simplified relative of [com.aimediaeditor.app.ui.home.HomeScreen]'s own media grid --
 * this screen's selection is always active (there's no browse-only mode to enter first),
 * so every tap toggles selection directly instead of requiring a long-press first.
 */
@Composable
private fun SearchResultGrid(
    items: List<MediaItem>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id }) { item ->
            SearchResultThumbnail(
                item = item,
                selected = item.id in selectedIds,
                onClick = { onToggle(item.id) }
            )
        }
    }
}

@Composable
private fun SearchResultThumbnail(
    item: MediaItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (item.type == MediaType.VIDEO) {
            Text(
                "▶",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        if (selected) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Text(
                    "✓",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
