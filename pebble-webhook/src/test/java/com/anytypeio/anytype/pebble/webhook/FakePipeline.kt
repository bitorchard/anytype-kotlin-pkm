package com.anytypeio.anytype.pebble.webhook

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.core.AssimilationPipeline
import com.anytypeio.anytype.pebble.core.AssimilationResult
import com.anytypeio.anytype.pebble.core.RawVoiceInput

/**
 * Scripted [AssimilationPipeline] for unit tests.
 *
 * Each [enqueue] call adds one result that is returned (in order) for subsequent [process] calls.
 */
class FakePipeline : AssimilationPipeline {
    private val queue = ArrayDeque<AssimilationResult>()
    val calls = mutableListOf<Pair<RawVoiceInput, SpaceId>>()

    fun enqueue(result: AssimilationResult) {
        queue.addLast(result)
    }

    override suspend fun process(input: RawVoiceInput, space: SpaceId): AssimilationResult {
        calls.add(input to space)
        return queue.removeFirstOrNull() ?: AssimilationResult.Failure("no result queued", retryable = false)
    }
}
