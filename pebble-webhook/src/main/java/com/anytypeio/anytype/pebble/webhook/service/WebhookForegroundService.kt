package com.anytypeio.anytype.pebble.webhook.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.anytypeio.anytype.pebble.webhook.model.WebhookConfig
import com.anytypeio.anytype.pebble.webhook.server.WebhookServer
import timber.log.Timber

/**
 * Android Foreground Service that hosts the Ktor CIO webhook server.
 *
 * Lifecycle:
 * - Start via [WebhookServiceManager.start].
 * - Stop via [WebhookServiceManager.stop] or the notification stop action.
 * - The Ktor server starts in [onCreate] / [onStartCommand] and stops in [onDestroy].
 *
 * The [WebhookServer] is obtained from the application-level DI graph via a lazy
 * service locator pattern to avoid holding a Dagger sub-component reference across
 * service restarts.
 */
class WebhookForegroundService : Service() {

    private lateinit var webhookServer: WebhookServer
    private lateinit var config: WebhookConfig

    override fun onCreate() {
        super.onCreate()
        WebhookNotification.createChannel(this)
        // Server instance resolved from application component
        webhookServer = (application as WebhookServerProvider).webhookServer()
        config = (application as WebhookServerProvider).webhookConfig()
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

        return START_STICKY
    }

    override fun onDestroy() {
        webhookServer.stop()
        Timber.i("[Pebble] WebhookForegroundService: stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.anytypeio.anytype.pebble.webhook.ACTION_STOP"
    }
}

/**
 * Contract for the [Application] class to provide [WebhookServer] and [WebhookConfig]
 * without a compile-time dependency on the app module's DI graph.
 *
 * Implemented by the `app` module's `AndroidApplication` (≤3 lines of wiring).
 */
interface WebhookServerProvider {
    fun webhookServer(): WebhookServer
    fun webhookConfig(): WebhookConfig
}
