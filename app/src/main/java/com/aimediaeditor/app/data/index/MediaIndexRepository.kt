package com.aimediaeditor.app.data.index

import android.content.Context
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bridge between the Room-backed index ([MediaIndexEntity]/[MediaLabelEntity]) and
 * the pure, testable search logic ([MediaSearchQuery]/[IndexedMediaSummary]) -- converts
 * one way for indexing (analyze + write), the other for search (read + convert + match).
 */
class MediaIndexRepository(context: Context) {

    private val dao = AppDatabase.get(context).mediaIndexDao()
    private val analyzer = MediaAnalyzer(context.applicationContext)

    /**
     * Analyzes and stores every item in [allMedia] not already indexed, then removes
     * index rows for anything no longer present (deleted from the device since the
     * last run). One item's analysis failure never aborts the rest -- [MediaAnalyzer]
     * itself degrades gracefully per-field, and this loop additionally skips an item
     * whose analysis throws outright rather than losing the whole batch.
     */
    suspend fun indexPendingMedia(allMedia: List<MediaItem>, onProgress: (indexed: Int, total: Int) -> Unit = { _, _ -> }) {
        withContext(Dispatchers.IO) {
            try {
                val alreadyIndexed = dao.getAllIndexedIds().toSet()
                val pending = allMedia.filterNot { it.id in alreadyIndexed }
                pending.forEachIndexed { index, item ->
                    try {
                        val (entity, labels) = analyzer.analyze(item)
                        dao.replaceMediaWithLabels(entity, labels)
                    } catch (e: Exception) {
                        // This one item's analysis failed outright (not just a
                        // degraded/partial result, which MediaAnalyzer already
                        // handles internally) -- skip it, keep indexing the rest.
                    }
                    onProgress(index + 1, pending.size)
                }
                dao.pruneDeleted(allMedia.map { it.id })
            } finally {
                analyzer.close()
            }
        }
    }

    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaRows = dao.getAllMedia()
        val labelsByMediaId = dao.getAllLabels().groupBy({ it.mediaId }, { it.label })
        val summaries = mediaRows.map { row ->
            IndexedMediaSummary(
                mediaId = row.mediaId,
                isVideo = row.mediaType == MediaType.VIDEO.name,
                dateAddedSeconds = row.dateAddedSeconds,
                width = row.width,
                height = row.height,
                localityName = row.localityName,
                hasFaces = row.hasFaces,
                labels = labelsByMediaId[row.mediaId].orEmpty(),
                qualityScore = row.qualityScore
            )
        }
        val matchedIds = MediaSearchQuery.search(summaries, query, System.currentTimeMillis()).map { it.mediaId }
        val rowsById = mediaRows.associateBy { it.mediaId }
        // Preserve MediaSearchQuery's own ordering (quality- or recency-sorted), not
        // whatever order Room happened to return rows in.
        matchedIds.mapNotNull { id -> rowsById[id]?.let { it.toMediaItem() } }
    }

    private fun MediaIndexEntity.toMediaItem() = MediaItem(
        id = mediaId,
        uri = android.net.Uri.parse(uri),
        displayName = displayName,
        dateAddedSeconds = dateAddedSeconds,
        durationMs = durationMs,
        width = width,
        height = height,
        mimeType = mimeType,
        type = if (mediaType == MediaType.VIDEO.name) MediaType.VIDEO else MediaType.IMAGE
    )
}
