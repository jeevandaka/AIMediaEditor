package com.aimediaeditor.app.editor.model

/**
 * Architecture notes section 1.4's "validation gate": every command --
 * manual or AI-generated -- is clamped against the actual bounds of the
 * project before it's applied. This never trusts the caller's numbers;
 * an LLM that hallucinates a clip ID or an out-of-range timestamp gets
 * a safe no-op or a clamped result here, never a crash or corrupted state.
 *
 * The boundary arithmetic below (clampRange, split-point math) was
 * hand-traced against edge cases -- empty timelines, clips shorter than
 * the minimum duration, wildly out-of-range AI output -- and verified
 * against a throwaway Python port before being written here, since this
 * file can't be compiled in the sandbox that produced it. See the
 * project README for what "verified" means in that sentence.
 */
object ProjectSanitizer {

    private const val MIN_CLIP_DURATION_MS = 200L
    private const val MIN_TEXT_DURATION_MS = 300L
    private const val MAX_TEXT_LENGTH = 280
    private const val MAX_VOLUME = 2f

    fun apply(state: ProjectState, command: EditCommand): ProjectState = when (command) {

        is EditCommand.TrimClip -> state.mapClip(command.clipId) { clip ->
            val (start, end) = clampRange(command.startMs, command.endMs, clip.sourceDurationMs, MIN_CLIP_DURATION_MS)
            clip.copy(trimStartMs = start, trimEndMs = end)
        }

        is EditCommand.SplitClip -> state.splitClip(command.clipId, command.splitAtMs)

        is EditCommand.ReorderClips -> {
            val byId = state.clips.associateBy { it.id }
            val reordered = command.orderedClipIds.mapNotNull { byId[it] }
            // A clip the AI forgot to mention keeps its place at the end
            // rather than silently disappearing from the project.
            val missing = state.clips.filterNot { it.id in command.orderedClipIds }
            state.copy(clips = reordered + missing)
        }

        is EditCommand.DeleteClip ->
            state.copy(clips = state.clips.filterNot { it.id == command.clipId })

        is EditCommand.SetAspectRatio -> state.copy(aspectRatio = command.ratio)

        is EditCommand.ApplyFilter -> if (command.clipId == null) {
            state.copy(clips = state.clips.map { it.copy(filter = command.filter) })
        } else {
            state.mapClip(command.clipId) { it.copy(filter = command.filter) }
        }

        is EditCommand.SmartReframe -> state.mapClip(command.clipId) {
            it.copy(
                focalPoint = FocalPoint(
                    x = command.focalPoint.x.coerceIn(0f, 1f),
                    y = command.focalPoint.y.coerceIn(0f, 1f)
                )
            )
        }

        is EditCommand.AddText -> {
            val (start, end) = clampRange(command.startMs, command.endMs, state.durationMs, MIN_TEXT_DURATION_MS)
            state.copy(
                textOverlays = state.textOverlays + TextOverlay(
                    id = newId("text"),
                    text = command.text.take(MAX_TEXT_LENGTH),
                    startMs = start,
                    endMs = end,
                    yPositionFraction = command.yPositionFraction.coerceIn(0f, 1f)
                )
            )
        }

        is EditCommand.AddAudio -> state.copy(
            audioTracks = state.audioTracks + AudioTrack(
                id = newId("audio"),
                sourceUri = command.sourceUri,
                startMs = command.startMs.coerceIn(0, state.durationMs.coerceAtLeast(0)),
                volume = command.volume.coerceIn(0f, MAX_VOLUME)
            )
        )
    }

    private fun ProjectState.mapClip(clipId: String, transform: (VideoClip) -> VideoClip): ProjectState =
        copy(clips = clips.map { if (it.id == clipId) transform(it) else it })

    private fun ProjectState.splitClip(clipId: String, splitAtMs: Long): ProjectState {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index == -1) return this // unknown clip id -> safe no-op, not a crash
        val clip = clips[index]

        val minSplit = clip.trimStartMs + MIN_CLIP_DURATION_MS
        val maxSplit = clip.trimEndMs - MIN_CLIP_DURATION_MS
        if (minSplit >= maxSplit) return this // clip too short to yield two valid pieces

        val splitPoint = splitAtMs.coerceIn(minSplit, maxSplit)
        val first = clip.copy(trimEndMs = splitPoint)
        val second = clip.copy(id = newId("clip"), trimStartMs = splitPoint)

        val newClips = clips.toMutableList().apply {
            removeAt(index)
            addAll(index, listOf(first, second))
        }
        return copy(clips = newClips)
    }

    /**
     * Clamps [startRaw, endRaw] into [0, timelineEnd], then tries to
     * stretch it to at least [minDuration] -- but only as far as
     * [timelineEnd] allows, so this never throws even when the
     * timeline itself is shorter than the requested minimum.
     */
    private fun clampRange(startRaw: Long, endRaw: Long, timelineEnd: Long, minDuration: Long): Pair<Long, Long> {
        val safeEnd = timelineEnd.coerceAtLeast(0)
        val start = startRaw.coerceIn(0, safeEnd)
        val end = endRaw.coerceIn(start, safeEnd)
        return if (end - start >= minDuration || safeEnd - start < minDuration) {
            start to end
        } else {
            start to (start + minDuration).coerceAtMost(safeEnd)
        }
    }

    private fun newId(prefix: String): String = "${prefix}_${(0..Int.MAX_VALUE).random()}"
}
