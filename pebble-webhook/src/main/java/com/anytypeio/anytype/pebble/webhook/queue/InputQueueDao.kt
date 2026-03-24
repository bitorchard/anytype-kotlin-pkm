package com.anytypeio.anytype.pebble.webhook.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InputQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: InputQueueEntity)

    @Query("SELECT * FROM input_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InputQueueEntity?

    @Query("SELECT * FROM input_queue WHERE status = 'PENDING' ORDER BY enqueued_at ASC LIMIT 1")
    suspend fun dequeue(): InputQueueEntity?

    @Query("SELECT * FROM input_queue WHERE status IN ('PENDING', 'FAILED') AND retry_count < :maxRetries ORDER BY enqueued_at ASC")
    fun observePending(maxRetries: Int = 3): Flow<List<InputQueueEntity>>

    @Query("UPDATE input_queue SET status = 'PROCESSING' WHERE id = :id")
    suspend fun markProcessing(id: String)

    @Query("UPDATE input_queue SET status = 'PROCESSED', processed_at = :processedAt, result_change_set_id = :changeSetId WHERE id = :id")
    suspend fun markProcessed(id: String, processedAt: Long, changeSetId: String?)

    @Query("UPDATE input_queue SET status = :status, retry_count = retry_count + 1, last_error = :error WHERE id = :id")
    suspend fun markFailed(id: String, status: String, error: String)

    @Query("SELECT * FROM input_queue ORDER BY enqueued_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<InputQueueEntity>

    @Query("SELECT COUNT(*) FROM input_queue WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int
}
