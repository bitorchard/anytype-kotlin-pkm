package com.anytypeio.anytype.pebble.core.observability

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineEventStoreTest {

    /** In-memory DAO stub that records inserts and supports count/deleteOldest. */
    private class FakeDao : PipelineEventDao {
        val stored = mutableListOf<PipelineEventEntity>()

        override suspend fun insert(event: PipelineEventEntity) { stored.add(event) }
        override fun getEventsForTrace(traceId: String): Flow<List<PipelineEventEntity>> =
            flowOf(stored.filter { it.traceId == traceId })
        override fun getRecentTraceIds(limit: Int): Flow<List<String>> =
            flowOf(stored.map { it.traceId }.distinct().take(limit))
        override suspend fun getFailures(sinceMs: Long): List<PipelineEventEntity> =
            stored.filter { it.status == "FAILURE" && it.timestampMs >= sinceMs }
        override suspend fun count(): Int = stored.size
        override suspend fun deleteOldest(deleteCount: Int) {
            val sorted = stored.sortedBy { it.timestampMs }
            repeat(deleteCount.coerceAtMost(sorted.size)) { i -> stored.remove(sorted[i]) }
        }
        override suspend fun getLatestFailure(): PipelineEventEntity? =
            stored.filter { it.status == "FAILURE" }.maxByOrNull { it.timestampMs }
    }

    private fun makeEvent(traceId: String = "t1", ts: Long = System.currentTimeMillis()) =
        PipelineEvent(
            traceId = traceId,
            stage = PipelineStage.INPUT_RECEIVED,
            status = EventStatus.SUCCESS,
            message = "ok",
            timestampMs = ts
        )

    @Test
    fun `insert 600 events then prune keeps only 500 most recent`() = runTest {
        val dao = FakeDao()
        val store = RoomPipelineEventStore(dao)

        repeat(600) { i -> store.record(makeEvent(ts = i.toLong())) }

        assertEquals(500, dao.stored.size)
        // Oldest 100 should be gone; remaining should be events 100..599
        val remaining = dao.stored.map { it.timestampMs }.sorted()
        assertEquals(100L, remaining.first())
        assertEquals(599L, remaining.last())
    }

    @Test
    fun `getFailures returns only FAILURE status events after given timestamp`() = runTest {
        val dao = FakeDao()
        val store = RoomPipelineEventStore(dao)

        store.record(makeEvent(ts = 1000L))
        store.record(
            PipelineEvent(
                traceId = "t2",
                stage = PipelineStage.ERROR,
                status = EventStatus.FAILURE,
                message = "boom",
                timestampMs = 2000L
            )
        )
        store.record(
            PipelineEvent(
                traceId = "t3",
                stage = PipelineStage.ERROR,
                status = EventStatus.FAILURE,
                message = "old",
                timestampMs = 500L
            )
        )

        val failures = store.getFailures(sinceMs = 1500L)
        assertEquals(1, failures.size)
        assertEquals("t2", failures[0].traceId)
    }
}
