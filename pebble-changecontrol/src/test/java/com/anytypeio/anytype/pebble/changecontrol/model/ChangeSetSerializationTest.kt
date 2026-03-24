package com.anytypeio.anytype.pebble.changecontrol.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChangeSetSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ChangeSet round-trips through JSON`() {
        val changeSet = ChangeSet(
            id = "cs-001",
            inputId = "input-001",
            traceId = "trace-abc",
            status = ChangeSetStatus.PENDING,
            summary = "Add basketball game event for Aarav",
            operations = listOf(
                ChangeOperation(
                    id = "op-001",
                    changeSetId = "cs-001",
                    ordinal = 0,
                    type = OperationType.CREATE_OBJECT,
                    params = OperationParams.CreateObjectParams(
                        spaceId = "space-1",
                        typeKey = "ot-pkm-event",
                        details = mapOf("name" to "Basketball game"),
                        localRef = "local-1"
                    )
                ),
                ChangeOperation(
                    id = "op-002",
                    changeSetId = "cs-001",
                    ordinal = 1,
                    type = OperationType.ADD_RELATION,
                    params = OperationParams.AddRelationParams(
                        objectId = "local-1",
                        relationKey = "pkm-attendees",
                        value = "aarav-person-id"
                    )
                )
            ),
            metadata = ChangeSetMetadata(
                spaceId = "space-1",
                sourceText = "Aarav has a basketball game on Friday",
                modelVersion = "gpt-4o",
                extractionConfidence = 0.92f
            ),
            createdAt = 1711152000000L
        )

        val serialized = json.encodeToString(changeSet)
        val deserialized = json.decodeFromString<ChangeSet>(serialized)

        assertEquals(changeSet.id, deserialized.id)
        assertEquals(changeSet.traceId, deserialized.traceId)
        assertEquals(changeSet.status, deserialized.status)
        assertEquals(changeSet.operations.size, deserialized.operations.size)
    }

    @Test
    fun `ChangeSetStatus covers all required states`() {
        val required = setOf(
            "PENDING", "APPROVED", "APPLYING", "APPLIED",
            "APPLY_FAILED", "REJECTED", "ROLLING_BACK",
            "ROLLED_BACK", "PARTIALLY_ROLLED_BACK"
        )
        val actual = ChangeSetStatus.values().map { it.name }.toSet()
        assertEquals(required, actual)
    }

    @Test
    fun `OperationParams sealed class covers all 5 operation types`() {
        val types = setOf(
            OperationParams.CreateObjectParams("s", "t"),
            OperationParams.DeleteObjectParams("obj-1"),
            OperationParams.SetDetailsParams("obj-1", mapOf("name" to "X")),
            OperationParams.AddRelationParams("obj-1", "pkm-relatedTo"),
            OperationParams.RemoveRelationParams("obj-1", "pkm-relatedTo")
        )
        assertEquals(5, types.size)
    }

    @Test
    fun `OperationParams polymorphic serialization round-trips`() {
        val params: OperationParams = OperationParams.SetDetailsParams(
            objectId = "obj-xyz",
            details = mapOf("name" to "Updated", "pkm-date" to "2024-01-01")
        )
        val encoded = json.encodeToString(params)
        val decoded = json.decodeFromString<OperationParams>(encoded)
        assertEquals(params, decoded)
    }

    @Test
    fun `CreateObjectParams localRef is preserved through serialization`() {
        val params = OperationParams.CreateObjectParams(
            spaceId = "space-1",
            typeKey = "ot-pkm-event",
            details = mapOf("name" to "Event A"),
            localRef = "local-42"
        )
        val encoded = json.encodeToString<OperationParams>(params)
        val decoded = json.decodeFromString<OperationParams>(encoded) as OperationParams.CreateObjectParams
        assertEquals("local-42", decoded.localRef)
    }

    @Test
    fun `ChangeOperation with inverse round-trips`() {
        val op = ChangeOperation(
            id = "op-1",
            changeSetId = "cs-1",
            ordinal = 0,
            type = OperationType.SET_DETAILS,
            params = OperationParams.SetDetailsParams("obj-1", mapOf("name" to "New")),
            inverse = OperationParams.SetDetailsParams("obj-1", mapOf("name" to "Old")),
            beforeState = mapOf("name" to "Old"),
            afterState = mapOf("name" to "New"),
            status = OperationStatus.APPLIED
        )
        val serialized = json.encodeToString(op)
        val deserialized = json.decodeFromString<ChangeOperation>(serialized)
        assertNotNull(deserialized.inverse)
        assertEquals(op.beforeState, deserialized.beforeState)
        assertEquals(op.afterState, deserialized.afterState)
    }
}
