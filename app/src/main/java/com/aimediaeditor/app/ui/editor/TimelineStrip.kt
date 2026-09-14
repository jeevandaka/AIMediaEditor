package com.aimediaeditor.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.data.media.VideoThumbnailLoader
import com.aimediaeditor.app.editor.model.VideoClip
import com.aimediaeditor.app.editor.model.maxTrimEndMs

// Not private: AudioTrackStrip shares this exact scale so a given timestamp lines up
// at the same horizontal offset in both the video row and the audio lane below it. This
// is the 1x-zoom value -- EditorScreen scales it by the user's chosen zoom factor and
// passes the result down as each composable's `pixelsPerSecond` parameter, so nothing in
// either lane hardcodes a fixed scale any more.
internal val BASE_PIXELS_PER_SECOND = 56.dp
// Must stay wide enough that the reorder grip (22.dp, centered) and both trim handles
// (14.dp each, at the edges) never overlap -- 14+22+14 = 50.dp is the exact minimum with
// zero clearance; below that, a short clip's reorder grip and trim handle physically
// share pixels, so touches near an edge could be claimed by either detectDragGestures,
// and which one wins isn't guaranteed. 64.dp leaves real clearance on both sides.
internal val MIN_CLIP_WIDTH = 64.dp
private const val MIN_CLIP_DURATION_MS = 200L

// Filmstrip tiling: aim for one frame roughly every 40.dp of clip width, capped at 8 so a
// long clip doesn't extract dozens of frames -- past 8 tiles the individual tiles get
// wider instead, still evenly covering the clip, just each one spanning more time.
private val FILMSTRIP_TILE_WIDTH = 40.dp
private const val MAX_FILMSTRIP_TILES = 8

/** mm:ss -- not private: AudioTrackStrip uses the same format for its own position label. */
internal fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * A plain (non-Lazy) horizontally scrollable Row -- there's no need for
 * LazyRow's virtualization at MVP clip counts, and a plain Row lets clip
 * positions be read with the long-stable onGloballyPositioned API instead
 * of LazyListState internals, which is one less API surface to get wrong
 * blind. Reorder logic here is the single riskiest piece of Phase 2 --
 * see the README's device-testing list.
 */
@Composable
fun TimelineStrip(
    clips: List<VideoClip>,
    selectedClipId: String?,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onTrimCommitted: (clipId: String, startMs: Long, endMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    pixelsPerSecond: Dp = BASE_PIXELS_PER_SECOND,
    scrollState: ScrollState = rememberScrollState(),
    playheadMs: Long? = null
) {
    var order by remember(clips.map { it.id }) { mutableStateOf(clips.map { it.id }) }
    var bounds by remember { mutableStateOf(mapOf<String, ClosedRange<Float>>()) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    // Scroll lives on this outer Box, not the Row, so the playhead line below can be a
    // second child of the SAME scrollable box -- it then scrolls together with the clips
    // and stays pinned to the correct timestamp, rather than needing the Row itself to
    // host an absolutely-positioned overlay on top of its own sequential children.
    Box(
        modifier = modifier
            .horizontalScroll(scrollState)
            .height(96.dp)
    ) {
        Row(modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
            for (clipId in order) {
                val clip = clips.firstOrNull { it.id == clipId } ?: continue
                val isDragging = clipId == draggingId

                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val left = coords.positionInParent().x
                            bounds = bounds + (clipId to (left..(left + coords.size.width)))
                        }
                        .graphicsLayer { translationX = if (isDragging) dragOffsetPx else 0f }
                        .zIndex(if (isDragging) 1f else 0f)
                ) {
                    ClipItem(
                        clip = clip,
                        isSelected = clipId == selectedClipId,
                        onSelect = { onSelect(clipId) },
                        onTrimCommitted = { start, end -> onTrimCommitted(clipId, start, end) },
                        pixelsPerSecond = pixelsPerSecond
                    )

                    // Dedicated grip handle for reordering -- a separate touch target from
                    // tap-to-select and the trim handles, so none of the three gestures compete.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .size(22.dp)
                            .pointerInput(clipId, order) {
                                detectDragGestures(
                                    onDragStart = { draggingId = clipId; dragOffsetPx = 0f },
                                    onDragEnd = { onReorder(order); draggingId = null; dragOffsetPx = 0f },
                                    onDragCancel = { draggingId = null; dragOffsetPx = 0f }
                                ) { change, dragAmount ->
                                    change.consume()
                                    dragOffsetPx += dragAmount.x
                                    val myBounds = bounds[clipId] ?: return@detectDragGestures
                                    val myCenter = (myBounds.start + myBounds.endInclusive) / 2f + dragOffsetPx
                                    val currentIndex = order.indexOf(clipId)
                                    val neighborIndex = if (dragOffsetPx > 0) currentIndex + 1 else currentIndex - 1
                                    val neighborId = order.getOrNull(neighborIndex) ?: return@detectDragGestures
                                    val neighborBounds = bounds[neighborId] ?: return@detectDragGestures
                                    val neighborCenter = (neighborBounds.start + neighborBounds.endInclusive) / 2f
                                    val crossed = if (dragOffsetPx > 0) myCenter > neighborCenter else myCenter < neighborCenter
                                    if (crossed) {
                                        val fromIdx = order.indexOf(clipId)
                                        order = order.toMutableList().apply { add(neighborIndex, removeAt(fromIdx)) }
                                        val travelled = neighborBounds.endInclusive - neighborBounds.start
                                        dragOffsetPx -= if (dragOffsetPx > 0) travelled else -travelled
                                    }
                                }
                            }
                    ) {
                        Text("\u2261", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (playheadMs != null) {
            Playhead(playheadMs, pixelsPerSecond)
        }
    }
}

@Composable
private fun ClipItem(
    clip: VideoClip,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTrimCommitted: (startMs: Long, endMs: Long) -> Unit,
    pixelsPerSecond: Dp
) {
    val density = LocalDensity.current
    val pixelsPerMs = with(density) { pixelsPerSecond.toPx() } / 1000f
    val trimmedDurationMs = clip.trimEndMs - clip.trimStartMs
    val widthDp = with(density) {
        (trimmedDurationMs * pixelsPerMs).toDp().coerceAtLeast(MIN_CLIP_WIDTH)
    }

    var liveStart by remember(clip.id, clip.trimStartMs) { mutableStateOf(clip.trimStartMs) }
    var liveEnd by remember(clip.id, clip.trimEndMs) { mutableStateOf(clip.trimEndMs) }

    Box(
        modifier = Modifier
            .width(widthDp)
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0xFFE94560) else Color.DarkGray)
            .clickable(onClick = onSelect)
    ) {
        if (clip.sourceType == MediaType.IMAGE) {
            AsyncImage(
                model = android.net.Uri.parse(clip.sourceUri),
                contentDescription = null,
                modifier = Modifier.fillMaxHeight()
            )
            // A photo's duration is now something the handles actually
            // change, so it's worth showing -- otherwise the only feedback
            // for a drag is the clip's width.
            Text(
                "%.1fs".format(trimmedDurationMs / 1000f),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f))
            )
        } else {
            // A real filmstrip -- several frames tiled across the clip's width, each one
            // sampled from its own slice of the CURRENTLY TRIMMED range -- not one frame
            // stretched/cropped to cover the whole clip regardless of length. One frame
            // per slice is what actually represents "what happens across this clip";
            // a single frame just shows one moment no matter how long the clip is.
            // Re-fetched when the trim is committed, not on every pixel of a live drag --
            // extracting several frames isn't instant, so doing it mid-gesture would be
            // janky rather than helpful.
            val context = LocalContext.current
            val tileCount = remember(widthDp) {
                kotlin.math.ceil(widthDp / FILMSTRIP_TILE_WIDTH).toInt().coerceIn(1, MAX_FILMSTRIP_TILES)
            }
            var filmstrip by remember(clip.id) { mutableStateOf<List<Bitmap?>>(emptyList()) }
            LaunchedEffect(clip.sourceUri, clip.trimStartMs, clip.trimEndMs, tileCount) {
                filmstrip = VideoThumbnailLoader.loadFilmstrip(
                    context,
                    android.net.Uri.parse(clip.sourceUri),
                    startMs = clip.trimStartMs,
                    endMs = clip.trimEndMs,
                    tileCount = tileCount
                )
            }
            Row(modifier = Modifier.fillMaxSize()) {
                repeat(tileCount) { i ->
                    val bmp = filmstrip.getOrNull(i)
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
            // Start -> end, not just duration -- updates live while dragging a trim
            // handle, so the exact moment being trimmed to is always visible instead of
            // needing to let go and check. mm:ss to match what other editors show here,
            // per the report that this lane read as a solid, unlabelled block before.
            Text(
                "${formatClock(liveStart)} \u2192 ${formatClock(liveEnd)}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
            )
        }

        if (isSelected) {
            TrimHandle(
                alignment = Alignment.CenterStart,
                onDrag = { deltaPx ->
                    liveStart = (liveStart + (deltaPx / pixelsPerMs).toLong())
                        .coerceIn(0, liveEnd - MIN_CLIP_DURATION_MS)
                },
                onDragEnd = { onTrimCommitted(liveStart, liveEnd) }
            )
            TrimHandle(
                alignment = Alignment.CenterEnd,
                onDrag = { deltaPx ->
                    liveEnd = (liveEnd + (deltaPx / pixelsPerMs).toLong())
                        .coerceIn(liveStart + MIN_CLIP_DURATION_MS, clip.maxTrimEndMs)
                },
                onDragEnd = { onTrimCommitted(liveStart, liveEnd) }
            )
        }
    }
}

/** A 2.dp red line at [positionMs] -- not private: AudioTrackStrip draws the identical
 *  line at the identical timestamp, so the two lanes' playheads visually agree even
 *  though they scroll independently (see README). Each is pinned to a timestamp within
 *  its OWN scrollable content rather than to a shared screen position, so both stay
 *  correct regardless of either lane's current scroll offset. */
@Composable
internal fun BoxScope.Playhead(positionMs: Long, pixelsPerSecond: Dp) {
    val density = LocalDensity.current
    val pixelsPerMs = with(density) { pixelsPerSecond.toPx() } / 1000f
    val x = with(density) { (positionMs * pixelsPerMs).toDp() }
    Box(
        modifier = Modifier
            .offset(x = x)
            .width(2.dp)
            .fillMaxHeight()
            .background(Color.Red)
    )
}

/** BoxScope extension so it can align itself to either edge of the caller's Box. Not
 *  private: AudioTrackStrip reuses this exact handle for the same drag-to-trim feel. */
@Composable
internal fun BoxScope.TrimHandle(
    alignment: Alignment,
    onDrag: (deltaPx: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .fillMaxHeight()
            .width(14.dp)
            .background(Color.White.copy(alpha = 0.85f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = {}
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            }
    )
}
