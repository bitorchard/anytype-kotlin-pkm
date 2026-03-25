package com.anytypeio.anytype.feature.pebble.ui.settings

/**
 * Immutable snapshot of all Pebble configuration values.
 */
data class PebbleSettings(
    val webhookEnabled: Boolean = true,
    val webhookPort: Int = 8391,
    val webhookAuthToken: String = "",
    val llmProvider: LlmProvider = LlmProvider.ANTHROPIC,
    val llmApiKey: String = "",
    val llmModel: String = "claude-sonnet-4-5",
    val autoApproveEnabled: Boolean = false,
    val autoApproveThreshold: Float = 0.85f,
    val createNewThreshold: Float = 0.50f
)

enum class LlmProvider { ANTHROPIC, OPENAI }
