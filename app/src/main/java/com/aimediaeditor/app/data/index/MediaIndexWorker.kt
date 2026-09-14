package com.aimediaeditor.app.data.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aimediaeditor.app.data.media.MediaRepository

/**
 * Runs the media-analysis pipeline in the background, plain [CoroutineWorker] (no
 * foreground notification -- unlike [com.aimediaeditor.app.editor.export.ExportWorker],
 * indexing isn't something the user is actively waiting on, so it shouldn't announce
 * itself the same way). Triggered once after the media permission is granted
 * ([com.aimediaeditor.app.ui.home.HomeViewModel]); [MediaIndexRepository.indexPendingMedia]
 * is itself incremental (only analyzes items not already indexed), so re-running this on
 * every app launch is cheap once the library has been fully indexed once.
 */
class MediaIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val mediaRepository = MediaRepository(applicationContext)
        val indexRepository = MediaIndexRepository(applicationContext)
        val allMedia = mediaRepository.loadAllMedia()
        indexRepository.indexPendingMedia(allMedia)
        Result.success()
    } catch (e: Exception) {
        // A failed indexing pass shouldn't retry-loop forever or surface as an app
        // crash -- search just stays limited to whatever was indexed before, same
        // "degrade, don't fail loudly" contract MediaAnalyzer already follows per-item.
        Result.failure()
    }
}
