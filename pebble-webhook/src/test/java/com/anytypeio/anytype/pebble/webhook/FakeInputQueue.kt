package com.anytypeio.anytype.pebble.webhook

import com.anytypeio.anytype.pebble.webhook.model.InputQueueEntry
import com.anytypeio.anytype.pebble.webhook.model.QueueEntryStatus
import com.anytypeio.anytype.pebble.webhook.model.RawInput
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [InputQueue] for unit tests.
 *
 * Callers add entries via [seed]; the processor observes [getPending] just as it would
 * with the Room-backed implementation.
 */
class FakeInputQueue : InputQueue {

    private val entries = mutableMapOf<String, InputQueueEntry>()
    private val _pendingFlow = MutableStateFlow<List<InputQueueEntry>>(emptyList())

    val processedIds = mutableListOf<String>()
    val failedIds = mutableListOf<Pair<String, String>>()

    fun seed(vararg inputs: RawInput) {
        inputs.forEach { raw ->
            val entry = InputQueueEntry(id = raw.id, input = raw, status = QueueEntryStatus.PENDING)
            entries[raw.id] = entry
        }
        publishPending()
    }

    fun clear() {
        entries.clear()
        processedIds.clear()
        failedIds.clear()
        _pendingFlow.value = emptyList()
    }

    override suspend fun enqueue(input: RawInput): String {
        val entry = InputQueueEntry(id = input.id, input = input, status = QueueEntryStatus.PENDING)
        entries[input.id] = entry
        publishPending()
        return input.id
    }

    override suspend fun dequeue(): InputQueueEntry? = entries.values.firstOrNull {
        it.status == QueueEntryStatus.PENDING
    }

    override suspend fun markProcessed(id: String, resultChangeSetId: String?) {
        processedIds.add(id)
        entries[id] = entries[id]!!.copy(
            status = QueueEntryStatus.PROCESSED,
            resultChangeSetId = resultChangeSetId
        )
        publishPending()
    }

    override suspend fun markFailed(id: String, error: String) {
        failedIds.add(id to error)
        val entry = entries[id] ?: return
        val newRetry = entry.retryCount + 1
        val newStatus = if (newRetry >= InputQueue.MAX_RETRIES) QueueEntryStatus.DEAD_LETTER
                        else QueueEntryStatus.FAILED
        entries[id] = entry.copy(retryCount = newRetry, status = newStatus, lastError = error)
        publishPending()
    }

    override fun getPending(): Flow<List<InputQueueEntry>> = _pendingFlow.asStateFlow()

    override suspend fun getRecent(limit: Int): List<InputQueueEntry> =
        entries.values.sortedByDescending { it.enqueuedAt }.take(limit)

    private fun publishPending() {
        _pendingFlow.value = entries.values.filter { it.status == QueueEntryStatus.PENDING }
    }
}
