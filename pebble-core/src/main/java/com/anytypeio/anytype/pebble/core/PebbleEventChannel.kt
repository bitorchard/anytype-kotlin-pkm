package com.anytypeio.anytype.pebble.core

import kotlinx.coroutines.flow.Flow

/**
 * Observable channel for Pebble system events.
 * Published by pipeline stages and consumed by UI and orchestration layers.
 */
interface PebbleEventChannel {
    /** Emits whenever the webhook server receives a new raw input. */
    fun observeInputReceived(): Flow<PebbleInputEvent>

    /** Emits when the full assimilation pipeline has produced a result for an input. */
    fun observeAssimilationCompleted(): Flow<PebbleAssimilationEvent>
}

data class PebbleInputEvent(
    val inputId: String,
    val traceId: String,
    val text: String,
    val receivedAt: Long = System.currentTimeMillis()
)

data class PebbleAssimilationEvent(
    val traceId: String,
    val changeSetId: String,
    val success: Boolean,
    val errorMessage: String? = null
)
