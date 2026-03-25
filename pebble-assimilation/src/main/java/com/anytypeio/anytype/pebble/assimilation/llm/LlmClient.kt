package com.anytypeio.anytype.pebble.assimilation.llm

import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult

/**
 * Contract for a provider-agnostic LLM extraction client.
 *
 * Implementations send the system prompt + user input to an LLM API and parse the
 * structured JSON response into an [ExtractionResult].
 */
interface LlmClient {

    /** Human-readable model identifier used in observability metadata (e.g. "claude-sonnet-4-5"). */
    val modelName: String

    /**
     * Extract entities and relationships from [userInput].
     *
     * @param systemPrompt  Taxonomy-aware system prompt from TaxonomyPromptGenerator.
     * @param userInput     The raw voice-input text.
     * @return Parsed [ExtractionResult] on success.
     * @throws LlmException on API errors (auth failure, rate limit, timeout, parse error).
     */
    suspend fun extractEntities(
        systemPrompt: String,
        userInput: String
    ): ExtractionResult
}

/**
 * Structured exception hierarchy for LLM client failures.
 */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** API key was rejected (HTTP 401). */
    class AuthException(message: String) : LlmException(message)

    /** Rate limit hit (HTTP 429). */
    class RateLimitException(message: String) : LlmException(message)

    /** Network or socket timeout. */
    class TimeoutException(message: String, cause: Throwable? = null) : LlmException(message, cause)

    /** Unexpected HTTP error. */
    class ApiException(val httpStatus: Int, message: String) : LlmException("HTTP $httpStatus: $message")

    /** Response could not be parsed as valid JSON. */
    class ParseException(message: String, cause: Throwable? = null) : LlmException(message, cause)

    /** No network connectivity. */
    class NetworkException(message: String, cause: Throwable? = null) : LlmException(message, cause)
}
