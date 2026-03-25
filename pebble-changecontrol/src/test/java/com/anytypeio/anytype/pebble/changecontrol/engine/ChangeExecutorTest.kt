package com.anytypeio.anytype.pebble.changecontrol.engine

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey
import com.anytypeio.anytype.pebble.changecontrol.FakePebbleGraphService
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ExecutionResult
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.PebbleObject
import com.anytypeio.anytype.pebble.core.PebbleObjectResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Uses a hand-written [FakePebbleGraphService] instead of a Mockito mock to avoid
 * the Mockito/coroutine/inline-value-class incompatibility that prevents
 * `createObject(SpaceId, TypeKey, ...)` stubs from matching reliably.
 */
class ChangeExecutorTest {

    private val graphService = FakePebbleGraphService()
    private val changeStore: ChangeStore = mock()
    private val executor = ChangeExecutor(graphService, changeStore)

    private val spaceId = "test-space"
    private val testSpace = SpaceId(spaceId)
    private val testTypeKey = TypeKey("ot-pkm-event")

    @Before
    fun setup() {
        graphService.createObjectResults.clear()
        graphService.createObjectCalls.clear()
        graphService.getObjectResults.clear()
        graphService.updateObjectDetailsCalls.clear()
        graphService.deleteObjectsCalls.clear()
    }

    private fun buildChangeSet(vararg ops: ChangeOperation) = ChangeSet(
        id = "cs-001",
        inputId = "input-001",
        traceId = "trace-001",
        status = ChangeSetStatus.APPROVED,
        summary = "Test change set",
        operations = ops.toList(),
        metadata = ChangeSetMetadata(spaceId = spaceId, sourceText = "test"),
        createdAt = System.currentTimeMillis()
    )

    private fun createOp(id: String, ordinal: Int, localRef: String?) = ChangeOperation(
        id = id,
        changeSetId = "cs-001",
        ordinal = ordinal,
        type = OperationType.CREATE_OBJECT,
        params = OperationParams.CreateObjectParams(
            spaceId = spaceId,
            typeKey = "ot-pkm-event",
            details = mapOf("name" to "Test Object"),
            localRef = localRef
        ),
        status = OperationStatus.PENDING
    )

    private fun linkOp(id: String, ordinal: Int, objectId: String, value: String) = ChangeOperation(
        id = id,
        changeSetId = "cs-001",
        ordinal = ordinal,
        type = OperationType.ADD_RELATION,
        params = OperationParams.AddRelationParams(
            objectId = objectId,
            relationKey = "pkm-attendees",
            value = value
        ),
        status = OperationStatus.PENDING
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `executes 3-op change set and transitions status to APPLIED`() = runTest {
        val createPerson = createOp("op-1", 0, "local-person")
        val createEvent = createOp("op-2", 1, "local-event")
        val link = linkOp("op-3", 2, "local-event", "local-person")
        val changeSet = buildChangeSet(createPerson, createEvent, link)

        graphService.enqueueCreateObject(PebbleObjectResult("person-id-real"))
        graphService.enqueueCreateObject(PebbleObjectResult("event-id-real"))

        val result = executor.execute(changeSet)

        assertTrue(result is ExecutionResult.Success)
        val success = result as ExecutionResult.Success
        assertEquals(3, success.operationResults.size)
        verify(changeStore).updateStatus("cs-001", ChangeSetStatus.APPLYING)
        verify(changeStore).updateStatus("cs-001", ChangeSetStatus.APPLIED)
    }

    @Test
    fun `resultObjectId populated on CREATE_OBJECT operation`() = runTest {
        val createOp = createOp("op-1", 0, "local-1")
        val changeSet = buildChangeSet(createOp)

        graphService.enqueueCreateObject(PebbleObjectResult("real-id-abc"))

        val result = executor.execute(changeSet) as ExecutionResult.Success

        assertEquals("real-id-abc", result.operationResults.first().resultObjectId)
    }

    @Test
    fun `partial failure when second of 3 ops throws`() = runTest {
        val op1 = createOp("op-1", 0, "local-1")
        val op2 = createOp("op-2", 1, "local-2")
        val op3 = createOp("op-3", 2, "local-3")
        val changeSet = buildChangeSet(op1, op2, op3)

        graphService.enqueueCreateObject(PebbleObjectResult("id-1"))
        graphService.enqueueCreateObjectError(RuntimeException("Middleware error"))

        val result = executor.execute(changeSet)

        assertTrue(result is ExecutionResult.PartialFailure)
        val failure = result as ExecutionResult.PartialFailure
        assertEquals(1, failure.firstFailedOrdinal)
        verify(changeStore).updateStatus("cs-001", ChangeSetStatus.APPLY_FAILED)
    }

    @Test
    fun `local refs resolved in subsequent operations after create`() = runTest {
        val createOp = createOp("op-1", 0, "local-event")
        val setDetails = ChangeOperation(
            id = "op-2",
            changeSetId = "cs-001",
            ordinal = 1,
            type = OperationType.SET_DETAILS,
            params = OperationParams.SetDetailsParams(
                objectId = "local-event",
                details = mapOf("pkm-date" to "2024-04-05")
            ),
            status = OperationStatus.PENDING
        )
        val changeSet = buildChangeSet(createOp, setDetails)

        graphService.enqueueCreateObject(PebbleObjectResult("event-real-id"))
        graphService.getObjectResults["event-real-id"] =
            PebbleObject("event-real-id", "Event", "ot-pkm-event", emptyMap(), null)

        executor.execute(changeSet)

        assertEquals(1, graphService.updateObjectDetailsCalls.size)
        assertEquals("event-real-id", graphService.updateObjectDetailsCalls.first().first)
    }

    @Test
    fun `beforeState and afterState captured for SET_DETAILS`() = runTest {
        val setDetails = ChangeOperation(
            id = "op-1",
            changeSetId = "cs-001",
            ordinal = 0,
            type = OperationType.SET_DETAILS,
            params = OperationParams.SetDetailsParams("obj-existing", mapOf("name" to "New Name")),
            status = OperationStatus.PENDING
        )
        val changeSet = buildChangeSet(setDetails)

        graphService.getObjectResults["obj-existing"] =
            PebbleObject("obj-existing", "Old Name", "ot-task", mapOf("name" to "Old Name"), null)

        executor.execute(changeSet)

        verify(changeStore).updateOperation(
            operationId = eq("op-1"),
            status = eq(OperationStatus.APPLIED),
            beforeState = eq(mapOf("name" to "Old Name")),
            afterState = eq(mapOf("name" to "New Name")),
            resultObjectId = anyOrNull(),
            inverse = anyOrNull()
        )
    }
}
