package com.aimediaeditor.app.data.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.data.media.VideoThumbnailLoader
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Turns one [MediaItem] into a [MediaIndexEntity] + its [MediaLabelEntity] rows --
 * the "media analysis" stage of spec section 6's indexing pipeline (MediaStore ->
 * Media Scanner -> metadata extraction -> media analysis -> local database).
 *
 * Entirely on-device (spec section 15's privacy principle, architecture notes
 * "Level 1"): ML Kit's bundled models run locally with no network call, same
 * treatment as every other on-device analysis in this app. Every step here is
 * best-effort -- a failure analyzing ONE item (unreadable EXIF, a codec the bitmap
 * loader can't decode, a geocoder with no result, ML Kit finding nothing) degrades
 * that item's row to fewer fields, never aborts indexing the rest of the library.
 *
 * HONEST RISK: this is the first ML Kit usage in this project. The general shape
 * (InputImage.fromBitmap, ImageLabeling/FaceDetection.getClient(options), a
 * Task<T>-returning process() call) matches ML Kit's own long-standing, widely
 * documented API, but -- like every other Android-only class this session -- it has
 * never actually been compiled or run: no Android SDK, no device, no confirmed
 * network path to Google's Maven (where these artifacts are published) from this
 * sandbox. See the README's "Media indexing" section.
 */
class MediaAnalyzer(private val context: Context) {

    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(CONFIDENCE_THRESHOLD).build()
    )
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
    )

    suspend fun analyze(item: MediaItem): Pair<MediaIndexEntity, List<MediaLabelEntity>> {
        val (latitude, longitude) = extractLocation(item)
        val locality = if (latitude != null && longitude != null) reverseGeocode(latitude, longitude) else null
        val bitmap = loadAnalysisBitmap(item)

        val labels = bitmap?.let { runImageLabeling(it) } ?: emptyList()
        val faceCount = bitmap?.let { runFaceDetection(it) } ?: 0

        val entity = MediaIndexEntity(
            mediaId = item.id,
            uri = item.uri.toString(),
            displayName = item.displayName,
            mediaType = item.type.name,
            dateAddedSeconds = item.dateAddedSeconds,
            durationMs = item.durationMs,
            width = item.width,
            height = item.height,
            mimeType = item.mimeType,
            latitude = latitude,
            longitude = longitude,
            localityName = locality,
            hasFaces = faceCount > 0,
            faceCount = faceCount,
            qualityScore = estimateQuality(item, labels.size),
            indexedAtMs = System.currentTimeMillis()
        )
        val labelEntities = labels.map { (label, confidence) ->
            MediaLabelEntity(mediaId = item.id, label = label.lowercase(Locale.ROOT), confidence = confidence)
        }
        return entity to labelEntities
    }

    /** Releases both ML Kit clients -- call once when a whole indexing run finishes
     *  (success or failure), not per-item; the clients are meant to be reused across
     *  many [analyze] calls, same as this app reuses one ExoPlayer across clip changes. */
    fun close() {
        imageLabeler.close()
        faceDetector.close()
    }

    private suspend fun loadAnalysisBitmap(item: MediaItem): Bitmap? = when (item.type) {
        MediaType.IMAGE -> loadDownscaledImage(item.uri)
        MediaType.VIDEO -> VideoThumbnailLoader.loadFrame(context, item.uri)
    }

    private fun loadDownscaledImage(uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // Labeling/face detection don't need full resolution -- downscaling by 2x
            // keeps analysis fast and memory-light across a whole-library indexing run,
            // the same "never load a full-resolution image just to look at it" principle
            // this app already applies to video (filmstrip tiles, waveform decoding).
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeStream(stream, null, options)
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun runImageLabeling(bitmap: Bitmap): List<Pair<String, Float>> = try {
        val image = InputImage.fromBitmap(bitmap, 0)
        imageLabeler.process(image).awaitTask().map { it.text to it.confidence }
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun runFaceDetection(bitmap: Bitmap): Int = try {
        val image = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(image).awaitTask().size
    } catch (e: Exception) {
        0
    }

    private fun extractLocation(item: MediaItem): Pair<Double?, Double?> = try {
        when (item.type) {
            MediaType.IMAGE -> extractImageLocation(item.uri)
            MediaType.VIDEO -> extractVideoLocation(item.uri)
        }
    } catch (e: Exception) {
        null to null
    }

    private fun extractImageLocation(uri: Uri): Pair<Double?, Double?> {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val latLong = exif.latLong // AndroidX ExifInterface.getLatLong(): DoubleArray?
            if (latLong != null) return latLong[0] to latLong[1]
        }
        return null to null
    }

    private fun extractVideoLocation(uri: Uri): Pair<Double?, Double?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            parseIso6709Location(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
        } finally {
            retriever.release()
        }
    }

    /**
     * MediaMetadataRetriever.METADATA_KEY_LOCATION returns ISO 6709 text, e.g.
     * "+37.5090-122.2620/" -- two signed decimal numbers back to back with no
     * delimiter (the second number's own sign IS the delimiter), latitude first, an
     * optional altitude and trailing slash after. Never throws on unexpected input --
     * a null/blank/malformed string just means no location for this item, not a
     * reason to fail the rest of the analysis.
     */
    private fun parseIso6709Location(raw: String?): Pair<Double?, Double?> {
        if (raw.isNullOrBlank()) return null to null
        val match = ISO_6709_REGEX.find(raw) ?: return null to null
        val lat = match.groupValues[1].toDoubleOrNull()
        val lon = match.groupValues[2].toDoubleOrNull()
        return lat to lon
    }

    /**
     * Deprecated (API 33+ added an async callback overload) but still functional on
     * every API level this app targets -- called here from a background WorkManager
     * coroutine already, so the "don't block the main thread" reason for the
     * deprecation doesn't apply, and using it avoids API-level branching for what's a
     * nice-to-have (locality-name search), not a safety-critical path.
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(latitude: Double, longitude: Double): String? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
            results?.firstOrNull()?.let { address ->
                address.locality ?: address.subAdminArea ?: address.adminArea ?: address.countryName
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * MVP quality heuristic -- deliberately simple, not a real aesthetic/blur/exposure
     * model (out of scope for this round). Combines resolution (a genuinely tiny image
     * is unlikely to be a "good" photo) with whether ML Kit found anything to label at
     * all (an unlabelable image is disproportionately likely to be a blank, very dark,
     * or corrupt frame). Normalized to [0f, 1f] so "best"-sorted search results are at
     * least directionally sensible -- not a claim of real photo-quality scoring.
     */
    private fun estimateQuality(item: MediaItem, labelCount: Int): Float {
        val megapixels = (item.width.toLong() * item.height.toLong()) / 1_000_000f
        val resolutionScore = (megapixels / 12f).coerceIn(0f, 1f) // 12MP treated as "full score"
        val labelScore = min(labelCount, 5) / 5f
        return (resolutionScore * 0.6f + labelScore * 0.4f).coerceIn(0f, 1f)
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { error -> cont.resumeWithException(error) }
        addOnCanceledListener { cont.cancel() }
    }

    private companion object {
        const val CONFIDENCE_THRESHOLD = 0.6f
        val ISO_6709_REGEX = Regex("^([+-]\\d+\\.\\d+)([+-]\\d+\\.\\d+)")
    }
}
