package com.aimediaeditor.app.data.index

import android.content.Context
import com.aimediaeditor.app.ai.AiMediaSelectionResult
import com.aimediaeditor.app.ai.LocalMediaSelectionService
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

    private companion object {
        // Bounds the on-device model's prompt length -- see aiSearch()'s doc comment.
        const val MAX_CANDIDATES = 150
    }

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
        val (mediaRows, summaries) = loadIndexAsSummaries()
        val matchedIds = MediaSearchQuery.search(summaries, query, System.currentTimeMillis()).map { it.mediaId }
        val rowsById = mediaRows.associateBy { it.mediaId }
        // Preserve MediaSearchQuery's own ordering (quality- or recency-sorted), not
        // whatever order Room happened to return rows in.
        matchedIds.mapNotNull { id -> rowsById[id]?.let { it.toMediaItem() } }
    }

    /**
     * "Stage 2" of the search chain: the same deterministic Stage 1 matching [search]
     * uses, but as a RECALL pass feeding a bounded candidate list to the on-device
     * model ([LocalMediaSelectionService]) for ranking/selection with real natural-
     * language judgement, instead of Stage 1's own keyword/date-phrase result being
     * the final answer. Falls back to the [MAX_CANDIDATES] most recent items when
     * Stage 1 finds nothing at all (a phrasing its deterministic parser doesn't
     * recognize shouldn't also blind Stage 2, which doesn't depend on that parser).
     *
     * [modelFilePath] comes from [com.aimediaeditor.app.ai.LocalLlmModelManager] --
     * the caller is responsible for confirming the model is actually downloaded
     * first; this doesn't check.
     */
    suspend fun aiSearch(context: Context, modelFilePath: String, query: String): AiSearchOutcome =
        withContext(Dispatchers.IO) {
            val (mediaRows, summaries) = loadIndexAsSummaries()
            if (summaries.isEmpty()) {
                return@withContext AiSearchOutcome.Failure(
                    "Your media library hasn't finished indexing yet -- try again in a moment."
                )
            }
            val deterministic = MediaSearchQuery.search(summaries, query, System.currentTimeMillis())
            val candidates = (deterministic.ifEmpty { summaries.sortedByDescending { it.dateAddedSeconds } })
                .take(MAX_CANDIDATES)

            when (val result = LocalMediaSelectionService.requestSelection(context, modelFilePath, candidates, query)) {
                is AiMediaSelectionResult.Success -> {
                    val rowsById = mediaRows.associateBy { it.mediaId }
                    val items = result.mediaIds.mapNotNull { id -> rowsById[id]?.let { it.toMediaItem() } }
                    AiSearchOutcome.Success(items, result.assistantMessage)
                }
                is AiMediaSelectionResult.Failure -> AiSearchOutcome.Failure(result.message)
            }
        }

    private suspend fun loadIndexAsSummaries(): Pair<List<MediaIndexEntity>, List<IndexedMediaSummary>> {
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
        return mediaRows to summaries
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

/** Outcome of one [MediaIndexRepository.aiSearch] call. */
sealed interface AiSearchOutcome {
    data class Success(val items: List<MediaItem>, val assistantMessage: String?) : AiSearchOutcome
    data class Failure(val message: String) : AiSearchOutcome
}
