package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.extraction.EntityExtractionService
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import java.util.Date

/**
 * Test fake for [EntityExtractionService].
 *
 * Avoids Mockito's `thenThrow(CheckedException)` restriction and any
 * inline-value-class / coroutine matcher incompatibilities.
 */
class FakeEntityExtractionService : EntityExtractionService {

    private val queue: ArrayDeque<() -> ExtractionResult> = ArrayDeque()

    val calls: MutableList<Triple<String, String, Date>> = mutableListOf()

    fun enqueue(result: ExtractionResult) {
        queue.addLast { result }
    }

    fun enqueueError(error: Throwable) {
        queue.addLast { throw error }
    }

    override suspend fun extract(
        inputText: String,
        traceId: String,
        currentDate: Date
    ): ExtractionResult {
        calls.add(Triple(inputText, traceId, currentDate))
        return queue.removeFirst()()
    }
}
