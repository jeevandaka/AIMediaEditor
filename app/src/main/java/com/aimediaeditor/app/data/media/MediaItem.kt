package com.aimediaeditor.app.data.media

import android.net.Uri

/**
 * A single photo or video read from MediaStore. Mirrors a MediaStore
 * row closely for now -- richer fields (location, detected objects,
 * embeddings; see spec section 6) arrive with the local index in Phase 5.
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAddedSeconds: Long,
    val durationMs: Long?,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val type: MediaType
)

enum class MediaType { IMAGE, VIDEO }
