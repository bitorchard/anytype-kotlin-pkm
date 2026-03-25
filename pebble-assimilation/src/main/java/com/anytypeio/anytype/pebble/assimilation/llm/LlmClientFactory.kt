package com.anytypeio.anytype.pebble.assimilation.llm

/**
 * Creates the appropriate [LlmClient] implementation based on [LlmClientConfig.provider].
 */
object LlmClientFactory {

    fun create(config: LlmClientConfig): LlmClient = when (config.provider) {
        LlmProvider.ANTHROPIC -> AnthropicLlmClient(config)
        LlmProvider.OPENAI -> OpenAiLlmClient(config)
    }
}
