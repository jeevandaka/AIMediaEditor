@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aimediaeditor.app.editor.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs entirely per architecture-notes section 2.4: an isolated background
 * job (WorkManager, not the main thread) with a foreground notification, so
 * export survives the user backgrounding the app and never blocks the UI.
 *
 * What's NOT independently verifiable without a device, specifically here:
 * whether the foreground-service-type declaration actually satisfies each
 * Android version's requirements (this is exactly the kind of thing that
 * throws MissingForegroundServiceTypeException immediately at runtime if
 * misconfigured -- see the README's device-testing list), and obviously
 * whether Transformer itself produces a correct, playable file on real
 * hardware and codecs.
 */
class ExportWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val project = PendingExportHolder.project
            ?: return Result.failure(workDataOf(KEY_ERROR to "No project queued for export"))

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(workDataOf(KEY_ERROR to "Export needs Android 10 or newer"))
        }
        // Photo clips are supported as of this round -- the only remaining
        // hard requirement is that there is something to export at all,
        // since Media3 rejects an EditedMediaItemSequence with no items.
        if (project.clips.isEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR to "This project has no clips to export"))
        }

        setForeground(createForegroundInfo())

        val tempFile = File(appContext.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        return try {
            val composition = CompositionBuilder.build(project)
            runTransformer(composition, tempFile.absolutePath)
            val savedUri = saveToMediaStore(tempFile)
            Result.success(workDataOf(KEY_OUTPUT_URI to savedUri.toString()))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Export failed")))
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Transformer needs a Looper thread to post its internal callbacks to
     * (Dispatchers.Main is guaranteed to have one; CoroutineWorker.doWork()
     * is not, by default). The callback-based Listener is bridged into a
     * suspend call so the rest of doWork() can stay linear.
     */
    private suspend fun runTransformer(composition: Composition, outputPath: String): Unit =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(appContext)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(composition, outputPath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }

    private fun createForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Video export", NotificationManager.IMPORTANCE_LOW)
            appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Exporting your video")
            .setSmallIcon(android.R.drawable.stat_sys_download) // platform icon -- no custom asset needed
            .setOngoing(true)
            .build()

        // mediaProcessing (API 35+) is the type this work actually is; dataSync is the
        // closest available fit on API 34, which predates that type existing at all.
        // Below 34, foreground service types aren't enforced the same way.
        return when {
            Build.VERSION.SDK_INT >= 35 ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 34 ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun saveToMediaStore(tempFile: File): Uri {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "AIMediaEditor_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AIMediaEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create a MediaStore entry for the export")

        resolver.openOutputStream(uri)?.use { out ->
            tempFile.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("Could not open an output stream for the export")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    companion object {
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "export_channel"
        private const val NOTIFICATION_ID = 4201
    }
}
