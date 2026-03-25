package com.anytypeio.anytype.pebble.assimilation.llm

import com.anytypeio.anytype.pebble.core.PebbleConstants

/**
 * Supported LLM providers.
 */
enum class LlmProvider { ANTHROPIC, OPENAI }

/**
 * Configuration for the LLM client.
 *
 * @param provider   Which provider to use.
 * @param apiKey     API key; sourced from EncryptedSharedPreferences at runtime.
 * @param model      Model identifier (e.g. "claude-sonnet-4-5", "gpt-4o").
 * @param timeoutMs  Maximum time to wait for the LLM response.
 * @param maxTokens  Maximum tokens to request in the response.
 */
data class LlmClientConfig(
    val provider: LlmProvider = LlmProvider.ANTHROPIC,
    val apiKey: String = "",
    val model: String = DEFAULT_ANTHROPIC_MODEL,
    val timeoutMs: Long = PebbleConstants.DEFAULT_LLM_TIMEOUT_MS,
    val maxTokens: Int = 1024
) {
    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o"
    }
}
