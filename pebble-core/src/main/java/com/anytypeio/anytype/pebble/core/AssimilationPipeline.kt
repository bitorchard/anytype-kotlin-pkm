package com.anytypeio.anytype.pebble.core

import com.anytypeio.anytype.core_models.primitives.SpaceId

/**
 * Contract for the assimilation engine (Phase 4).
 *
 * Defined in `pebble-core` so that `pebble-webhook` can depend on it without a
 * circular dependency on `pebble-assimilation`.
 *
 * The [pebble-assimilation] module provides the implementation;
 * [com.anytypeio.anytype.pebble.webhook.pipeline.InputProcessor] consumes this interface.
 */
interface AssimilationPipeline {
    suspend fun process(input: RawVoiceInput, space: SpaceId): AssimilationResult
}

/**
 * Minimal voice-input model shared between the webhook and assimilation modules.
 * Mirrors [com.anytypeio.anytype.pebble.webhook.model.RawInput] but avoids a
 * cross-module dependency by duplicating only the fields the assimilation engine needs.
 */
data class RawVoiceInput(
    val id: String,
    val traceId: String,
    val text: String,
    val receivedAt: Long,
    val source: String = "pebble"
)

sealed class AssimilationResult {
    /** Assimilation succeeded; a change set was produced. */
    data class Success(val changeSetId: String, val traceId: String) : AssimilationResult()

    /** Assimilation failed; the input should be retried or moved to dead-letter. */
    data class Failure(val error: String, val retryable: Boolean = true) : AssimilationResult()

    /** Network is unavailable; the input should remain in the queue and be retried when online. */
    object Offline : AssimilationResult()
}
