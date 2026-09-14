package com.aimediaeditor.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aimediaeditor.app.data.media.AudioWaveformLoader
import com.aimediaeditor.app.editor.model.AudioTrack
import com.aimediaeditor.app.editor.model.effectiveDurationMs

private const val MIN_AUDIO_DURATION_MS = 300L
private const val WAVEFORM_BUCKET_COUNT = 80

/**
 * A draggable lane for audio tracks, one block per track, positioned by [AudioTrack.startMs]
 * and sized by its effective duration -- rather than the fixed volume/loop-preset-only
 * controls that existed before ("attach it at a specific place" wasn't otherwise possible
 * without deleting and re-adding). Takes the same [pixelsPerSecond] scale and [ScrollState]
 * [TimelineStrip]'s video row does (both default to the same [BASE_PIXELS_PER_SECOND], and
 * EditorScreen passes both the same zoomed pixelsPerSecond and the same ScrollState
 * instance) so a given timestamp lines up at the same horizontal offset in both, and
 * scrolling either lane moves the other in lockstep.
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
    modifier: Modifier = Modifier,
    pixelsPerSecond: Dp = BASE_PIXELS_PER_SECOND,
    scrollState: ScrollState = rememberScrollState(),
    playheadMs: Long? = null,
    // Video clip boundaries (plus timeline start/the playhead) that reposition/trim drags
    // snap to -- see TimelineStrip.snapToNearest for why this is audio-only for now.
    snapPointsMs: List<Long> = emptyList()
) {
    if (audioTracks.isEmpty()) return

    val density = LocalDensity.current
    val pixelsPerMs = with(density) { pixelsPerSecond.toPx() } / 1000f
    val totalWidth: Dp = with(density) {
        (projectDurationMs.coerceAtLeast(1000L) * pixelsPerMs).toDp()
    }
    // Lifted up here (not local to one AudioTrackBlock) because the guide line is drawn
    // as a sibling of the blocks, in the lane's own absolute coordinate space -- drawing
    // it inside a block's own Box would double-apply that block's startDp offset.
    var activeSnapMs by remember { mutableStateOf<Long?>(null) }

    // Two nested boxes on purpose: the outer one is the scrollable VIEWPORT, sized by
    // whatever its own parent gives it (fillMaxWidth from EditorScreen) -- that's what
    // horizontalScroll needs to know how much of the content is actually visible. The
    // inner one is the full-timeline-width CONTENT area the tracks are positioned within.
    // Putting an explicit width on the SAME box as horizontalScroll (as an earlier version
    // of this file did) collapses that distinction -- the "viewport" and "content" width
    // become the same box, which is what made every drag on it fail to register correctly.
    // The playhead and snap guide are further children of this same outer box (siblings of
    // the content box, not inside it) so they scroll in step with the tracks and stay
    // pinned to the right timestamp regardless of this lane's own scroll position.
    Box(
        modifier = modifier
            .horizontalScroll(scrollState)
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
                    snapPointsMs = snapPointsMs,
                    onSelect = { onSelect(track.id) },
                    onPositionCommitted = onPositionCommitted,
                    onSnapChanged = { activeSnapMs = it }
                )
            }
        }
        if (playheadMs != null) {
            Playhead(playheadMs, pixelsPerSecond)
        }
        activeSnapMs?.let { SnapGuide(it, pixelsPerSecond) }
    }
}

@Composable
private fun AudioTrackBlock(
    track: AudioTrack,
    isSelected: Boolean,
    projectDurationMs: Long,
    pixelsPerMs: Float,
    snapPointsMs: List<Long>,
    onSelect: () -> Unit,
    onPositionCommitted: (trackId: String, startMs: Long, durationMs: Long) -> Unit,
    onSnapChanged: (Long?) -> Unit
) {
    val density = LocalDensity.current
    val context = LocalContext.current
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

    // Decoded once per source file (not re-decoded on every trim/reposition drag frame --
    // the shape of the waveform never changes, only how much of it is currently "played",
    // which is handled at draw time below) and covers the source's FULL duration, not just
    // the currently trimmed range, since trimming only ever moves the right edge (see the
    // TrimHandle usage below) -- the drawn portion is a prefix of this array, not a re-fetch.
    var waveform by remember(track.id) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(track.sourceUri) {
        waveform = AudioWaveformLoader.loadWaveform(
            context,
            android.net.Uri.parse(track.sourceUri),
            WAVEFORM_BUCKET_COUNT
        )
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
        waveform?.let { amplitudes ->
            // Only draw the PLAYED prefix of the full-source waveform -- an audio track's
            // duration here is always trimmed from the end (source's own beginning stays
            // fixed), so "how much of the bars to show" is exactly the same fraction as
            // "how much of the source plays", not a re-decode of a different range.
            val playedFraction = if (track.sourceDurationMs > 0L) {
                (liveDuration.toFloat() / track.sourceDurationMs).coerceIn(0f, 1f)
            } else {
                1f
            }
            val barCount = (amplitudes.size * playedFraction).toInt().coerceIn(1, amplitudes.size)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val barWidth = size.width / barCount
                for (i in 0 until barCount) {
                    // A floor so even a near-silent bucket still reads as a bar, not a
                    // gap that looks like a decode failure.
                    val barHeight = size.height * amplitudes[i].coerceAtLeast(0.06f)
                    drawRect(
                        color = Color.White.copy(alpha = 0.5f),
                        topLeft = Offset(i * barWidth, (size.height - barHeight) / 2f),
                        size = Size((barWidth * 0.7f).coerceAtLeast(1f), barHeight)
                    )
                }
            }
        }

        // Position AND length, not just length -- the same "blind" problem the video
        // clips had: a duration-only label doesn't say WHERE on the timeline this track
        // starts, which is the whole point of a draggable, precisely-placeable track.
        // mm:ss to match the video row's own label format.
        Text(
            "♪ ${formatClock(liveStart)} · ${"%.1f".format(liveDuration / 1000f)}s",
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
                        onDragEnd = {
                            onPositionCommitted(track.id, liveStart, liveDuration)
                            onSnapChanged(null)
                        },
                        onDragCancel = { liveStart = track.startMs; onSnapChanged(null) }
                    ) { change, dragAmount ->
                        change.consume()
                        val maxStart = (projectDurationMs - liveDuration).coerceAtLeast(0L)
                        val raw = (liveStart + (dragAmount.x / pixelsPerMs).toLong()).coerceIn(0L, maxStart)
                        val (snapped, guide) = snapToNearest(raw, snapPointsMs, snapThresholdMs(pixelsPerMs))
                        liveStart = snapped.coerceIn(0L, maxStart)
                        onSnapChanged(guide)
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
                val rawDuration = (liveDuration + (deltaPx / pixelsPerMs).toLong())
                    .coerceIn(MIN_AUDIO_DURATION_MS, maxDuration)
                val (snappedEnd, guide) = snapToNearest(
                    liveStart + rawDuration,
                    snapPointsMs,
                    snapThresholdMs(pixelsPerMs)
                )
                liveDuration = (snappedEnd - liveStart).coerceIn(MIN_AUDIO_DURATION_MS, maxDuration)
                onSnapChanged(guide)
            },
            onDragEnd = {
                onPositionCommitted(track.id, liveStart, liveDuration)
                onSnapChanged(null)
            },
            onDragCancel = {
                liveDuration = track.effectiveDurationMs(projectDurationMs)
                onSnapChanged(null)
            }
        )
    }
}
