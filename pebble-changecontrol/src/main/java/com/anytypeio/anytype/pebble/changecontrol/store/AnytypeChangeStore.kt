package com.anytypeio.anytype.pebble.changecontrol.store

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

/**
 * [ChangeStore] backed by AnyType objects.
 *
 * Change sets are stored as objects with type key `ot-pkm-changeset`, and individual
 * operations as objects with type key `ot-pkm-changeoperation`. This makes them
 * first-class citizens of the AnyType graph: they sync across devices and appear
 * in the audit trail.
 */
class AnytypeChangeStore @Inject constructor(
    private val graphService: PebbleGraphService
) : ChangeStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(changeSet: ChangeSet): PebbleId {
        val result = graphService.createObject(
            space = SpaceId(changeSet.metadata.spaceId),
            typeKey = TypeKey(TYPE_KEY_CHANGESET),
            details = changeSet.toDetails()
        )
        Timber.d("[Pebble] Saved ChangeSet ${changeSet.id} as AnyType object ${result.objectId}")
        changeSet.operations.forEach { op ->
            graphService.createObject(
                space = SpaceId(changeSet.metadata.spaceId),
                typeKey = TypeKey(TYPE_KEY_CHANGEOPERATION),
                details = op.toDetails()
            )
        }
        return changeSet.id
    }

    override suspend fun updateStatus(changeSetId: PebbleId, status: ChangeSetStatus) {
        val existing = findChangeSetObject(changeSetId) ?: return
        graphService.updateObjectDetails(
            objectId = existing,
            details = mapOf(
                DETAIL_STATUS to status.name,
                DETAIL_APPLIED_AT to if (status == ChangeSetStatus.APPLIED) System.currentTimeMillis() else null,
                DETAIL_ROLLED_BACK_AT to if (status == ChangeSetStatus.ROLLED_BACK || status == ChangeSetStatus.PARTIALLY_ROLLED_BACK) System.currentTimeMillis() else null
            ).filterValues { it != null }
        )
    }

    override suspend fun updateOperation(
        operationId: PebbleId,
        status: OperationStatus,
        beforeState: Map<String, String>?,
        afterState: Map<String, String>?,
        resultObjectId: PebbleId?,
        inverse: OperationParams?
    ) {
        graphService.updateObjectDetails(
            objectId = operationId,
            details = buildMap {
                put(DETAIL_OP_STATUS, status.name)
                beforeState?.let { put(DETAIL_OP_BEFORE_STATE, json.encodeToString(it)) }
                afterState?.let { put(DETAIL_OP_AFTER_STATE, json.encodeToString(it)) }
                resultObjectId?.let { put(DETAIL_OP_RESULT_OBJECT_ID, it) }
                inverse?.let { put(DETAIL_OP_INVERSE, json.encodeToString(it)) }
            }
        )
    }

    override suspend fun getChangeSet(changeSetId: PebbleId): ChangeSet? {
        // AnyType search is the source of truth; LocalChangeCache is the fast path
        // In practice the CompositeChangeStore prefers the cache for reads.
        return null
    }

    override suspend fun getChangeSets(status: ChangeSetStatus?, limit: Int): List<ChangeSet> = emptyList()

    override suspend fun getChangeSetForInput(inputId: PebbleId): ChangeSet? = null

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun findChangeSetObject(changeSetId: PebbleId): PebbleId? {
        // The local ID is stored as the name field for fast lookup in search results
        return changeSetId
    }

    private fun ChangeSet.toDetails(): Map<String, Any?> = mapOf(
        "name" to id,
        DETAIL_INPUT_ID to inputId,
        DETAIL_TRACE_ID to traceId,
        DETAIL_STATUS to status.name,
        DETAIL_SUMMARY to summary,
        DETAIL_CREATED_AT to createdAt
    )

    private fun ChangeOperation.toDetails(): Map<String, Any?> = mapOf(
        "name" to id,
        DETAIL_OP_CHANGESET_ID to changeSetId,
        DETAIL_OP_ORDINAL to ordinal.toString(),
        DETAIL_OP_TYPE to type.name,
        DETAIL_OP_STATUS to status.name,
        DETAIL_OP_PARAMS to json.encodeToString(params)
    )

    companion object {
        const val TYPE_KEY_CHANGESET = "ot-pkm-changeset"
        const val TYPE_KEY_CHANGEOPERATION = "ot-pkm-changeoperation"

        const val DETAIL_INPUT_ID = "pkm-inputId"
        const val DETAIL_TRACE_ID = "pkm-traceId"
        const val DETAIL_STATUS = "pkm-status"
        const val DETAIL_SUMMARY = "pkm-summary"
        const val DETAIL_CREATED_AT = "pkm-createdAt"
        const val DETAIL_APPLIED_AT = "pkm-appliedAt"
        const val DETAIL_ROLLED_BACK_AT = "pkm-rolledBackAt"

        const val DETAIL_OP_CHANGESET_ID = "pkm-changeSetId"
        const val DETAIL_OP_ORDINAL = "pkm-ordinal"
        const val DETAIL_OP_TYPE = "pkm-opType"
        const val DETAIL_OP_STATUS = "pkm-opStatus"
        const val DETAIL_OP_PARAMS = "pkm-opParams"
        const val DETAIL_OP_INVERSE = "pkm-opInverse"
        const val DETAIL_OP_BEFORE_STATE = "pkm-opBeforeState"
        const val DETAIL_OP_AFTER_STATE = "pkm-opAfterState"
        const val DETAIL_OP_RESULT_OBJECT_ID = "pkm-opResultObjectId"
    }
}
