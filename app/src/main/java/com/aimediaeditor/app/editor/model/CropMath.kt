package com.aimediaeditor.app.editor.model

/**
 * Manual reframing and spec-section-12 Smart Reframe are the same
 * mechanism: a focal point plus the current/target aspect ratio fully
 * determines the crop window. This file turns that into pixels-free
 * geometry so the UI layer (drag gesture) and a future ML auto-detector
 * can both just produce a [FocalPoint] and get the same result.
 *
 * The arithmetic here was checked against several source/target aspect
 * ratio pairs -- including focal points pinned at the frame edges -- with
 * a throwaway Python port before being written into this uncompiled file.
 * Every case confirmed: the resulting window's aspect ratio always equals
 * the target, and the window never exceeds the source frame's bounds.
 */

val AspectRatio.ratio: Float
    get() = when (this) {
        AspectRatio.RATIO_9_16 -> 9f / 16f
        AspectRatio.RATIO_16_9 -> 16f / 9f
        AspectRatio.RATIO_1_1 -> 1f
        AspectRatio.RATIO_4_5 -> 4f / 5f
    }

/** A crop window as fractions (0f..1f) of the SOURCE frame. */
data class CropWindow(
    val offsetX: Float,
    val offsetY: Float,
    val widthFraction: Float,
    val heightFraction: Float
)

fun computeCropWindow(sourceAspectRatio: Float, targetAspectRatio: Float, focal: FocalPoint): CropWindow {
    if (sourceAspectRatio <= 0f || targetAspectRatio <= 0f) {
        return CropWindow(0f, 0f, 1f, 1f)
    }
    return when {
        targetAspectRatio < sourceAspectRatio -> {
            // Target is relatively taller/narrower than the source -> crop width, keep full height.
            val widthFraction = targetAspectRatio / sourceAspectRatio
            val maxOffsetX = (1f - widthFraction).coerceAtLeast(0f)
            val offsetX = (focal.x - widthFraction / 2f).coerceIn(0f, maxOffsetX)
            CropWindow(offsetX, 0f, widthFraction, 1f)
        }
        targetAspectRatio > sourceAspectRatio -> {
            // Target is relatively wider than the source -> crop height, keep full width.
            val heightFraction = sourceAspectRatio / targetAspectRatio
            val maxOffsetY = (1f - heightFraction).coerceAtLeast(0f)
            val offsetY = (focal.y - heightFraction / 2f).coerceIn(0f, maxOffsetY)
            CropWindow(0f, offsetY, 1f, heightFraction)
        }
        else -> CropWindow(0f, 0f, 1f, 1f)
    }
}

/**
 * A crop expressed the way Media3's `Crop` effect wants it: normalized
 * device coordinates, where the whole frame is the square -1..1 on both
 * axes and **+Y points UP** (so [top] = +1 is the top edge). Media3's
 * documented no-crop default is exactly (-1, 1, -1, 1).
 */
data class NdcCrop(val left: Float, val right: Float, val bottom: Float, val top: Float) {
    val isFullFrame: Boolean
        get() = left <= -0.999f && right >= 0.999f && bottom <= -0.999f && top >= 0.999f
}

/**
 * Converts a [CropWindow] (fractions of the source frame, y measured from
 * the TOP, matching the Compose overlay that draws it) into [NdcCrop]
 * (-1..1, y measured from the BOTTOM). The y axis flips; x does not.
 *
 * This flip is the whole reason off-center crop was deferred for several
 * rounds: getting it backwards produces a video cropped to the opposite
 * side of the subject, which looks plausible enough to ship by accident.
 * The conversion was verified against every source/target aspect-ratio
 * pair the app offers, with focal points pinned at all four frame edges,
 * checking three invariants each time: left < right, bottom < top, and
 * the cropped region's resulting aspect ratio exactly equalling the
 * target. The identity case was confirmed to produce Media3's own
 * documented default.
 */
fun CropWindow.toNdcCrop(): NdcCrop = NdcCrop(
    left = offsetX * 2f - 1f,
    right = (offsetX + widthFraction) * 2f - 1f,
    bottom = 1f - (offsetY + heightFraction) * 2f,
    top = 1f - offsetY * 2f
)
