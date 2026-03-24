package com.anytypeio.anytype.pebble.changecontrol.engine

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ExecutionResult
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationResult
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleId
import timber.log.Timber
import javax.inject.Inject

/**
 * Applies a [ChangeSet]'s operations to the AnyType object graph in topological order.
 *
 * For each operation:
 * - Captures [beforeState] from the graph before mutation.
 * - Executes via [PebbleGraphService].
 * - Captures [afterState] after mutation.
 * - Computes the [inverse] operation for rollback.
 * - Propagates created object IDs to subsequent operations that reference the same local ref.
 *
 * On any failure the change set transitions to [ChangeSetStatus.APPLY_FAILED];
 * already-applied operations remain on the graph (compensating rollback is a separate step).
 */
class ChangeExecutor @Inject constructor(
    private val graphService: PebbleGraphService,
    private val changeStore: ChangeStore
) {

    suspend fun execute(changeSet: ChangeSet): ExecutionResult {
        changeStore.updateStatus(changeSet.id, ChangeSetStatus.APPLYING)

        val ordered = OperationOrderer.executionOrder(changeSet.operations)
        val results = mutableListOf<OperationResult>()

        // Maps local refs → resolved AnyType IDs (populated as CREATE_OBJECT ops complete)
        val resolvedIds = mutableMapOf<String, PebbleId>()

        for (op in ordered) {
            val resolvedOp = op.resolveLocalRefs(resolvedIds)
            try {
                val result = executeOperation(resolvedOp, changeSet.metadata.spaceId, resolvedIds)
                results.add(result)

                if (result.status == OperationStatus.APPLIED) {
                    val createdId = result.resultObjectId
                    if (createdId != null) {
                        val localRef = (resolvedOp.params as? OperationParams.CreateObjectParams)?.localRef
                        if (localRef != null) resolvedIds[localRef] = createdId
                    }
                } else {
                    // Operation failed
                    markRemainingPending(ordered, results, results.size, changeSet.id)
                    changeStore.updateStatus(changeSet.id, ChangeSetStatus.APPLY_FAILED)
                    return ExecutionResult.PartialFailure(
                        changeSetId = changeSet.id,
                        firstFailedOrdinal = resolvedOp.ordinal,
                        error = IllegalStateException(result.error ?: "Unknown error"),
                        operationResults = results
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "[Pebble] ChangeExecutor: operation ${op.id} (ordinal ${op.ordinal}) failed")
                val failedResult = OperationResult(
                    operationId = op.id,
                    ordinal = op.ordinal,
                    status = OperationStatus.FAILED,
                    error = e.message
                )
                changeStore.updateOperation(
                    operationId = op.id,
                    status = OperationStatus.FAILED,
                    beforeState = null,
                    afterState = null
                )
                results.add(failedResult)
                markRemainingPending(ordered, results, results.size, changeSet.id)
                changeStore.updateStatus(changeSet.id, ChangeSetStatus.APPLY_FAILED)
                return ExecutionResult.PartialFailure(
                    changeSetId = changeSet.id,
                    firstFailedOrdinal = op.ordinal,
                    error = e,
                    operationResults = results
                )
            }
        }

        changeStore.updateStatus(changeSet.id, ChangeSetStatus.APPLIED)
        Timber.i("[Pebble] ChangeExecutor: change set ${changeSet.id} applied successfully (${results.size} ops)")
        return ExecutionResult.Success(changeSet.id, results)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun executeOperation(
        op: ChangeOperation,
        spaceId: String,
        resolvedIds: Map<String, PebbleId>
    ): OperationResult {
        return when (val p = op.params) {
            is OperationParams.CreateObjectParams -> {
                val created = graphService.createObject(
                    space = SpaceId(spaceId),
                    typeKey = TypeKey(p.typeKey),
                    details = p.details
                )
                val inverse = OperationParams.DeleteObjectParams(objectId = created.objectId)
                val afterState = mapOf("id" to created.objectId, "typeKey" to p.typeKey) + p.details
                changeStore.updateOperation(
                    operationId = op.id,
                    status = OperationStatus.APPLIED,
                    beforeState = null,
                    afterState = afterState,
                    resultObjectId = created.objectId,
                    inverse = inverse
                )
                OperationResult(op.id, op.ordinal, OperationStatus.APPLIED, resultObjectId = created.objectId)
            }

            is OperationParams.DeleteObjectParams -> {
                val before = graphService.getObject(SpaceId(spaceId), p.objectId)
                val beforeState = before?.details?.mapValues { it.value?.toString() ?: "" } ?: emptyMap()
                graphService.deleteObjects(listOf(p.objectId))
                val inverse = OperationParams.CreateObjectParams(
                    spaceId = spaceId,
                    typeKey = before?.typeKey ?: "",
                    details = beforeState
                )
                changeStore.updateOperation(
                    operationId = op.id,
                    status = OperationStatus.APPLIED,
                    beforeState = beforeState,
                    afterState = emptyMap(),
                    inverse = inverse
                )
                OperationResult(op.id, op.ordinal, OperationStatus.APPLIED)
            }

            is OperationParams.SetDetailsParams -> {
                val before = graphService.getObject(SpaceId(spaceId), p.objectId, p.details.keys.toList())
                val beforeState = before?.details
                    ?.filterKeys { p.details.containsKey(it) }
                    ?.mapValues { it.value?.toString() ?: "" } ?: emptyMap()
                graphService.updateObjectDetails(p.objectId, p.details)
                val inverse = OperationParams.SetDetailsParams(objectId = p.objectId, details = beforeState)
                changeStore.updateOperation(
                    operationId = op.id,
                    status = OperationStatus.APPLIED,
                    beforeState = beforeState,
                    afterState = p.details,
                    inverse = inverse
                )
                OperationResult(op.id, op.ordinal, OperationStatus.APPLIED)
            }

            is OperationParams.AddRelationParams -> {
                graphService.addRelationToObject(p.objectId, p.relationKey)
                if (p.value != null) graphService.setRelationValue(p.objectId, p.relationKey, p.value)
                val inverse = OperationParams.RemoveRelationParams(objectId = p.objectId, relationKey = p.relationKey)
                changeStore.updateOperation(
                    operationId = op.id,
                    status = OperationStatus.APPLIED,
                    beforeState = null,
                    afterState = buildMap { p.value?.let { put(p.relationKey, it) } },
                    inverse = inverse
                )
                OperationResult(op.id, op.ordinal, OperationStatus.APPLIED)
            }

            is OperationParams.RemoveRelationParams -> {
                val before = graphService.getObject(SpaceId(spaceId), p.objectId, listOf(p.relationKey))
                val beforeValue = before?.details?.get(p.relationKey)?.toString()
                graphService.setRelationValue(p.objectId, p.relationKey, null)
                val inverse = OperationParams.AddRelationParams(
                    objectId = p.objectId,
                    relationKey = p.relationKey,
                    value = beforeValue
                )
                changeStore.updateOperation(
                    operationId = op.id,
                    status = OperationStatus.APPLIED,
                    beforeState = beforeValue?.let { mapOf(p.relationKey to it) },
                    afterState = emptyMap(),
                    inverse = inverse
                )
                OperationResult(op.id, op.ordinal, OperationStatus.APPLIED)
            }
        }
    }

    /** Marks operations that haven't run yet as FAILED in the result list (not in the store). */
    private fun markRemainingPending(
        ordered: List<ChangeOperation>,
        results: MutableList<OperationResult>,
        processedCount: Int,
        changeSetId: PebbleId
    ) {
        val processedIds = results.map { it.operationId }.toSet()
        ordered
            .filter { it.id !in processedIds }
            .forEach { op ->
                results.add(OperationResult(op.id, op.ordinal, OperationStatus.PENDING))
            }
    }

    /**
     * Replaces any local ref strings inside params with the resolved AnyType object IDs.
     *
     * Local refs have the form "local-N" and are used in CREATE_OBJECT params so the
     * assimilation engine can express "link the newly created object A to newly created object B"
     * before either object has been persisted.
     */
    private fun ChangeOperation.resolveLocalRefs(resolved: Map<String, PebbleId>): ChangeOperation {
        if (resolved.isEmpty()) return this
        val newParams = when (val p = params) {
            is OperationParams.SetDetailsParams ->
                p.copy(objectId = resolved[p.objectId] ?: p.objectId)
            is OperationParams.AddRelationParams ->
                p.copy(
                    objectId = resolved[p.objectId] ?: p.objectId,
                    value = p.value?.let { resolved[it] ?: it }
                )
            is OperationParams.RemoveRelationParams ->
                p.copy(objectId = resolved[p.objectId] ?: p.objectId)
            is OperationParams.DeleteObjectParams ->
                p.copy(objectId = resolved[p.objectId] ?: p.objectId)
            is OperationParams.CreateObjectParams -> p
        }
        return copy(params = newParams)
    }
}
