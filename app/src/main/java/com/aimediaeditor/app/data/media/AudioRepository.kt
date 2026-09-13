package com.aimediaeditor.app.data.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AudioItem(
    val id: Long,
    val uri: android.net.Uri,
    val displayName: String,
    val durationMs: Long
)

/**
 * Separate from [MediaRepository] on purpose: Phase 1's photo/video query
 * was already written and is left alone here, per the "don't touch
 * working Phase 1 code" instruction -- this is a new, additive query
 * against a different MediaStore table.
 */
class AudioRepository(private val context: Context) {

    suspend fun loadAudio(): List<AudioItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION
        )
        val items = mutableListOf<AudioItem>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += AudioItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    displayName = cursor.getString(nameCol) ?: "",
                    durationMs = cursor.getLong(durationCol)
                )
            }
        }
        items
    }
}
