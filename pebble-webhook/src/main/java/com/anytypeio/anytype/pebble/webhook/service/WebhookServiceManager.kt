package com.anytypeio.anytype.pebble.webhook.service

import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber
import javax.inject.Inject

/**
 * Helper for starting and stopping [WebhookForegroundService] from anywhere in the app.
 */
class WebhookServiceManager @Inject constructor() {

    fun start(context: Context) {
        Timber.i("[Pebble] WebhookServiceManager: starting foreground service")
        val intent = Intent(context, WebhookForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        Timber.i("[Pebble] WebhookServiceManager: stopping foreground service")
        val intent = Intent(context, WebhookForegroundService::class.java).apply {
            action = WebhookForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun isRunning(server: com.anytypeio.anytype.pebble.webhook.server.WebhookServer): Boolean =
        server.isRunning
}
