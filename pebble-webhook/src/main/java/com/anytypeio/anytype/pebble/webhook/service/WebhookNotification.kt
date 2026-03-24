package com.anytypeio.anytype.pebble.webhook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Builds the persistent notification displayed while [WebhookForegroundService] is running.
 */
object WebhookNotification {

    const val CHANNEL_ID = "pebble_webhook_channel"
    const val NOTIFICATION_ID = 8391

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pebble PKM Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for the Pebble voice input listener"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, port: Int): Notification {
        val stopIntent = Intent(context, WebhookForegroundService::class.java).apply {
            action = WebhookForegroundService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Pebble PKM listener active")
            .setContentText("Listening for voice input on port $port")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }
}
