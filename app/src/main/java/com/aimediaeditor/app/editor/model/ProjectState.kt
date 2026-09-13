package com.aimediaeditor.app.editor.model

/**
 * The full, serializable state of an editing project -- the "Edit
 * Decision List" from architecture notes section 1. This is the ONLY
 * thing the AI is allowed to produce or modify; it never touches
 * pixels or files directly. Source media referenced here is never
 * mutated (spec section 17, architecture notes section 1.1).
 *
 * Deliberately Android-free (plain Kotlin, [sourceUri] as a String
 * rather than android.net.Uri) so this whole package compiles and is
 * unit-testable on a plain JVM, no emulator required.
 */
data class ProjectState(
    val id: String,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_9_16,
    val clips: List<VideoClip> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val textOverlays: List<TextOverlay> = emptyList()
) {
    /** Timeline length is driven by the video clips; audio/text ride on top of it. */
    val durationMs: Long get() = clips.sumOf { it.durationMs }
}

enum class AspectRatio(val label: String) {
    RATIO_9_16("9:16"),
    RATIO_16_9("16:9"),
    RATIO_1_1("1:1"),
    RATIO_4_5("4:5")
}

/**
 * One segment of source media placed on the timeline. [sourceUri] and
 * [sourceDurationMs] describe the ORIGINAL file and are never changed
 * by editing; [trimStartMs]/[trimEndMs] describe what part of it plays
 * here, always measured against the original, untouched by [speed].
 */
data class VideoClip(
    val id: String,
    val sourceUri: String,
    val sourceDurationMs: Long,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val filter: FilterType = FilterType.NONE,
    val focalPoint: FocalPoint? = null // Smart Reframe target, spec section 12
) {
    val durationMs: Long get() = ((trimEndMs - trimStartMs) / speed).toLong()
}

/** Normalized 0f..1f position within the frame -- resolution-independent. */
data class FocalPoint(val x: Float, val y: Float)

enum class FilterType { NONE, CINEMATIC, BRIGHT, VIVID, MONOCHROME }

data class AudioTrack(
    val id: String,
    val sourceUri: String,
    val startMs: Long,
    val volume: Float = 1f
)

data class TextOverlay(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val yPositionFraction: Float // 0f (top) .. 1f (bottom)
)
