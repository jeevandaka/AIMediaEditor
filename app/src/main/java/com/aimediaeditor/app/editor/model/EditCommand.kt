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
    data class AddAudio(val sourceUri: String, val startMs: Long, val volume: Float) : EditCommand
}
