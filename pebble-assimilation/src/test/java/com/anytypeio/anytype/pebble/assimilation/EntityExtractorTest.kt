package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.extraction.EntityExtractor
import com.anytypeio.anytype.pebble.assimilation.llm.LlmClient
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedRelationship
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.core.taxonomy.PkmObjectType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class EntityExtractorTest {

    private val llmClient: LlmClient = mock()
    private val contextWindow: ContextWindow = mock()
    private lateinit var extractor: EntityExtractor

    @Before
    fun setup() {
        whenever(contextWindow.recentEntitySummary()).thenReturn("")
        extractor = EntityExtractor(llmClient, contextWindow)
    }

    @Test
    fun `extracts person and event from basketball input`() = runTest {
        val extraction = ExtractionResult(
            entities = listOf(
                ExtractedEntity("e1", "ot-human", "Aarav", confidence = 0.95f),
                ExtractedEntity("e2", "ot-pkm-event", "Basketball game", mapOf("pkm-date" to "2026-03-27"), confidence = 0.90f)
            ),
            relationships = listOf(
                ExtractedRelationship("e2", "e1", "pkm-attendees")
            ),
            overallConfidence = 0.92f
        )
        whenever(llmClient.extractEntities(any(), any())).thenReturn(extraction)

        val result = extractor.extract("Aarav has a basketball game on Friday")

        assertEquals(2, result.entities.size)
        assertEquals("ot-human", result.entities.first { it.localRef == "e1" }.typeKey)
        assertEquals("ot-pkm-event", result.entities.first { it.localRef == "e2" }.typeKey)
        assertEquals(1, result.relationships.size)
    }

    @Test
    fun `unknown type key falls back to Note`() = runTest {
        val extraction = ExtractionResult(
            entities = listOf(
                ExtractedEntity("e1", "ot-unicorn-type", "Mystery Thing")
            ),
            relationships = emptyList()
        )
        whenever(llmClient.extractEntities(any(), any())).thenReturn(extraction)

        val result = extractor.extract("Some mystery thing")

        assertEquals(
            "Unknown type should fall back to Note",
            PkmObjectType.NoteType.uniqueKey,
            result.entities.first().typeKey
        )
    }

    @Test
    fun `relationships with dangling refs are filtered out`() = runTest {
        val extraction = ExtractionResult(
            entities = listOf(ExtractedEntity("e1", "ot-human", "Aarav")),
            relationships = listOf(
                ExtractedRelationship("e1", "e999", "pkm-attendees")  // e999 doesn't exist
            )
        )
        whenever(llmClient.extractEntities(any(), any())).thenReturn(extraction)

        val result = extractor.extract("test")

        assertTrue("Dangling relationship should be filtered", result.relationships.isEmpty())
    }

    @Test
    fun `context window summary injected into system prompt`() = runTest {
        whenever(contextWindow.recentEntitySummary()).thenReturn("RECENT CONTEXT: Aarav (ot-human)")
        val extraction = ExtractionResult(listOf(ExtractedEntity("e1", "ot-human", "Aarav")), emptyList())
        whenever(llmClient.extractEntities(any(), any())).thenReturn(extraction)

        extractor.extract("test")

        // Verify that the system prompt passed to the LLM contains the context
        verify(llmClient).extractEntities(
            org.mockito.kotlin.argThat { prompt -> prompt.contains("RECENT CONTEXT") },
            any()
        )
    }
}
