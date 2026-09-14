package com.aimediaeditor.app.editor.model

/**
 * The strict, closed command taxonomy an LLM is allowed to emit
 * (architecture notes section 7, spec section 21's EDIT_COMMANDS).
 * The AI never executes anything directly -- it only ever produces one
 * of these, and every one of them still goes through [ProjectSanitizer]
 * before it's allowed to touch [ProjectState]. No arbitrary code
 * execution, no free-form string manipulation: if it isn't one of
 * these cases, it isn't a valid command.
 */
sealed interface EditCommand {
    data class TrimClip(val clipId: String, val startMs: Long, val endMs: Long) : EditCommand
    data class SplitClip(val clipId: String, val splitAtMs: Long) : EditCommand
    data class ReorderClips(val orderedClipIds: List<String>) : EditCommand
    data class DeleteClip(val clipId: String) : EditCommand
    data class SetAspectRatio(val ratio: AspectRatio) : EditCommand
    // Replaced this round's single-select ApplyFilter(filter, clipId): a clip now
    // carries a reorderable STACK of filters (UX spec section 20, Tier 1), not one.
    // Toggle adds the filter to the clip's stack if absent, removes it if present --
    // no separate "apply"/"remove" pair needed since a stack's effects are inherently
    // add/remove, not overwrite. (The old command's null-clipId "apply to whole
    // project" case had no caller anywhere in the app and isn't carried forward --
    // a multi-select stack applied identically across every clip in one command has
    // murkier semantics, e.g. what "toggle" means when clips disagree, so this stays
    // per-clip only until something actually needs the project-wide case.)
    data class ToggleEffect(val clipId: String, val filter: FilterType) : EditCommand
    // Changes the stack's order without changing its membership -- ProjectSanitizer
    // drops any entry that isn't already applied rather than trusting this list as a
    // full replacement, same "never trust the caller's numbers" contract every other
    // command in this file follows.
    data class ReorderEffects(val clipId: String, val orderedFilters: List<FilterType>) : EditCommand
    data class SmartReframe(val clipId: String, val focalPoint: FocalPoint) : EditCommand
    data class AddText(val text: String, val startMs: Long, val endMs: Long, val yPositionFraction: Float) : EditCommand
    data class AddAudio(val sourceUri: String, val startMs: Long, val volume: Float, val sourceDurationMs: Long = 0L) : EditCommand
    // Added this round: speed was already a field on VideoClip from Phase 2,
    // but nothing could change it. It belongs in the taxonomy like any other
    // edit, so it goes through the same validation gate.
    data class SetSpeed(val clipId: String, val speed: Float) : EditCommand
    // Also added this round: VideoClip.volume and AudioTrack.volume have been
    // in the EDL since Phase 2 with nothing able to change them, and nothing
    // applying them either. Both ends are wired up now.
    data class SetClipVolume(val clipId: String, val volume: Float) : EditCommand
    data class SetAudioVolume(val trackId: String, val volume: Float) : EditCommand
    data class SetAudioLooping(val trackId: String, val isLooping: Boolean) : EditCommand
    // Added this round: an audio track's placement (startMs) and length (durationMs)
    // were only ever set once, implicitly, at AddAudio time -- there was no way to move
    // or resize a track afterward, so "attach the music at exactly this point" wasn't
    // possible without deleting and re-adding. The audio timeline strip commits both
    // together (drag-to-reposition and drag-to-trim are the same gesture family as
    // TrimClip/SmartReframe: live while dragging, one command on release).
    data class SetAudioPosition(val trackId: String, val startMs: Long, val durationMs: Long) : EditCommand
    // Added this round: a text overlay's X position was always fixed at centre (0.5),
    // with only Y adjustable, and only via 3 dialog presets -- no way to drag it into
    // place on the preview like the UX spec's "direct manipulation" principle asks for.
    // Commits on drag release, same as every other live-drag command in this taxonomy.
    data class SetTextPosition(val overlayId: String, val xFraction: Float, val yFraction: Float) : EditCommand
    // Added in Phase 2: undo() can already reverse the most recent add, but a user
    // removing one specific earlier text/audio item shouldn't have to undo everything
    // that came after it too.
    data class RemoveTextOverlay(val overlayId: String) : EditCommand
    data class RemoveAudioTrack(val trackId: String) : EditCommand
}
