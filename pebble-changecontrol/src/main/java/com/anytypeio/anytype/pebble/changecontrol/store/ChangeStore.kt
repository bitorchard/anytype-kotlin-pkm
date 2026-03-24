package com.anytypeio.anytype.pebble.changecontrol.store

import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.core.PebbleId

/**
 * Persistence contract for [ChangeSet] and [ChangeOperation] objects.
 *
 * Implementations may be AnyType-native (synced across devices) or Room-backed
 * (fast local cache). The [CompositeChangeStore] combines both.
 */
interface ChangeStore {

    /** Persists a new change set (and its operations) and returns the persisted ID. */
    suspend fun save(changeSet: ChangeSet): PebbleId

    /** Transitions a change set to a new status. */
    suspend fun updateStatus(changeSetId: PebbleId, status: ChangeSetStatus)

    /**
     * Updates a single operation's execution state.
     *
     * @param beforeState Snapshot captured before the operation ran; null if unchanged.
     * @param afterState Snapshot captured after the operation ran; null if unchanged.
     */
    suspend fun updateOperation(
        operationId: PebbleId,
        status: OperationStatus,
        beforeState: Map<String, String>?,
        afterState: Map<String, String>?,
        resultObjectId: PebbleId? = null,
        inverse: OperationParams? = null
    )

    /** Returns a change set by ID, including its operations. */
    suspend fun getChangeSet(changeSetId: PebbleId): ChangeSet?

    /**
     * Lists change sets, optionally filtered by [status].
     * Most-recent first.
     */
    suspend fun getChangeSets(status: ChangeSetStatus? = null, limit: Int = 50): List<ChangeSet>

    /** Looks up the change set produced from a given voice input. */
    suspend fun getChangeSetForInput(inputId: PebbleId): ChangeSet?
}
