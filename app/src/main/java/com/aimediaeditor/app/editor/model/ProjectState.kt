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
    val textOverlays: List<TextOverlay> = emptyList(),
    // Added this round (UX spec section 25, Tier 1): a fade-to-black transition sitting
    // between one clip and whatever follows it. Its own list, not a field on VideoClip,
    // matching how audio/text are modeled -- a transition is a project-timeline concept
    // (it needs to know about TWO clips), not a property of one clip in isolation.
    // Defaults to empty so every already-saved project decodes unchanged.
    val transitions: List<ClipTransition> = emptyList()
) {
    /** Timeline length is driven by the video clips; audio/text ride on top of it. */
    val durationMs: Long get() = clips.sumOf { it.durationMs }
}

/**
 * A transition sits AFTER [afterClipId] -- between that clip and whatever clip
 * immediately follows it in [ProjectState.clips]' current order. A transition whose
 * [afterClipId] names the LAST clip (nothing follows it) or a clip that no longer
 * exists is meaningless and is filtered out by [ProjectState.effectiveTransitions]
 * rather than crashing or rendering somewhere unintended.
 */
@Serializable
data class ClipTransition(
    val afterClipId: String,
    val type: TransitionType = TransitionType.FADE_TO_BLACK,
    val durationMs: Long = DEFAULT_TRANSITION_DURATION_MS
)

/**
 * Only one type for now: a symmetric dip-to-black centred on the cut point, built from
 * an alpha-ramped full-frame black overlay (see CompositionBuilder) -- the mechanism
 * this app already has confirmed working end-to-end (the same alpha/anchor overlay
 * machinery Phase 3's text overlay burn-in uses). A true cross-dissolve (two clips'
 * video blended together, not faded through black) needs Media3's multi-sequence video
 * compositor, which this app has no confirmed usage of yet -- left for later rather
 * than guessed at blind.
 */
enum class TransitionType { FADE_TO_BLACK }

const val DEFAULT_TRANSITION_DURATION_MS = 500L
const val MIN_TRANSITION_DURATION_MS = 200L
const val MAX_TRANSITION_DURATION_MS = 2000L

/**
 * The single place that decides which stored [ClipTransition]s are actually live --
 * so CompositionBuilder (export/preview) and any future UI reading "does this clip
 * have a transition after it" can never disagree. Filters out a transition whose
 * [ClipTransition.afterClipId] no longer names an existing clip, or names the LAST
 * clip (nothing follows it to transition into) -- both are possible after deleting or
 * reordering clips, and both should just make the transition inert, not crash
 * rendering or leave a stale toggle showing as active in the UI.
 */
fun ProjectState.effectiveTransitions(): List<ClipTransition> {
    if (clips.size < 2 || transitions.isEmpty()) return emptyList()
    val lastClipId = clips.last().id
    val validClipIds = clips.mapTo(HashSet()) { it.id }
    return transitions.filter { it.afterClipId in validClipIds && it.afterClipId != lastClipId }
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
    // Legacy single-filter field, kept ONLY so a project saved before the effect stack
    // existed still decodes with its filter intact -- see effectiveEffects() below.
    // Nothing writes a non-NONE value here anymore; ProjectSanitizer clears it back to
    // NONE the moment a clip's effects are touched, so there's exactly one live source
    // of truth (effects) once a clip has been edited under the new model.
    val filter: FilterType = FilterType.NONE,
    // Added this round (UX spec section 20, "reorderable multi-effect stack," Tier 1):
    // a clip can now carry several filters applied in order, not just one. Defaults to
    // empty so every VideoClip constructed before this round, and every already-saved
    // project (which has `filter` but no `effects` key at all), decodes unchanged --
    // effectiveEffects() below is what actually resolves "what applies to this clip,"
    // never this field read directly.
    val effects: List<FilterType> = emptyList(),
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
 * The single place that decides "which filters actually apply to this clip, in what
 * order" -- so CompositionBuilder (export), the live single-clip preview, and the
 * effect-stack UI can never disagree. A clip saved under the new model (effects
 * non-empty) uses that list as-is; a clip saved under the OLD single-filter model
 * (effects empty, legacy filter non-NONE) is read as a one-item stack, so nothing
 * about an already-edited project's look changes just from opening it after this
 * update. A brand-new, never-filtered clip resolves to an empty stack either way.
 */
fun VideoClip.effectiveEffects(): List<FilterType> =
    effects.ifEmpty { if (filter != FilterType.NONE) listOf(filter) else emptyList() }

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
