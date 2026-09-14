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

    /**
     * A real filmstrip: [tileCount] frames, evenly spaced across [startMs]..[endMs] (the
     * clip's CURRENT trim range, not the whole source), each centred in its own slice --
     * tile i covers the time range startMs + i*span/tileCount .. startMs + (i+1)*span/tileCount,
     * sampled at its midpoint. One [MediaMetadataRetriever] is opened once and reused for
     * every frame in the strip rather than one retriever per frame, since re-opening the
     * source file per tile is the expensive part, not the individual frame decode.
     *
     * Each entry is independently nullable -- one bad frame (a keyframe gap, a moment the
     * decoder chokes on) doesn't take down the rest of the strip, since the caller can
     * just show a plain tile in that one slot instead of failing the whole thumbnail.
     */
    suspend fun loadFilmstrip(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        tileCount: Int
    ): List<Bitmap?> = withContext(Dispatchers.IO) {
        if (tileCount <= 0) return@withContext emptyList()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val span = (endMs - startMs).coerceAtLeast(0L)
            (0 until tileCount).map { i ->
                val fraction = (i + 0.5f) / tileCount
                val atUs = (startMs + (span * fraction).toLong()).coerceAtLeast(0L) * 1000L
                try {
                    retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            List(tileCount) { null }
        } finally {
            retriever.release()
        }
    }
}
