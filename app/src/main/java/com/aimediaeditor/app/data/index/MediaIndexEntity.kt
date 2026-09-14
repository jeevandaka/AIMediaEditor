package com.aimediaeditor.app.data.index

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per indexed media item -- the local database spec sections 5/6/20 describe
 * ("MediaStore -> Media Scanner -> metadata extraction -> media analysis -> local
 * database -> search/index"). Stores the same base fields
 * [com.aimediaeditor.app.data.media.MediaItem] already carries, plus what
 * [MediaAnalyzer] adds: GPS/locality, face presence, and a quality score. Deliberately
 * NOT the type [MediaSearchQuery] matches against -- see [IndexedMediaSummary]'s doc
 * comment for why the search logic stays in a Room/Android-free file.
 */
@Entity(tableName = "media_index")
data class MediaIndexEntity(
    @PrimaryKey val mediaId: Long,
    val uri: String,
    val displayName: String,
    val mediaType: String, // MediaType.name ("IMAGE"/"VIDEO") -- a plain string column
    // avoids a Room TypeConverter for a two-value enum that's only ever compared by name.
    val dateAddedSeconds: Long,
    val durationMs: Long?,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val latitude: Double?,
    val longitude: Double?,
    // Reverse-geocoded from latitude/longitude via android.location.Geocoder -- null if
    // there was no GPS data, or geocoding failed/found nothing (never a reason to fail
    // indexing the rest of the item).
    val localityName: String?,
    val hasFaces: Boolean,
    val faceCount: Int,
    val qualityScore: Float, // 0f..1f, see MediaAnalyzer for how this is computed
    val indexedAtMs: Long
)

/**
 * One row per (media item, detected label) pair -- a real many-to-many relationship
 * (ML Kit returns several labels per item), so this is its own table rather than a
 * single delimited-string column on [MediaIndexEntity], which would make label search
 * ("dog" shouldn't match a filename that happens to contain "dog" as a substring of
 * something else) an unreliable LIKE query instead of an exact-value match.
 */
@Entity(
    tableName = "media_labels",
    foreignKeys = [
        ForeignKey(
            entity = MediaIndexEntity::class,
            parentColumns = ["mediaId"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("mediaId"), Index("label")]
)
data class MediaLabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val label: String, // lowercase, e.g. "dog", "beach", "sunset"
    val confidence: Float
)
