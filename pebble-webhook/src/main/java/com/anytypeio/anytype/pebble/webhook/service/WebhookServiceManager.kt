package com.anytypeio.anytype.pebble.webhook.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.anytypeio.anytype.core_models.primitives.SpaceId
import timber.log.Timber
import javax.inject.Inject

/**
 * Helper for starting and stopping [WebhookForegroundService] from anywhere in the app.
 */
class WebhookServiceManager @Inject constructor() {

    /**
     * Start the webhook foreground service.
     *
     * @param spaceId the active space for which the [InputProcessor] should process entries.
     *                If null, the webhook server will start but the processor will not.
     */
    fun start(context: Context, spaceId: SpaceId? = null) {
        Timber.i("[Pebble] WebhookServiceManager: starting foreground service (space=${spaceId?.id})")
        val intent = Intent(context, WebhookForegroundService::class.java).apply {
            spaceId?.let { putExtra(WebhookForegroundService.EXTRA_SPACE_ID, it.id) }
        }
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
