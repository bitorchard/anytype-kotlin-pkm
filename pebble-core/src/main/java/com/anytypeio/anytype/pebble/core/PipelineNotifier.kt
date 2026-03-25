package com.anytypeio.anytype.pebble.core

/**
 * Notifies the user about key pipeline events.
 *
 * The interface is defined in pebble-core so that pebble-webhook can call it without
 * depending on the Android-specific notification implementation in feature-pebble-ui.
 *
 * The concrete Android implementation lives in the app module and uses
 * [com.anytypeio.anytype.feature.pebble.ui.notifications.PebbleErrorNotification].
 */
interface PipelineNotifier {

    /**
     * A new ChangeSet has been created and is awaiting user approval.
     *
     * @param changeSetId the persisted ID of the change set.
     * @param summary     human-readable summary of the proposed changes.
     */
    fun notifyApprovalPending(changeSetId: String, summary: String)

    /**
     * A ChangeSet was automatically applied (confidence above threshold).
     *
     * @param changeSetId  the ID of the applied change set.
     * @param summary      human-readable summary of the applied changes.
     * @param inputPreview the first ~60 chars of the original voice input.
     */
    fun notifyAutoApplied(changeSetId: String, summary: String, inputPreview: String)

    /**
     * A non-retryable pipeline error occurred.
     *
     * @param errorMessage a human-readable description of the error.
     * @param errorType    a machine-readable tag (e.g. "LLM_API_KEY", "RATE_LIMIT").
     */
    fun notifyError(errorMessage: String, errorType: String)
}
