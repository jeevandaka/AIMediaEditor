package com.aimediaeditor.app.data.index

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface MediaIndexDao {

    @Upsert
    suspend fun upsertMedia(entity: MediaIndexEntity)

    @Query("DELETE FROM media_labels WHERE mediaId = :mediaId")
    suspend fun clearLabels(mediaId: Long)

    @Insert
    suspend fun insertLabels(labels: List<MediaLabelEntity>)

    /**
     * One item's full analysis result, written atomically -- upsert the row, replace
     * its labels wholesale (simpler and safer than diffing old vs new labels for what's
     * at most a few dozen rows per item).
     */
    @Transaction
    suspend fun replaceMediaWithLabels(entity: MediaIndexEntity, labels: List<MediaLabelEntity>) {
        upsertMedia(entity)
        clearLabels(entity.mediaId)
        if (labels.isNotEmpty()) insertLabels(labels)
    }

    @Query("SELECT mediaId FROM media_index")
    suspend fun getAllIndexedIds(): List<Long>

    /** Drops rows for media no longer present on the device (deleted since last indexed). */
    @Query("DELETE FROM media_index WHERE mediaId NOT IN (:currentIds)")
    suspend fun pruneDeleted(currentIds: List<Long>)

    @Query("SELECT * FROM media_index")
    suspend fun getAllMedia(): List<MediaIndexEntity>

    @Query("SELECT * FROM media_labels")
    suspend fun getAllLabels(): List<MediaLabelEntity>

    @Delete
    suspend fun deleteMedia(entity: MediaIndexEntity)
}
