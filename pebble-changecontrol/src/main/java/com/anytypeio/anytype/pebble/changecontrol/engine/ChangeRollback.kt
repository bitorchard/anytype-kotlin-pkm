package com.anytypeio.anytype.pebble.changecontrol.engine

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ConflictResolution
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.changecontrol.model.RollbackConflict
import com.anytypeio.anytype.pebble.changecontrol.model.RollbackOperationResult
import com.anytypeio.anytype.pebble.changecontrol.model.RollbackResult
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import timber.log.Timber
import javax.inject.Inject

/**
 * Rolls back a previously applied [ChangeSet] using pre-computed inverse operations.
 *
 * Conflict detection:
 * The [ChangeOperation.afterState] captured at execution time is compared against the
 * object's current state before applying the inverse. A conflict exists when one or more
 * keys differ, meaning someone (or another pipeline run) has modified the object since
 * this change set was applied.
 *
 * Resolution strategies (chosen by the caller):
 * - [ConflictResolution.SKIP] — leave the conflicting object as-is, continue rolling back others.
 * - [ConflictResolution.ABORT] — stop immediately; no further operations are rolled back.
 * - [ConflictResolution.FORCE] — overwrite with the rollback values regardless of conflicts.
 */
class ChangeRollback @Inject constructor(
    private val graphService: PebbleGraphService,
    private val changeStore: ChangeStore
) {

    suspend fun rollback(
        changeSet: ChangeSet,
        resolution: ConflictResolution = ConflictResolution.SKIP
    ): RollbackResult {
        changeStore.updateStatus(changeSet.id, ChangeSetStatus.ROLLING_BACK)

        val appliedOps = changeSet.operations
            .filter { it.status == OperationStatus.APPLIED && it.inverse != null }
        val rollbackOrder = OperationOrderer.reverseOrder(appliedOps)

        val results = mutableListOf<RollbackOperationResult>()
        val conflicts = mutableListOf<RollbackConflict>()

        for (op in rollbackOrder) {
            val conflict = detectConflict(op, changeSet.metadata.spaceId)
            if (conflict != null) {
                Timber.w("[Pebble] ChangeRollback: conflict on op ${op.id} (object ${conflict.objectId})")
                when (resolution) {
                    ConflictResolution.ABORT -> {
                        changeStore.updateStatus(changeSet.id, ChangeSetStatus.PARTIALLY_ROLLED_BACK)
                        results.add(RollbackOperationResult(op.id, op.ordinal, rolledBack = false))
                        return RollbackResult.Aborted(changeSet.id, conflict, results)
                    }
                    ConflictResolution.SKIP -> {
                        conflicts.add(conflict)
                        results.add(RollbackOperationResult(op.id, op.ordinal, rolledBack = false, skipped = true))
                        continue
                    }
                    ConflictResolution.FORCE -> {
                        conflicts.add(conflict)
                        // Fall through to execute the inverse operation
                    }
                }
            }

            try {
                applyInverse(op, changeSet.metadata.spaceId)
                changeStore.updateOperation(
                    operationId = op.id,
                    status = OperationStatus.ROLLED_BACK,
                    beforeState = null,
                    afterState = null
                )
                results.add(RollbackOperationResult(op.id, op.ordinal, rolledBack = true))
            } catch (e: Exception) {
                Timber.e(e, "[Pebble] ChangeRollback: failed to apply inverse for op ${op.id}")
                results.add(RollbackOperationResult(op.id, op.ordinal, rolledBack = false, error = e.message))
                conflicts.add(RollbackConflict(
                    operationId = op.id,
                    objectId = extractObjectId(op.inverse!!) ?: "unknown",
                    expectedState = op.afterState ?: emptyMap(),
                    actualState = emptyMap(),
                    conflictingKeys = emptySet()
                ))
            }
        }

        return if (conflicts.isEmpty()) {
            changeStore.updateStatus(changeSet.id, ChangeSetStatus.ROLLED_BACK)
            Timber.i("[Pebble] ChangeRollback: clean rollback of ${changeSet.id}")
            RollbackResult.FullRollback(changeSet.id, results)
        } else {
            changeStore.updateStatus(changeSet.id, ChangeSetStatus.PARTIALLY_ROLLED_BACK)
            Timber.w("[Pebble] ChangeRollback: partial rollback of ${changeSet.id}; ${conflicts.size} conflict(s)")
            RollbackResult.PartialRollback(changeSet.id, results, conflicts)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Compares the operation's [ChangeOperation.afterState] (what it left behind)
     * with the object's current state to detect external modifications.
     *
     * Returns null when there is no conflict or when no afterState was recorded.
     */
    private suspend fun detectConflict(op: ChangeOperation, spaceId: String): RollbackConflict? {
        val afterState = op.afterState ?: return null
        if (afterState.isEmpty()) return null

        val objectId = extractObjectId(op.inverse ?: return null) ?: return null

        val current = graphService.getObject(SpaceId(spaceId), objectId, afterState.keys.toList())
            ?.details
            ?.mapValues { it.value?.toString() ?: "" }
            ?: return null

        val conflictingKeys = afterState.keys.filter { key ->
            current[key] != afterState[key]
        }.toSet()

        return if (conflictingKeys.isNotEmpty()) {
            RollbackConflict(
                operationId = op.id,
                objectId = objectId,
                expectedState = afterState,
                actualState = current,
                conflictingKeys = conflictingKeys
            )
        } else null
    }

    /** Executes the pre-computed inverse operation against the graph. */
    private suspend fun applyInverse(op: ChangeOperation, spaceId: String) {
        when (val inv = op.inverse!!) {
            is OperationParams.CreateObjectParams ->
                graphService.createObject(SpaceId(spaceId), TypeKey(inv.typeKey), inv.details)

            is OperationParams.DeleteObjectParams ->
                graphService.deleteObjects(listOf(inv.objectId))

            is OperationParams.SetDetailsParams ->
                graphService.updateObjectDetails(inv.objectId, inv.details)

            is OperationParams.AddRelationParams -> {
                graphService.addRelationToObject(inv.objectId, inv.relationKey)
                if (inv.value != null) graphService.setRelationValue(inv.objectId, inv.relationKey, inv.value)
            }

            is OperationParams.RemoveRelationParams ->
                graphService.setRelationValue(inv.objectId, inv.relationKey, null)
        }
    }

    /** Extracts the primary object ID from an [OperationParams]. */
    private fun extractObjectId(params: OperationParams): String? = when (params) {
        is OperationParams.DeleteObjectParams -> params.objectId
        is OperationParams.SetDetailsParams -> params.objectId
        is OperationParams.AddRelationParams -> params.objectId
        is OperationParams.RemoveRelationParams -> params.objectId
        is OperationParams.CreateObjectParams -> null
    }
}
