package com.aimediaeditor.app.data.project

import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.editor.model.ProjectState
import kotlinx.serialization.Serializable

/**
 * What actually gets written to disk for one project (spec section 22,
 * autosave). [ProjectState] itself is only the EDL -- editable content the
 * sanitizer/AI layer reason about -- so persistence metadata (display name,
 * timestamps) lives in the wrapper around it instead of on the EDL itself.
 */
@Serializable
data class ProjectRecord(
    val project: ProjectState,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/**
 * Lightweight row for the Projects list (spec section 3's "recent editable
 * projects") -- avoids the list screen deserializing every clip/overlay of
 * every project just to show a name and a thumbnail.
 */
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val clipCount: Int,
    val durationMs: Long,
    val thumbnailUri: String?,
    // Coil can't decode a video frame yet (same limitation as Home's grid -- see README),
    // so the UI needs to know when to show a play glyph instead of attempting an image load.
    val thumbnailIsVideo: Boolean
)

fun ProjectRecord.toSummary(): ProjectSummary {
    val first = project.clips.firstOrNull()
    return ProjectSummary(
        id = project.id,
        name = name,
        updatedAtMs = updatedAtMs,
        clipCount = project.clips.size,
        durationMs = project.durationMs,
        thumbnailUri = first?.sourceUri,
        thumbnailIsVideo = first?.sourceType == MediaType.VIDEO
    )
}
