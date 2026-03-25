package com.anytypeio.anytype.pebble.core.observability

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "Pebble:Core"
private const val MAX_EVENTS = 500

class RoomPipelineEventStore @Inject constructor(
    private val dao: PipelineEventDao
) : PipelineEventStore {

    override suspend fun record(event: PipelineEvent) {
        Timber.tag(TAG).d(
            "[trace=${event.traceId}] ${event.stage} | ${event.status} | ${event.message}"
        )
        val metadataJson = Json.encodeToString(event.metadata)
        dao.insert(PipelineEventEntity.fromDomain(event, metadataJson))
        prune(MAX_EVENTS)
    }

    override fun getEventsForTrace(traceId: String): Flow<List<PipelineEvent>> =
        dao.getEventsForTrace(traceId).map { entities ->
            entities.map { entity ->
                val meta: Map<String, String> = runCatching {
                    Json.decodeFromString(entity.metadataJson)
                }.getOrDefault(emptyMap())
                entity.toDomain(meta)
            }
        }

    override fun getRecentTraces(limit: Int): Flow<List<String>> =
        dao.getRecentTraceIds(limit)

    override suspend fun getFailures(sinceMs: Long): List<PipelineEvent> =
        dao.getFailures(sinceMs).map { entity ->
            val meta: Map<String, String> = runCatching {
                Json.decodeFromString(entity.metadataJson)
            }.getOrDefault(emptyMap())
            entity.toDomain(meta)
        }

    override suspend fun prune(keepCount: Int) {
        val total = dao.count()
        if (total > keepCount) {
            dao.deleteOldest(total - keepCount)
        }
    }
}
