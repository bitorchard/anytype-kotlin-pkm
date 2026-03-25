package com.anytypeio.anytype.ui.pebble

import android.content.Context
import com.anytypeio.anytype.feature.pebble.ui.notifications.PebbleErrorNotification
import com.anytypeio.anytype.pebble.core.PipelineNotifier

/**
 * Bridges [PipelineNotifier] (pebble-core) → [PebbleErrorNotification] (feature-pebble-ui).
 *
 * Lives in the app module because it is the only module that can depend on both.
 */
class AndroidPipelineNotifier(context: Context) : PipelineNotifier {

    private val notification = PebbleErrorNotification(context)

    init {
        PebbleErrorNotification.createChannel(context)
    }

    override fun notifyApprovalPending(changeSetId: String, summary: String) {
        notification.notifyApprovalPending(inputPreview = summary)
    }

    override fun notifyAutoApplied(changeSetId: String, summary: String, inputPreview: String) {
        notification.notifyAutoApplied(inputPreview = inputPreview, changeSetId = changeSetId)
    }

    override fun notifyError(errorMessage: String, errorType: String) {
        when (errorType) {
            "LLM_API_KEY" -> notification.notifyApiKeyRejected()
            "RATE_LIMIT" -> notification.notifyRateLimited()
            "LLM_TIMEOUT" -> notification.notifyLlmTimeout()
            else -> notification.notifyApplyFailed()
        }
    }
}
