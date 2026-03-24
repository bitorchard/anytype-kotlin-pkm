package com.anytypeio.anytype.pebble.webhook.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A single transcribed voice input received from the Pebble smartwatch companion app.
 *
 * [id] is the durable queue entry ID used for dequeue/ack operations.
 * [traceId] is a propagation token that follows this input through every pipeline stage
 * (webhook → assimilation → change control → AnyType graph) for end-to-end observability.
 */
@Serializable
data class RawInput(
    val id: String = UUID.randomUUID().toString(),
    val traceId: String = UUID.randomUUID().toString(),
    val text: String,
    val receivedAt: Long = System.currentTimeMillis(),
    /** Originating device/integration; defaults to "pebble". */
    val source: String = "pebble",
    val metadata: Map<String, String> = emptyMap()
)

/** HTTP request body sent to POST /api/v1/input. */
@Serializable
data class InputRequest(
    val text: String,
    val source: String = "pebble",
    val timestamp: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

/** HTTP response body for a successfully queued input. */
@Serializable
data class InputResponse(
    val id: String,
    val traceId: String,
    val status: String = "queued"
)
