package com.anytypeio.anytype.pebble.assimilation.extraction

import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import java.util.Date

/**
 * Contract for LLM-backed entity extraction.
 *
 * Allows tests to provide a fake implementation without Mockito,
 * avoiding the `@JvmInline` value-class / coroutine matcher incompatibility.
 */
interface EntityExtractionService {
    /**
     * Extract entities from [inputText].
     *
     * @throws [com.anytypeio.anytype.pebble.assimilation.llm.LlmException] on API failure.
     */
    suspend fun extract(
        inputText: String,
        traceId: String = "",
        currentDate: Date = Date()
    ): ExtractionResult
}
