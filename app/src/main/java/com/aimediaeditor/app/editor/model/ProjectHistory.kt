package com.aimediaeditor.app.editor.model

/**
 * Linear undo/redo over [ProjectState] snapshots. Spec section 27 requires
 * every AI action to be reversible; this makes every manual one reversible
 * too, for free, since [ProjectState] is already immutable -- "snapshot" is
 * just "keep the reference," no deep copy needed.
 *
 * Plain Kotlin, no Android dependency, same as the rest of editor/model.
 */
class ProjectHistory(initial: ProjectState) {
    private val past = ArrayDeque<ProjectState>()
    private val future = ArrayDeque<ProjectState>()

    var current: ProjectState = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Runs [command] through [ProjectSanitizer] and records the result, unless it was a no-op. */
    fun apply(command: EditCommand) {
        val next = ProjectSanitizer.apply(current, command)
        if (next == current) return // unknown clip/overlay/track id, or a genuine no-op -- nothing to undo
        past.addLast(current)
        current = next
        future.clear() // a fresh edit invalidates whatever redo branch existed
    }

    fun undo() {
        if (past.isEmpty()) return
        future.addLast(current)
        current = past.removeLast()
    }

    fun redo() {
        if (future.isEmpty()) return
        past.addLast(current)
        current = future.removeLast()
    }
}
