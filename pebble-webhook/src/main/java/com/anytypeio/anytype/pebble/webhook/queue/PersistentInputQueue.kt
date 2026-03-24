package com.anytypeio.anytype.pebble.webhook.queue

import com.anytypeio.anytype.pebble.webhook.model.InputQueueEntry
import com.anytypeio.anytype.pebble.webhook.model.QueueEntryStatus
import com.anytypeio.anytype.pebble.webhook.model.RawInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

/**
 * Room-backed implementation of [InputQueue].
 *
 * Entries survive process restarts; the [com.anytypeio.anytype.pebble.webhook.pipeline.InputProcessor]
 * observes [getPending] to re-process any PENDING entries that were interrupted by a crash.
 */
class PersistentInputQueue @Inject constructor(
    private val dao: InputQueueDao
) : InputQueue {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun enqueue(input: RawInput): String {
        val entity = InputQueueEntity(
            id = input.id,
            traceId = input.traceId,
            text = input.text,
            source = input.source,
            receivedAt = input.receivedAt,
            metadataJson = json.encodeToString(input.metadata),
            status = QueueEntryStatus.PENDING.name,
            enqueuedAt = System.currentTimeMillis()
        )
        dao.insert(entity)
        Timber.d("[Pebble] Enqueued input ${input.id} (traceId=${input.traceId})")
        return input.id
    }

    override suspend fun dequeue(): InputQueueEntry? {
        val entity = dao.dequeue() ?: return null
        dao.markProcessing(entity.id)
        return entity.toModel()
    }

    override suspend fun markProcessed(id: String, resultChangeSetId: String?) {
        dao.markProcessed(id, System.currentTimeMillis(), resultChangeSetId)
    }

    override suspend fun markFailed(id: String, error: String) {
        val current = dao.getById(id)
        val retries = (current?.retryCount ?: 0) + 1
        val newStatus = if (retries >= InputQueue.MAX_RETRIES) {
            Timber.w("[Pebble] Input $id exceeded max retries; moving to DEAD_LETTER")
            QueueEntryStatus.DEAD_LETTER.name
        } else {
            QueueEntryStatus.FAILED.name
        }
        dao.markFailed(id, newStatus, error)
    }

    override fun getPending(): Flow<List<InputQueueEntry>> =
        dao.observePending(InputQueue.MAX_RETRIES).map { entities -> entities.map { it.toModel() } }

    override suspend fun getRecent(limit: Int): List<InputQueueEntry> =
        dao.getRecent(limit).map { it.toModel() }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun InputQueueEntity.toModel(): InputQueueEntry {
        val metadata = try {
            json.decodeFromString<Map<String, String>>(metadataJson)
        } catch (e: Exception) {
            emptyMap()
        }
        return InputQueueEntry(
            id = id,
            input = RawInput(
                id = id,
                traceId = traceId,
                text = text,
                receivedAt = receivedAt,
                source = source,
                metadata = metadata
            ),
            status = QueueEntryStatus.valueOf(status),
            retryCount = retryCount,
            enqueuedAt = enqueuedAt,
            processedAt = processedAt,
            lastError = lastError,
            resultChangeSetId = resultChangeSetId
        )
    }
}
