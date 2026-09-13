package com.aimediaeditor.app.editor.export

import com.aimediaeditor.app.editor.model.ProjectState

/**
 * WorkManager's normal input path is a small [androidx.work.Data] key-value
 * bundle, not an arbitrary object -- serializing a whole ProjectState to
 * pass it through that would mean adding kotlinx.serialization for one call
 * site. Since this app only ever runs one export started from its own live
 * UI (never restarts a queued export after process death), an in-memory
 * holder is a reasonable trade -- same pattern as the nav graph's
 * PendingProjectHolder, same reasoning.
 */
object PendingExportHolder {
    @Volatile
    var project: ProjectState? = null
}
