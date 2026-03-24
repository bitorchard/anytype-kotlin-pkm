package com.anytypeio.anytype.pebble.webhook.model

import kotlinx.serialization.Serializable

/** Processing state of a queued input. */
enum class QueueEntryStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED,
    DEAD_LETTER
}

/**
 * A persisted queue entry wrapping a [RawInput] with retry metadata.
 */
@Serializable
data class InputQueueEntry(
    val id: String,
    val input: RawInput,
    val status: QueueEntryStatus = QueueEntryStatus.PENDING,
    val retryCount: Int = 0,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val processedAt: Long? = null,
    val lastError: String? = null,
    /** ID of the ChangeSet produced by processing this entry (populated on success). */
    val resultChangeSetId: String? = null
)
