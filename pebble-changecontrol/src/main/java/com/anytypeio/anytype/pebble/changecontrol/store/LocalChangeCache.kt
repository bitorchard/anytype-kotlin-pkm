package com.anytypeio.anytype.pebble.changecontrol.store

import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import com.anytypeio.anytype.pebble.core.PebbleId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Room-backed [ChangeStore] providing fast local reads without middleware round-trips.
 *
 * Write operations are kept lightweight; the [AnytypeChangeStore] handles durable cloud-synced
 * persistence. [CompositeChangeStore] orchestrates both.
 */
class LocalChangeCache @Inject constructor(
    private val dao: ChangeSetDao
) : ChangeStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(changeSet: ChangeSet): PebbleId {
        dao.insertChangeSet(changeSet.toCache())
        changeSet.operations.forEach { op -> dao.insertOperation(op.toCache()) }
        return changeSet.id
    }

    override suspend fun updateStatus(changeSetId: PebbleId, status: ChangeSetStatus) {
        dao.updateStatus(changeSetId, status.name)
        when (status) {
            ChangeSetStatus.APPLIED -> dao.updateAppliedAt(changeSetId, System.currentTimeMillis())
            ChangeSetStatus.ROLLED_BACK,
            ChangeSetStatus.PARTIALLY_ROLLED_BACK ->
                dao.updateRolledBackAt(changeSetId, System.currentTimeMillis())
            else -> Unit
        }
    }

    override suspend fun updateOperation(
        operationId: PebbleId,
        status: OperationStatus,
        beforeState: Map<String, String>?,
        afterState: Map<String, String>?,
        resultObjectId: PebbleId?,
        inverse: OperationParams?
    ) {
        dao.updateOperation(
            id = operationId,
            status = status.name,
            beforeJson = beforeState?.let { json.encodeToString(it) },
            afterJson = afterState?.let { json.encodeToString(it) },
            inverseJson = inverse?.let { json.encodeToString(it) },
            resultObjectId = resultObjectId
        )
    }

    override suspend fun getChangeSet(changeSetId: PebbleId): ChangeSet? {
        val cached = dao.getById(changeSetId) ?: return null
        val operations = dao.getOperationsForChangeSet(changeSetId).map { it.toModel() }
        return cached.toModel(operations)
    }

    override suspend fun getChangeSets(status: ChangeSetStatus?, limit: Int): List<ChangeSet> {
        val caches = if (status != null) {
            dao.getByStatus(status.name, limit)
        } else {
            dao.getAll(limit)
        }
        return caches.map { cached ->
            val ops = dao.getOperationsForChangeSet(cached.id).map { it.toModel() }
            cached.toModel(ops)
        }
    }

    override suspend fun getChangeSetForInput(inputId: PebbleId): ChangeSet? {
        val cached = dao.getByInputId(inputId) ?: return null
        val operations = dao.getOperationsForChangeSet(cached.id).map { it.toModel() }
        return cached.toModel(operations)
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun ChangeSet.toCache() = ChangeSetCache(
        id = id,
        inputId = inputId,
        traceId = traceId,
        status = status.name,
        summary = summary,
        spaceId = metadata.spaceId,
        createdAt = createdAt,
        appliedAt = appliedAt,
        rolledBackAt = rolledBackAt,
        errorMessage = errorMessage,
        operationCount = operations.size
    )

    private fun ChangeOperation.toCache() = ChangeOperationCache(
        id = id,
        changeSetId = changeSetId,
        ordinal = ordinal,
        type = type.name,
        status = status.name,
        paramsJson = json.encodeToString(params),
        inverseJson = inverse?.let { json.encodeToString(it) },
        beforeStateJson = beforeState?.let { json.encodeToString(it) },
        afterStateJson = afterState?.let { json.encodeToString(it) },
        resultObjectId = resultObjectId
    )

    private fun ChangeSetCache.toModel(operations: List<ChangeOperation>) = ChangeSet(
        id = id,
        inputId = inputId,
        traceId = traceId,
        status = ChangeSetStatus.valueOf(status),
        summary = summary,
        operations = operations,
        metadata = ChangeSetMetadata(spaceId = spaceId, sourceText = ""),
        createdAt = createdAt,
        appliedAt = appliedAt,
        rolledBackAt = rolledBackAt,
        errorMessage = errorMessage
    )

    private fun ChangeOperationCache.toModel() = ChangeOperation(
        id = id,
        changeSetId = changeSetId,
        ordinal = ordinal,
        type = OperationType.valueOf(type),
        params = json.decodeFromString(paramsJson),
        inverse = inverseJson?.let { json.decodeFromString(it) },
        beforeState = beforeStateJson?.let { json.decodeFromString(it) },
        afterState = afterStateJson?.let { json.decodeFromString(it) },
        status = OperationStatus.valueOf(status),
        resultObjectId = resultObjectId
    )
}
