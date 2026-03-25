package com.anytypeio.anytype.pebble.webhook.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.webhook.model.WebhookConfig
import com.anytypeio.anytype.pebble.webhook.pipeline.InputProcessor
import com.anytypeio.anytype.pebble.webhook.server.WebhookServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * Android Foreground Service that hosts the Ktor CIO webhook server.
 *
 * Lifecycle:
 * - Start via [WebhookServiceManager.start].
 * - Stop via [WebhookServiceManager.stop] or the notification stop action.
 * - The Ktor server starts in [onCreate] / [onStartCommand] and stops in [onDestroy].
 * - [InputProcessor] is started alongside the server to drive the assimilation pipeline.
 *
 * The [WebhookServer] and [InputProcessor] are obtained from the application-level DI
 * graph via a service-locator pattern to avoid holding a Dagger sub-component reference
 * across service restarts.
 */
class WebhookForegroundService : Service() {

    private lateinit var webhookServer: WebhookServer
    private lateinit var config: WebhookConfig
    private lateinit var inputProcessor: InputProcessor
    private lateinit var serviceScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WebhookNotification.createChannel(this)
        val provider = application as WebhookServerProvider
        webhookServer = provider.webhookServer()
        config = provider.webhookConfig()
        inputProcessor = provider.inputProcessor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(
            WebhookNotification.NOTIFICATION_ID,
            WebhookNotification.build(this, config.port)
        )

        if (!webhookServer.isRunning) {
            webhookServer.start(config)
            Timber.i("[Pebble] WebhookForegroundService: server started on port ${config.port}")
        }

        val spaceId = intent?.getStringExtra(EXTRA_SPACE_ID)
        if (spaceId != null) {
            inputProcessor.start(serviceScope, SpaceId(spaceId))
            Timber.i("[Pebble] WebhookForegroundService: InputProcessor started for space $spaceId")
        } else {
            Timber.w("[Pebble] WebhookForegroundService: no spaceId provided — InputProcessor not started")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        inputProcessor.stop()
        webhookServer.stop()
        serviceScope.cancel()
        Timber.i("[Pebble] WebhookForegroundService: stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.anytypeio.anytype.pebble.webhook.ACTION_STOP"
        const val EXTRA_SPACE_ID = "extra_space_id"
    }
}

/**
 * Contract for the [Application] class to provide Pebble pipeline components
 * without a compile-time dependency on the app module's DI graph.
 *
 * Implemented by the `app` module's `AndroidApplication`.
 */
interface WebhookServerProvider {
    fun webhookServer(): WebhookServer
    fun webhookConfig(): WebhookConfig
    fun inputProcessor(): InputProcessor
}
