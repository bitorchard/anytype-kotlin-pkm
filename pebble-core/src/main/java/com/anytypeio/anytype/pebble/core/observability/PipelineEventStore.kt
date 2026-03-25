package com.anytypeio.anytype.pebble.core.observability

import kotlinx.coroutines.flow.Flow

interface PipelineEventStore {
    suspend fun record(event: PipelineEvent)
    fun getEventsForTrace(traceId: String): Flow<List<PipelineEvent>>
    /** Returns distinct traceIds ordered by most recent event first. */
    fun getRecentTraces(limit: Int = 50): Flow<List<String>>
    suspend fun getFailures(sinceMs: Long): List<PipelineEvent>
    /** Prunes oldest events, keeping at most [keepCount] total. */
    suspend fun prune(keepCount: Int = 500)
}
