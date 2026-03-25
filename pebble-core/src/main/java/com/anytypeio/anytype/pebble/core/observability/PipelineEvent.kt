package com.anytypeio.anytype.pebble.core.observability

import java.util.UUID

/**
 * A single observable event at one pipeline stage boundary for a given voice input trace.
 * Events are stored locally and never synced to AnyType — they exist only for debugging.
 */
data class PipelineEvent(
    val id: String = UUID.randomUUID().toString(),
    /** Links to the originating [com.anytypeio.anytype.pebble.webhook.model.RawInput.traceId]. */
    val traceId: String,
    val stage: PipelineStage,
    val status: EventStatus,
    val message: String,
    /** Structured key-value metadata (e.g., model="claude-sonnet", entityCount="3"). */
    val metadata: Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis(),
    /** Wall-clock duration of this stage; null if not yet measured. */
    val durationMs: Long? = null
)
