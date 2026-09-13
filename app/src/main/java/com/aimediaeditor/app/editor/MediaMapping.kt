package com.aimediaeditor.app.editor

import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.editor.model.VideoClip
import kotlin.random.Random

/** Photos have no inherent duration -- give them a sensible default when placed on a timeline. */
private const val DEFAULT_PHOTO_DURATION_MS = 3000L

fun MediaItem.toVideoClip(): VideoClip {
    val duration = durationMs ?: DEFAULT_PHOTO_DURATION_MS
    return VideoClip(
        id = "clip_${id}_${Random.nextInt()}",
        sourceUri = uri.toString(),
        sourceDurationMs = duration,
        trimStartMs = 0L,
        trimEndMs = duration,
        sourceType = type,
        sourceWidth = width.takeIf { it > 0 } ?: 1920,
        sourceHeight = height.takeIf { it > 0 } ?: 1080
    )
}

fun List<MediaItem>.toVideoClips(): List<VideoClip> = map { it.toVideoClip() }
