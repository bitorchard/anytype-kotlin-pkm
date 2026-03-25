package com.anytypeio.anytype.pebble.webhook.server

import com.anytypeio.anytype.pebble.core.observability.EventStatus
import com.anytypeio.anytype.pebble.core.observability.PipelineEvent
import com.anytypeio.anytype.pebble.core.observability.PipelineEventStore
import com.anytypeio.anytype.pebble.core.observability.PipelineStage
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
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber

private const val TAG = "Pebble:Webhook"

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class StatusResponse(
    val status: String,
    val port: Int,
    val pendingInputs: Int,
    val version: String = "1.0"
)

fun Routing.webhookRoutes(
    queue: InputQueue,
    config: WebhookConfig,
    eventStore: PipelineEventStore? = null
) {
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
                val remoteIp = call.request.local.remoteAddress
                val bodyLen = request.text.length
                Timber.tag(TAG).d("[trace=${input.traceId}] INPUT_RECEIVED | remoteIp=$remoteIp | bodyLength=$bodyLen")
                eventStore?.let { store ->
                    launch {
                        store.record(
                            PipelineEvent(
                                traceId = input.traceId,
                                stage = PipelineStage.INPUT_RECEIVED,
                                status = EventStatus.SUCCESS,
                                message = "Webhook received input",
                                metadata = mapOf(
                                    "remoteIp" to remoteIp,
                                    "bodyLength" to bodyLen.toString(),
                                    "preview" to input.text.take(30)
                                )
                            )
                        )
                    }
                }
                queue.enqueue(input)
                Timber.tag(TAG).i("[trace=${input.traceId}] INPUT_QUEUED")
                call.respond(HttpStatusCode.OK, InputResponse(id = input.id, traceId = input.traceId))
            } catch (e: Exception) {
                Timber.tag(TAG).e("[Pebble] Webhook: failed to enqueue input: ${e.message}")
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
