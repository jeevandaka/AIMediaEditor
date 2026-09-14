package com.aimediaeditor.app.editor.model

import com.aimediaeditor.app.data.media.MediaType
import kotlinx.serialization.Serializable

/**
 * The full, serializable state of an editing project -- the "Edit
 * Decision List" from architecture notes section 1. This is the ONLY
 * thing the AI is allowed to produce or modify; it never touches
 * pixels or files directly. Source media referenced here is never
 * mutated (spec section 17, architecture notes section 1.1).
 *
 * Deliberately Android-free (plain Kotlin, [sourceUri] as a String
 * rather than android.net.Uri) so this whole package compiles and is
 * unit-testable on a plain JVM, no emulator required. kotlinx.serialization
 * is a pure-Kotlin library, so @Serializable here doesn't compromise that --
 * it's what lets [com.aimediaeditor.app.data.project.ProjectRepository]
 * write this whole tree to disk as JSON for autosave/reopen (spec section 22)
 * without a parallel hand-written serialization format to keep in sync.
 */
@Serializable
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
@Serializable
data class VideoClip(
    val id: String,
    val sourceUri: String,
    val sourceDurationMs: Long,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val filter: FilterType = FilterType.NONE,
    val focalPoint: FocalPoint? = null, // Smart Reframe target, spec section 12 -- also doubles as the manual crop/reframe anchor in the Phase 2 editor UI
    // Added in Phase 2, all defaulted so nothing that already constructs a VideoClip breaks.
    // "VideoClip" keeps the spec's section-17 name even though it can hold a still image
    // placed on the timeline -- see sourceType.
    val sourceType: MediaType = MediaType.VIDEO,
    val sourceWidth: Int = 1920,
    val sourceHeight: Int = 1080
) {
    val durationMs: Long get() = ((trimEndMs - trimStartMs) / speed).toLong()
}

/**
 * Longest a single still image may be shown for. A photo has no intrinsic
 * length, so something has to bound it; 30s is well beyond any sensible
 * single-photo shot while still stopping a runaway drag.
 */
const val MAX_PHOTO_DURATION_MS = 30_000L

/**
 * Upper bound that both the sanitizer and the timeline's drag handles clamp
 * trimEndMs against. A video cannot extend past its own source length; a
 * still has no source length, so it gets the cap above instead. Defined
 * once here so the validation gate and the UI can never disagree about it.
 */
val VideoClip.maxTrimEndMs: Long
    get() = if (sourceType == MediaType.IMAGE) MAX_PHOTO_DURATION_MS else sourceDurationMs

/** Normalized 0f..1f position within the frame -- resolution-independent. */
@Serializable
data class FocalPoint(val x: Float, val y: Float)

enum class FilterType { NONE, CINEMATIC, BRIGHT, VIVID, MONOCHROME }

@Serializable
data class AudioTrack(
    val id: String,
    val sourceUri: String,
    val startMs: Long,
    val volume: Float = 1f,
    val sourceDurationMs: Long = 0L,
    // Added this round: a track shorter than the video used to simply stop.
    // Looping repeats it for the remaining length of the visual sequence.
    val isLooping: Boolean = false,
    // How long this track plays (one loop cycle, if looping) before it started being
    // user-adjustable via the audio timeline strip. 0L means "not set" -- a project
    // saved before this field existed, or a track that hasn't been resized since being
    // added -- and is resolved through effectiveDurationMs below rather than treated
    // as a real zero-length track.
    val durationMs: Long = 0L
)

/**
 * The single place that decides what "how long does this track actually play" means,
 * so CompositionBuilder (export) and the audio timeline strip (UI) can never disagree:
 * an explicit user-set duration wins, then the source's own length, then (only for a
 * source whose duration couldn't be read) the project's own length.
 */
fun AudioTrack.effectiveDurationMs(projectDurationMs: Long): Long =
    durationMs.takeIf { it > 0L }
        ?: sourceDurationMs.takeIf { it > 0L }
        ?: projectDurationMs.coerceAtLeast(1000L)

@Serializable
data class TextOverlay(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val yPositionFraction: Float, // 0f (top) .. 1f (bottom)
    // Added this round: X was always centre (0.5) with no way to change it -- a real gap
    // against the UX spec's "direct manipulation" principle (drag to move, not one axis
    // constrained to a fixed value). Defaults to centre so nothing that already
    // constructs a TextOverlay changes behaviour, and so a project saved before this
    // field existed decodes cleanly (no key in old JSON -> falls back to this default,
    // not a crash -- same pattern as AudioTrack.durationMs above).
    val xPositionFraction: Float = 0.5f // 0f (left) .. 1f (right)
)
