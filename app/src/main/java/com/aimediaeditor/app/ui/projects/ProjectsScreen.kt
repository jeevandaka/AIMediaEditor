package com.aimediaeditor.app.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import com.aimediaeditor.app.data.project.ProjectSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel = viewModel(),
    onOpenProject: (String) -> Unit,
    onBack: () -> Unit
) {
    val projects by viewModel.projects.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                projects.isEmpty() -> Text(
                    "No projects yet. Select some photos or videos on Home and tap Create Project.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                else -> LazyColumn {
                    items(projects, key = { it.id }) { summary ->
                        ProjectListItem(
                            summary = summary,
                            onClick = { onOpenProject(summary.id) },
                            onDelete = { pendingDelete = summary }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${toDelete.name}\"?") },
            text = { Text("This can't be undone. The original photos and videos are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(toDelete.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

/** Shared by [ProjectsScreen] and Home's "recent projects" row so the two never drift apart. */
@Composable
fun ProjectListItem(
    summary: ProjectSummary,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProjectThumbnail(summary, modifier = Modifier.size(56.dp))
        Column(modifier = Modifier.padding(horizontal = 12.dp).weight(1f)) {
            Text(summary.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${summary.clipCount} clip${if (summary.clipCount == 1) "" else "s"} · " +
                    "${formatDuration(summary.durationMs)} · ${formatRelativeTime(summary.updatedAtMs)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Text("✕", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun ProjectThumbnail(summary: ProjectSummary, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.DarkGray, RoundedCornerShape(6.dp))
    ) {
        when {
            summary.thumbnailUri == null -> Unit
            // No decoded video frame yet (see README known limitations) -- a play glyph
            // is honest about that; attempting an image load here would just show blank.
            summary.thumbnailIsVideo -> Text(
                "▶",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            else -> AsyncImage(
                model = summary.thumbnailUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

private fun formatRelativeTime(atMs: Long): String {
    val diffMs = System.currentTimeMillis() - atMs
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(atMs))
    }
}
