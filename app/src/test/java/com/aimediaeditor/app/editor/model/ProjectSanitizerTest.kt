package com.aimediaeditor.app.editor.model

import com.aimediaeditor.app.data.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL ProjectSanitizer -- not a mirror -- so it can never drift
 * from what actually ships. ProjectSanitizer's own doc comment says its boundary
 * arithmetic was "hand-traced against edge cases... and verified against a throwaway
 * Python port," never actually executed as Kotlin before this file existed; these
 * cases were first proven to pass against a faithful copy of this exact logic in a
 * standalone Kotlin/JVM script (this project's substitute for a real Android build in
 * a sandbox with no SDK), then ported here as permanent, `./gradlew test`-runnable
 * regression coverage.
 */
class ProjectSanitizerTest {

    private fun sampleVideoClip(id: String, trimStartMs: Long = 0L, durationMs: Long = 5000L) = VideoClip(
        id = id,
        sourceUri = "content://x/$id",
        sourceDurationMs = 10_000L,
        trimStartMs = trimStartMs,
        trimEndMs = trimStartMs + durationMs
    )

    @Test
    fun `TrimClip clamps a video clip's range into 0 to sourceDurationMs`() {
        val clip = sampleVideoClip("c1")
        val project = ProjectState(id = "p", clips = listOf(clip))
        val result = ProjectSanitizer.apply(project, EditCommand.TrimClip("c1", startMs = -500L, endMs = 999_999L))
        val trimmed = result.clips.single()
        assertEquals(0L, trimmed.trimStartMs)
        assertEquals(clip.sourceDurationMs, trimmed.trimEndMs)
    }

    @Test
    fun `TrimClip on a photo edits length, resets start to 0, clamps to MAX_PHOTO_DURATION_MS`() {
        val photo = sampleVideoClip("p1", trimStartMs = 2000L, durationMs = 1L).copy(sourceType = MediaType.IMAGE)
        val project = ProjectState(id = "p", clips = listOf(photo))
        val result = ProjectSanitizer.apply(project, EditCommand.TrimClip("p1", startMs = 0L, endMs = 999_999L))
        val trimmed = result.clips.single()
        assertEquals(0L, trimmed.trimStartMs)
        assertEquals(MAX_PHOTO_DURATION_MS, trimmed.trimEndMs)
    }

    @Test
    fun `SplitClip is a no-op for an unknown clip id`() {
        val project = ProjectState(id = "p", clips = listOf(sampleVideoClip("c1")))
        val result = ProjectSanitizer.apply(project, EditCommand.SplitClip("nonexistent", 2500L))
        assertEquals(project, result)
    }

    @Test
    fun `SplitClip is a no-op when the clip is too short to yield two valid pieces`() {
        val tiny = sampleVideoClip("c1", durationMs = 100L) // shorter than the 200ms minimum
        val project = ProjectState(id = "p", clips = listOf(tiny))
        val result = ProjectSanitizer.apply(project, EditCommand.SplitClip("c1", 50L))
        assertEquals(project, result)
    }

    @Test
    fun `SplitClip produces two clips, the second half gets a new id`() {
        val clip = sampleVideoClip("c1", durationMs = 5000L)
        val project = ProjectState(id = "p", clips = listOf(clip))
        val result = ProjectSanitizer.apply(project, EditCommand.SplitClip("c1", 2500L))
        assertEquals(2, result.clips.size)
        val (first, second) = result.clips
        assertEquals("c1", first.id)
        assertTrue(second.id != "c1")
        assertEquals(2500L, first.trimEndMs)
        assertEquals(2500L, second.trimStartMs)
    }

    @Test
    fun `SplitClip re-attaches a transition to the second half, not the two new halves`() {
        // Regression test for a real bug fixed the same round transitions were added:
        // splitting a clip that had a transition after it left the transition keyed to
        // the ORIGINAL id (now the first half), so it would silently play between the
        // two new halves instead of between the split clip and its original neighbour.
        val clip = sampleVideoClip("c1", durationMs = 5000L)
        val next = sampleVideoClip("c2")
        val project = ProjectState(
            id = "p",
            clips = listOf(clip, next),
            transitions = listOf(ClipTransition(afterClipId = "c1"))
        )
        val result = ProjectSanitizer.apply(project, EditCommand.SplitClip("c1", 2500L))
        assertEquals(3, result.clips.size)
        val secondHalfId = result.clips[1].id
        assertTrue(secondHalfId != "c1")
        assertEquals(secondHalfId, result.transitions.single().afterClipId)
    }

    @Test
    fun `ToggleEffect adds on first tap, removes on second tap`() {
        val clip = sampleVideoClip("c1")
        val project = ProjectState(id = "p", clips = listOf(clip))
        val afterAdd = ProjectSanitizer.apply(project, EditCommand.ToggleEffect("c1", FilterType.CINEMATIC))
        assertEquals(listOf(FilterType.CINEMATIC), afterAdd.clips.single().effectiveEffects())
        val afterRemove = ProjectSanitizer.apply(afterAdd, EditCommand.ToggleEffect("c1", FilterType.CINEMATIC))
        assertTrue(afterRemove.clips.single().effectiveEffects().isEmpty())
    }

    @Test
    fun `ToggleEffect migrates a legacy single-filter clip into the effect stack`() {
        val legacyClip = sampleVideoClip("c1").copy(filter = FilterType.CINEMATIC) // pre-multi-effect-stack shape
        val project = ProjectState(id = "p", clips = listOf(legacyClip))
        val result = ProjectSanitizer.apply(project, EditCommand.ToggleEffect("c1", FilterType.VIVID))
        val updated = result.clips.single()
        assertEquals(listOf(FilterType.CINEMATIC, FilterType.VIVID), updated.effectiveEffects())
        assertEquals(FilterType.NONE, updated.filter) // legacy field cleared once migrated
    }

    @Test
    fun `ReorderEffects drops filters not applied, appends omitted ones after the requested order`() {
        val clip = sampleVideoClip("c1").copy(effects = listOf(FilterType.CINEMATIC, FilterType.BRIGHT))
        val project = ProjectState(id = "p", clips = listOf(clip))
        val result = ProjectSanitizer.apply(
            project,
            EditCommand.ReorderEffects("c1", listOf(FilterType.BRIGHT, FilterType.VIVID)) // VIVID isn't applied
        )
        assertEquals(listOf(FilterType.BRIGHT, FilterType.CINEMATIC), result.clips.single().effectiveEffects())
    }

    @Test
    fun `ToggleTransition adds on first tap, removes on second tap`() {
        val project = ProjectState(id = "p", clips = listOf(sampleVideoClip("c1"), sampleVideoClip("c2")))
        val afterAdd = ProjectSanitizer.apply(project, EditCommand.ToggleTransition("c1"))
        assertEquals(listOf("c1"), afterAdd.transitions.map { it.afterClipId })
        val afterRemove = ProjectSanitizer.apply(afterAdd, EditCommand.ToggleTransition("c1"))
        assertTrue(afterRemove.transitions.isEmpty())
    }

    @Test
    fun `ToggleTransition is a no-op on the last clip`() {
        val project = ProjectState(id = "p", clips = listOf(sampleVideoClip("c1"), sampleVideoClip("c2")))
        val result = ProjectSanitizer.apply(project, EditCommand.ToggleTransition("c2"))
        assertEquals(project, result)
    }

    @Test
    fun `ToggleTransition is a no-op on an unknown clip id`() {
        val project = ProjectState(id = "p", clips = listOf(sampleVideoClip("c1"), sampleVideoClip("c2")))
        val result = ProjectSanitizer.apply(project, EditCommand.ToggleTransition("nonexistent"))
        assertEquals(project, result)
    }

    @Test
    fun `SetTransitionDuration clamps into 200ms to 2000ms, no-op if nothing to update`() {
        val project = ProjectState(
            id = "p",
            clips = listOf(sampleVideoClip("c1"), sampleVideoClip("c2")),
            transitions = listOf(ClipTransition(afterClipId = "c1"))
        )
        val tooLong = ProjectSanitizer.apply(project, EditCommand.SetTransitionDuration("c1", 999_999L))
        assertEquals(MAX_TRANSITION_DURATION_MS, tooLong.transitions.single().durationMs)

        val tooShort = ProjectSanitizer.apply(project, EditCommand.SetTransitionDuration("c1", 1L))
        assertEquals(MIN_TRANSITION_DURATION_MS, tooShort.transitions.single().durationMs)

        val noTransitionProject = ProjectState(id = "p", clips = listOf(sampleVideoClip("c1")))
        val noOp = ProjectSanitizer.apply(noTransitionProject, EditCommand.SetTransitionDuration("c1", 800L))
        assertTrue(noOp.transitions.isEmpty())
    }

    @Test
    fun `effectiveTransitions filters out a transition whose clip was deleted`() {
        val project = ProjectState(
            id = "p",
            clips = listOf(sampleVideoClip("c1"), sampleVideoClip("c2"), sampleVideoClip("c3")),
            transitions = listOf(ClipTransition(afterClipId = "c2"))
        )
        val afterDelete = ProjectSanitizer.apply(project, EditCommand.DeleteClip("c2"))
        assertTrue(afterDelete.effectiveTransitions().isEmpty())
    }

    @Test
    fun `effectiveTransitions filters out a transition whose clip is now last`() {
        val project = ProjectState(
            id = "p",
            clips = listOf(sampleVideoClip("c1"), sampleVideoClip("c2"), sampleVideoClip("c3")),
            transitions = listOf(ClipTransition(afterClipId = "c2")) // valid: c2 is followed by c3
        )
        assertEquals(1, project.effectiveTransitions().size)
        val afterDeletingLast = ProjectSanitizer.apply(project, EditCommand.DeleteClip("c3"))
        assertTrue(afterDeletingLast.effectiveTransitions().isEmpty())
    }
}
