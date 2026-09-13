@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aimediaeditor.app.ui.editor

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import coil3.compose.AsyncImage
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.editor.model.FocalPoint
import com.aimediaeditor.app.editor.model.VideoClip
import com.aimediaeditor.app.editor.model.computeCropWindow

/**
 * Renders whichever clip is selected. Video uses the single ExoPlayer
 * instance the caller owns (see EditorScreen -- one instance for the
 * whole screen, media item swapped as selection changes, never a second
 * instance created alongside it, per architecture-notes section 2.1).
 * Photos need no player at all -- Coil renders them directly.
 *
 * The dashed rectangle is the crop window computed by
 * [com.aimediaeditor.app.editor.model.computeCropWindow]; dragging it
 * moves the clip's [com.aimediaeditor.app.editor.model.FocalPoint] and
 * only commits an EditCommand on drag END, not on every pixel of
 * movement -- otherwise one drag gesture would flood undo history with
 * hundreds of entries.
 */
@Composable
fun ClipPreview(
    clip: VideoClip?,
    exoPlayer: ExoPlayer,
    targetAspectRatio: Float,
    onReframeCommitted: (FocalPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    if (clip == null) {
        Box(modifier = modifier.background(Color.Black)) {
            Text(
                "Add clips to preview them",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        return
    }

    val sourceAspectRatio = clip.sourceWidth.toFloat() / clip.sourceHeight.toFloat()

    Box(
        modifier = modifier
            .aspectRatio(sourceAspectRatio)
            .background(Color.Black)
    ) {
        when (clip.sourceType) {
            MediaType.VIDEO -> {
                PlayerSurface(player = exoPlayer, modifier = Modifier.fillMaxSize())
                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    ProgressSlider(exoPlayer)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PlayPauseButton(exoPlayer)
                    }
                }
            }
            MediaType.IMAGE -> AsyncImage(
                model = Uri.parse(clip.sourceUri),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        CropOverlay(
            baseFocal = clip.focalPoint ?: FocalPoint(0.5f, 0.5f),
            sourceAspectRatio = sourceAspectRatio,
            targetAspectRatio = targetAspectRatio,
            onReframeCommitted = onReframeCommitted
        )
    }
}

@Composable
private fun CropOverlay(
    baseFocal: FocalPoint,
    sourceAspectRatio: Float,
    targetAspectRatio: Float,
    onReframeCommitted: (FocalPoint) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var liveFocal by remember(baseFocal) { mutableStateOf(baseFocal) }
    val cropWindow = computeCropWindow(sourceAspectRatio, targetAspectRatio, liveFocal)

    // Nothing to show or drag when the target already matches the source (no crop needed).
    if (cropWindow.widthFraction >= 0.999f && cropWindow.heightFraction >= 0.999f) return

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(baseFocal) {
                detectDragGestures(
                    onDragEnd = { onReframeCommitted(liveFocal) },
                    onDragCancel = { liveFocal = baseFocal }
                ) { change, dragAmount ->
                    change.consume()
                    val w = boxSize.width
                    val h = boxSize.height
                    if (w > 0 && h > 0) {
                        liveFocal = FocalPoint(
                            x = (liveFocal.x + dragAmount.x / w).coerceIn(0f, 1f),
                            y = (liveFocal.y + dragAmount.y / h).coerceIn(0f, 1f)
                        )
                    }
                }
            }
    ) {
        val left = cropWindow.offsetX * size.width
        val top = cropWindow.offsetY * size.height
        val w = cropWindow.widthFraction * size.width
        val h = cropWindow.heightFraction * size.height
        val dim = Color.Black.copy(alpha = 0.55f)

        if (top > 0f) drawRect(color = dim, size = Size(size.width, top))
        if (top + h < size.height) {
            drawRect(color = dim, topLeft = Offset(0f, top + h), size = Size(size.width, size.height - top - h))
        }
        if (left > 0f) drawRect(color = dim, topLeft = Offset(0f, top), size = Size(left, h))
        if (left + w < size.width) {
            drawRect(color = dim, topLeft = Offset(left + w, top), size = Size(size.width - left - w, h))
        }
        drawRect(color = Color.White, topLeft = Offset(left, top), size = Size(w, h), style = Stroke(width = 3f))
    }
}
