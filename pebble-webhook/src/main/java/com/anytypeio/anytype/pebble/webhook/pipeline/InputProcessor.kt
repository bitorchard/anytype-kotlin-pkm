package com.anytypeio.anytype.pebble.webhook.pipeline

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.core.AssimilationPipeline
import com.anytypeio.anytype.pebble.core.AssimilationResult
import com.anytypeio.anytype.pebble.core.RawVoiceInput
import com.anytypeio.anytype.pebble.webhook.model.InputQueueEntry
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Observes the [InputQueue] and drives each pending entry through the [AssimilationPipeline].
 *
 * Lifecycle:
 * - Call [start] once the assimilation pipeline is ready (typically from the foreground service).
 * - Call [stop] on service shutdown.
 * - Respects offline state via [AssimilationResult.Offline] — entries remain in the queue.
 */
class InputProcessor @Inject constructor(
    private val queue: InputQueue,
    private val pipeline: AssimilationPipeline
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope, spaceId: SpaceId) {
        if (job?.isActive == true) return
        job = scope.launch {
            queue.getPending().collectLatest { entries ->
                for (entry in entries) {
                    processEntry(entry, spaceId)
                }
            }
        }
        Timber.i("[Pebble] InputProcessor: started for space ${spaceId.id}")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun processEntry(entry: InputQueueEntry, spaceId: SpaceId) {
        Timber.d("[Pebble] InputProcessor: processing input ${entry.id} (traceId=${entry.input.traceId})")

        val voiceInput = RawVoiceInput(
            id = entry.input.id,
            traceId = entry.input.traceId,
            text = entry.input.text,
            receivedAt = entry.input.receivedAt,
            source = entry.input.source
        )

        when (val result = pipeline.process(voiceInput, spaceId)) {
            is AssimilationResult.Success -> {
                queue.markProcessed(entry.id, resultChangeSetId = result.changeSetId)
                Timber.i("[Pebble] InputProcessor: input ${entry.id} → changeSet ${result.changeSetId}")
            }
            is AssimilationResult.Failure -> {
                if (result.retryable) {
                    queue.markFailed(entry.id, result.error)
                    Timber.w("[Pebble] InputProcessor: input ${entry.id} failed (retryable): ${result.error}")
                } else {
                    queue.markFailed(entry.id, "permanent: ${result.error}")
                    Timber.e("[Pebble] InputProcessor: input ${entry.id} failed permanently: ${result.error}")
                }
            }
            AssimilationResult.Offline -> {
                Timber.d("[Pebble] InputProcessor: offline — leaving input ${entry.id} in queue")
                // Entry remains PENDING; flow will re-emit when connectivity returns
            }
        }
    }
}
