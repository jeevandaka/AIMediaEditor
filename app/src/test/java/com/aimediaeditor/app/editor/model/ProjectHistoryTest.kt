package com.aimediaeditor.app.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL production ProjectHistory. Undo/redo (spec section 27) has
 * been in the app since Phase 2 with no automated coverage of its own -- verified only
 * by manual on-device testing instructions. This is plain Kotlin over an immutable
 * ProjectState (no Android dependency), so it's exactly the kind of logic that's been
 * unit-testable the whole time; this suite closes that gap.
 */
class ProjectHistoryTest {

    private fun sampleClip(id: String) = VideoClip(
        id = id,
        sourceUri = "content://x/$id",
        sourceDurationMs = 10_000L,
        trimStartMs = 0L,
        trimEndMs = 5000L
    )

    @Test
    fun `starts with nothing to undo or redo`() {
        val initial = ProjectState(id = "p", clips = listOf(sampleClip("c1")))
        val history = ProjectHistory(initial)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(initial, history.current)
    }

    @Test
    fun `apply records a real edit and enables undo`() {
        val initial = ProjectState(id = "p", clips = listOf(sampleClip("c1")))
        val history = ProjectHistory(initial)
        history.apply(EditCommand.ToggleEffect("c1", FilterType.CINEMATIC))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(listOf(FilterType.CINEMATIC), history.current.clips.single().effectiveEffects())
    }

    @Test
    fun `apply does not record a genuine sanitizer no-op`() {
        val initial = ProjectState(id = "p", clips = listOf(sampleClip("c1")))
        val history = ProjectHistory(initial)
        // c1 is the only/last clip -- ProjectSanitizer treats this as a no-op.
        history.apply(EditCommand.ToggleTransition("c1"))
        assertFalse(history.canUndo)
        assertEquals(initial, history.current)
    }

    @Test
    fun `undo then redo reverts and reapplies a single edit exactly`() {
        val initial = ProjectState(id = "p", clips = listOf(sampleClip("c1")))
        val history = ProjectHistory(initial)
        history.apply(EditCommand.ToggleEffect("c1", FilterType.CINEMATIC))
        val afterEdit = history.current

        history.undo()
        assertEquals(initial, history.current)
        assertTrue(history.canRedo)
        assertFalse(history.canUndo)

        history.redo()
        assertEquals(afterEdit, history.current)
    }

    @Test
    fun `walks a multi-step undo redo sequence in the correct order`() {
        val initial = ProjectState(id = "p", clips = listOf(sampleClip("c1")))
        val history = ProjectHistory(initial)
        history.apply(EditCommand.ToggleEffect("c1", FilterType.CINEMATIC))
        val afterFirst = history.current
        history.apply(EditCommand.ToggleEffect("c1", FilterType.VIVID))
        val afterSecond = history.current
        assertEquals(listOf(FilterType.CINEMATIC, FilterType.VIVID), afterSecond.clips.single().effectiveEffects())

        history.undo()
        assertEquals(afterFirst, history.current)
        history.undo()
        assertEquals(initial, history.current)
        assertFalse(history.canUndo)

        history.redo()
        assertEquals(afterFirst, history.current)
        history.redo()
        assertEquals(afterSecond, history.current)
        assertFalse(history.canRedo)
    }

    @Test
    fun `a fresh edit after undo clears the redo branch`() {
        val initial = ProjectState(id = "p", clips = listOf(sampleClip("c1")))
        val history = ProjectHistory(initial)
        history.apply(EditCommand.ToggleEffect("c1", FilterType.CINEMATIC))
        history.undo()
        assertTrue(history.canRedo)

        history.apply(EditCommand.ToggleEffect("c1", FilterType.VIVID)) // a NEW edit, not a redo
        assertFalse(history.canRedo)
        assertEquals(listOf(FilterType.VIVID), history.current.clips.single().effectiveEffects())
    }

    @Test
    fun `undo and redo on empty stacks are safe no-ops`() {
        val initial = ProjectState(id = "p", clips = listOf(sampleClip("c1")))
        val history = ProjectHistory(initial)
        history.undo()
        assertEquals(initial, history.current)
        history.redo()
        assertEquals(initial, history.current)
    }
}
