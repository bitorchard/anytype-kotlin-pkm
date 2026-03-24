package com.anytypeio.anytype.pebble.webhook.server

import com.anytypeio.anytype.pebble.webhook.model.InputRequest
import com.anytypeio.anytype.pebble.webhook.model.InputResponse
import com.anytypeio.anytype.pebble.webhook.model.RawInput
import com.anytypeio.anytype.pebble.webhook.model.WebhookConfig
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import timber.log.Timber

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class StatusResponse(
    val status: String,
    val port: Int,
    val pendingInputs: Int,
    val version: String = "1.0"
)

fun Routing.webhookRoutes(queue: InputQueue, config: WebhookConfig) {
    route("/api/v1") {
        post("/input") {
            if (!WebhookAuth.checkAuth(call, config)) return@post

            val request = try {
                call.receive<InputRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body: ${e.message}"))
                return@post
            }

            if (request.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("text must not be blank"))
                return@post
            }

            val input = RawInput(
                text = request.text,
                receivedAt = request.timestamp ?: System.currentTimeMillis(),
                source = request.source,
                metadata = request.metadata
            )

            try {
                queue.enqueue(input)
                Timber.i("[Pebble] Webhook received input id=${input.id} traceId=${input.traceId}")
                call.respond(HttpStatusCode.OK, InputResponse(id = input.id, traceId = input.traceId))
            } catch (e: Exception) {
                Timber.e(e, "[Pebble] Webhook: failed to enqueue input")
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to queue input"))
            }
        }

        get("/status") {
            if (!WebhookAuth.checkAuth(call, config)) return@get
            val pending = try {
                queue.getRecent(1).size // lightweight health check
            } catch (e: Exception) {
                -1
            }
            call.respond(
                HttpStatusCode.OK,
                StatusResponse(status = "ok", port = config.port, pendingInputs = pending)
            )
        }

        get("/inputs") {
            if (!WebhookAuth.checkAuth(call, config)) return@get
            val recent = try {
                queue.getRecent(20)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to retrieve inputs"))
                return@get
            }
            call.respond(HttpStatusCode.OK, recent.map { entry ->
                mapOf(
                    "id" to entry.id,
                    "traceId" to entry.input.traceId,
                    "text" to entry.input.text,
                    "status" to entry.status.name,
                    "enqueuedAt" to entry.enqueuedAt,
                    "retryCount" to entry.retryCount
                )
            })
        }
    }
}
