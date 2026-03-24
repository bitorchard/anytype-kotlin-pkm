package com.anytypeio.anytype.pebble.changecontrol.store

import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.core.PebbleId
import timber.log.Timber
import javax.inject.Inject

/**
 * [ChangeStore] that writes to both [AnytypeChangeStore] (cloud-synced durable storage)
 * and [LocalChangeCache] (fast Room-backed queries), and reads from [LocalChangeCache]
 * with fallback to [AnytypeChangeStore].
 */
class CompositeChangeStore @Inject constructor(
    private val anytypeStore: AnytypeChangeStore,
    private val localCache: LocalChangeCache
) : ChangeStore {

    override suspend fun save(changeSet: ChangeSet): PebbleId {
        localCache.save(changeSet)
        try {
            anytypeStore.save(changeSet)
        } catch (e: Exception) {
            Timber.w(e, "[Pebble] CompositeChangeStore: AnyType write failed for ${changeSet.id}; cache still updated")
        }
        return changeSet.id
    }

    override suspend fun updateStatus(changeSetId: PebbleId, status: ChangeSetStatus) {
        localCache.updateStatus(changeSetId, status)
        try {
            anytypeStore.updateStatus(changeSetId, status)
        } catch (e: Exception) {
            Timber.w(e, "[Pebble] CompositeChangeStore: AnyType status update failed for $changeSetId")
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
        localCache.updateOperation(operationId, status, beforeState, afterState, resultObjectId, inverse)
        try {
            anytypeStore.updateOperation(operationId, status, beforeState, afterState, resultObjectId, inverse)
        } catch (e: Exception) {
            Timber.w(e, "[Pebble] CompositeChangeStore: AnyType operation update failed for $operationId")
        }
    }

    override suspend fun getChangeSet(changeSetId: PebbleId): ChangeSet? {
        return localCache.getChangeSet(changeSetId)
            ?: anytypeStore.getChangeSet(changeSetId)
    }

    override suspend fun getChangeSets(status: ChangeSetStatus?, limit: Int): List<ChangeSet> {
        val cached = localCache.getChangeSets(status, limit)
        return cached.ifEmpty { anytypeStore.getChangeSets(status, limit) }
    }

    override suspend fun getChangeSetForInput(inputId: PebbleId): ChangeSet? {
        return localCache.getChangeSetForInput(inputId)
            ?: anytypeStore.getChangeSetForInput(inputId)
    }
}
