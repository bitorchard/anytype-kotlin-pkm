package com.anytypeio.anytype.pebble.webhook.model

import com.anytypeio.anytype.pebble.core.PebbleConstants

/**
 * Runtime configuration for the embedded Ktor webhook server.
 *
 * All fields have sensible defaults; the user can override via the app settings screen.
 */
data class WebhookConfig(
    val port: Int = PebbleConstants.DEFAULT_WEBHOOK_PORT,
    /**
     * Bearer token required in `Authorization: Bearer <token>` header.
     * An empty string disables authentication (not recommended for production use).
     */
    val authToken: String = "",
    val host: String = "0.0.0.0",
    val maxRequestBodyBytes: Long = 65_536L,
    val requestTimeoutMs: Long = 30_000L
) {
    val authEnabled: Boolean get() = authToken.isNotEmpty()
}
