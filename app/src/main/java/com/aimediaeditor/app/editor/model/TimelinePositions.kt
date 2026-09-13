package com.aimediaeditor.app.editor.model

/** Sum of the durations of every clip before [clipId]. 0 if the clip is first or not found. */
fun ProjectState.clipStartOffsetMs(clipId: String): Long {
    var offset = 0L
    for (clip in clips) {
        if (clip.id == clipId) return offset
        offset += clip.durationMs
    }
    return 0L
}
