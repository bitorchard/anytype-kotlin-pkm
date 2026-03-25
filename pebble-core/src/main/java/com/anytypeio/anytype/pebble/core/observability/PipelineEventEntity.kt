package com.anytypeio.anytype.pebble.core.observability

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pipeline_events",
    indices = [Index("trace_id"), Index("timestamp_ms")]
)
data class PipelineEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "trace_id") val traceId: String,
    @ColumnInfo(name = "stage") val stage: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "message") val message: String,
    /** JSON-encoded map of key-value pairs. */
    @ColumnInfo(name = "metadata_json") val metadataJson: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?
) {
    fun toDomain(metadataMap: Map<String, String>): PipelineEvent = PipelineEvent(
        id = id,
        traceId = traceId,
        stage = PipelineStage.valueOf(stage),
        status = EventStatus.valueOf(status),
        message = message,
        metadata = metadataMap,
        timestampMs = timestampMs,
        durationMs = durationMs
    )

    companion object {
        fun fromDomain(event: PipelineEvent, metadataJson: String): PipelineEventEntity =
            PipelineEventEntity(
                id = event.id,
                traceId = event.traceId,
                stage = event.stage.name,
                status = event.status.name,
                message = event.message,
                metadataJson = metadataJson,
                timestampMs = event.timestampMs,
                durationMs = event.durationMs
            )
    }
}
