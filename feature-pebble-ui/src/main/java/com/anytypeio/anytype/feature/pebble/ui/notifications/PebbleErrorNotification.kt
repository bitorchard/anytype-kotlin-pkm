package com.anytypeio.anytype.feature.pebble.ui.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Pebble error notifications.
 *
 * - One dedicated channel: `PEBBLE_ERROR`
 * - Each error type maps to a stable notification ID so repeated failures don't spam.
 * - `setOnlyAlertOnce(true)` on each notification type prevents sound/vibration on updates.
 * - Call [cancel] when the underlying error is resolved.
 */
@Singleton
class PebbleErrorNotification @Inject constructor(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "PEBBLE_ERROR"
        const val CHANNEL_NAME = "Pebble Errors"
        const val EXTRA_PEBBLE_DESTINATION = "pebble_destination"

        private const val ID_API_KEY = 10_001
        private const val ID_RATE_LIMIT = 10_002
        private const val ID_LLM_TIMEOUT = 10_003
        private const val ID_PORT_CONFLICT = 10_004
        private const val ID_APPLY_FAILED = 10_005
        private const val ID_QUEUE_DEEP = 10_006
        private const val ID_APPROVAL_PENDING = 10_010
        private const val ID_AUTO_APPLIED = 10_011

        /** Create the notification channel on app start (idempotent). */
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Alerts about Pebble pipeline failures"
                }
                context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(channel)
            }
        }
    }

    fun notifyApiKeyRejected() {
        notify(
            id = ID_API_KEY,
            title = "Pebble: API key rejected",
            body = "Check your LLM API key in Settings",
            destination = PebbleDestination.SETTINGS
        )
    }

    fun notifyRateLimited() {
        notify(
            id = ID_RATE_LIMIT,
            title = "Pebble: LLM rate limited",
            body = "Input queued — will retry automatically",
            destination = PebbleDestination.DEBUG
        )
    }

    fun notifyLlmTimeout() {
        notify(
            id = ID_LLM_TIMEOUT,
            title = "Pebble: LLM unreachable",
            body = "Check internet connection; input is queued",
            destination = PebbleDestination.DEBUG
        )
    }

    fun notifyPortConflict(port: Int) {
        notify(
            id = ID_PORT_CONFLICT,
            title = "Pebble: Server failed to start",
            body = "Port $port already in use — change in Settings",
            destination = PebbleDestination.SETTINGS
        )
    }

    fun notifyApplyFailed() {
        notify(
            id = ID_APPLY_FAILED,
            title = "Pebble: Changes not saved",
            body = "Tap to review and retry",
            destination = PebbleDestination.CHANGE_LOG
        )
    }

    fun notifyQueueDeep(depth: Int) {
        notify(
            id = ID_QUEUE_DEEP,
            title = "Pebble: $depth inputs waiting",
            body = "Processing may be stalled — check Debug",
            destination = PebbleDestination.DEBUG
        )
    }

    fun notifyApprovalPending(inputPreview: String) {
        notify(
            id = ID_APPROVAL_PENDING,
            title = "Pebble: Voice input processed",
            body = "Tap to review: \"${inputPreview.take(60)}\"",
            destination = PebbleDestination.APPROVAL
        )
    }

    fun notifyAutoApplied(inputPreview: String, changeSetId: String) {
        val undoIntent = buildUndoIntent(changeSetId)
        notify(
            id = ID_AUTO_APPLIED,
            title = "Pebble: Changes applied",
            body = "From: \"${inputPreview.take(60)}\"",
            destination = PebbleDestination.CHANGE_LOG,
            actionLabel = "Undo",
            actionIntent = undoIntent
        )
    }

    fun cancel(errorType: PebbleErrorType) {
        val id = when (errorType) {
            PebbleErrorType.API_KEY -> ID_API_KEY
            PebbleErrorType.RATE_LIMIT -> ID_RATE_LIMIT
            PebbleErrorType.LLM_TIMEOUT -> ID_LLM_TIMEOUT
            PebbleErrorType.PORT_CONFLICT -> ID_PORT_CONFLICT
            PebbleErrorType.APPLY_FAILED -> ID_APPLY_FAILED
            PebbleErrorType.QUEUE_DEEP -> ID_QUEUE_DEEP
            PebbleErrorType.APPROVAL_PENDING -> ID_APPROVAL_PENDING
            PebbleErrorType.AUTO_APPLIED -> ID_AUTO_APPLIED
        }
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun notify(
        id: Int,
        title: String,
        body: String,
        destination: PebbleDestination,
        actionLabel: String? = null,
        actionIntent: PendingIntent? = null
    ) {
        val tapIntent = buildTapIntent(destination)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (actionLabel != null && actionIntent != null) {
            builder.addAction(0, actionLabel, actionIntent)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }

    private fun buildTapIntent(destination: PebbleDestination): PendingIntent {
        val intent = buildDeepLinkIntent(destination)
        return PendingIntent.getActivity(
            context,
            destination.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildUndoIntent(changeSetId: String): PendingIntent {
        val intent = Intent(context, PebbleRollbackReceiver::class.java).apply {
            putExtra(PebbleRollbackReceiver.EXTRA_CHANGE_SET_ID, changeSetId)
        }
        return PendingIntent.getBroadcast(
            context,
            changeSetId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildDeepLinkIntent(destination: PebbleDestination): Intent {
        val packageName = context.packageName
        return context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PEBBLE_DESTINATION, destination.name)
        } ?: Intent()
    }
}

enum class PebbleDestination {
    DASHBOARD, SETTINGS, DEBUG, CHANGE_LOG, APPROVAL
}

enum class PebbleErrorType {
    API_KEY, RATE_LIMIT, LLM_TIMEOUT, PORT_CONFLICT, APPLY_FAILED, QUEUE_DEEP,
    APPROVAL_PENDING, AUTO_APPLIED
}
