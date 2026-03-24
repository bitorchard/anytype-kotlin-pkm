package com.anytypeio.anytype.pebble.webhook.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "input_queue")
data class InputQueueEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "trace_id") val traceId: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "metadata_json") val metadataJson: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    @ColumnInfo(name = "processed_at") val processedAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "result_change_set_id") val resultChangeSetId: String? = null
)
