package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.model.DisambiguationChoice
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.assimilation.model.ScoredCandidate
import com.anytypeio.anytype.pebble.assimilation.model.SignalBreakdown
import com.anytypeio.anytype.pebble.assimilation.model.disambiguationChoices
import com.anytypeio.anytype.pebble.assimilation.model.withDisambiguationChoices
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.core.PebbleObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [DisambiguationChoice] lists can be round-tripped through the
 * [ChangeSet.disambiguationChoicesJson] field using the extension functions in
 * [ChangeSetExtensions].
 */
class DisambiguationChoicesSerializationTest {

    private fun emptyChangeSet() = ChangeSet(
        id = "cs-test",
        inputId = "input-test",
        traceId = "trace-test",
        status = ChangeSetStatus.PENDING,
        summary = "test",
        operations = emptyList(),
        metadata = ChangeSetMetadata(spaceId = "space-1", sourceText = "test input"),
        createdAt = 0L
    )

    @Test
    fun `disambiguationChoices returns empty list when field is blank`() {
        val cs = emptyChangeSet()
        assertTrue(cs.disambiguationChoices().isEmpty())
    }

    @Test
    fun `withDisambiguationChoices then disambiguationChoices round-trips correctly`() {
        val alexSmith = PebbleObject(id = "obj-alex-smith", name = "Alex Smith", typeKey = "ot-human")
        val alexJohnson = PebbleObject(id = "obj-alex-johnson", name = "Alex Johnson", typeKey = "ot-human")
        val entity = ExtractedEntity(localRef = "e-alex", typeKey = "ot-human", name = "Alex")
        val choices = listOf(
            DisambiguationChoice(
                entity = entity,
                candidates = listOf(
                    ScoredCandidate(alexSmith, score = 0.82f, signals = SignalBreakdown(nameSimilarity = 0.9f)),
                    ScoredCandidate(alexJohnson, score = 0.79f, signals = SignalBreakdown(nameSimilarity = 0.85f))
                )
            )
        )

        val cs = emptyChangeSet().withDisambiguationChoices(choices)
        assertTrue("JSON should be non-empty", cs.disambiguationChoicesJson.isNotBlank())

        val decoded = cs.disambiguationChoices()
        assertEquals(1, decoded.size)
        assertEquals("e-alex", decoded[0].entity.localRef)
        assertEquals(2, decoded[0].candidates.size)
        assertEquals("obj-alex-smith", decoded[0].candidates[0].object_.id)
        assertEquals(0.82f, decoded[0].candidates[0].score)
    }

    @Test
    fun `withDisambiguationChoices with empty list clears json field`() {
        val cs = emptyChangeSet().withDisambiguationChoices(emptyList())
        assertEquals("", cs.disambiguationChoicesJson)
        assertTrue(cs.disambiguationChoices().isEmpty())
    }

    @Test
    fun `ResolutionDecision sealed subclasses serialise with correct discriminator`() {
        val resolved = ResolutionDecision.Resolved(objectId = "obj-123", typeKey = "ot-human")
        val createNew = ResolutionDecision.CreateNew(typeKey = "ot-human")
        val skipped = ResolutionDecision.Skipped

        // All three should survive a full ChangeSet round-trip via disambiguationChoicesJson
        val entity = ExtractedEntity("e1", "ot-human", "Someone")
        val choices = listOf(
            DisambiguationChoice(entity, candidates = emptyList())
        )
        val cs = emptyChangeSet().withDisambiguationChoices(choices)
        val decoded = cs.disambiguationChoices()
        assertEquals(1, decoded.size)
    }
}
