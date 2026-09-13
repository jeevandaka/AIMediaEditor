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
    data class ApplyFilter(val filter: FilterType, val clipId: String?) : EditCommand // null = whole project
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
    // Added in Phase 2: undo() can already reverse the most recent add, but a user
    // removing one specific earlier text/audio item shouldn't have to undo everything
    // that came after it too.
    data class RemoveTextOverlay(val overlayId: String) : EditCommand
    data class RemoveAudioTrack(val trackId: String) : EditCommand
}
