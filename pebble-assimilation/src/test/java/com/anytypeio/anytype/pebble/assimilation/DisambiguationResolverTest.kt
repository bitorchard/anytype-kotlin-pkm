package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.assimilation.resolution.DisambiguationResolver
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DisambiguationResolverTest {

    private lateinit var resolver: DisambiguationResolver

    @Before
    fun setUp() {
        resolver = DisambiguationResolver()
    }

    private fun makeChangeSet(operations: List<ChangeOperation>) = ChangeSet(
        id = "cs-test",
        inputId = "input-test",
        traceId = "trace-test",
        status = ChangeSetStatus.PENDING,
        summary = "test",
        operations = operations,
        metadata = ChangeSetMetadata(spaceId = "space-1", sourceText = "test"),
        createdAt = 0L,
        disambiguationChoicesJson = "non-empty-sentinel"
    )

    private fun createOp(localRef: String, ordinal: Int = 0) = ChangeOperation(
        id = "op-create-$localRef",
        changeSetId = "cs-test",
        ordinal = ordinal,
        type = OperationType.CREATE_OBJECT,
        params = OperationParams.CreateObjectParams(
            spaceId = "space-1",
            typeKey = "ot-human",
            details = mapOf("name" to "Alex"),
            localRef = localRef
        )
    )

    private fun relationOp(objectId: String, ordinal: Int = 1) = ChangeOperation(
        id = "op-rel-$objectId",
        changeSetId = "cs-test",
        ordinal = ordinal,
        type = OperationType.ADD_RELATION,
        params = OperationParams.AddRelationParams(
            objectId = objectId,
            relationKey = "pkm-attendees",
            value = "event-123"
        )
    )

    // ── Resolved path ─────────────────────────────────────────────────────────

    @Test
    fun `Resolved decision replaces CREATE_OBJECT with SET_DETAILS on existing object`() {
        val cs = makeChangeSet(listOf(createOp("local-alex")))
        val result = resolver.resolve(cs, mapOf("local-alex" to ResolutionDecision.Resolved("obj-real-alex", "ot-human")))

        assertEquals(1, result.operations.size)
        val op = result.operations[0]
        assertEquals(OperationType.SET_DETAILS, op.type)
        val params = op.params as OperationParams.SetDetailsParams
        assertEquals("obj-real-alex", params.objectId)
        assertEquals(mapOf("name" to "Alex"), params.details)
    }

    @Test
    fun `Resolved decision updates downstream ADD_RELATION ops to use real objectId`() {
        val cs = makeChangeSet(listOf(createOp("local-alex", 0), relationOp("local-alex", 1)))
        val result = resolver.resolve(cs, mapOf("local-alex" to ResolutionDecision.Resolved("obj-real-alex", "ot-human")))

        assertEquals(2, result.operations.size)
        val relOp = result.operations[1]
        val params = relOp.params as OperationParams.AddRelationParams
        assertEquals("obj-real-alex", params.objectId)
    }

    // ── CreateNew path ────────────────────────────────────────────────────────

    @Test
    fun `CreateNew decision leaves CREATE_OBJECT op unchanged`() {
        val cs = makeChangeSet(listOf(createOp("local-alex")))
        val result = resolver.resolve(cs, mapOf("local-alex" to ResolutionDecision.CreateNew("ot-human")))

        assertEquals(1, result.operations.size)
        assertEquals(OperationType.CREATE_OBJECT, result.operations[0].type)
        val params = result.operations[0].params as OperationParams.CreateObjectParams
        assertEquals("local-alex", params.localRef)
    }

    // ── Skipped path ──────────────────────────────────────────────────────────

    @Test
    fun `Skipped decision removes CREATE_OBJECT op for that localRef`() {
        val cs = makeChangeSet(listOf(createOp("local-alex")))
        val result = resolver.resolve(cs, mapOf("local-alex" to ResolutionDecision.Skipped))
        assertTrue(result.operations.isEmpty())
    }

    @Test
    fun `Skipped decision also removes downstream relation ops referencing that localRef`() {
        val cs = makeChangeSet(listOf(createOp("local-alex", 0), relationOp("local-alex", 1)))
        val result = resolver.resolve(cs, mapOf("local-alex" to ResolutionDecision.Skipped))
        assertTrue(result.operations.isEmpty())
    }

    // ── Disambiguation cleared ─────────────────────────────────────────────────

    @Test
    fun `resolved ChangeSet has empty disambiguationChoicesJson`() {
        val cs = makeChangeSet(listOf(createOp("local-alex")))
        val result = resolver.resolve(cs, mapOf("local-alex" to ResolutionDecision.CreateNew("ot-human")))
        assertEquals("", result.disambiguationChoicesJson)
    }

    // ── Ordinal re-indexing ───────────────────────────────────────────────────

    @Test
    fun `operations are re-indexed after skipping`() {
        val cs = makeChangeSet(listOf(
            createOp("local-a", 0),
            createOp("local-b", 1),
            createOp("local-c", 2)
        ))
        val result = resolver.resolve(cs, mapOf("local-b" to ResolutionDecision.Skipped))
        assertEquals(2, result.operations.size)
        assertEquals(0, result.operations[0].ordinal)
        assertEquals(1, result.operations[1].ordinal)
    }

    // ── Ops without userChoices entry pass through unchanged ──────────────────

    @Test
    fun `ops whose localRef has no user choice pass through unchanged`() {
        val cs = makeChangeSet(listOf(createOp("local-unknown")))
        val result = resolver.resolve(cs, emptyMap())

        assertEquals(1, result.operations.size)
        assertEquals(OperationType.CREATE_OBJECT, result.operations[0].type)
    }
}
