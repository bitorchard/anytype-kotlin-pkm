package com.anytypeio.anytype.pebble.webhook.server

import com.anytypeio.anytype.pebble.core.observability.PipelineEventStore
import com.anytypeio.anytype.pebble.webhook.model.WebhookConfig
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

/**
 * Manages the lifecycle of the embedded Ktor CIO HTTP server.
 *
 * The server is created lazily on [start] and torn down on [stop].
 * Thread-safe start/stop is enforced via [synchronized].
 */
class WebhookServer @Inject constructor(
    private val queue: InputQueue,
    private val eventStore: PipelineEventStore? = null
) {
    private var server: EmbeddedServer<*, *>? = null
    private var currentConfig: WebhookConfig? = null

    @Synchronized
    fun start(config: WebhookConfig) {
        if (server != null) {
            Timber.w("[Pebble] WebhookServer: already running on port ${currentConfig?.port}")
            return
        }

        Timber.i("[Pebble] WebhookServer: starting on ${config.host}:${config.port}")
        server = embeddedServer(
            factory = CIO,
            port = config.port,
            host = config.host
        ) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            routing {
                webhookRoutes(queue, config, eventStore)
            }
        }.also {
            it.start(wait = false)
            currentConfig = config
            Timber.i("[Pebble] WebhookServer: listening on port ${config.port}")
        }
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        Timber.i("[Pebble] WebhookServer: stopping")
        try {
            s.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        } catch (e: Exception) {
            Timber.w(e, "[Pebble] WebhookServer: error during shutdown")
        } finally {
            server = null
            currentConfig = null
        }
    }

    val isRunning: Boolean
        get() = server != null

    val port: Int?
        get() = currentConfig?.port
}
