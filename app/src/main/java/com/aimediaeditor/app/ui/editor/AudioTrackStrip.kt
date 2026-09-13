package com.aimediaeditor.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aimediaeditor.app.editor.model.AudioTrack
import com.aimediaeditor.app.editor.model.effectiveDurationMs

private const val MIN_AUDIO_DURATION_MS = 300L

/**
 * A draggable lane for audio tracks, one block per track, positioned by [AudioTrack.startMs]
 * and sized by its effective duration -- rather than the fixed volume/loop-preset-only
 * controls that existed before ("attach it at a specific place" wasn't otherwise possible
 * without deleting and re-adding). Uses the exact same [PIXELS_PER_SECOND] scale as
 * [TimelineStrip]'s video row so a given timestamp lines up at the same horizontal offset
 * in both, even though the two currently scroll independently (see README) rather than in
 * a synced lockstep.
 *
 * Unlike [TimelineStrip]'s clips, tracks here are NOT laid out back-to-back in a Row --
 * each is absolutely positioned by its own startMs, since two tracks can legitimately
 * overlap or leave gaps. That's why this is a Box of offset children, not a Row.
 */
@Composable
fun AudioTrackStrip(
    audioTracks: List<AudioTrack>,
    selectedTrackId: String?,
    projectDurationMs: Long,
    onSelect: (String) -> Unit,
    onPositionCommitted: (trackId: String, startMs: Long, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (audioTracks.isEmpty()) return

    val density = LocalDensity.current
    val pixelsPerMs = with(density) { PIXELS_PER_SECOND.toPx() } / 1000f
    val totalWidth: Dp = with(density) {
        (projectDurationMs.coerceAtLeast(1000L) * pixelsPerMs).toDp()
    }

    // Two nested boxes on purpose: the outer one is the scrollable VIEWPORT, sized by
    // whatever its own parent gives it (fillMaxWidth from EditorScreen) -- that's what
    // horizontalScroll needs to know how much of the content is actually visible. The
    // inner one is the full-timeline-width CONTENT area the tracks are positioned within.
    // Putting an explicit width on the SAME box as horizontalScroll (as an earlier version
    // of this file did) collapses that distinction -- the "viewport" and "content" width
    // become the same box, which is what made every drag on it fail to register correctly.
    Box(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .height(48.dp)
            .padding(vertical = 4.dp)
    ) {
        Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
            audioTracks.forEach { track ->
                AudioTrackBlock(
                    track = track,
                    isSelected = track.id == selectedTrackId,
                    projectDurationMs = projectDurationMs,
                    pixelsPerMs = pixelsPerMs,
                    onSelect = { onSelect(track.id) },
                    onPositionCommitted = onPositionCommitted
                )
            }
        }
    }
}

@Composable
private fun AudioTrackBlock(
    track: AudioTrack,
    isSelected: Boolean,
    projectDurationMs: Long,
    pixelsPerMs: Float,
    onSelect: () -> Unit,
    onPositionCommitted: (trackId: String, startMs: Long, durationMs: Long) -> Unit
) {
    val density = LocalDensity.current
    val maxDuration = (track.sourceDurationMs.takeIf { it > 0L } ?: projectDurationMs)
        .coerceAtLeast(MIN_AUDIO_DURATION_MS)

    var liveStart by remember(track.id, track.startMs) { mutableStateOf(track.startMs) }
    var liveDuration by remember(track.id, track.durationMs) {
        mutableStateOf(track.effectiveDurationMs(projectDurationMs))
    }

    val startDp = with(density) { (liveStart * pixelsPerMs).toDp() }
    val widthDp = with(density) { (liveDuration * pixelsPerMs).toDp() }.let {
        if (it < MIN_CLIP_WIDTH) MIN_CLIP_WIDTH else it
    }

    Box(
        modifier = Modifier
            .offset(x = startDp)
            .width(widthDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0xFF2D6A4F) else Color(0xFF1B4332))
            .clickable(onClick = onSelect)
    ) {
        Text(
            "♪ %.1fs".format(liveDuration / 1000f),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)
        )

        // Dedicated grip for repositioning -- NOT the whole block, and not overlapping
        // the trim handle below. An earlier version put the reposition drag on the whole
        // block, which meant it and the trim handle's own drag detector were two
        // competing detectDragGestures over the same region -- neither reliably won.
        // This mirrors TimelineStrip's reorder grip, which uses the exact same
        // separate-touch-target structure and is known to work.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(22.dp)
                .pointerInput(track.id) {
                    detectDragGestures(
                        onDragStart = { onSelect() },
                        onDragEnd = { onPositionCommitted(track.id, liveStart, liveDuration) },
                        onDragCancel = { liveStart = track.startMs }
                    ) { change, dragAmount ->
                        change.consume()
                        val maxStart = (projectDurationMs - liveDuration).coerceAtLeast(0L)
                        liveStart = (liveStart + (dragAmount.x / pixelsPerMs).toLong()).coerceIn(0L, maxStart)
                    }
                }
        ) {
            Text("↔", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }

        // Only the length is draggable here (right edge) -- repositioning is the
        // dedicated grip above. No left-edge handle: trimming which PART of the source
        // plays (skipping its intro) is a separate, not-yet-built feature; this trims
        // how MUCH of it plays, always starting from the source's own beginning.
        TrimHandle(
            alignment = Alignment.CenterEnd,
            onDrag = { deltaPx ->
                liveDuration = (liveDuration + (deltaPx / pixelsPerMs).toLong())
                    .coerceIn(MIN_AUDIO_DURATION_MS, maxDuration)
            },
            onDragEnd = { onPositionCommitted(track.id, liveStart, liveDuration) }
        )
    }
}
