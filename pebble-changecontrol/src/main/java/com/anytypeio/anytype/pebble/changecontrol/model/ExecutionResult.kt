package com.anytypeio.anytype.pebble.changecontrol.model

import com.anytypeio.anytype.pebble.core.PebbleId

/** Result returned by [com.anytypeio.anytype.pebble.changecontrol.engine.ChangeExecutor.execute]. */
sealed class ExecutionResult {

    /** All operations applied successfully. */
    data class Success(
        val changeSetId: PebbleId,
        val operationResults: List<OperationResult>
    ) : ExecutionResult()

    /**
     * One or more operations failed; the change set is in [ChangeSetStatus.APPLY_FAILED].
     * Operations before [firstFailedOrdinal] are [OperationStatus.APPLIED];
     * operations from that ordinal onwards are [OperationStatus.FAILED] or [OperationStatus.PENDING].
     */
    data class PartialFailure(
        val changeSetId: PebbleId,
        val firstFailedOrdinal: Int,
        val error: Throwable,
        val operationResults: List<OperationResult>
    ) : ExecutionResult()
}

/** Per-operation result within an [ExecutionResult]. */
data class OperationResult(
    val operationId: PebbleId,
    val ordinal: Int,
    val status: OperationStatus,
    val resultObjectId: PebbleId? = null,
    val error: String? = null
)
