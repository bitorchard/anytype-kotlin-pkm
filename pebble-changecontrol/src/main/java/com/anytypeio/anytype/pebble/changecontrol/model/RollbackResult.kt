package com.anytypeio.anytype.pebble.changecontrol.model

import com.anytypeio.anytype.pebble.core.PebbleId

/** How to handle a conflict detected during rollback. */
enum class ConflictResolution {
    /** Overwrite the conflicting changes with the rollback values. */
    FORCE,
    /** Skip rolling back this specific operation; continue with others. */
    SKIP,
    /** Abort the entire rollback at the first conflict. */
    ABORT
}

/** Result returned by [com.anytypeio.anytype.pebble.changecontrol.engine.ChangeRollback.rollback]. */
sealed class RollbackResult {

    /** Every operation was rolled back cleanly. */
    data class FullRollback(
        val changeSetId: PebbleId,
        val operationResults: List<RollbackOperationResult>
    ) : RollbackResult()

    /**
     * Some operations were skipped due to conflicts.
     * The change set status becomes [ChangeSetStatus.PARTIALLY_ROLLED_BACK].
     */
    data class PartialRollback(
        val changeSetId: PebbleId,
        val operationResults: List<RollbackOperationResult>,
        val conflicts: List<RollbackConflict>
    ) : RollbackResult()

    /**
     * Rollback was aborted at the first conflict.
     * Objects modified before the abort point remain changed.
     */
    data class Aborted(
        val changeSetId: PebbleId,
        val conflict: RollbackConflict,
        val operationResults: List<RollbackOperationResult>
    ) : RollbackResult()
}

data class RollbackOperationResult(
    val operationId: PebbleId,
    val ordinal: Int,
    val rolledBack: Boolean,
    val skipped: Boolean = false,
    val error: String? = null
)

data class RollbackConflict(
    val operationId: PebbleId,
    val objectId: PebbleId,
    /** State the rollback expected to find (captured at apply time). */
    val expectedState: Map<String, String>,
    /** Actual current state at rollback time. */
    val actualState: Map<String, String>,
    /** Keys that differ between expected and actual. */
    val conflictingKeys: Set<String>
)
