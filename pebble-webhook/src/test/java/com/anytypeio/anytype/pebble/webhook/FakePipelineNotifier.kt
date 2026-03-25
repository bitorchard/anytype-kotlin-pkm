package com.anytypeio.anytype.pebble.webhook

import com.anytypeio.anytype.pebble.core.PipelineNotifier

class FakePipelineNotifier : PipelineNotifier {
    data class ApprovalEvent(val changeSetId: String, val summary: String)
    data class AutoAppliedEvent(val changeSetId: String, val summary: String, val inputPreview: String)
    data class ErrorEvent(val errorMessage: String, val errorType: String)

    val approvalEvents = mutableListOf<ApprovalEvent>()
    val autoAppliedEvents = mutableListOf<AutoAppliedEvent>()
    val errorEvents = mutableListOf<ErrorEvent>()

    override fun notifyApprovalPending(changeSetId: String, summary: String) {
        approvalEvents.add(ApprovalEvent(changeSetId, summary))
    }

    override fun notifyAutoApplied(changeSetId: String, summary: String, inputPreview: String) {
        autoAppliedEvents.add(AutoAppliedEvent(changeSetId, summary, inputPreview))
    }

    override fun notifyError(errorMessage: String, errorType: String) {
        errorEvents.add(ErrorEvent(errorMessage, errorType))
    }
}
