package com.anytypeio.anytype.pebble.webhook.queue

import com.anytypeio.anytype.pebble.webhook.model.InputQueueEntry
import com.anytypeio.anytype.pebble.webhook.model.RawInput
import kotlinx.coroutines.flow.Flow

/**
 * Durable queue for incoming [RawInput] objects.
 *
 * Survives process restarts via Room persistence. Implements at-least-once delivery
 * with configurable retry limits and dead-letter handling.
 */
interface InputQueue {

    /** Persists [input] and returns its queue entry ID. */
    suspend fun enqueue(input: RawInput): String

    /** Returns the next PENDING entry without removing it. Returns null if the queue is empty. */
    suspend fun dequeue(): InputQueueEntry?

    /** Marks the entry as successfully processed. */
    suspend fun markProcessed(id: String, resultChangeSetId: String? = null)

    /**
     * Marks the entry as failed. Increments retry counter.
     * If [retryCount] exceeds [MAX_RETRIES] the entry moves to DEAD_LETTER status.
     */
    suspend fun markFailed(id: String, error: String)

    /** Observable stream of pending (and retryable failed) entries. */
    fun getPending(): Flow<List<InputQueueEntry>>

    /** Returns the most recently enqueued entries (for debug/UI). */
    suspend fun getRecent(limit: Int = 20): List<InputQueueEntry>

    companion object {
        const val MAX_RETRIES = 3
    }
}
