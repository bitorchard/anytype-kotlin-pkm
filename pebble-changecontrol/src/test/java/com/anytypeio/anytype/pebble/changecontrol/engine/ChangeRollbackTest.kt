package com.anytypeio.anytype.pebble.changecontrol.engine

import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ConflictResolution
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import com.anytypeio.anytype.pebble.changecontrol.model.RollbackResult
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ChangeRollbackTest {

    private val graphService: PebbleGraphService = mock()
    private val changeStore: ChangeStore = mock()
    private val rollback = ChangeRollback(graphService, changeStore)

    private val spaceId = "test-space"

    private fun appliedOp(
        id: String,
        ordinal: Int,
        objectId: String,
        afterState: Map<String, String> = mapOf("name" to "Created"),
        inverseParams: OperationParams = OperationParams.DeleteObjectParams(objectId)
    ) = ChangeOperation(
        id = id,
        changeSetId = "cs-001",
        ordinal = ordinal,
        type = OperationType.CREATE_OBJECT,
        params = OperationParams.CreateObjectParams(spaceId, "ot-pkm-event", localRef = null),
        inverse = inverseParams,
        afterState = afterState,
        status = OperationStatus.APPLIED
    )

    private fun buildAppliedChangeSet(vararg ops: ChangeOperation) = ChangeSet(
        id = "cs-001",
        inputId = "input-001",
        traceId = "trace-001",
        status = ChangeSetStatus.APPLIED,
        summary = "Applied change set",
        operations = ops.toList(),
        metadata = ChangeSetMetadata(spaceId = spaceId, sourceText = "test"),
        createdAt = System.currentTimeMillis()
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `clean rollback of 3 ops transitions to ROLLED_BACK`() = runTest {
        val op1 = appliedOp("op-1", 0, "obj-1")
        val op2 = appliedOp("op-2", 1, "obj-2")
        val op3 = appliedOp("op-3", 2, "obj-3")
        val changeSet = buildAppliedChangeSet(op1, op2, op3)

        // No conflicts: current state matches expected after-state
        whenever(graphService.getObject(any(), eq("obj-1"), any()))
            .thenReturn(PebbleObject("obj-1", "Created", "ot-pkm-event", mapOf("name" to "Created"), null))
        whenever(graphService.getObject(any(), eq("obj-2"), any()))
            .thenReturn(PebbleObject("obj-2", "Created", "ot-pkm-event", mapOf("name" to "Created"), null))
        whenever(graphService.getObject(any(), eq("obj-3"), any()))
            .thenReturn(PebbleObject("obj-3", "Created", "ot-pkm-event", mapOf("name" to "Created"), null))

        val result = rollback.rollback(changeSet)

        assertTrue(result is RollbackResult.FullRollback)
        val full = result as RollbackResult.FullRollback
        assertEquals(3, full.operationResults.size)
        assertTrue(full.operationResults.all { it.rolledBack })
        verify(changeStore).updateStatus("cs-001", ChangeSetStatus.ROLLED_BACK)
    }

    @Test
    fun `SKIP strategy skips conflicted op and rolls back others`() = runTest {
        val op1 = appliedOp("op-1", 0, "obj-1")
        val op2 = appliedOp("op-2", 1, "obj-2", afterState = mapOf("name" to "Created"))
        val changeSet = buildAppliedChangeSet(op1, op2)

        // op1 has no conflict
        whenever(graphService.getObject(any(), eq("obj-1"), any()))
            .thenReturn(PebbleObject("obj-1", "Created", "ot-pkm-event", mapOf("name" to "Created"), null))
        // op2 has conflict: someone changed name to "User Modified"
        whenever(graphService.getObject(any(), eq("obj-2"), any()))
            .thenReturn(PebbleObject("obj-2", "User Modified", "ot-pkm-event", mapOf("name" to "User Modified"), null))

        val result = rollback.rollback(changeSet, ConflictResolution.SKIP)

        assertTrue(result is RollbackResult.PartialRollback)
        val partial = result as RollbackResult.PartialRollback
        assertEquals(1, partial.conflicts.size)
        verify(changeStore).updateStatus("cs-001", ChangeSetStatus.PARTIALLY_ROLLED_BACK)
    }

    @Test
    fun `ABORT strategy stops at first conflict`() = runTest {
        val op1 = appliedOp("op-1", 1, "obj-1") // higher ordinal = executed second = rolled back first
        val op2 = appliedOp("op-2", 0, "obj-2")  // lower ordinal = executed first = rolled back second
        val changeSet = buildAppliedChangeSet(op1, op2)

        // op1 has conflict (it's the first in rollback order since ordinal 1 is reversed)
        whenever(graphService.getObject(any(), eq("obj-1"), any()))
            .thenReturn(PebbleObject("obj-1", "User Modified", "ot-pkm-event", mapOf("name" to "User Modified"), null))

        val result = rollback.rollback(changeSet, ConflictResolution.ABORT)

        assertTrue(result is RollbackResult.Aborted)
        verify(graphService, never()).deleteObjects(any())
        verify(changeStore).updateStatus("cs-001", ChangeSetStatus.PARTIALLY_ROLLED_BACK)
    }

    @Test
    fun `FORCE strategy overwrites conflict and completes rollback`() = runTest {
        val op1 = appliedOp("op-1", 0, "obj-1", afterState = mapOf("name" to "Created"))
        val changeSet = buildAppliedChangeSet(op1)

        // Conflict: external change
        whenever(graphService.getObject(any(), eq("obj-1"), any()))
            .thenReturn(PebbleObject("obj-1", "User Modified", "ot-pkm-event", mapOf("name" to "User Modified"), null))

        val result = rollback.rollback(changeSet, ConflictResolution.FORCE)

        // FORCE should still apply the inverse (delete)
        verify(graphService).deleteObjects(eq(listOf("obj-1")))
        assertTrue(result is RollbackResult.PartialRollback) // conflict recorded but rollback applied
    }

    @Test
    fun `operations with no after-state skip conflict detection`() = runTest {
        val op = ChangeOperation(
            id = "op-1",
            changeSetId = "cs-001",
            ordinal = 0,
            type = OperationType.ADD_RELATION,
            params = OperationParams.AddRelationParams("obj-1", "pkm-relatedTo"),
            inverse = OperationParams.RemoveRelationParams("obj-1", "pkm-relatedTo"),
            afterState = null, // no state captured — skip conflict check
            status = OperationStatus.APPLIED
        )
        val changeSet = buildAppliedChangeSet(op)

        val result = rollback.rollback(changeSet)

        assertTrue(result is RollbackResult.FullRollback)
        verify(graphService, never()).getObject(any(), any(), any())
    }
}
