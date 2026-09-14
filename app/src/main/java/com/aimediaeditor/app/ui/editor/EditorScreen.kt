@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.aimediaeditor.app.ui.editor

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.exoplayer.ExoPlayer
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.editor.EditorViewModel
import com.aimediaeditor.app.editor.export.CompositionBuilder
import com.aimediaeditor.app.editor.export.ExportWorker
import com.aimediaeditor.app.editor.model.AspectRatio
import com.aimediaeditor.app.editor.model.AudioTrack
import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.FilterType
import com.aimediaeditor.app.editor.model.VideoClip
import com.aimediaeditor.app.editor.model.TextOverlay
import com.aimediaeditor.app.editor.model.clipStartOffsetMs
import com.aimediaeditor.app.editor.model.ratio
import java.util.UUID

private enum class PreviewMode { CLIP, TIMELINE }

@Composable
fun EditorScreen(
    initialMedia: List<MediaItem> = emptyList(),
    existingProjectId: String? = null,
    onBack: () -> Unit,
    viewModel: EditorViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(existingProjectId) {
        if (existingProjectId != null) viewModel.loadProject(existingProjectId) else viewModel.initializeProject(initialMedia)
    }

    // Flushes any pending debounced autosave the moment the user leaves this screen,
    // by navigating back or by the app backgrounding -- both are points where losing the
    // last ~800ms debounce window would mean losing an edit (spec section 22).
    fun saveAndBack() {
        viewModel.saveNow()
        onBack()
    }

    // One ExoPlayer for this whole screen -- created once, media item swapped as the
    // selected clip changes, released exactly once on dispose. Never a second instance
    // alongside it (architecture-notes section 2.1).
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_STOP -> viewModel.saveNow()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    val selectedClip = uiState.project.clips.firstOrNull { it.id == uiState.selectedClipId }
    var previewMode by remember { mutableStateOf(PreviewMode.CLIP) }

    // Text overlays whose time window overlaps the SELECTED clip's own span on the
    // project timeline -- these are the ones it makes sense to show a drag handle for in
    // the single-clip preview, since that's the only clip currently visible there. An
    // overlay spanning multiple clips gets a handle on each one it overlaps; dragging any
    // of them moves the one underlying overlay (they all read/write the same id).
    val visibleTextOverlays = remember(uiState.project.textOverlays, selectedClip, uiState.project.clips) {
        val clip = selectedClip
        if (clip == null) {
            emptyList()
        } else {
            val clipStart = uiState.project.clipStartOffsetMs(clip.id)
            val clipEnd = clipStart + clip.durationMs
            uiState.project.textOverlays.filter { it.startMs < clipEnd && it.endMs > clipStart }
        }
    }

    LaunchedEffect(selectedClip?.id, previewMode) {
        if (previewMode != PreviewMode.CLIP) return@LaunchedEffect
        if (selectedClip != null && selectedClip.sourceType == MediaType.VIDEO) {
            exoPlayer.setMediaItem(Media3Item.fromUri(Uri.parse(selectedClip.sourceUri)))
            exoPlayer.prepare()
        } else {
            exoPlayer.stop()
        }
    }

    // Live filter preview for the selected video clip. Separate from the effect
    // above on purpose: it must NOT re-trigger setMediaItem/prepare (that would
    // restart playback from 0 every time a filter chip is tapped) -- setVideoEffects
    // can be called on its own at any time. Reuses CompositionBuilder.stackedFilterEffects
    // (the exact function export uses, for the clip's full effect stack, not just one
    // filter) rather than a second hand-written mapping, so this preview can't drift
    // from what actually gets exported.
    //
    // Honest risk: ExoPlayer.setVideoEffects(List<Effect>) is a real, documented
    // Media3 API for exactly this ("preview an effect live during playback"), but it
    // could not be confirmed against this exact 1.11.0 artifact -- Media3 is published
    // only to Google's Maven repo, which this sandbox has no network path to (same
    // constraint noted throughout this README). If this doesn't compile, that's a
    // signature/availability mismatch on this one call, not a problem with
    // stackedFilterEffects() itself (which export already exercises).
    LaunchedEffect(selectedClip?.id, selectedClip?.effects, selectedClip?.filter, previewMode) {
        if (previewMode == PreviewMode.CLIP && selectedClip?.sourceType == MediaType.VIDEO) {
            exoPlayer.setVideoEffects(CompositionBuilder.stackedFilterEffects(selectedClip.effectiveEffects()))
        }
    }

    // Same gap, for speed: SetSpeed already updated the clip's data (and export already
    // reads it via CompositionBuilder's setSpeed on the EditedMediaItem), but nothing
    // ever told the live preview's ExoPlayer to actually play faster/slower, so picking
    // a speed chip looked like it did nothing. setPlaybackSpeed is base Player API (not
    // an effects/Transformer call), so unlike setVideoEffects above this one has no
    // version-availability uncertainty.
    LaunchedEffect(selectedClip?.id, selectedClip?.speed, previewMode) {
        if (previewMode == PreviewMode.CLIP && selectedClip?.sourceType == MediaType.VIDEO) {
            exoPlayer.setPlaybackSpeed(selectedClip.speed)
        }
    }

    // Only one of the two players is ever actively prepared at a time --
    // switching modes stops whichever one is becoming inactive first,
    // so there's never a moment with two concurrent decoders running
    // (architecture-notes section 2.1, same principle as last round).
    LaunchedEffect(previewMode) {
        if (previewMode == PreviewMode.TIMELINE) {
            exoPlayer.stop()
        }
    }

    // Photos are part of the composition as of this round, so the only
    // reason to skip building one is an empty project (Media3 rejects a
    // sequence with no items).
    val timelineComposition = remember(uiState.project, previewMode) {
        if (previewMode == PreviewMode.TIMELINE && uiState.project.clips.isNotEmpty()) {
            CompositionBuilder.build(uiState.project)
        } else {
            null
        }
    }

    var showTextDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var selectedAudioTrackId by remember { mutableStateOf<String?>(null) }

    // Timeline zoom: both lanes take this as their pixelsPerSecond, so zooming affects
    // clips and audio blocks identically -- a given clip never looks a different length
    // relative to the other lane. Shared scroll state so scrolling one lane moves the
    // other too, rather than each scrolling independently.
    var zoomFactor by remember { mutableFloatStateOf(1f) }
    val pixelsPerSecond: Dp = BASE_PIXELS_PER_SECOND * zoomFactor
    val timelineScrollState = rememberScrollState()

    // Live position during "Play Timeline" playback, drawn as a playhead line on both
    // lanes below. Null (no line drawn) outside that mode -- CLIP mode has no single
    // project-wide position, just whichever moment the selected clip's own player is at.
    var timelinePositionMs by remember { mutableStateOf(0L) }

    // Where an audio track's reposition/trim drag snaps to: the start of the timeline,
    // the end of every video clip (their "seams," since clips sit back-to-back with no
    // gaps -- cumulative durationMs is exactly each clip's own start-of-next-clip point),
    // and the playhead while one is showing. Recomputed only when one of those actually
    // changes, not on every recomposition.
    val snapPointsMs = remember(uiState.project.clips, previewMode, timelinePositionMs) {
        buildList {
            add(0L)
            var acc = 0L
            uiState.project.clips.forEach { clip ->
                acc += clip.durationMs
                add(acc)
            }
            if (previewMode == PreviewMode.TIMELINE) add(timelinePositionMs)
        }
    }
    var exportRequestId by remember { mutableStateOf<UUID?>(null) }
    var exportBlockedMessage by remember { mutableStateOf<String?>(null) }
    val workManager = remember { WorkManager.getInstance(context) }
    val exportWorkInfo by (exportRequestId?.let { workManager.getWorkInfoByIdFlow(it) }
        ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextButton(onClick = { showRenameDialog = true }) {
                        Text(uiState.projectName, maxLines = 1)
                    }
                },
                navigationIcon = { TextButton(onClick = ::saveAndBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = viewModel::undo, enabled = uiState.canUndo) { Text("Undo") }
                    TextButton(onClick = viewModel::redo, enabled = uiState.canRedo) { Text("Redo") }
                    TextButton(
                        onClick = {
                            previewMode = if (previewMode == PreviewMode.CLIP) PreviewMode.TIMELINE else PreviewMode.CLIP
                        }
                    ) { Text(if (previewMode == PreviewMode.CLIP) "Play Timeline" else "Back to Editing") }
                    TextButton(
                        onClick = {
                            exportBlockedMessage = null
                            exportRequestId = viewModel.exportProject()
                        },
                        enabled = uiState.project.clips.isNotEmpty()
                    ) { Text("Export") }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            ExportStatusBar(workInfo = exportWorkInfo, blockedMessage = exportBlockedMessage)

            if (previewMode == PreviewMode.TIMELINE) {
                TimelinePreview(
                    composition = timelineComposition,
                    aspectRatio = uiState.project.aspectRatio.ratio,
                    modifier = Modifier.fillMaxWidth(),
                    onPositionChanged = { timelinePositionMs = it }
                )
            } else {
                ClipPreview(
                    clip = selectedClip,
                    exoPlayer = exoPlayer,
                    targetAspectRatio = uiState.project.aspectRatio.ratio,
                    onReframeCommitted = { focal ->
                        selectedClip?.let { viewModel.onCommand(EditCommand.SmartReframe(it.id, focal)) }
                    },
                    textOverlays = visibleTextOverlays,
                    onTextPositionCommitted = { overlayId, x, y ->
                        viewModel.onCommand(EditCommand.SetTextPosition(overlayId, x, y))
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                AspectRatioRow(
                    current = uiState.project.aspectRatio,
                    onSelect = { viewModel.onCommand(EditCommand.SetAspectRatio(it)) }
                )

                EditorToolbar(
                    canEditSelection = selectedClip != null,
                    onSplit = {
                        selectedClip?.let { clip ->
                            val splitAt = if (clip.sourceType == MediaType.VIDEO) {
                                exoPlayer.currentPosition.coerceIn(clip.trimStartMs, clip.trimEndMs)
                            } else {
                                (clip.trimStartMs + clip.trimEndMs) / 2
                            }
                            viewModel.onCommand(EditCommand.SplitClip(clip.id, splitAt))
                        }
                    },
                    onDelete = { selectedClip?.let { viewModel.onCommand(EditCommand.DeleteClip(it.id)) } },
                    onAddText = { showTextDialog = true },
                    onAddAudio = {
                        viewModel.loadAvailableAudio()
                        showAudioDialog = true
                    }
                )

                ClipStyleRow(
                    clip = selectedClip,
                    onFilterToggled = { filter ->
                        selectedClip?.let { viewModel.onCommand(EditCommand.ToggleEffect(it.id, filter)) }
                    },
                    onEffectsReordered = { ordered ->
                        selectedClip?.let { viewModel.onCommand(EditCommand.ReorderEffects(it.id, ordered)) }
                    },
                    onSpeedSelected = { speed ->
                        selectedClip?.let { viewModel.onCommand(EditCommand.SetSpeed(it.id, speed)) }
                    },
                    onVolumeSelected = { volume ->
                        selectedClip?.let { viewModel.onCommand(EditCommand.SetClipVolume(it.id, volume)) }
                    }
                )
            }

            // Always visible in both modes now (previously CLIP-mode only), so the
            // playhead has somewhere to live during Timeline playback too, and the
            // lanes/audio controls stay reachable while reviewing the whole project --
            // matches the spec's "timeline is always visible below the preview" model
            // rather than the editor swapping it out for a second full-screen mode.
            ZoomRow(zoomFactor = zoomFactor, onZoomChange = { zoomFactor = it })

            TimelineStrip(
                clips = uiState.project.clips,
                selectedClipId = uiState.selectedClipId,
                onSelect = viewModel::selectClip,
                onReorder = { viewModel.onCommand(EditCommand.ReorderClips(it)) },
                onTrimCommitted = { clipId, start, end ->
                    viewModel.onCommand(EditCommand.TrimClip(clipId, start, end))
                },
                modifier = Modifier.fillMaxWidth(),
                pixelsPerSecond = pixelsPerSecond,
                scrollState = timelineScrollState,
                playheadMs = if (previewMode == PreviewMode.TIMELINE) timelinePositionMs else null
            )

            // Drag a track left/right to reposition it, drag its right edge to trim
            // how long it plays -- see AudioTrackStrip for why this is a Box of
            // absolutely-positioned blocks rather than a Row like the video clips above.
            AudioTrackStrip(
                audioTracks = uiState.project.audioTracks,
                selectedTrackId = selectedAudioTrackId,
                projectDurationMs = uiState.project.durationMs,
                onSelect = { selectedAudioTrackId = it },
                onPositionCommitted = { trackId, start, duration ->
                    viewModel.onCommand(EditCommand.SetAudioPosition(trackId, start, duration))
                },
                modifier = Modifier.fillMaxWidth(),
                pixelsPerSecond = pixelsPerSecond,
                scrollState = timelineScrollState,
                playheadMs = if (previewMode == PreviewMode.TIMELINE) timelinePositionMs else null,
                snapPointsMs = snapPointsMs
            )

            if (previewMode == PreviewMode.CLIP) {
                OverlaysList(
                    textOverlays = uiState.project.textOverlays,
                    audioTracks = uiState.project.audioTracks,
                    onRemoveText = { viewModel.onCommand(EditCommand.RemoveTextOverlay(it)) },
                    onRemoveAudio = { viewModel.onCommand(EditCommand.RemoveAudioTrack(it)) },
                    onAudioVolume = { id, v -> viewModel.onCommand(EditCommand.SetAudioVolume(id, v)) },
                    onAudioLooping = { id, loop -> viewModel.onCommand(EditCommand.SetAudioLooping(id, loop)) }
                )
            }
        }
    }

    if (showTextDialog) {
        AddTextDialog(
            onDismiss = { showTextDialog = false },
            onConfirm = { input ->
                val startMs = selectedClip?.let { uiState.project.clipStartOffsetMs(it.id) } ?: 0L
                viewModel.onCommand(
                    EditCommand.AddText(input.text, startMs, startMs + input.durationMs, input.yPositionFraction)
                )
                showTextDialog = false
            }
        )
    }

    if (showAudioDialog) {
        AddAudioDialog(
            availableAudio = uiState.availableAudio,
            onDismiss = { showAudioDialog = false },
            onSelect = { audio ->
                val startMs = selectedClip?.let { uiState.project.clipStartOffsetMs(it.id) } ?: 0L
                viewModel.onCommand(EditCommand.AddAudio(audio.uri.toString(), startMs, 1f, audio.durationMs))
                showAudioDialog = false
            }
        )
    }

    if (showRenameDialog) {
        RenameProjectDialog(
            currentName = uiState.projectName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                viewModel.renameProject(name)
                showRenameDialog = false
            }
        )
    }
}

@Composable
private fun RenameProjectDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExportStatusBar(workInfo: WorkInfo?, blockedMessage: String?) {
    if (blockedMessage != null) {
        Text(
            blockedMessage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        )
        return
    }
    if (workInfo == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (workInfo.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("Exporting\u2026")
            }
            WorkInfo.State.SUCCEEDED -> Text("Export complete -- saved to Movies/AIMediaEditor")
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(ExportWorker.KEY_ERROR)
                Text("Export failed" + (error?.let { ": $it" } ?: ""))
            }
            WorkInfo.State.CANCELLED -> Text("Export cancelled")
            else -> {}
        }
    }
}

private val SPEED_PRESETS = listOf(0.5f to "0.5x", 1f to "1x", 2f to "2x")
private val VOLUME_PRESETS = listOf(0f to "Mute", 0.5f to "50%", 1f to "100%")

/**
 * Per-clip look and timing. Filters apply to photos and videos alike;
 * speed is video-only and the row says so rather than offering a control
 * that would be silently ignored.
 *
 * Filters are a reorderable STACK now (UX spec section 20, Tier 1), not a single
 * choice -- the chip row is multi-select (tap toggles membership, not "replace the
 * selection"), and a second row below it lets the order be changed when more than one
 * is applied. Reordering uses up/down buttons rather than drag: this area already sits
 * between two rounds' worth of real gesture-conflict bugs (audio drag, video trim), so
 * adding a new drag surface here was judged not worth the risk this round -- the same
 * reasoning the README's "Adopting the UX spec" section gives for holding back
 * pinch-to-zoom. FilterType.NONE is left out of the chip row entirely -- an empty
 * stack already means "no filter," so there's no separate sentinel chip to tap.
 */
@Composable
private fun ClipStyleRow(
    clip: VideoClip?,
    onFilterToggled: (FilterType) -> Unit,
    onEffectsReordered: (List<FilterType>) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onVolumeSelected: (Float) -> Unit
) {
    if (clip == null) return
    val appliedEffects = clip.effectiveEffects()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterType.entries.filter { it != FilterType.NONE }.forEach { filter ->
                FilterChip(
                    selected = filter in appliedEffects,
                    onClick = { onFilterToggled(filter) },
                    label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        if (appliedEffects.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Order:", modifier = Modifier.padding(end = 4.dp))
                appliedEffects.forEachIndexed { index, filter ->
                    Text(
                        "${index + 1}. ${filter.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    TextButton(
                        enabled = index > 0,
                        onClick = {
                            val reordered = appliedEffects.toMutableList().apply {
                                add(index - 1, removeAt(index))
                            }
                            onEffectsReordered(reordered)
                        }
                    ) { Text("↑") }
                    TextButton(
                        enabled = index < appliedEffects.lastIndex,
                        onClick = {
                            val reordered = appliedEffects.toMutableList().apply {
                                add(index + 1, removeAt(index))
                            }
                            onEffectsReordered(reordered)
                        }
                    ) { Text("↓") }
                }
            }
        }
        if (clip.sourceType == MediaType.VIDEO) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Speed", modifier = Modifier.padding(end = 4.dp))
                SPEED_PRESETS.forEach { (value, label) ->
                    FilterChip(
                        selected = clip.speed == value,
                        onClick = { onSpeedSelected(value) },
                        label = { Text(label) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Volume", modifier = Modifier.padding(end = 4.dp))
                VOLUME_PRESETS.forEach { (value, label) ->
                    FilterChip(
                        selected = clip.volume == value,
                        onClick = { onVolumeSelected(value) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 3f
private const val ZOOM_STEP = 0.25f

/** +/- zoom for the timeline lanes below, not pinch -- a pinch gesture over the same
 *  area the trim/reorder/reposition drags already use is exactly the kind of overlapping-
 *  gesture risk the last two rounds' bug reports came from; buttons carry none of that. */
@Composable
private fun ZoomRow(zoomFactor: Float, onZoomChange: (Float) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(
            onClick = { onZoomChange((zoomFactor - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)) },
            enabled = zoomFactor > MIN_ZOOM
        ) { Text("−") }
        Text(
            "${(zoomFactor * 100).toInt()}%",
            modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 4.dp)
        )
        TextButton(
            onClick = { onZoomChange((zoomFactor + ZOOM_STEP).coerceAtMost(MAX_ZOOM)) },
            enabled = zoomFactor < MAX_ZOOM
        ) { Text("+") }
    }
}

@Composable
private fun AspectRatioRow(current: AspectRatio, onSelect: (AspectRatio) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AspectRatio.entries.forEach { ratio ->
            FilterChip(selected = ratio == current, onClick = { onSelect(ratio) }, label = { Text(ratio.label) })
        }
    }
}

@Composable
private fun EditorToolbar(
    canEditSelection: Boolean,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onAddText: () -> Unit,
    onAddAudio: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(onClick = onSplit, enabled = canEditSelection) { Text("Split") }
        TextButton(onClick = onDelete, enabled = canEditSelection) { Text("Delete") }
        TextButton(onClick = onAddText) { Text("+ Text") }
        TextButton(onClick = onAddAudio) { Text("+ Music") }
    }
}

@Composable
private fun OverlaysList(
    textOverlays: List<TextOverlay>,
    audioTracks: List<AudioTrack>,
    onRemoveText: (String) -> Unit,
    onRemoveAudio: (String) -> Unit,
    onAudioVolume: (String, Float) -> Unit,
    onAudioLooping: (String, Boolean) -> Unit
) {
    if (textOverlays.isEmpty() && audioTracks.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        textOverlays.forEach { overlay ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("\u201C${overlay.text}\u201D", modifier = Modifier.padding(vertical = 6.dp))
                TextButton(onClick = { onRemoveText(overlay.id) }) { Text("Remove") }
            }
        }
        audioTracks.forEach { track ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Audio track", modifier = Modifier.padding(vertical = 6.dp))
                TextButton(onClick = { onRemoveAudio(track.id) }) { Text("Remove") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VOLUME_PRESETS.forEach { (value, label) ->
                    FilterChip(
                        selected = track.volume == value,
                        onClick = { onAudioVolume(track.id, value) },
                        label = { Text(label) }
                    )
                }
                FilterChip(
                    selected = track.isLooping,
                    onClick = { onAudioLooping(track.id, !track.isLooping) },
                    label = { Text("Loop") }
                )
            }
        }
    }
}
