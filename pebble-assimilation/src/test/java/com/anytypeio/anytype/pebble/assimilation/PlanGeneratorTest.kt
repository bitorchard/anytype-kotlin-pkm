package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedRelationship
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.assimilation.model.ResolvedEntity
import com.anytypeio.anytype.pebble.assimilation.plan.PlanGenerator
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanGeneratorTest {

    private val generator = PlanGenerator()
    private val spaceId = "test-space"

    @Test
    fun `create-new decision generates CREATE_OBJECT operation`() {
        val entity = ExtractedEntity("e1", "ot-human", "Aarav", emptyMap())
        val resolved = listOf(ResolvedEntity(entity, ResolutionDecision.CreateNew("ot-human")))
        val extraction = ExtractionResult(listOf(entity), emptyList())
        val plan = generator.generate(resolved, extraction, spaceId = spaceId, sourceText = "test")
        val creates = plan.operations.filter { it.type == OperationType.CREATE_OBJECT }
        assertEquals("Should have 1 CREATE_OBJECT", 1, creates.size)
        val params = creates.first().params as OperationParams.CreateObjectParams
        assertEquals("ot-human", params.typeKey)
        assertEquals("Aarav", params.details["name"])
    }

    @Test
    fun `resolved decision generates SET_DETAILS for attributes only`() {
        val entity = ExtractedEntity("e1", "ot-human", "Aarav", mapOf("pkm-date" to "2026-03-24"))
        val resolved = listOf(ResolvedEntity(entity, ResolutionDecision.Resolved("existing-id", "ot-human")))
        val extraction = ExtractionResult(listOf(entity), emptyList())
        val plan = generator.generate(resolved, extraction, spaceId = spaceId, sourceText = "test")
        val updates = plan.operations.filter { it.type == OperationType.SET_DETAILS }
        assertEquals("Resolved entity with attributes → 1 SET_DETAILS", 1, updates.size)
        val creates = plan.operations.filter { it.type == OperationType.CREATE_OBJECT }
        assertEquals("Resolved entity → 0 CREATE_OBJECT", 0, creates.size)
    }

    @Test
    fun `skipped entities produce no operations`() {
        val entity = ExtractedEntity("e1", "ot-human", "Aarav")
        val resolved = listOf(ResolvedEntity(entity, ResolutionDecision.Skipped))
        val extraction = ExtractionResult(listOf(entity), emptyList())
        val plan = generator.generate(resolved, extraction, spaceId = spaceId, sourceText = "test")
        assertTrue("Skipped entity → 0 operations", plan.operations.isEmpty())
    }

    @Test
    fun `basketball scenario generates person + event + link`() {
        val person = ExtractedEntity("e1", "ot-human", "Aarav")
        val event = ExtractedEntity("e2", "ot-pkm-event", "Basketball game", mapOf("pkm-date" to "2026-03-28"))
        val relationship = ExtractedRelationship("e2", "e1", "pkm-attendees")
        val resolved = listOf(
            ResolvedEntity(person, ResolutionDecision.CreateNew("ot-human")),
            ResolvedEntity(event, ResolutionDecision.CreateNew("ot-pkm-event"))
        )
        val extraction = ExtractionResult(listOf(person, event), listOf(relationship))
        val plan = generator.generate(resolved, extraction, spaceId = spaceId, sourceText = "Aarav has a basketball game on Friday")

        val creates = plan.operations.filter { it.type == OperationType.CREATE_OBJECT }
        val links = plan.operations.filter { it.type == OperationType.ADD_RELATION }
        assertEquals("Should create 2 objects", 2, creates.size)
        assertEquals("Should create 1 link", 1, links.size)
    }

    @Test
    fun `CREATE_OBJECT operations precede ADD_RELATION in execution order`() {
        val person = ExtractedEntity("e1", "ot-human", "Aarav")
        val event = ExtractedEntity("e2", "ot-pkm-event", "Game")
        val rel = ExtractedRelationship("e2", "e1", "pkm-attendees")
        val resolved = listOf(
            ResolvedEntity(person, ResolutionDecision.CreateNew("ot-human")),
            ResolvedEntity(event, ResolutionDecision.CreateNew("ot-pkm-event"))
        )
        val extraction = ExtractionResult(listOf(person, event), listOf(rel))
        val plan = generator.generate(resolved, extraction, spaceId = spaceId, sourceText = "test")

        val ops = plan.operations
        val createIndices = ops.indices.filter { ops[it].type == OperationType.CREATE_OBJECT }
        val linkIndices = ops.indices.filter { ops[it].type == OperationType.ADD_RELATION }
        assertTrue("All creates should precede all links", createIndices.all { ci -> linkIndices.all { li -> ci < li } })
    }

    @Test
    fun `plan summary metadata is populated`() {
        val entity = ExtractedEntity("e1", "ot-human", "Aarav")
        val resolved = listOf(ResolvedEntity(entity, ResolutionDecision.CreateNew("ot-human")))
        val extraction = ExtractionResult(listOf(entity), emptyList(), overallConfidence = 0.95f, modelVersion = "claude-sonnet-4-5")
        val plan = generator.generate(
            resolved, extraction,
            spaceId = spaceId, sourceText = "Aarav called",
            modelVersion = "claude-sonnet-4-5", extractionConfidence = 0.95f
        )
        assertEquals(spaceId, plan.metadata.spaceId)
        assertEquals("claude-sonnet-4-5", plan.metadata.modelVersion)
        assertEquals(0.95f, plan.metadata.extractionConfidence, 0.001f)
    }
}
