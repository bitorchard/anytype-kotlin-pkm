package com.anytypeio.anytype.pebble.webhook.server

import com.anytypeio.anytype.pebble.webhook.model.WebhookConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * Simple bearer-token authentication for webhook routes.
 *
 * When [WebhookConfig.authEnabled] is false, all requests are allowed through.
 */
object WebhookAuth {

    suspend fun checkAuth(call: ApplicationCall, config: WebhookConfig): Boolean {
        if (!config.authEnabled) return true

        val authHeader = call.request.headers["Authorization"] ?: run {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing Authorization header"))
            return false
        }

        if (!authHeader.startsWith("Bearer ")) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Expected Bearer token"))
            return false
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        if (token != config.authToken) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            return false
        }

        return true
    }
}
