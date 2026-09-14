package com.aimediaeditor.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls a single representative frame out of a video file. Unlike some other pieces of
 * this project, MediaMetadataRetriever is core android.media -- not Media3 -- so its
 * signature doesn't carry the "couldn't verify against this exact library version"
 * uncertainty Media3 calls have had in this sandbox; it's been stable since API 1.
 *
 * Deliberately no caching beyond what the caller's own `remember` gives it for one
 * composition's lifetime -- fine for the handful of clips a project actually has (same
 * scale caveat as everywhere else in this MVP); a persistent thumbnail cache is future
 * work, not something this needs yet.
 */
object VideoThumbnailLoader {

    /** Returns null on any failure (corrupt file, unsupported codec, revoked permission,
     *  a URI that no longer resolves) rather than throwing -- a missing thumbnail should
     *  never be why a clip fails to render. */
    suspend fun loadFrame(context: Context, uri: Uri, atUs: Long = 0L): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(atUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
}
