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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import coil3.compose.AsyncImage
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.editor.model.FilterType
import com.aimediaeditor.app.editor.model.FocalPoint
import com.aimediaeditor.app.editor.model.VideoClip
import com.aimediaeditor.app.editor.model.computeCropWindow

/**
 * Fixed height for the single-clip preview, regardless of the source's own aspect
 * ratio -- a tall portrait photo/video used to stretch this box to its own intrinsic
 * height (sourceWidth/sourceHeight applied to a full-width box), which could exceed
 * the screen and push every editing control below it out of view, with no way to
 * scroll past it (see [CropOverlay]'s drag detector, which covered that same
 * oversized area and captured vertical drags meant for the page's own scroll).
 */
private val PREVIEW_HEIGHT = 320.dp

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
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .background(Color.Black)
        ) {
            Text(
                "Add clips to preview them",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        return
    }

    val sourceAspectRatio = clip.sourceWidth.toFloat() / clip.sourceHeight.toFloat()

    // Outer box is a FIXED height -- never sized by the source's own aspect ratio.
    // The inner box is what actually carries the aspect ratio, letterboxed/pillarboxed
    // to fit inside the fixed height, so a portrait source can never blow up the layout.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT)
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(sourceAspectRatio)
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
                    modifier = Modifier.fillMaxSize(),
                    // Live filter preview for photos -- video's live preview goes through
                    // ExoPlayer.setVideoEffects() in EditorScreen instead, since a GL
                    // video effect can't be applied to a Compose Image. Both read the
                    // same FilterType, they just render it through different pipelines.
                    colorFilter = colorMatrixFor(clip.filter)?.let { ColorFilter.colorMatrix(it) }
                )
            }

            CropOverlay(
                clipId = clip.id,
                baseFocal = clip.focalPoint ?: FocalPoint(0.5f, 0.5f),
                sourceAspectRatio = sourceAspectRatio,
                targetAspectRatio = targetAspectRatio,
                onReframeCommitted = onReframeCommitted
            )
        }
    }
}

/**
 * A Compose-side approximation of [com.aimediaeditor.app.editor.export.CompositionBuilder]'s
 * Media3 filter effects, for the one place that can't use those directly (a static
 * Coil image, not a GL rendering pipeline). Deliberately approximate, not pixel-identical
 * to the export -- good enough to confirm "yes, a filter is selected and visibly doing
 * something," which is what was missing; export remains the source of truth for the
 * exact look. Standard Android color-matrix math (4x5, RGBA rows + additive offset
 * column, 0..255 range) -- not verified on-device, but not novel arithmetic either.
 */
private fun colorMatrixFor(filter: FilterType): ColorMatrix? = when (filter) {
    FilterType.NONE -> null
    FilterType.MONOCHROME -> ColorMatrix().apply { setToSaturation(0f) }
    FilterType.BRIGHT -> ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 40f,
            0f, 1f, 0f, 0f, 40f,
            0f, 0f, 1f, 0f, 40f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    FilterType.CINEMATIC -> contrastMatrix(contrast = 1.25f, extraBrightness = 0f)
    FilterType.VIVID -> contrastMatrix(contrast = 1.35f, extraBrightness = 12f)
}

private fun contrastMatrix(contrast: Float, extraBrightness: Float): ColorMatrix {
    val translate = (1f - contrast) * 128f + extraBrightness
    return ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

@Composable
private fun CropOverlay(
    clipId: String,
    baseFocal: FocalPoint,
    sourceAspectRatio: Float,
    targetAspectRatio: Float,
    onReframeCommitted: (FocalPoint) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // Keyed on clipId, not just baseFocal: two different clips that both happen to
    // have the same focal point (e.g. two never-cropped photos, both defaulting to
    // centre) are the same VALUE, so keying on baseFocal alone left this drag state
    // un-reset across a clip switch -- the overlay kept showing the previous clip's
    // dragged crop position on the newly-selected clip, even though the actual
    // per-clip data (and the export) was already correct. That's what looked like
    // "cropping one photo cropped all of them."
    var liveFocal by remember(clipId, baseFocal) { mutableStateOf(baseFocal) }
    val cropWindow = computeCropWindow(sourceAspectRatio, targetAspectRatio, liveFocal)

    // Nothing to show or drag when the target already matches the source (no crop needed).
    if (cropWindow.widthFraction >= 0.999f && cropWindow.heightFraction >= 0.999f) return

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(clipId, baseFocal) {
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
