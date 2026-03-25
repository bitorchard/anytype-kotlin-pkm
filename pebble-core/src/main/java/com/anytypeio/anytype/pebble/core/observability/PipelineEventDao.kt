package com.anytypeio.anytype.pebble.core.observability

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PipelineEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PipelineEventEntity)

    @Query("SELECT * FROM pipeline_events WHERE trace_id = :traceId ORDER BY timestamp_ms ASC")
    fun getEventsForTrace(traceId: String): Flow<List<PipelineEventEntity>>

    @Query(
        "SELECT DISTINCT trace_id FROM pipeline_events " +
        "ORDER BY timestamp_ms DESC LIMIT :limit"
    )
    fun getRecentTraceIds(limit: Int): Flow<List<String>>

    @Query(
        "SELECT * FROM pipeline_events WHERE status = 'FAILURE' AND timestamp_ms >= :sinceMs " +
        "ORDER BY timestamp_ms DESC"
    )
    suspend fun getFailures(sinceMs: Long): List<PipelineEventEntity>

    @Query("SELECT COUNT(*) FROM pipeline_events")
    suspend fun count(): Int

    @Query(
        "DELETE FROM pipeline_events WHERE id IN " +
        "(SELECT id FROM pipeline_events ORDER BY timestamp_ms ASC LIMIT :deleteCount)"
    )
    suspend fun deleteOldest(deleteCount: Int)

    @Query("SELECT * FROM pipeline_events WHERE status = 'FAILURE' ORDER BY timestamp_ms DESC LIMIT 1")
    suspend fun getLatestFailure(): PipelineEventEntity?
}
