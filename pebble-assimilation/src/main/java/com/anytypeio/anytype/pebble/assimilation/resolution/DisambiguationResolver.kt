package com.anytypeio.anytype.pebble.assimilation.resolution

import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import javax.inject.Inject

/**
 * Applies user-supplied disambiguation choices to a [ChangeSet] that has pending
 * [ChangeSet.disambiguationChoicesJson].
 *
 * For each entity identified by its `localRef`:
 * - [ResolutionDecision.Resolved] — the `CREATE_OBJECT` op for that ref is replaced by a
 *   `SET_DETAILS` op on the existing object; all relation ops that reference the old `localRef`
 *   are updated to use the real `objectId`.
 * - [ResolutionDecision.CreateNew] — the `CREATE_OBJECT` op is left unchanged (a new object
 *   will be created at execution time).
 * - [ResolutionDecision.Skipped] — all ops that reference the `localRef` are removed.
 *
 * The returned [ChangeSet] has an empty [ChangeSet.disambiguationChoicesJson] and re-indexed
 * ordinals.
 */
class DisambiguationResolver @Inject constructor() {

    /**
     * @param changeSet The pending change set with unresolved disambiguation choices.
     * @param userChoices Map of `localRef` → [ResolutionDecision] as supplied by the user.
     * @return Updated [ChangeSet] with all choices applied and [ChangeSet.disambiguationChoicesJson]
     *         cleared.
     */
    fun resolve(changeSet: ChangeSet, userChoices: Map<String, ResolutionDecision>): ChangeSet {
        val resolvedIds: Map<String, String> = userChoices
            .filterValues { it is ResolutionDecision.Resolved }
            .mapValues { (_, v) -> (v as ResolutionDecision.Resolved).objectId }

        val skippedRefs: Set<String> = userChoices
            .filterValues { it is ResolutionDecision.Skipped }
            .keys

        val updatedOps = buildList {
            for (op in changeSet.operations) {
                val resolved = resolveOperation(op, userChoices, resolvedIds, skippedRefs)
                if (resolved != null) add(resolved)
            }
        }

        // Re-assign ordinals after possible deletions
        val reindexed = updatedOps.mapIndexed { i, op -> op.copy(ordinal = i) }

        return changeSet.copy(
            operations = reindexed,
            disambiguationChoicesJson = ""
        )
    }

    private fun resolveOperation(
        op: ChangeOperation,
        userChoices: Map<String, ResolutionDecision>,
        resolvedIds: Map<String, String>,
        skippedRefs: Set<String>
    ): ChangeOperation? = when (val params = op.params) {

        is OperationParams.CreateObjectParams -> {
            val localRef = params.localRef
            if (localRef == null || localRef !in userChoices) {
                op
            } else {
                when (val decision = userChoices[localRef]!!) {
                    is ResolutionDecision.Resolved -> op.copy(
                        type = OperationType.SET_DETAILS,
                        params = OperationParams.SetDetailsParams(
                            objectId = decision.objectId,
                            details = params.details
                        )
                    )
                    is ResolutionDecision.CreateNew -> op
                    is ResolutionDecision.Skipped -> null
                }
            }
        }

        is OperationParams.AddRelationParams -> {
            when {
                params.objectId in skippedRefs -> null
                params.objectId in resolvedIds ->
                    op.copy(params = params.copy(objectId = resolvedIds[params.objectId]!!))
                else -> op
            }
        }

        is OperationParams.SetDetailsParams -> {
            when {
                params.objectId in skippedRefs -> null
                params.objectId in resolvedIds ->
                    op.copy(params = params.copy(objectId = resolvedIds[params.objectId]!!))
                else -> op
            }
        }

        is OperationParams.RemoveRelationParams -> {
            when {
                params.objectId in skippedRefs -> null
                params.objectId in resolvedIds ->
                    op.copy(params = params.copy(objectId = resolvedIds[params.objectId]!!))
                else -> op
            }
        }

        is OperationParams.DeleteObjectParams -> {
            if (params.objectId in skippedRefs) null else op
        }
    }
}
